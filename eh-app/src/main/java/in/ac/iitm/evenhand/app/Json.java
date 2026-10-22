package in.ac.iitm.evenhand.app;

import java.util.Locale;

/** A JSON object written field by field: just what the page reads, with strings escaped. */
final class Json {

    private final StringBuilder sb = new StringBuilder("{");

    Json str(String key, String value) {
        return raw(key, quote(value));
    }

    Json num(String key, double value) {
        if (!Double.isFinite(value)) return raw(key, "null");
        return raw(key, value == Math.rint(value) && Math.abs(value) < 1e15
                ? String.valueOf((long) value) : String.valueOf(value));
    }

    Json bool(String key, boolean value) {
        return raw(key, String.valueOf(value));
    }

    Json raw(String key, String json) {
        if (sb.length() > 1) sb.append(',');
        sb.append(quote(key)).append(':').append(json);
        return this;
    }

    @Override
    public String toString() {
        return sb + "}";
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '<' -> b.append("\\u003c");   // safe to embed in a page
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** A JSON array of already-encoded values. */
    static String array(Iterable<String> encoded) {
        StringBuilder b = new StringBuilder("[");
        for (String e : encoded) {
            if (b.length() > 1) b.append(',');
            b.append(e);
        }
        return b.append(']').toString();
    }
}
