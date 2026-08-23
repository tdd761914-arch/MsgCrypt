import Foundation
import Whatsbridge

private final class WhatsEventListener: NSObject, WhatsbridgeListenerProtocol {
    var handler: ((String) -> Void)?
    func onEvent(_ eventJSON: String?) { if let eventJSON { handler?(eventJSON) } }
}

final class WhatsAppGateway: MessagingGateway {
    var event: ((GatewayEvent) -> Void)?
    private let accountID: UUID
    private let root: URL
    private let listener = WhatsEventListener()
    private var manager: WhatsbridgeManager?

    init(accountID: UUID, root: URL) {
        self.accountID = accountID
        self.root = root
        listener.handler = { [weak self] in self?.receive($0) }
    }

    func connect(account: Account, phone: String) async throws {
        if manager == nil {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var bridgeError: NSError?
            manager = WhatsbridgeNewManager(root.path, listener, &bridgeError)
            if let bridgeError { throw bridgeError }
        }
        guard let manager else { throw StoreError.configuration("Не удалось создать WhatsMeow") }
        var bridgeError: NSError?
        _ = manager.connect(&bridgeError)
        if let bridgeError { throw bridgeError }
    }

    func loadChats() async throws {
        guard let manager else { throw StoreError.configuration("WhatsApp не подключён") }
        var bridgeError: NSError?
        let raw = manager.listChats(&bridgeError)
        if let bridgeError { throw bridgeError }
        guard let data = raw.data(using: String.Encoding.utf8),
              let rows = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return }
        for row in rows { emitChat(row) }
    }

    func loadHistory(chatID: String, limit: Int) async throws {
        guard let manager else { throw StoreError.configuration("WhatsApp не подключён") }
        var bridgeError: NSError?
        _ = manager.loadHistory(chatID, limit: limit, error: &bridgeError)
        if let bridgeError { throw bridgeError }
    }
    func sendText(chatID: String, text: String) async throws -> String {
        guard let manager else { throw StoreError.configuration("WhatsApp не подключён") }
        var bridgeError: NSError?
        let result = manager.sendText(chatID, text: text, error: &bridgeError)
        if let bridgeError { throw bridgeError }
        return result
    }
    func logOut() async throws {
        guard let manager else { return }
        var bridgeError: NSError?
        _ = manager.logout(&bridgeError)
        if let bridgeError { throw bridgeError }
    }
    func close() { manager?.close(); manager = nil }

    private func receive(_ json: String) {
        guard let data = json.data(using: .utf8),
              let row = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = row["type"] as? String else { return }
        switch type {
        case "auth":
            let raw = row["state"] as? String ?? "error"
            let state: AuthState = raw == "ready" ? .ready : (raw == "connecting" ? .connecting : .loggedOut)
            event?(.auth(accountID: accountID, state: state, detail: row["detail"] as? String ?? ""))
        case "qr": event?(.qr(accountID: accountID, value: row["qr"] as? String ?? ""))
        case "chat": emitChat(row)
        case "text":
            event?(.text(accountID: accountID, chatID: row["chat_id"] as? String ?? "",
                         chatTitle: row["chat_title"] as? String ?? "",
                         providerID: row["message_id"] as? String ?? "", text: row["text"] as? String ?? "",
                         timestamp: Date(timeIntervalSince1970: (row["timestamp"] as? NSNumber)?.doubleValue ?? 0),
                         outgoing: row["outgoing"] as? Bool ?? false))
        case "error": event?(.error(accountID: accountID, message: row["detail"] as? String ?? "WhatsApp error"))
        default: break
        }
    }

    private func emitChat(_ row: [String: Any]) {
        let id = row["chat_id"] as? String ?? row["id"] as? String ?? ""
        guard !id.isEmpty else { return }
        event?(.chat(Chat(accountID: accountID, remoteID: id,
                          title: row["chat_title"] as? String ?? row["title"] as? String ?? id,
                          lastText: row["last_text"] as? String ?? "",
                          lastAt: Date(timeIntervalSince1970: (row["timestamp"] as? NSNumber)?.doubleValue
                                   ?? (row["last_at"] as? NSNumber)?.doubleValue ?? 0),
                          unread: (row["unread"] as? NSNumber)?.intValue ?? 0, secureState: .none)))
    }
}
