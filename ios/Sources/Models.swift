import Foundation

enum Provider: String, Codable, CaseIterable, Identifiable {
    case whatsapp
    case telegram

    var id: String { rawValue }
    var title: String { self == .whatsapp ? "WhatsApp" : "Telegram" }
    var loginHint: String { self == .whatsapp ? "QR-код" : "номер, код и 2FA" }
}

enum AuthState: String, Codable {
    case new, connecting, waitingQR, waitingPhone, waitingCode, waitingPassword, ready, loggedOut, error

    var title: String {
        switch self {
        case .new: return "Не подключён"
        case .connecting: return "Подключение…"
        case .waitingQR: return "Отсканируйте QR-код"
        case .waitingPhone: return "Введите номер"
        case .waitingCode: return "Введите код Telegram"
        case .waitingPassword: return "Введите пароль 2FA"
        case .ready: return "Готов"
        case .loggedOut: return "Сессия завершена"
        case .error: return "Ошибка"
        }
    }
}

enum SecureState: String, Codable {
    case none, negotiating, keyReady, verified, keyChanged, error
}

struct Account: Identifiable, Codable, Hashable {
    let id: UUID
    let provider: Provider
    let slot: Int
    var label: String
    var authState: AuthState
    var telegramAPIID: Int32
    var telegramAPIHash: String
    let createdAt: Date
}

struct Chat: Identifiable, Codable, Hashable {
    var id: String { accountID.uuidString + "\u{0}" + remoteID }
    let accountID: UUID
    let remoteID: String
    var title: String
    var lastText: String
    var lastAt: Date
    var unread: Int
    var secureState: SecureState
}

enum MessageSecurity: String, Codable {
    case encryptedVerified, encryptedUnverified, legacyPlain, failed
}

struct Message: Identifiable, Codable, Hashable {
    let id: UUID
    let accountID: UUID
    let chatID: String
    var providerMessageID: String
    let text: String
    let sentAt: Date
    let outgoing: Bool
    let security: MessageSecurity
}

enum StoreError: LocalizedError {
    case accountLimit(Provider)
    case totalLimit
    case missingAccount
    case configuration(String)

    var errorDescription: String? {
        switch self {
        case .accountLimit(let provider): return "Можно добавить не больше двух аккаунтов \(provider.title)"
        case .totalLimit: return "Можно добавить не больше четырёх аккаунтов"
        case .missingAccount: return "Аккаунт не найден"
        case .configuration(let text): return text
        }
    }
}
