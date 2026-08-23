import Foundation
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    static let maxPerProvider = 2
    static let maxTotal = 4

    @Published private(set) var accounts: [Account] = []
    @Published private(set) var chats: [Chat] = []
    @Published private(set) var messages: [Message] = []
    @Published var lastError: String?

    let messaging: MessagingService
    private let persistenceURL: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MsgCrypt", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        persistenceURL = base.appendingPathComponent("store.json")
        messaging = MessagingService(root: base)
        load()
        messaging.attach(store: self)
    }

    func addAccount(_ provider: Provider) throws -> Account {
        guard accounts.count < Self.maxTotal else { throw StoreError.totalLimit }
        let used = accounts.filter { $0.provider == provider }.map(\.slot)
        guard used.count < Self.maxPerProvider else { throw StoreError.accountLimit(provider) }
        let slot = (0..<Self.maxPerProvider).first { !used.contains($0) }!
        let account = Account(id: UUID(), provider: provider, slot: slot,
                              label: "\(provider.title) \(slot + 1)", authState: .new,
                              telegramAPIID: 0, telegramAPIHash: "", createdAt: Date())
        accounts.append(account)
        save()
        return account
    }

    func deleteAccount(_ id: UUID) {
        messaging.removeAccount(id)
        accounts.removeAll { $0.id == id }
        chats.removeAll { $0.accountID == id }
        messages.removeAll { $0.accountID == id }
        save()
    }

    func account(_ id: UUID) -> Account? { accounts.first { $0.id == id } }
    func accountChats(_ id: UUID) -> [Chat] {
        chats.filter { $0.accountID == id }.sorted { $0.lastAt > $1.lastAt }
    }
    func chatMessages(_ accountID: UUID, _ chatID: String) -> [Message] {
        messages.filter { $0.accountID == accountID && $0.chatID == chatID }.sorted { $0.sentAt < $1.sentAt }
    }

    func updateAuth(_ id: UUID, state: AuthState, detail: String? = nil) {
        guard let index = accounts.firstIndex(where: { $0.id == id }) else { return }
        accounts[index].authState = state
        if state == .ready, let detail, !detail.isEmpty { accounts[index].label = detail }
        save()
    }

    func configureTelegram(_ id: UUID, apiID: Int32, apiHash: String) throws -> Account {
        guard apiID > 0, apiHash.trimmingCharacters(in: .whitespacesAndNewlines).count >= 16 else {
            throw StoreError.configuration("Введите корректные Telegram api_id и api_hash")
        }
        guard let index = accounts.firstIndex(where: { $0.id == id }) else { throw StoreError.missingAccount }
        accounts[index].telegramAPIID = apiID
        accounts[index].telegramAPIHash = apiHash.trimmingCharacters(in: .whitespacesAndNewlines)
        save()
        return accounts[index]
    }

    func upsertChat(_ next: Chat) {
        if let index = chats.firstIndex(where: { $0.accountID == next.accountID && $0.remoteID == next.remoteID }) {
            let security = chats[index].secureState
            chats[index] = next
            chats[index].secureState = security
        } else { chats.append(next) }
        save()
    }

    func setSecurity(_ accountID: UUID, _ chatID: String, _ state: SecureState) {
        if let index = chats.firstIndex(where: { $0.accountID == accountID && $0.remoteID == chatID }) {
            chats[index].secureState = state
            save()
        }
    }

    func addMessage(_ message: Message, title: String) {
        guard !messages.contains(where: { $0.id == message.id }) else { return }
        messages.append(message)
        if messages.count > 20_000 { messages.removeFirst(messages.count - 20_000) }
        if let index = chats.firstIndex(where: { $0.accountID == message.accountID && $0.remoteID == message.chatID }) {
            chats[index].lastText = message.text
            chats[index].lastAt = message.sentAt
        } else {
            chats.append(Chat(accountID: message.accountID, remoteID: message.chatID, title: title,
                              lastText: message.text, lastAt: message.sentAt, unread: 0, secureState: .none))
        }
        save()
    }

    private struct Snapshot: Codable {
        let accounts: [Account]
        let chats: [Chat]
        let messages: [Message]
    }

    private func load() {
        guard let data = try? Data(contentsOf: persistenceURL),
              let snapshot = decode(data) else { return }
        accounts = snapshot.accounts
        chats = snapshot.chats
        messages = snapshot.messages
    }

    private func decode(_ data: Data) -> Snapshot? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Snapshot.self, from: data)
    }

    private func save() {
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            let data = try encoder.encode(Snapshot(accounts: accounts, chats: chats, messages: messages))
            try data.write(to: persistenceURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch { lastError = "Не удалось сохранить данные: \(error.localizedDescription)" }
    }
}
