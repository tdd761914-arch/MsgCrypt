package dev.msgcrypt.app.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;

final class Bytes {
    private Bytes() {}

    static ByteBuffer writer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    }

    static ByteBuffer reader(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    }

    static byte[] uuid(UUID value) {
        return writer(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] value) throws ProtocolException {
        if (value.length != 16) throw new ProtocolException("UUID must be 16 bytes");
        ByteBuffer b = reader(value);
        return new UUID(b.getLong(), b.getLong());
    }

    static byte[] take(ByteBuffer input, int count) throws ProtocolException {
        if (count < 0 || input.remaining() < count) throw new ProtocolException("Truncated packet");
        byte[] result = new byte[count];
        input.get(result);
        return result;
    }

    static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] item : arrays) size = Math.addExact(size, item.length);
        ByteBuffer output = writer(size);
        for (byte[] item : arrays) output.put(item);
        return output.array();
    }

    static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = left[i] & 0xff;
            int b = right[i] & 0xff;
            if (a != b) return Integer.compare(a, b);
        }
        return Integer.compare(left.length, right.length);
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return java.security.MessageDigest.isEqual(a, b);
    }

    static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            output[i * 2] = alphabet[value >>> 4];
            output[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(output);
    }

    static byte[] fromHex(String hex) throws ProtocolException {
        if ((hex.length() & 1) != 0) throw new ProtocolException("Odd hex length");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new ProtocolException("Invalid hex");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    static byte[] copy(byte[] input) {
        return Arrays.copyOf(input, input.length);
    }
}

