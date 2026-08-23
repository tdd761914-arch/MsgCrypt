package dev.msgcrypt.app.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;

public final class IdentityKeyStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PREFIX = "msgcrypt-signing-";
    private final SharedPreferences preferences;

    public IdentityKeyStore(Context context) {
        preferences = context.getSharedPreferences("msgcrypt-identities", Context.MODE_PRIVATE);
    }

    public synchronized CryptoIdentity loadOrCreate(String accountId) throws Exception {
        String alias = PREFIX + accountId;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyPair pair;
        if (keyStore.containsAlias(alias)) {
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, null);
            PublicKey publicKey = keyStore.getCertificate(alias).getPublicKey();
            pair = new KeyPair(publicKey, privateKey);
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build());
            pair = generator.generateKeyPair();
        }

        String nodeKey = "node-" + accountId;
        String encoded = preferences.getString(nodeKey, null);
        byte[] nodeId;
        if (encoded == null) {
            nodeId = new byte[16];
            new SecureRandom().nextBytes(nodeId);
            if (!preferences.edit().putString(nodeKey, Base64.encodeToString(nodeId, Base64.NO_WRAP)).commit()) {
                throw new IllegalStateException("Unable to persist MsgCrypt node ID");
            }
        } else {
            nodeId = Base64.decode(encoded, Base64.NO_WRAP);
            if (nodeId.length != 16) throw new IllegalStateException("Stored MsgCrypt node ID is corrupt");
        }
        return new CryptoIdentity(nodeId, pair);
    }

    public synchronized void delete(String accountId) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        String alias = PREFIX + accountId;
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
        preferences.edit().remove("node-" + accountId).apply();
    }
}

