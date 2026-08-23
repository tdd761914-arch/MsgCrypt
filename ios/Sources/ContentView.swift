import CoreImage.CIFilterBuiltins
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: AppStore
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                Section("Аккаунты \(store.accounts.count)/\(AppStore.maxTotal)") {
                    ForEach(store.accounts) { account in
                        NavigationLink {
                            if account.authState == .ready { ChatListView(accountID: account.id) }
                            else { LoginView(accountID: account.id) }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(account.label).font(.headline)
                                Text("\(account.provider.title) · \(account.authState.title)").foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { offsets in offsets.map { store.accounts[$0].id }.forEach(store.deleteAccount) }
                }
                Section {
                    Button("Добавить WhatsApp или Telegram", systemImage: "plus.circle") { adding = true }
                        .disabled(store.accounts.count >= AppStore.maxTotal)
                }
                Section("Правила") {
                    Text("Только текст. Новые исходящие сообщения отправляются исключительно после MsgCrypt-handshake и ручной сверки отпечатка.")
                }
            }
            .navigationTitle("MsgCrypt")
            .confirmationDialog("Тип аккаунта", isPresented: $adding) {
                ForEach(Provider.allCases) { provider in
                    Button("\(provider.title) — \(provider.loginHint)") { add(provider) }
                }
            }
            .msgCryptError()
        }
    }

    private func add(_ provider: Provider) {
        do { _ = try store.addAccount(provider) }
        catch { store.lastError = error.localizedDescription }
    }
}

struct LoginView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var messaging: MessagingService
    let accountID: UUID
    @State private var apiID = ""
    @State private var apiHash = ""
    @State private var phone = ""
    @State private var value = ""
    @State private var startedWhatsApp = false

    private var account: Account? { store.account(accountID) }

    var body: some View {
        Form {
            if let account {
                Section(account.provider.title) { Text(account.authState.title) }
                if account.provider == .whatsapp {
                    Section("QR-код") {
                        if let text = messaging.qrCodes[accountID], !text.isEmpty {
                            QRCodeView(text: text).frame(maxWidth: .infinity).frame(height: 320)
                            Text("WhatsApp → Связанные устройства → Привязать устройство")
                        } else { ProgressView("Получение QR…") }
                        Button("Обновить QR") { connectWhatsApp(account) }
                    }
                } else {
                    telegramForm(account)
                }
            } else { Text("Аккаунт удалён") }
        }
        .navigationTitle("Вход")
        .onAppear {
            guard let account else { return }
            if apiID.isEmpty, account.telegramAPIID > 0 { apiID = String(account.telegramAPIID) }
            if apiHash.isEmpty { apiHash = account.telegramAPIHash }
            if account.provider == .whatsapp, !startedWhatsApp {
                startedWhatsApp = true
                connectWhatsApp(account)
            }
        }
        .msgCryptError()
    }

    @ViewBuilder private func telegramForm(_ account: Account) -> some View {
        switch account.authState {
        case .waitingCode:
            Section("Код") {
                TextField("Код Telegram", text: $value).keyboardType(.numberPad)
                Button("Продолжить") { run { try await messaging.submitCode(account, value) } }
            }
        case .waitingPassword:
            Section("Двухфакторная защита") {
                SecureField("Пароль 2FA", text: $value)
                Button("Продолжить") { run { try await messaging.submitPassword(account, value) } }
            }
        case .connecting:
            Section { ProgressView("Подключение…") }
        default:
            Section("Telegram API") {
                TextField("api_id", text: $apiID).keyboardType(.numberPad)
                SecureField("api_hash", text: $apiHash)
                Link("Получить на my.telegram.org", destination: URL(string: "https://my.telegram.org")!)
            }
            Section("Телефон") {
                TextField("+380…", text: $phone).keyboardType(.phonePad)
                Button("Войти") {
                    guard let number = Int32(apiID) else { store.lastError = "api_id должен быть числом"; return }
                    run { try await messaging.configureAndConnectTelegram(accountID, apiID: number, apiHash: apiHash, phone: phone) }
                }
            }
        }
    }

    private func connectWhatsApp(_ account: Account) { run { try await messaging.connect(account) } }
    private func run(_ operation: @escaping () async throws -> Void) {
        Task { do { try await operation() } catch { store.lastError = error.localizedDescription } }
    }
}

struct ChatListView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var messaging: MessagingService
    let accountID: UUID
    private var account: Account? { store.account(accountID) }

    var body: some View {
        List(store.accountChats(accountID)) { chat in
            NavigationLink {
                ChatView(accountID: accountID, chatID: chat.remoteID, title: chat.title)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(chat.title).font(.headline)
                    Text("\(security(chat.secureState)) · \(chat.lastText.isEmpty ? "Нет текста" : chat.lastText)")
                        .lineLimit(2).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(account?.label ?? "Чаты")
        .refreshable { if let account { try? await messaging.loadChats(account) } }
        .task { if let account { try? await messaging.loadChats(account) } }
        .msgCryptError()
    }

    private func security(_ state: SecureState) -> String {
        switch state {
        case .verified: return "🔒 проверен"
        case .keyReady: return "🔑 сверить ключ"
        case .negotiating: return "⏳ handshake"
        case .keyChanged: return "⚠ ключ изменился"
        default: return "без MsgCrypt"
        }
    }
}

struct ChatView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var messaging: MessagingService
    let accountID: UUID
    let chatID: String
    let title: String
    @State private var draft = ""
    @State private var sending = false
    @State private var showingVerify = false

    private var account: Account? { store.account(accountID) }
    private var state: SecureState { messaging.state(accountID, chatID) }
    private var fingerprint: String { messaging.fingerprint(accountID, chatID) }

    var body: some View {
        VStack(spacing: 0) {
            securityHeader
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(store.chatMessages(accountID, chatID)) { message in MessageBubble(message: message).id(message.id) }
                    }.padding()
                }
                .onChange(of: store.messages.count) { _ in
                    if let last = store.chatMessages(accountID, chatID).last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            HStack {
                TextField(state == .verified ? "Зашифрованное сообщение" : "Сначала подтвердите ключ", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder).lineLimit(1...5).disabled(state != .verified || sending)
                Button { send() } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }
                    .disabled(state != .verified || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || sending)
            }.padding()
        }
        .navigationTitle(title)
        .task { if let account { try? await messaging.loadHistory(account, chatID: chatID) } }
        .alert("Подтвердить ключ?", isPresented: $showingVerify) {
            Button("Отмена", role: .cancel) {}
            Button("Я сверил отпечаток") { verify() }
        } message: { Text(fingerprint) }
        .msgCryptError()
    }

    @ViewBuilder private var securityHeader: some View {
        VStack(spacing: 6) {
            Text(securityText).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
            if state == .none || state == .error || state == .keyChanged {
                Button("Начать MsgCrypt handshake") { handshake() }.buttonStyle(.borderedProminent)
            } else if state == .keyReady {
                Button("Сверить и подтвердить отпечаток") { showingVerify = true }.buttonStyle(.borderedProminent)
            }
        }.padding().background(.thinMaterial)
    }

    private var securityText: String {
        switch state {
        case .verified: return "🔒 Ключ проверен\n\(fingerprint)"
        case .keyReady: return "🔑 Сверьте отпечаток другим каналом\n\(fingerprint)"
        case .negotiating: return "⏳ Ожидание MsgCrypt собеседника"
        case .keyChanged: return "⚠ Ключ изменился — отправка заблокирована"
        case .error: return "⚠ Ошибка защищённой сессии"
        case .none: return "🔓 MsgCrypt-сессия не создана"
        }
    }

    private func handshake() {
        guard let account else { return }
        Task { do { try await messaging.beginHandshake(account, chatID: chatID) }
               catch { store.lastError = error.localizedDescription } }
    }
    private func verify() {
        guard let account else { return }
        do { try messaging.verify(account, chatID: chatID) }
        catch { store.lastError = error.localizedDescription }
    }
    private func send() {
        guard let account else { return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        sending = true
        Task { defer { sending = false }
            do { try await messaging.sendEncrypted(account, chatID: chatID, text: text); draft = "" }
            catch { store.lastError = error.localizedDescription }
        }
    }
}

private struct MessageBubble: View {
    let message: Message
    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 50) }
            VStack(alignment: .leading, spacing: 3) {
                Text(message.text)
                Text("\(badge) · \(message.sentAt.formatted(date: .numeric, time: .shortened))")
                    .font(.caption2).foregroundStyle(.secondary)
            }.padding(10).background(message.outgoing ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            if !message.outgoing { Spacer(minLength: 50) }
        }
    }
    private var badge: String {
        switch message.security {
        case .encryptedVerified: return "🔒"
        case .encryptedUnverified: return "🔐 не проверено"
        case .legacyPlain: return "обычное входящее"
        case .failed: return "ошибка"
        }
    }
}

private struct QRCodeView: View {
    let text: String
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()
    var body: some View {
        if let image = image { Image(uiImage: image).interpolation(.none).resizable().scaledToFit() }
        else { Text("Не удалось создать QR") }
    }
    private var image: UIImage? {
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private extension View {
    func msgCryptError() -> some View {
        modifier(MsgCryptErrorModifier())
    }
}

private struct MsgCryptErrorModifier: ViewModifier {
    @EnvironmentObject private var store: AppStore
    func body(content: Content) -> some View {
        content.alert("MsgCrypt", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
            Button("OK") { store.lastError = nil }
        } message: { Text(store.lastError ?? "") }
    }
}
