package dev.msgcrypt.app.crypto;

import java.security.KeyPair;
import java.util.Objects;

public final class CryptoIdentity {
    public final byte[] nodeId;
    public final KeyPair signingKey;

    public CryptoIdentity(byte[] nodeId, KeyPair signingKey) {
        if (nodeId == null || nodeId.length != 16) throw new IllegalArgumentException("Node ID must be 16 bytes");
        this.nodeId = Bytes.copy(nodeId);
        this.signingKey = Objects.requireNonNull(signingKey);
    }
}

