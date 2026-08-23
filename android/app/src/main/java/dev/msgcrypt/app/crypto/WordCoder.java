package dev.msgcrypt.app.crypto;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class WordCoder {
    private final String[] words;
    private final Map<String, Integer> bytes;

    public WordCoder(String[] words) {
        if (words == null || words.length != 256) {
            throw new IllegalArgumentException("WordCoder requires exactly 256 words");
        }
        this.words = words.clone();
        Map<String, Integer> reverse = new HashMap<>();
        for (int i = 0; i < this.words.length; i++) {
            String word = this.words[i];
            if (word == null || word.isBlank() || word.length() > 10) {
                throw new IllegalArgumentException("Invalid word at byte " + i);
            }
            if (reverse.put(word, i) != null) throw new IllegalArgumentException("Duplicate word: " + word);
        }
        this.bytes = Collections.unmodifiableMap(reverse);
    }

    public static WordCoder fromJson(InputStream stream) throws IOException, ProtocolException {
        if (stream == null) throw new IOException("wordcoder-ru.json is missing");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new ProtocolException("WordCoder dictionary is too large");
            output.write(buffer, 0, count);
        }
        JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        String[] words = new String[256];
        Set<String> seen = new HashSet<>();
        for (int value = 0; value < 256; value++) {
            String key = String.format("0x%02X", value);
            String word = json.optString(key, null);
            if (word == null || !seen.add(word)) throw new ProtocolException("Invalid WordCoder entry " + key);
            words[value] = word;
        }
        return new WordCoder(words);
    }

    public String encode(byte[] input) {
        StringBuilder result = new StringBuilder(input.length * 5);
        for (int i = 0; i < input.length; i++) {
            if (i != 0) result.append(' ');
            result.append(words[input[i] & 0xff]);
        }
        return result.toString();
    }

    public byte[] decode(String[] encoded, int offset) throws ProtocolException {
        if (offset < 0 || offset > encoded.length) throw new ProtocolException("Invalid WordCoder offset");
        byte[] result = new byte[encoded.length - offset];
        for (int i = offset; i < encoded.length; i++) {
            Integer value = bytes.get(encoded[i]);
            if (value == null) throw new ProtocolException("Unknown WordCoder word");
            result[i - offset] = value.byteValue();
        }
        return result;
    }
}

