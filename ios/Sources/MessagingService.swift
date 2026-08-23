import Foundation
import SwiftUI

@MainActor
final class MessagingService: ObservableObject {
    @Published private(set) var qrCodes: [UUID: String] = [:]
    @Published private(set) var fingerprints: [String: String] = [:]

    private weak var store: AppStore?
    private let root: URL
    private let identities = IdentityStore()
    private var gateways: [UUID: MessagingGateway] = [:]
    private var sessions: [String: MsgCryptoSession] = [:]

    init(root: URL) { self.root = root }
    func attach(store: AppStore) { self.store = store }

    func connect(_ account: Account, phone: String = "") async throws {
        try await gateway(account).connect(account: account, phone: phone)
    }

    func configureAndConnectTelegram(_ accountID: UUID, apiID: Int32, apiHash: String, phone: String) async throws {
        guard let store else { throw StoreError.missingAccount }
        let account = try store.configureTelegram(accountID, apiID: apiID, apiHash: apiHash)
        gateways[accountID]?.close()
        gateways[accountID] = nil
        try await connect(account, phone: phone)
    }

    func submitCode(_ account: Account, _ code: String) async throws { try await gateway(account).submitCode(code) }
    func submitPassword(_ account: Account, _ password: String) async throws { try await gateway(account).submitPassword(password) }
    func loadChats(_ account: Account) async throws { try await gateway(account).loadChats() }
    func loadHistory(_ account: Account, chatID: String) async throws { try await gateway(account).loadHistory(chatID: chatID, limit: 100) }

    func beginHandshake(_ account: Account, chatID: String) async throws {
        let crypto = try session(account.id, chatID)
        store?.setSecurity(account.id, chatID, .negotiating)
        try await send(try crypto.beginHandshake(), through: gateway(account), chatID: chatID)
    }

    func verify(_ account: Account, chatID: String) throws {
        let crypto = try session(account.id, chatID)
        try crypto.verifyPeer()
        store?.setSecurity(account.id, chatID, .verified)
    }

    func sendEncrypted(_ account: Account, chatID: String, text: String) async throws {
        let crypto = try session(account.id, chatID)
        try await send(try crypto.sealText(text), through: gateway(account), chatID: chatID)
        store?.addMessage(Message(id: UUID(), accountID: account.id, chatID: chatID, providerMessageID: "",
                                  text: text, sentAt: Date(), outgoing: true, security: .encryptedVerified), title: chatID)
    }

    func state(_ accountID: UUID, _ chatID: String) -> SecureState { sessions[key(accountID, chatID)]?.state ?? .none }
    func fingerprint(_ accountID: UUID, _ chatID: String) -> String { fingerprints[key(accountID, chatID)] ?? "" }

    func removeAccount(_ id: UUID) {
        gateways.removeValue(forKey: id)?.close()
        sessions = sessions.filter { !$0.key.hasPrefix(id.uuidString + "\u{0}") }
        qrCodes[id] = nil
        identities.delete(id)
        for provider in Provider.allCases {
            let folder = root.appendingPathComponent(provider.rawValue, isDirectory: true)
                .appendingPathComponent(id.uuidString, isDirectory: true)
            if folder.path.hasPrefix(root.path + "/") { try? FileManager.default.removeItem(at: folder) }
        }
    }

    private func gateway(_ account: Account) -> MessagingGateway {
        if let existing = gateways[account.id] { return existing }
        let folder = root.appendingPathComponent(account.provider.rawValue, isDirectory: true)
            .appendingPathComponent(account.id.uuidString, isDirectory: true)
        let created: MessagingGateway = account.provider == .whatsapp
            ? WhatsAppGateway(accountID: account.id, root: folder)
            : TelegramGateway(accountID: account.id, root: folder)
        created.event = { [weak self] event in Task { @MainActor in self?.receive(event) } }
        gateways[account.id] = created
        return created
    }

    private func session(_ accountID: UUID, _ chatID: String) throws -> MsgCryptoSession {
        let id = key(accountID, chatID)
        if let current = sessions[id] { return current }
        let created = try MsgCryptoSession(accountID: accountID, identities: identities)
        sessions[id] = created
        return created
    }

    private func send(_ carriers: [String], through gateway: MessagingGateway, chatID: String) async throws {
        for carrier in carriers { _ = try await gateway.sendText(chatID: chatID, text: carrier) }
    }

    private func receive(_ event: GatewayEvent) {
        switch event {
        case .auth(let id, let state, let detail): store?.updateAuth(id, state: state, detail: detail)
        case .qr(let id, let value): qrCodes[id] = value; store?.updateAuth(id, state: .waitingQR)
        case .chat(let chat): store?.upsertChat(chat)
        case .error(_, let message): store?.lastError = message
        case .text(let accountID, let chatID, let title, let providerID, let transport, let timestamp, let outgoing):
            receiveText(accountID: accountID, chatID: chatID, title: title, providerID: providerID,
                        transport: transport, timestamp: timestamp, outgoing: outgoing)
        }
    }

    private func receiveText(accountID: UUID, chatID: String, title: String, providerID: String,
                             transport: String, timestamp: Date, outgoing: Bool) {
        guard let account = store?.account(accountID) else { return }
        do {
            switch try session(accountID, chatID).receive(transport) {
            case .legacy(let text):
                store?.addMessage(Message(id: UUID(), accountID: accountID, chatID: chatID,
                                          providerMessageID: providerID, text: text, sentAt: timestamp,
                                          outgoing: outgoing, security: .legacyPlain), title: title)
            case .keyReady(let fingerprint, let outbound):
                fingerprints[key(accountID, chatID)] = fingerprint
                store?.setSecurity(accountID, chatID, .keyReady)
                Task { try? await send(outbound, through: gateway(account), chatID: chatID) }
            case .keyChanged(let fingerprint, let outbound):
                fingerprints[key(accountID, chatID)] = fingerprint
                store?.setSecurity(accountID, chatID, .keyChanged)
                Task { try? await send(outbound, through: gateway(account), chatID: chatID) }
            case .text(let text, let sentAt, let verified, let id):
                store?.addMessage(Message(id: id, accountID: accountID, chatID: chatID,
                                          providerMessageID: providerID, text: text, sentAt: sentAt,
                                          outgoing: outgoing, security: verified ? .encryptedVerified : .encryptedUnverified), title: title)
            case .closed: store?.setSecurity(accountID, chatID, .none)
            case .partial, .consumed: break
            }
        } catch { store?.lastError = "Повреждён или неподлинный MsgCrypt-пакет: \(error.localizedDescription)" }
    }

    private func key(_ accountID: UUID, _ chatID: String) -> String { accountID.uuidString + "\u{0}" + chatID }
}
