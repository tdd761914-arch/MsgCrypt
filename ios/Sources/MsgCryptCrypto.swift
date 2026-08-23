import CryptoKit
import Foundation
import Security

enum MsgCryptoError: LocalizedError {
    case invalid(String)
    case blocked

    var errorDescription: String? {
        switch self {
        case .invalid(let text): return text
        case .blocked: return "Отправка заблокирована: подтвердите ключ собеседника"
        }
    }
}

private extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) {
        var big = value.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }

    static func uuid(_ value: UUID) -> Data {
        var raw = value.uuid
        return Swift.withUnsafeBytes(of: &raw) { Data($0) }
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }

    func lexicographicallyCompared(to other: Data) -> Int {
        for (left, right) in zip(self, other) {
            if left != right { return left < right ? -1 : 1 }
        }
        return count == other.count ? 0 : (count < other.count ? -1 : 1)
    }
}

private struct ByteReader {
    let data: Data
    var offset = 0
    var remaining: Int { data.count - offset }

    mutating func take(_ count: Int) throws -> Data {
        guard count >= 0, remaining >= count else { throw MsgCryptoError.invalid("Обрезанный MsgCrypt-пакет") }
        defer { offset += count }
        return data.subdata(in: offset..<(offset + count))
    }

    mutating func u8() throws -> UInt8 { try take(1)[0] }
    mutating func u16() throws -> UInt16 { try integer(2) }
    mutating func u32() throws -> UInt32 { try integer(4) }
    mutating func u64() throws -> UInt64 { try integer(8) }

    private mutating func integer<T: FixedWidthInteger>(_ size: Int) throws -> T {
        let bytes = try take(size)
        return bytes.reduce(T.zero) { ($0 << 8) | T($1) }
    }
}

private func uuid(from data: Data) throws -> UUID {
    guard data.count == 16 else { throw MsgCryptoError.invalid("UUID должен занимать 16 байт") }
    let b = [UInt8](data)
    return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                       b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
}

private struct MsgIdentity {
    let nodeID: Data
    let signingKey: P256.Signing.PrivateKey
}

final class IdentityStore {
    private let service = "dev.msgcrypt.identity.v1"

    fileprivate func loadOrCreate(_ accountID: UUID) throws -> MsgIdentity {
        let account = accountID.uuidString
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let blob = result as? Data {
            guard blob.count == 48 else { throw MsgCryptoError.invalid("Повреждён локальный ключ MsgCrypt") }
            let key = try P256.Signing.PrivateKey(rawRepresentation: Data(blob.prefix(32)))
            return MsgIdentity(nodeID: Data(blob.suffix(16)), signingKey: key)
        }
        guard status == errSecItemNotFound else { throw MsgCryptoError.invalid("Keychain: \(status)") }

        let key = P256.Signing.PrivateKey()
        var node = Data(count: 16)
        let randomStatus = node.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, 16, bytes.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw MsgCryptoError.invalid("Не удалось создать node ID") }
        var blob = key.rawRepresentation
        blob.append(node)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: blob
        ]
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw MsgCryptoError.invalid("Не удалось сохранить ключ: \(addStatus)") }
        return MsgIdentity(nodeID: node, signingKey: key)
    }

    func delete(_ accountID: UUID) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountID.uuidString
        ] as CFDictionary)
    }
}

private final class WordCoder {
    private let words: [String]
    private let reverse: [String: UInt8]

    init() throws {
        guard let url = Bundle.main.url(forResource: "wordcoder-ru", withExtension: "json") else {
            throw MsgCryptoError.invalid("Нет словаря WordCoder")
        }
        let object = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: url))
        var ordered: [String] = []
        for value in 0...255 {
            let key = String(format: "0x%02X", value)
            guard let word = object[key], !word.isEmpty else { throw MsgCryptoError.invalid("Нет WordCoder \(key)") }
            ordered.append(word)
        }
        guard Set(ordered).count == 256 else { throw MsgCryptoError.invalid("WordCoder содержит повторы") }
        words = ordered
        reverse = Dictionary(uniqueKeysWithValues: ordered.enumerated().map { ($0.element, UInt8($0.offset)) })
    }

    func encode(_ data: Data) -> String { data.map { words[Int($0)] }.joined(separator: " ") }
    func decode(_ values: ArraySlice<Substring>) throws -> Data {
        var output = Data(capacity: values.count)
        for value in values {
            guard let byte = reverse[String(value)] else { throw MsgCryptoError.invalid("Неизвестное слово WordCoder") }
            output.append(byte)
        }
        return output
    }
}

private struct CarrierResult {
    let isCarrier: Bool
    let isComplete: Bool
    let id: UUID?
    let packet: Data?
}

private final class CarrierCodec {
    static let prefix = "MSGCRYPT1"
    static let chunkBytes = 160
    static let maxChunks = 512
    static let maxPacketBytes = 65_536

    private struct Assembly {
        let createdAt: Date
        var parts: [Data?]
    }
    private let coder: WordCoder
    private var assemblies: [UUID: Assembly] = [:]

    init(coder: WordCoder) { self.coder = coder }

    func encode(id: UUID, packet: Data) throws -> [String] {
        guard !packet.isEmpty, packet.count <= Self.maxPacketBytes else { throw MsgCryptoError.invalid("Неверный размер пакета") }
        let count = (packet.count + Self.chunkBytes - 1) / Self.chunkBytes
        guard count <= Self.maxChunks else { throw MsgCryptoError.invalid("Слишком много чанков") }
        let idText = Data.uuid(id).hex
        return (0..<count).map { index in
            let start = index * Self.chunkBytes
            let end = min(packet.count, start + Self.chunkBytes)
            return "\(Self.prefix) \(idText) \(index)/\(count) \(coder.encode(packet.subdata(in: start..<end)))"
        }
    }

    func ingest(_ text: String) throws -> CarrierResult {
        guard text.hasPrefix(Self.prefix + " ") else { return CarrierResult(isCarrier: false, isComplete: false, id: nil, packet: nil) }
        assemblies = assemblies.filter { Date().timeIntervalSince($0.value.createdAt) < 600 }
        let fields = text.split(whereSeparator: { $0.isWhitespace })
        guard fields.count >= 4, fields[0] == Substring(Self.prefix), fields[1].count == 32,
              let idData = Data(hex: String(fields[1])) else { throw MsgCryptoError.invalid("Повреждён carrier") }
        let id = try uuid(from: idData)
        let position = fields[2].split(separator: "/", omittingEmptySubsequences: false)
        guard position.count == 2, let index = Int(position[0]), let count = Int(position[1]),
              count > 0, count <= Self.maxChunks, index >= 0, index < count else {
            throw MsgCryptoError.invalid("Неверный номер чанка")
        }
        let chunk = try coder.decode(fields[3...])
        guard !chunk.isEmpty, chunk.count <= Self.chunkBytes else { throw MsgCryptoError.invalid("Неверный размер чанка") }
        var assembly = assemblies[id] ?? Assembly(createdAt: Date(), parts: Array(repeating: nil, count: count))
        guard assembly.parts.count == count else { assemblies[id] = nil; throw MsgCryptoError.invalid("Конфликт числа чанков") }
        if let old = assembly.parts[index], old != chunk { assemblies[id] = nil; throw MsgCryptoError.invalid("Конфликтующий чанк") }
        assembly.parts[index] = chunk
        assemblies[id] = assembly
        guard assembly.parts.allSatisfy({ $0 != nil }) else { return CarrierResult(isCarrier: true, isComplete: false, id: id, packet: nil) }
        let packet = assembly.parts.compactMap { $0 }.reduce(into: Data()) { $0.append($1) }
        assemblies[id] = nil
        guard packet.count <= Self.maxPacketBytes else { throw MsgCryptoError.invalid("Собранный пакет слишком велик") }
        return CarrierResult(isCarrier: true, isComplete: true, id: id, packet: packet)
    }
}

private extension Data {
    init?(hex: String) {
        guard hex.count.isMultiple(of: 2) else { return nil }
        self.init(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let end = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<end], radix: 16) else { return nil }
            append(byte)
            index = end
        }
    }
}

private struct SignedPacket {
    static let hello: UInt8 = 1
    static let data: UInt8 = 2
    static let close: UInt8 = 3

    let kind: UInt8
    let senderNodeID: Data
    let sessionID: UUID
    let messageID: UUID
    let timestamp: UInt64
    let counter: UInt64
    let payload: Data
    let signature: Data
    let signedBytes: Data
    let aad: Data
}

private enum PacketCodec {
    static let headerSize = 68

    static func aad(kind: UInt8, nodeID: Data, sessionID: UUID, messageID: UUID,
                    timestamp: UInt64, counter: UInt64) throws -> Data {
        guard (SignedPacket.hello...SignedPacket.close).contains(kind), nodeID.count == 16 else {
            throw MsgCryptoError.invalid("Неверный заголовок MsgCrypt")
        }
        var result = Data([0x4d, 0x43, 1, kind])
        result.append(nodeID)
        result.append(.uuid(sessionID))
        result.append(.uuid(messageID))
        result.appendBE(timestamp)
        result.appendBE(counter)
        return result
    }

    static func signed(_ aad: Data, payload: Data) throws -> Data {
        guard aad.count == headerSize, payload.count <= 60 * 1024 else { throw MsgCryptoError.invalid("Payload слишком велик") }
        var result = aad
        result.appendBE(UInt32(payload.count))
        result.append(payload)
        return result
    }

    static func serialize(_ signed: Data, signature: Data) throws -> Data {
        guard signature.count >= 8, signature.count <= 128 else { throw MsgCryptoError.invalid("Неверная подпись") }
        var result = signed
        result.appendBE(UInt16(signature.count))
        result.append(signature)
        return result
    }

    static func parse(_ raw: Data) throws -> SignedPacket {
        guard raw.count >= headerSize + 14, raw.count <= CarrierCodec.maxPacketBytes else {
            throw MsgCryptoError.invalid("Неверный размер SignedPacket")
        }
        var input = ByteReader(data: raw)
        guard try input.take(2) == Data([0x4d, 0x43]), try input.u8() == 1 else {
            throw MsgCryptoError.invalid("Неверная версия MsgCrypt")
        }
        let kind = try input.u8()
        guard (SignedPacket.hello...SignedPacket.close).contains(kind) else { throw MsgCryptoError.invalid("Неизвестный тип пакета") }
        let node = try input.take(16)
        let session = try uuid(from: input.take(16))
        let message = try uuid(from: input.take(16))
        let timestamp = try input.u64()
        let counter = try input.u64()
        let aad = raw.prefix(headerSize)
        let payloadLength = Int(try input.u32())
        guard payloadLength <= 60 * 1024, input.remaining >= payloadLength + 2 else { throw MsgCryptoError.invalid("Неверная длина payload") }
        let payload = try input.take(payloadLength)
        let signedEnd = input.offset
        let signatureLength = Int(try input.u16())
        guard signatureLength >= 8, signatureLength <= 128, input.remaining == signatureLength else {
            throw MsgCryptoError.invalid("Неверная длина подписи")
        }
        let signature = try input.take(signatureLength)
        return SignedPacket(kind: kind, senderNodeID: node, sessionID: session, messageID: message,
                            timestamp: timestamp, counter: counter, payload: payload, signature: signature,
                            signedBytes: raw.prefix(signedEnd), aad: Data(aad))
    }
}

enum CryptoInbound {
    case legacy(String)
    case partial
    case consumed
    case keyReady(fingerprint: String, outbound: [String])
    case keyChanged(fingerprint: String, outbound: [String])
    case text(value: String, sentAt: Date, verified: Bool, id: UUID)
    case closed
}

final class MsgCryptoSession {
    private let identity: MsgIdentity
    private let carriers: CarrierCodec
    private(set) var state: SecureState = .none
    private(set) var peerFingerprint = ""
    private var sessionID: UUID?
    private var helloSentFor: UUID?
    private var localEphemeral: P256.KeyAgreement.PrivateKey?
    private var peerIdentity: P256.Signing.PublicKey?
    private var peerEphemeral: P256.KeyAgreement.PublicKey?
    private var peerNodeID: Data?
    private var trustedPeerKey: Data?
    private var sessionKey: SymmetricKey?
    private var sendCounter: UInt64 = 0
    private var seen = Set<UUID>()
    private var seenOrder: [UUID] = []

    init(accountID: UUID, identities: IdentityStore = IdentityStore()) throws {
        identity = try identities.loadOrCreate(accountID)
        carriers = CarrierCodec(coder: try WordCoder())
    }

    func beginHandshake() throws -> [String] {
        if state == .verified || state == .keyReady { return [] }
        reset(UUID())
        state = .negotiating
        return try helloCarriers()
    }

    func verifyPeer() throws {
        guard state == .keyReady, let peerIdentity, let compact = peerIdentity.compactRepresentation else {
            throw MsgCryptoError.invalid("Ключ собеседника ещё не получен")
        }
        trustedPeerKey = compact
        state = .verified
    }

    func sealText(_ text: String) throws -> [String] {
        guard state == .verified, let key = sessionKey, peerIdentity != nil, let sessionID else { throw MsgCryptoError.blocked }
        let utf8 = Data(text.utf8)
        guard !utf8.isEmpty, utf8.count <= 16 * 1024 else { throw MsgCryptoError.invalid("Текст пуст или длиннее 16384 байт") }
        sendCounter += 1
        let id = UUID()
        let now = UInt64(Date().timeIntervalSince1970)
        let aad = try PacketCodec.aad(kind: SignedPacket.data, nodeID: identity.nodeID, sessionID: sessionID,
                                      messageID: id, timestamp: now, counter: sendCounter)
        var clear = Data([1])
        clear.appendBE(now)
        clear.appendBE(UInt32(utf8.count))
        clear.append(utf8)
        let nonceBytes = randomData(12)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let box = try AES.GCM.seal(clear, using: key, nonce: nonce, authenticating: aad)
        var payload = nonceBytes
        payload.append(box.ciphertext)
        payload.append(box.tag)
        let signed = try PacketCodec.signed(aad, payload: payload)
        let signature = try identity.signingKey.signature(for: signed).derRepresentation
        return try carriers.encode(id: id, packet: PacketCodec.serialize(signed, signature: signature))
    }

    func receive(_ transport: String) throws -> CryptoInbound {
        let carrier = try carriers.ingest(transport)
        guard carrier.isCarrier else { return .legacy(transport) }
        guard carrier.isComplete, let raw = carrier.packet, let carrierID = carrier.id else { return .partial }
        let packet = try PacketCodec.parse(raw)
        guard packet.messageID == carrierID else { throw MsgCryptoError.invalid("Message ID не совпал") }
        if packet.senderNodeID == identity.nodeID || seen.contains(packet.messageID) { return .consumed }
        switch packet.kind {
        case SignedPacket.hello: return try acceptHello(packet)
        case SignedPacket.data: return try acceptData(packet)
        case SignedPacket.close: return try acceptClose(packet)
        default: throw MsgCryptoError.invalid("Неизвестный тип MsgCrypt")
        }
    }

    private func acceptHello(_ packet: SignedPacket) throws -> CryptoInbound {
        guard packet.payload.count == 66 else { throw MsgCryptoError.invalid("Неверный HELLO") }
        let signingData = Data(packet.payload.prefix(33))
        let ephemeralData = Data(packet.payload.suffix(33))
        let signing = try P256.Signing.PublicKey(compactRepresentation: signingData)
        let ephemeral = try P256.KeyAgreement.PublicKey(compactRepresentation: ephemeralData)
        let signature = try P256.Signing.ECDSASignature(derRepresentation: packet.signature)
        guard signing.isValidSignature(signature, for: packet.signedBytes) else { throw MsgCryptoError.invalid("Неверная self-signature HELLO") }

        var mustReply = false
        if sessionID == nil {
            reset(packet.sessionID)
            mustReply = true
        } else if sessionID != packet.sessionID {
            if Data.uuid(packet.sessionID).lexicographicallyCompared(to: Data.uuid(sessionID!)) > 0 { return .consumed }
            reset(packet.sessionID)
            mustReply = true
        }
        peerIdentity = signing
        peerEphemeral = ephemeral
        peerNodeID = packet.senderNodeID
        try deriveKey()
        peerFingerprint = fingerprint(signingData)
        remember(packet.messageID)
        let outbound = (mustReply || helloSentFor != packet.sessionID) ? try helloCarriers() : []
        if let trustedPeerKey, trustedPeerKey != signingData {
            state = .keyChanged
            return .keyChanged(fingerprint: peerFingerprint, outbound: outbound)
        }
        state = .keyReady
        return .keyReady(fingerprint: peerFingerprint, outbound: outbound)
    }

    private func acceptData(_ packet: SignedPacket) throws -> CryptoInbound {
        guard let sessionID, packet.sessionID == sessionID, let key = sessionKey,
              let peerIdentity, packet.payload.count >= 28 else { throw MsgCryptoError.invalid("DATA до handshake") }
        if let peerNodeID, peerNodeID != packet.senderNodeID { throw MsgCryptoError.invalid("Неожиданный node ID") }
        let signature = try P256.Signing.ECDSASignature(derRepresentation: packet.signature)
        guard peerIdentity.isValidSignature(signature, for: packet.signedBytes) else { throw MsgCryptoError.invalid("Неверная DATA signature") }
        let nonceData = packet.payload.prefix(12)
        let encrypted = packet.payload.dropFirst(12)
        guard encrypted.count >= 16 else { throw MsgCryptoError.invalid("DATA слишком короткий") }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonceData),
                                        ciphertext: encrypted.dropLast(16), tag: encrypted.suffix(16))
        let clear = try AES.GCM.open(box, using: key, authenticating: packet.aad)
        var reader = ByteReader(data: clear)
        guard try reader.u8() == 1 else { throw MsgCryptoError.invalid("Поддерживается только текст") }
        let sentAt = try reader.u64()
        let size = Int(try reader.u32())
        guard size > 0, size <= 16 * 1024, reader.remaining == size,
              let text = String(data: try reader.take(size), encoding: .utf8) else { throw MsgCryptoError.invalid("Неверный UTF-8 текст") }
        remember(packet.messageID)
        return .text(value: text, sentAt: Date(timeIntervalSince1970: TimeInterval(sentAt)),
                     verified: state == .verified, id: packet.messageID)
    }

    private func acceptClose(_ packet: SignedPacket) throws -> CryptoInbound {
        guard packet.sessionID == sessionID, let peerIdentity else { return .consumed }
        let signature = try P256.Signing.ECDSASignature(derRepresentation: packet.signature)
        guard peerIdentity.isValidSignature(signature, for: packet.signedBytes) else { throw MsgCryptoError.invalid("Неверный CLOSE") }
        remember(packet.messageID)
        clear()
        return .closed
    }

    private func reset(_ id: UUID) {
        sessionID = id
        localEphemeral = P256.KeyAgreement.PrivateKey()
        peerIdentity = nil
        peerEphemeral = nil
        peerNodeID = nil
        sessionKey = nil
        helloSentFor = nil
        sendCounter = 0
    }

    private func helloCarriers() throws -> [String] {
        guard let sessionID, let localEphemeral,
              let signing = identity.signingKey.publicKey.compactRepresentation,
              let ephemeral = localEphemeral.publicKey.compactRepresentation else {
            throw MsgCryptoError.invalid("Не удалось экспортировать P-256 ключ")
        }
        let id = UUID()
        let now = UInt64(Date().timeIntervalSince1970)
        var payload = signing
        payload.append(ephemeral)
        let aad = try PacketCodec.aad(kind: SignedPacket.hello, nodeID: identity.nodeID, sessionID: sessionID,
                                      messageID: id, timestamp: now, counter: 0)
        let signed = try PacketCodec.signed(aad, payload: payload)
        let signature = try identity.signingKey.signature(for: signed).derRepresentation
        helloSentFor = sessionID
        return try carriers.encode(id: id, packet: PacketCodec.serialize(signed, signature: signature))
    }

    private func deriveKey() throws {
        guard let localEphemeral, let peerEphemeral, let sessionID,
              let local = localEphemeral.publicKey.compactRepresentation,
              let peer = peerEphemeral.compactRepresentation else { throw MsgCryptoError.invalid("Handshake не завершён") }
        let secret = try localEphemeral.sharedSecretFromKeyAgreement(with: peerEphemeral)
        let low = local.lexicographicallyCompared(to: peer) <= 0 ? local : peer
        let high = low == local ? peer : local
        var saltInput = low
        saltInput.append(high)
        let salt = Data(SHA256.hash(data: saltInput))
        var info = Data("MsgCrypt/1/session/".utf8)
        info.append(.uuid(sessionID))
        sessionKey = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: info, outputByteCount: 32)
    }

    private func fingerprint(_ compact: Data) -> String {
        let text = Data(SHA256.hash(data: compact)).hex
        return stride(from: 0, to: text.count, by: 4).map { start in
            let a = text.index(text.startIndex, offsetBy: start)
            let b = text.index(a, offsetBy: 4)
            return String(text[a..<b])
        }.joined(separator: " ")
    }

    private func remember(_ id: UUID) {
        guard seen.insert(id).inserted else { return }
        seenOrder.append(id)
        if seenOrder.count > 2048 { seen.remove(seenOrder.removeFirst()) }
    }

    private func clear() {
        sessionID = nil
        localEphemeral = nil
        peerIdentity = nil
        peerEphemeral = nil
        peerNodeID = nil
        sessionKey = nil
        helloSentFor = nil
        state = .none
    }

    private func randomData(_ count: Int) -> Data {
        var data = Data(count: count)
        _ = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return data
    }
}
