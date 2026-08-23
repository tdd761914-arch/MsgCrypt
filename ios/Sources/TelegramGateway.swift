import Foundation
import TDLibFramework

private func jsonString(_ object: [String: Any]) throws -> String {
    String(data: try JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
}

private final class TDLibHub {
    static let shared = TDLibHub()
    private let queue = DispatchQueue(label: "dev.msgcrypt.tdlib.receive", qos: .userInitiated)
    private let lock = NSLock()
    private var sinks: [Int32: ([String: Any]) -> Void] = [:]

    private init() {
        queue.async { [weak self] in
            while let self {
                guard let pointer = td_receive(1.0) else { continue }
                let text = String(cString: pointer)
                guard let data = text.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let number = object["@client_id"] as? NSNumber else { continue }
                lock.lock(); let sink = sinks[number.int32Value]; lock.unlock()
                sink?(object)
            }
        }
    }

    func register(_ id: Int32, sink: @escaping ([String: Any]) -> Void) {
        lock.lock(); sinks[id] = sink; lock.unlock()
    }
    func remove(_ id: Int32) { lock.lock(); sinks[id] = nil; lock.unlock() }
    func send(_ id: Int32, _ object: [String: Any]) throws { td_send(id, try jsonString(object)) }
}

final class TelegramGateway: MessagingGateway {
    var event: ((GatewayEvent) -> Void)?
    private let accountID: UUID
    private let root: URL
    private let clientID: Int32
    private var phone = ""
    private var titles: [Int64: String] = [:]

    init(accountID: UUID, root: URL) {
        self.accountID = accountID
        self.root = root
        clientID = td_create_client_id()
        TDLibHub.shared.register(clientID) { [weak self] in self?.receive($0) }
    }

    func connect(account: Account, phone: String) async throws {
        guard account.telegramAPIID > 0, !account.telegramAPIHash.isEmpty else {
            throw StoreError.configuration("Введите Telegram api_id и api_hash")
        }
        guard !phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw StoreError.configuration("Введите номер Telegram")
        }
        self.phone = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        event?(.auth(accountID: accountID, state: .connecting, detail: ""))
        try send([
            "@type": "setTdlibParameters",
            "api_id": account.telegramAPIID,
            "api_hash": account.telegramAPIHash,
            "application_version": "0.1.0",
            "database_directory": root.appendingPathComponent("db").path,
            "database_encryption_key": "",
            "device_model": "iOS MsgCrypt",
            "enable_storage_optimizer": true,
            "files_directory": root.appendingPathComponent("files").path,
            "ignore_file_names": true,
            "system_language_code": Locale.current.language.languageCode?.identifier ?? "ru",
            "system_version": ProcessInfo.processInfo.operatingSystemVersionString,
            "use_chat_info_database": true,
            "use_file_database": false,
            "use_message_database": true,
            "use_secret_chats": false,
            "use_test_dc": false
        ])
        try sendPhone()
    }

    func submitCode(_ code: String) async throws {
        guard !code.isEmpty else { throw StoreError.configuration("Введите код") }
        try send(["@type": "checkAuthenticationCode", "code": code])
    }
    func submitPassword(_ password: String) async throws {
        guard !password.isEmpty else { throw StoreError.configuration("Введите пароль") }
        try send(["@type": "checkAuthenticationPassword", "password": password])
    }
    func loadChats() async throws { try send(["@type": "loadChats", "chat_list": ["@type": "chatListMain"], "limit": 100]) }
    func loadHistory(chatID: String, limit: Int) async throws {
        guard let id = Int64(chatID) else { throw StoreError.configuration("Неверный Telegram chat id") }
        try send(["@type": "getChatHistory", "chat_id": id, "from_message_id": 0, "offset": 0,
                  "limit": min(max(limit, 1), 100), "only_local": false,
                  "@extra": "history:\(id)"])
    }
    func sendText(chatID: String, text: String) async throws -> String {
        guard let id = Int64(chatID) else { throw StoreError.configuration("Неверный Telegram chat id") }
        let requestID = UUID().uuidString
        try send(["@type": "sendMessage", "chat_id": id,
                  "input_message_content": ["@type": "inputMessageText",
                                             "text": ["@type": "formattedText", "text": text, "entities": []],
                                             "link_preview_options": ["@type": "linkPreviewOptions", "is_disabled": true],
                                             "clear_draft": true], "@extra": requestID])
        return requestID
    }
    func logOut() async throws { try send(["@type": "logOut"]) }
    func close() { try? send(["@type": "close"]); TDLibHub.shared.remove(clientID) }

    private func sendPhone() throws {
        try send(["@type": "setAuthenticationPhoneNumber", "phone_number": phone,
                  "settings": ["@type": "phoneNumberAuthenticationSettings",
                               "allow_flash_call": false, "allow_missed_call": false,
                               "is_current_phone_number": true, "allow_sms_retriever_api": false,
                               "authentication_tokens": []]])
    }
    private func send(_ value: [String: Any]) throws { try TDLibHub.shared.send(clientID, value) }

    private func receive(_ value: [String: Any]) {
        let type = value["@type"] as? String ?? ""
        if type == "updateAuthorizationState", let auth = value["authorization_state"] as? [String: Any] {
            authChanged(auth["@type"] as? String ?? "")
        } else if type == "updateNewChat", let chat = value["chat"] as? [String: Any] {
            emitChat(chat)
        } else if type == "updateChatTitle", let id = (value["chat_id"] as? NSNumber)?.int64Value {
            titles[id] = value["title"] as? String ?? String(id)
        } else if type == "updateChatLastMessage", let id = (value["chat_id"] as? NSNumber)?.int64Value {
            emitMessage(value["last_message"] as? [String: Any], fallbackChatID: id, asChatOnly: true)
        } else if type == "updateNewMessage" {
            emitMessage(value["message"] as? [String: Any], fallbackChatID: nil, asChatOnly: false)
        } else if type == "messages", let rows = value["messages"] as? [[String: Any]] {
            rows.forEach { emitMessage($0, fallbackChatID: nil, asChatOnly: false) }
        } else if type == "error" {
            event?(.error(accountID: accountID, message: value["message"] as? String ?? "Telegram error"))
        }
    }

    private func authChanged(_ type: String) {
        switch type {
        case "authorizationStateWaitPhoneNumber": try? sendPhone(); event?(.auth(accountID: accountID, state: .waitingPhone, detail: ""))
        case "authorizationStateWaitCode": event?(.auth(accountID: accountID, state: .waitingCode, detail: ""))
        case "authorizationStateWaitPassword": event?(.auth(accountID: accountID, state: .waitingPassword, detail: ""))
        case "authorizationStateReady": event?(.auth(accountID: accountID, state: .ready, detail: phone))
        case "authorizationStateClosing": event?(.auth(accountID: accountID, state: .connecting, detail: ""))
        case "authorizationStateClosed": event?(.auth(accountID: accountID, state: .loggedOut, detail: ""))
        default: break
        }
    }

    private func emitChat(_ chat: [String: Any]) {
        guard let id = (chat["id"] as? NSNumber)?.int64Value else { return }
        let title = chat["title"] as? String ?? String(id)
        titles[id] = title
        let last = chat["last_message"] as? [String: Any]
        event?(.chat(Chat(accountID: accountID, remoteID: String(id), title: title,
                          lastText: text(last) ?? "", lastAt: date(last),
                          unread: (chat["unread_count"] as? NSNumber)?.intValue ?? 0, secureState: .none)))
    }

    private func emitMessage(_ message: [String: Any]?, fallbackChatID: Int64?, asChatOnly: Bool) {
        guard let message, let id = (message["chat_id"] as? NSNumber)?.int64Value ?? fallbackChatID,
              let body = text(message) else { return }
        let title = titles[id] ?? String(id)
        if asChatOnly {
            event?(.chat(Chat(accountID: accountID, remoteID: String(id), title: title, lastText: body,
                              lastAt: date(message), unread: 0, secureState: .none)))
        } else {
            event?(.text(accountID: accountID, chatID: String(id), chatTitle: title,
                         providerID: String((message["id"] as? NSNumber)?.int64Value ?? 0), text: body,
                         timestamp: date(message), outgoing: message["is_outgoing"] as? Bool ?? false))
        }
    }

    private func text(_ message: [String: Any]?) -> String? {
        guard let content = message?["content"] as? [String: Any], content["@type"] as? String == "messageText",
              let formatted = content["text"] as? [String: Any] else { return nil }
        return formatted["text"] as? String
    }
    private func date(_ message: [String: Any]?) -> Date {
        Date(timeIntervalSince1970: (message?["date"] as? NSNumber)?.doubleValue ?? 0)
    }
}
