package dev.msgcrypt.app.crypto;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CarrierCodec {
    public static final String PREFIX = "MSGCRYPT1";
    public static final int CHUNK_BYTES = 160;
    public static final int MAX_CHUNKS = 512;
    public static final int MAX_PACKET_BYTES = 65536;
    private static final long ASSEMBLY_TTL_MILLIS = 10 * 60 * 1000L;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PART = Pattern.compile("([0-9]{1,3})/([0-9]{1,3})");

    private final WordCoder wordCoder;
    private final Clock clock;
    private final Map<UUID, Assembly> assemblies = new HashMap<>();

    public CarrierCodec(WordCoder wordCoder) {
        this(wordCoder, Clock.systemUTC());
    }

    CarrierCodec(WordCoder wordCoder, Clock clock) {
        this.wordCoder = wordCoder;
        this.clock = clock;
    }

    public List<String> encode(UUID messageId, byte[] packet) throws ProtocolException {
        if (packet.length == 0 || packet.length > MAX_PACKET_BYTES) {
            throw new ProtocolException("Packet size is out of range");
        }
        int count = (packet.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        if (count > MAX_CHUNKS) throw new ProtocolException("Too many carrier chunks");
        String id = Bytes.hex(Bytes.uuid(messageId));
        List<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK_BYTES;
            byte[] chunk = Arrays.copyOfRange(packet, start, Math.min(packet.length, start + CHUNK_BYTES));
            result.add(PREFIX + " " + id + " " + index + "/" + count + " " + wordCoder.encode(chunk));
        }
        return result;
    }

    public synchronized DecodeResult ingest(String text) throws ProtocolException {
        if (text == null || !text.startsWith(PREFIX + " ")) return DecodeResult.notCarrier();
        expireOld();
        String[] fields = text.trim().split(" +");
        if (fields.length < 4 || !PREFIX.equals(fields[0]) || !ID.matcher(fields[1]).matches()) {
            throw new ProtocolException("Malformed MsgCrypt carrier");
        }
        UUID id = Bytes.uuid(Bytes.fromHex(fields[1]));
        Matcher part = PART.matcher(fields[2]);
        if (!part.matches()) throw new ProtocolException("Malformed chunk position");
        int index = Integer.parseInt(part.group(1));
        int count = Integer.parseInt(part.group(2));
        if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count) {
            throw new ProtocolException("Chunk position is out of range");
        }
        byte[] payload = wordCoder.decode(fields, 3);
        if (payload.length < 1 || payload.length > CHUNK_BYTES) throw new ProtocolException("Chunk size is out of range");

        Assembly assembly = assemblies.get(id);
        if (assembly == null) {
            assembly = new Assembly(count, clock.millis());
            assemblies.put(id, assembly);
        } else if (assembly.parts.length != count) {
            assemblies.remove(id);
            throw new ProtocolException("Conflicting chunk count");
        }
        byte[] existing = assembly.parts[index];
        if (existing != null && !Bytes.constantTimeEquals(existing, payload)) {
            assemblies.remove(id);
            throw new ProtocolException("Conflicting duplicate chunk");
        }
        if (existing == null) {
            assembly.parts[index] = payload;
            assembly.received++;
            assembly.totalBytes += payload.length;
            if (assembly.totalBytes > MAX_PACKET_BYTES) {
                assemblies.remove(id);
                throw new ProtocolException("Assembled packet is too large");
            }
        }
        if (assembly.received != count) return DecodeResult.partial(id);

        ByteArrayOutputStream output = new ByteArrayOutputStream(assembly.totalBytes);
        for (byte[] bytes : assembly.parts) output.write(bytes, 0, bytes.length);
        assemblies.remove(id);
        return DecodeResult.complete(id, output.toByteArray());
    }

    private void expireOld() {
        long cutoff = clock.millis() - ASSEMBLY_TTL_MILLIS;
        Iterator<Map.Entry<UUID, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue().createdAt < cutoff) iterator.remove();
    }

    private static final class Assembly {
        final byte[][] parts;
        final long createdAt;
        int received;
        int totalBytes;

        Assembly(int count, long createdAt) {
            this.parts = new byte[count][];
            this.createdAt = createdAt;
        }
    }

    public static final class DecodeResult {
        public final boolean carrier;
        public final boolean complete;
        public final UUID messageId;
        public final byte[] packet;

        private DecodeResult(boolean carrier, boolean complete, UUID messageId, byte[] packet) {
            this.carrier = carrier;
            this.complete = complete;
            this.messageId = messageId;
            this.packet = packet;
        }

        static DecodeResult notCarrier() { return new DecodeResult(false, false, null, null); }
        static DecodeResult partial(UUID id) { return new DecodeResult(true, false, id, null); }
        static DecodeResult complete(UUID id, byte[] packet) { return new DecodeResult(true, true, id, packet); }
    }
}

