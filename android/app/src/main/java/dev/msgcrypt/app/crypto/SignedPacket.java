package dev.msgcrypt.app.crypto;

import java.util.UUID;

final class SignedPacket {
    static final int HELLO = 1;
    static final int DATA = 2;
    static final int CLOSE = 3;

    final int kind;
    final byte[] senderNodeId;
    final UUID sessionId;
    final UUID messageId;
    final long timestamp;
    final long counter;
    final byte[] payload;
    final byte[] signature;
    final byte[] signedBytes;
    final byte[] aad;

    SignedPacket(int kind, byte[] senderNodeId, UUID sessionId, UUID messageId,
                 long timestamp, long counter, byte[] payload, byte[] signature,
                 byte[] signedBytes, byte[] aad) {
        this.kind = kind;
        this.senderNodeId = Bytes.copy(senderNodeId);
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.counter = counter;
        this.payload = Bytes.copy(payload);
        this.signature = Bytes.copy(signature);
        this.signedBytes = Bytes.copy(signedBytes);
        this.aad = Bytes.copy(aad);
    }
}

