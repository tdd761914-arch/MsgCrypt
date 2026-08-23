package dev.msgcrypt.app.crypto;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoPrimitives {
    private static final String CURVE = "secp256r1";
    private static final int KEY_BYTES = 32;
    private final SecureRandom random;

    public CryptoPrimitives() {
        this(new SecureRandom());
    }

    CryptoPrimitives(SecureRandom random) {
        this.random = random;
    }

    public KeyPair generateP256() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE), random);
        return generator.generateKeyPair();
    }

    public byte[] compressed(PublicKey publicKey) throws GeneralSecurityException {
        if (!(publicKey instanceof ECPublicKey)) throw new GeneralSecurityException("Not an EC public key");
        ECPublicKey ec = (ECPublicKey) publicKey;
        byte[] x = fixed(ec.getW().getAffineX(), KEY_BYTES);
        byte[] output = new byte[KEY_BYTES + 1];
        output[0] = ec.getW().getAffineY().testBit(0) ? (byte) 0x03 : (byte) 0x02;
        System.arraycopy(x, 0, output, 1, x.length);
        return output;
    }

    public PublicKey publicKey(byte[] compressed) throws GeneralSecurityException, ProtocolException {
        if (compressed.length != 33 || (compressed[0] != 0x02 && compressed[0] != 0x03)) {
            throw new ProtocolException("Invalid compressed P-256 key");
        }
        ECParameterSpec params = parameters();
        BigInteger p = ((ECFieldFp) params.getCurve().getField()).getP();
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(compressed, 1, 33));
        if (x.compareTo(p) >= 0) throw new ProtocolException("P-256 x coordinate is out of range");
        BigInteger a = params.getCurve().getA();
        BigInteger b = params.getCurve().getB();
        BigInteger ySquared = x.modPow(BigInteger.valueOf(3), p).add(a.multiply(x)).add(b).mod(p);
        BigInteger y = ySquared.modPow(p.add(BigInteger.ONE).shiftRight(2), p);
        if (!y.multiply(y).mod(p).equals(ySquared)) throw new ProtocolException("Point is not on P-256");
        boolean odd = compressed[0] == 0x03;
        if (y.testBit(0) != odd) y = p.subtract(y);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    public byte[] sign(PrivateKey privateKey, byte[] data) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey, random);
        signature.update(data);
        return signature.sign();
    }

    public boolean verify(PublicKey publicKey, byte[] data, byte[] signatureBytes) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(signatureBytes);
    }

    public byte[] sharedSecret(PrivateKey privateKey, PublicKey peer) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(peer, true);
        byte[] secret = agreement.generateSecret();
        if (secret.length == KEY_BYTES) return secret;
        if (secret.length > KEY_BYTES) return Arrays.copyOfRange(secret, secret.length - KEY_BYTES, secret.length);
        byte[] padded = new byte[KEY_BYTES];
        System.arraycopy(secret, 0, padded, KEY_BYTES - secret.length, secret.length);
        return padded;
    }

    public byte[] sessionKey(byte[] ecdhSecret, byte[] localEphemeral, byte[] peerEphemeral, byte[] sessionId)
            throws GeneralSecurityException {
        byte[] low = Bytes.compareUnsigned(localEphemeral, peerEphemeral) <= 0 ? localEphemeral : peerEphemeral;
        byte[] high = low == localEphemeral ? peerEphemeral : localEphemeral;
        byte[] salt = sha256(Bytes.concat(low, high));
        byte[] info = Bytes.concat("MsgCrypt/1/session/".getBytes(java.nio.charset.StandardCharsets.UTF_8), sessionId);
        return hkdfSha256(ecdhSecret, salt, info, KEY_BYTES);
    }

    public byte[] randomBytes(int count) {
        byte[] result = new byte[count];
        random.nextBytes(result);
        return result;
    }

    public byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    public byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    public byte[] sha256(byte[] input) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    public String fingerprint(PublicKey key) throws GeneralSecurityException {
        String hex = Bytes.hex(sha256(compressed(key)));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i != 0) result.append(' ');
            result.append(hex, i, i + 4);
        }
        return result.toString();
    }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        if (length < 1 || length > 255 * 32) throw new GeneralSecurityException("Invalid HKDF length");
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[32] : salt;
        mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int written = 0;
        int block = 1;
        while (written < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(previous);
            if (info != null) mac.update(info);
            mac.update((byte) block++);
            previous = mac.doFinal();
            int count = Math.min(previous.length, length - written);
            System.arraycopy(previous, 0, output, written, count);
            written += count;
        }
        Arrays.fill(prk, (byte) 0);
        return output;
    }

    private static ECParameterSpec parameters() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] fixed(BigInteger value, int size) throws GeneralSecurityException {
        byte[] raw = value.toByteArray();
        if (raw.length == size) return raw;
        if (raw.length == size + 1 && raw[0] == 0) return Arrays.copyOfRange(raw, 1, raw.length);
        if (raw.length > size) throw new GeneralSecurityException("Coordinate is too large");
        byte[] output = new byte[size];
        System.arraycopy(raw, 0, output, size - raw.length, raw.length);
        return output;
    }
}
