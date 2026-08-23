import Foundation

enum GatewayEvent {
    case auth(accountID: UUID, state: AuthState, detail: String)
    case qr(accountID: UUID, value: String)
    case chat(Chat)
    case text(accountID: UUID, chatID: String, chatTitle: String, providerID: String,
              text: String, timestamp: Date, outgoing: Bool)
    case error(accountID: UUID, message: String)
}

protocol MessagingGateway: AnyObject {
    var event: ((GatewayEvent) -> Void)? { get set }
    func connect(account: Account, phone: String) async throws
    func submitCode(_ code: String) async throws
    func submitPassword(_ password: String) async throws
    func loadChats() async throws
    func loadHistory(chatID: String, limit: Int) async throws
    func sendText(chatID: String, text: String) async throws -> String
    func logOut() async throws
    func close()
}

extension MessagingGateway {
    func submitCode(_ code: String) async throws { throw StoreError.configuration("Этот транспорт не принимает код") }
    func submitPassword(_ password: String) async throws { throw StoreError.configuration("Этот транспорт не принимает пароль") }
}
