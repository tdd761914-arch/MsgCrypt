package dev.msgcrypt.app.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

final class PacketCodec {
    private static final byte[] MAGIC = {0x4d, 0x43};
    private static final int VERSION = 1;
    static final int HEADER_SIZE = 68;
    private static final int MAX_PAYLOAD = 60 * 1024;
    private static final int MAX_SIGNATURE = 128;

    byte[] aad(int kind, byte[] nodeId, UUID sessionId, UUID messageId, long timestamp, long counter)
            throws ProtocolException {
        validateHeader(kind, nodeId);
        return Bytes.writer(HEADER_SIZE)
                .put(MAGIC)
                .put((byte) VERSION)
                .put((byte) kind)
                .put(nodeId)
                .put(Bytes.uuid(sessionId))
                .put(Bytes.uuid(messageId))
                .putLong(timestamp)
                .putLong(counter)
                .array();
    }

    byte[] signedBytes(byte[] aad, byte[] payload) throws ProtocolException {
        if (aad.length != HEADER_SIZE) throw new ProtocolException("Invalid AAD header");
        if (payload.length > MAX_PAYLOAD) throw new ProtocolException("Payload is too large");
        return Bytes.concat(aad, Bytes.writer(4).putInt(payload.length).array(), payload);
    }

    byte[] serialize(byte[] signedBytes, byte[] signature) throws ProtocolException {
        if (signature.length < 8 || signature.length > MAX_SIGNATURE) {
            throw new ProtocolException("Signature size is out of range");
        }
        return Bytes.concat(signedBytes, Bytes.writer(2).putShort((short) signature.length).array(), signature);
    }

    SignedPacket parse(byte[] raw) throws ProtocolException {
        if (raw.length < HEADER_SIZE + 4 + 2 + 8 || raw.length > CarrierCodec.MAX_PACKET_BYTES) {
            throw new ProtocolException("Signed packet size is out of range");
        }
        ByteBuffer input = Bytes.reader(raw);
        if (input.get() != MAGIC[0] || input.get() != MAGIC[1]) throw new ProtocolException("Wrong packet magic");
        if ((input.get() & 0xff) != VERSION) throw new ProtocolException("Unsupported packet version");
        int kind = input.get() & 0xff;
        byte[] nodeId = Bytes.take(input, 16);
        UUID sessionId = Bytes.uuid(Bytes.take(input, 16));
        UUID messageId = Bytes.uuid(Bytes.take(input, 16));
        long timestamp = input.getLong();
        long counter = input.getLong();
        validateHeader(kind, nodeId);
        byte[] aad = Arrays.copyOf(raw, HEADER_SIZE);

        long unsignedLength = Integer.toUnsignedLong(input.getInt());
        if (unsignedLength > MAX_PAYLOAD || unsignedLength > input.remaining() - 2L) {
            throw new ProtocolException("Invalid payload length");
        }
        byte[] payload = Bytes.take(input, (int) unsignedLength);
        int signedLength = input.position();
        int signatureLength = input.getShort() & 0xffff;
        if (signatureLength < 8 || signatureLength > MAX_SIGNATURE || input.remaining() != signatureLength) {
            throw new ProtocolException("Invalid signature length");
        }
        byte[] signature = Bytes.take(input, signatureLength);
        byte[] signed = Arrays.copyOf(raw, signedLength);
        return new SignedPacket(kind, nodeId, sessionId, messageId, timestamp, counter,
                payload, signature, signed, aad);
    }

    private static void validateHeader(int kind, byte[] nodeId) throws ProtocolException {
        if (kind < SignedPacket.HELLO || kind > SignedPacket.CLOSE) throw new ProtocolException("Unknown packet kind");
        if (nodeId == null || nodeId.length != 16) throw new ProtocolException("Node ID must be 16 bytes");
    }
}

