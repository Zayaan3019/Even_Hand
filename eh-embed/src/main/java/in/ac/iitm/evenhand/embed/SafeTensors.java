package in.ac.iitm.evenhand.embed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads tensors from a {@code .safetensors} file: an 8-byte little-endian header length, a JSON
 * header naming each tensor's dtype, shape and byte range, then the raw little-endian data.
 *
 * <p>The format was chosen over PyTorch's pickle files because it cannot run code when loaded:
 * a model file is data, never a program. Only F32, F16 and BF16 weights are read; every value
 * is widened to {@code float}.
 */
final class SafeTensors {

    record Entry(String dtype, int[] shape, long begin, long end) {
        int elements() {
            long n = 1;
            for (int d : shape) n *= d;
            return Math.toIntExact(n);
        }
    }

    private static final Set<String> FLOAT_TYPES = Set.of("F32", "F16", "BF16");

    private final Map<String, Entry> entries;
    private final ByteBuffer data;

    private SafeTensors(Map<String, Entry> entries, ByteBuffer data) {
        this.entries = entries;
        this.data = data;
    }

    static SafeTensors open(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer len = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, len, 0);
            long headerLength = len.flip().getLong();
            if (headerLength <= 0 || headerLength > 100_000_000L || 8 + headerLength > ch.size()) {
                throw new IOException(file + " is not a safetensors file (header length " + headerLength + ")");
            }
            ByteBuffer header = ByteBuffer.allocate((int) headerLength);
            readFully(ch, header, 8);
            Map<String, Object> json = Json.object(new String(header.array(), StandardCharsets.UTF_8));

            Map<String, Entry> entries = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : json.entrySet()) {
                if (e.getKey().equals("__metadata__")) continue;
                @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) e.getValue();
                @SuppressWarnings("unchecked") List<Object> shape = (List<Object>) t.get("shape");
                @SuppressWarnings("unchecked") List<Object> offsets = (List<Object>) t.get("data_offsets");
                int[] dims = new int[shape.size()];
                for (int i = 0; i < dims.length; i++) dims[i] = ((Double) shape.get(i)).intValue();
                entries.put(e.getKey(), new Entry((String) t.get("dtype"), dims,
                        ((Double) offsets.get(0)).longValue(), ((Double) offsets.get(1)).longValue()));
            }
            long dataStart = 8 + headerLength;
            long dataLength = ch.size() - dataStart;
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                if (e.getValue().end() > dataLength || e.getValue().begin() > e.getValue().end()) {
                    throw new IOException("tensor " + e.getKey() + " lies outside the file; it is truncated or corrupt");
                }
            }
            // Read into the heap rather than memory-mapped: on Windows a mapped file stays locked
            // until the garbage collector frees the mapping, so the model could not be replaced.
            if (dataLength > Integer.MAX_VALUE) throw new IOException(file + " is larger than 2 GB");
            ByteBuffer data = ByteBuffer.allocate((int) dataLength);
            readFully(ch, data, dataStart);
            return new SafeTensors(entries, data.order(ByteOrder.LITTLE_ENDIAN));
        }
    }

    boolean has(String name) {
        return entries.containsKey(name);
    }

    Set<String> names() {
        return entries.keySet();
    }

    int[] shape(String name) {
        return entry(name).shape().clone();
    }

    /** The tensor flattened in row-major order. */
    float[] floats(String name) {
        Entry e = entry(name);
        if (!FLOAT_TYPES.contains(e.dtype())) {
            throw new IllegalArgumentException("tensor " + name + " has dtype " + e.dtype() + ", not a float type");
        }
        int n = e.elements();
        int width = e.dtype().equals("F32") ? 4 : 2;
        if (e.end() - e.begin() != (long) n * width) {
            throw new IllegalArgumentException("tensor " + name + " has the wrong number of bytes for its shape");
        }
        float[] out = new float[n];
        int base = (int) e.begin();
        switch (e.dtype()) {
            case "F32" -> {
                for (int i = 0; i < n; i++) out[i] = data.getFloat(base + 4 * i);
            }
            case "F16" -> {
                for (int i = 0; i < n; i++) out[i] = Float.float16ToFloat(data.getShort(base + 2 * i));
            }
            default -> {
                for (int i = 0; i < n; i++) out[i] = Float.intBitsToFloat((data.getShort(base + 2 * i) & 0xFFFF) << 16);
            }
        }
        return out;
    }

    private Entry entry(String name) {
        Entry e = entries.get(name);
        if (e == null) throw new IllegalArgumentException("the model file has no tensor named " + name);
        return e;
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long at = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, at);
            if (n < 0) throw new IOException("unexpected end of file");
            at += n;
        }
    }
}
