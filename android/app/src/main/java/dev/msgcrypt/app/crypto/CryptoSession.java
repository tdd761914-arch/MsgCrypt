package dev.msgcrypt.app.crypto;

import dev.msgcrypt.app.model.SecureState;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CryptoSession {
    public static final int MAX_TEXT_BYTES = 16 * 1024;
    private static final int HELLO_PAYLOAD_BYTES = 66;
    private static final int NONCE_BYTES = 12;
    private static final int MAX_SEEN_MESSAGES = 2048;

    private final CryptoIdentity identity;
    private final CryptoPrimitives crypto;
    private final PacketCodec packets;
    private final CarrierCodec carriers;
    private final Clock clock;
    private final Map<UUID, Boolean> seenMessages = new LinkedHashMap<>();

    private SecureState state = SecureState.NONE;
    private UUID sessionId;
    private UUID helloSentFor;
    private KeyPair localEphemeral;
    private PublicKey peerIdentity;
    private PublicKey peerEphemeral;
    private byte[] peerNodeId;
    private byte[] sessionKey;
    private long sendCounter;
    private long receiveCounter;

    public CryptoSession(CryptoIdentity identity, WordCoder wordCoder) {
        this(identity, wordCoder, new CryptoPrimitives(), Clock.systemUTC());
    }

    CryptoSession(CryptoIdentity identity, WordCoder wordCoder, CryptoPrimitives crypto, Clock clock) {
        this.identity = identity;
        this.crypto = crypto;
        this.packets = new PacketCodec();
        this.carriers = new CarrierCodec(wordCoder);
        this.clock = clock;
    }

    public synchronized SecureState state() {
        return state;
    }

    public synchronized String peerFingerprint() throws GeneralSecurityException {
        return peerIdentity == null ? "" : crypto.fingerprint(peerIdentity);
    }

    public synchronized List<String> beginHandshake() throws ProtocolException {
        try {
            if (state == SecureState.VERIFIED || state == SecureState.KEY_READY) return Collections.emptyList();
            reset(UUID.randomUUID());
            state = SecureState.NEGOTIATING;
            return helloCarriers();
        } catch (GeneralSecurityException error) {
            state = SecureState.ERROR;
            throw new ProtocolException("Unable to create MsgCrypt handshake", error);
        }
    }

    public synchronized void verifyPeer() {
        if (state != SecureState.KEY_READY) throw new IllegalStateException("Peer key is not ready");
        state = SecureState.VERIFIED;
    }

    public synchronized List<String> sealText(String text) throws ProtocolException {
        if (state != SecureState.VERIFIED || sessionKey == null || peerIdentity == null) {
            throw new ProtocolException("Отправка заблокирована: подтвердите ключ собеседника");
        }
        byte[] utf8 = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length == 0) throw new ProtocolException("Пустое сообщение");
        if (utf8.length > MAX_TEXT_BYTES) throw new ProtocolException("Текст длиннее 16384 байт");
        try {
            UUID messageId = UUID.randomUUID();
            long now = clock.instant().getEpochSecond();
            long counter = ++sendCounter;
            byte[] aad = packets.aad(SignedPacket.DATA, identity.nodeId, sessionId, messageId, now, counter);
            byte[] cleartext = Bytes.writer(1 + 8 + 4 + utf8.length)
                    .put((byte) 1)
                    .putLong(now)
                    .putInt(utf8.length)
                    .put(utf8)
                    .array();
            byte[] nonce = crypto.randomBytes(NONCE_BYTES);
            byte[] encrypted = crypto.encrypt(sessionKey, nonce, cleartext, aad);
            byte[] payload = Bytes.concat(nonce, encrypted);
            byte[] signed = packets.signedBytes(aad, payload);
            byte[] signature = crypto.sign(identity.signingKey.getPrivate(), signed);
            return carriers.encode(messageId, packets.serialize(signed, signature));
        } catch (GeneralSecurityException error) {
            state = SecureState.ERROR;
            throw new ProtocolException("Unable to encrypt MsgCrypt text", error);
        }
    }

    public synchronized Inbound receive(String transportText) throws ProtocolException {
        CarrierCodec.DecodeResult carrier = carriers.ingest(transportText);
        if (!carrier.carrier) return Inbound.legacy(transportText);
        if (!carrier.complete) return Inbound.partial();

        SignedPacket packet = packets.parse(carrier.packet);
        if (!packet.messageId.equals(carrier.messageId)) throw new ProtocolException("Carrier message ID mismatch");
        if (Bytes.constantTimeEquals(packet.senderNodeId, identity.nodeId)) return Inbound.consumed();
        if (seenMessages.containsKey(packet.messageId)) return Inbound.consumed();

        if (packet.kind == SignedPacket.HELLO) return acceptHello(packet);
        if (packet.kind == SignedPacket.DATA) return acceptData(packet);
        if (packet.kind == SignedPacket.CLOSE) return acceptClose(packet);
        throw new ProtocolException("Unsupported MsgCrypt packet kind");
    }

    private Inbound acceptHello(SignedPacket packet) throws ProtocolException {
        if (packet.payload.length != HELLO_PAYLOAD_BYTES) throw new ProtocolException("Invalid HELLO payload");
        try {
            PublicKey signing = crypto.publicKey(Arrays.copyOfRange(packet.payload, 0, 33));
            PublicKey ephemeral = crypto.publicKey(Arrays.copyOfRange(packet.payload, 33, 66));
            if (!crypto.verify(signing, packet.signedBytes, packet.signature)) {
                throw new ProtocolException("Invalid self-signature in HELLO");
            }
            boolean mustReply = false;
            if (sessionId == null) {
                reset(packet.sessionId);
                mustReply = true;
            } else if (!sessionId.equals(packet.sessionId)) {
                int order = Bytes.compareUnsigned(Bytes.uuid(packet.sessionId), Bytes.uuid(sessionId));
                if (order > 0) return Inbound.consumed();
                reset(packet.sessionId);
                mustReply = true;
            }

            if (peerIdentity != null && !Bytes.constantTimeEquals(crypto.compressed(peerIdentity), crypto.compressed(signing))) {
                peerIdentity = signing;
                peerEphemeral = ephemeral;
                peerNodeId = Bytes.copy(packet.senderNodeId);
                deriveSessionKey();
                state = SecureState.KEY_CHANGED;
                remember(packet.messageId);
                return Inbound.keyChanged(crypto.fingerprint(signing), mustReply ? helloCarriers() : Collections.emptyList());
            }

            peerIdentity = signing;
            peerEphemeral = ephemeral;
            peerNodeId = Bytes.copy(packet.senderNodeId);
            deriveSessionKey();
            state = SecureState.KEY_READY;
            remember(packet.messageId);
            List<String> outbound = mustReply || !packet.sessionId.equals(helloSentFor)
                    ? helloCarriers() : Collections.emptyList();
            return Inbound.keyReady(crypto.fingerprint(signing), outbound);
        } catch (GeneralSecurityException error) {
            state = SecureState.ERROR;
            throw new ProtocolException("Unable to process HELLO", error);
        }
    }

    private Inbound acceptData(SignedPacket packet) throws ProtocolException {
        if (sessionId == null || !sessionId.equals(packet.sessionId) || sessionKey == null || peerIdentity == null) {
            throw new ProtocolException("DATA received before a matching handshake");
        }
        if (peerNodeId != null && !Bytes.constantTimeEquals(peerNodeId, packet.senderNodeId)) {
            throw new ProtocolException("Unexpected MsgCrypt node ID");
        }
        if (packet.payload.length < NONCE_BYTES + 16) throw new ProtocolException("Encrypted payload is too short");
        try {
            if (!crypto.verify(peerIdentity, packet.signedBytes, packet.signature)) {
                throw new ProtocolException("Invalid DATA signature");
            }
            byte[] nonce = Arrays.copyOfRange(packet.payload, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(packet.payload, NONCE_BYTES, packet.payload.length);
            byte[] cleartext = crypto.decrypt(sessionKey, nonce, encrypted, packet.aad);
            ByteBuffer input = Bytes.reader(cleartext);
            if (input.remaining() < 13 || (input.get() & 0xff) != 1) throw new ProtocolException("Not a text payload");
            long sentAt = input.getLong();
            long length = Integer.toUnsignedLong(input.getInt());
            if (length < 1 || length > MAX_TEXT_BYTES || input.remaining() != length) {
                throw new ProtocolException("Invalid text length");
            }
            String text = new String(Bytes.take(input, (int) length), StandardCharsets.UTF_8);
            remember(packet.messageId);
            receiveCounter = Math.max(receiveCounter, packet.counter);
            return Inbound.text(text, sentAt, state == SecureState.VERIFIED, packet.messageId);
        } catch (GeneralSecurityException error) {
            throw new ProtocolException("Unable to verify/decrypt DATA", error);
        }
    }

    private Inbound acceptClose(SignedPacket packet) throws ProtocolException {
        if (peerIdentity == null || sessionId == null || !sessionId.equals(packet.sessionId)) return Inbound.consumed();
        try {
            if (!crypto.verify(peerIdentity, packet.signedBytes, packet.signature)) {
                throw new ProtocolException("Invalid CLOSE signature");
            }
            remember(packet.messageId);
            clearSession();
            return Inbound.closed();
        } catch (GeneralSecurityException error) {
            throw new ProtocolException("Unable to verify CLOSE", error);
        }
    }

    private void reset(UUID newSessionId) throws GeneralSecurityException {
        clearKeyMaterial();
        sessionId = newSessionId;
        localEphemeral = crypto.generateP256();
        helloSentFor = null;
        peerIdentity = null;
        peerEphemeral = null;
        peerNodeId = null;
        sendCounter = 0;
        receiveCounter = 0;
    }

    private List<String> helloCarriers() throws GeneralSecurityException, ProtocolException {
        UUID messageId = UUID.randomUUID();
        long now = clock.instant().getEpochSecond();
        byte[] payload = Bytes.concat(crypto.compressed(identity.signingKey.getPublic()),
                crypto.compressed(localEphemeral.getPublic()));
        byte[] aad = packets.aad(SignedPacket.HELLO, identity.nodeId, sessionId, messageId, now, 0);
        byte[] signed = packets.signedBytes(aad, payload);
        byte[] signature = crypto.sign(identity.signingKey.getPrivate(), signed);
        helloSentFor = sessionId;
        return carriers.encode(messageId, packets.serialize(signed, signature));
    }

    private void deriveSessionKey() throws GeneralSecurityException {
        byte[] local = crypto.compressed(localEphemeral.getPublic());
        byte[] peer = crypto.compressed(peerEphemeral);
        byte[] secret = crypto.sharedSecret(localEphemeral.getPrivate(), peerEphemeral);
        clearKeyMaterial();
        sessionKey = crypto.sessionKey(secret, local, peer, Bytes.uuid(sessionId));
        Arrays.fill(secret, (byte) 0);
    }

    private void remember(UUID id) {
        seenMessages.put(id, Boolean.TRUE);
        while (seenMessages.size() > MAX_SEEN_MESSAGES) {
            UUID oldest = seenMessages.keySet().iterator().next();
            seenMessages.remove(oldest);
        }
    }

    private void clearSession() {
        clearKeyMaterial();
        sessionId = null;
        helloSentFor = null;
        localEphemeral = null;
        peerIdentity = null;
        peerEphemeral = null;
        peerNodeId = null;
        state = SecureState.NONE;
    }

    private void clearKeyMaterial() {
        if (sessionKey != null) Arrays.fill(sessionKey, (byte) 0);
        sessionKey = null;
    }

    public static final class Inbound {
        public enum Kind { NOT_CARRIER, PARTIAL, CONSUMED, KEY_READY, KEY_CHANGED, TEXT, CLOSED }

        public final Kind kind;
        public final String text;
        public final long sentAt;
        public final boolean verified;
        public final UUID messageId;
        public final String fingerprint;
        public final List<String> outbound;

        private Inbound(Kind kind, String text, long sentAt, boolean verified, UUID messageId,
                        String fingerprint, List<String> outbound) {
            this.kind = kind;
            this.text = text;
            this.sentAt = sentAt;
            this.verified = verified;
            this.messageId = messageId;
            this.fingerprint = fingerprint;
            this.outbound = Collections.unmodifiableList(new ArrayList<>(outbound));
        }

        static Inbound legacy(String text) { return new Inbound(Kind.NOT_CARRIER, text, 0, false, null, "", Collections.emptyList()); }
        static Inbound partial() { return new Inbound(Kind.PARTIAL, null, 0, false, null, "", Collections.emptyList()); }
        static Inbound consumed() { return new Inbound(Kind.CONSUMED, null, 0, false, null, "", Collections.emptyList()); }
        static Inbound keyReady(String fingerprint, List<String> outbound) { return new Inbound(Kind.KEY_READY, null, 0, false, null, fingerprint, outbound); }
        static Inbound keyChanged(String fingerprint, List<String> outbound) { return new Inbound(Kind.KEY_CHANGED, null, 0, false, null, fingerprint, outbound); }
        static Inbound text(String text, long sentAt, boolean verified, UUID id) { return new Inbound(Kind.TEXT, text, sentAt, verified, id, "", Collections.emptyList()); }
        static Inbound closed() { return new Inbound(Kind.CLOSED, null, 0, false, null, "", Collections.emptyList()); }
    }
}
