package in.ac.iitm.evenhand.embed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a model's {@code config.json} and a safetensors header: objects,
 * arrays, strings, numbers, booleans and null. Objects become {@code Map<String,Object>},
 * arrays {@code List<Object>}, numbers {@code Double}.
 */
final class Json {

    private final String s;
    private int at;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipSpace();
        Object v = p.value();
        p.skipSpace();
        if (p.at != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (at >= s.length()) throw error("unexpected end");
        char c = s.charAt(at);
        return switch (c) {
            case '{' -> obj();
            case '[' -> arr();
            case '"' -> str();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> num();
        };
    }

    private Map<String, Object> obj() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            String key = str();
            skipSpace();
            expect(':');
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> arr() {
        List<Object> out = new ArrayList<>();
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String str() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (at + 4 > s.length()) throw error("short \\u escape");
                    b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private Double num() {
        int start = at;
        while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) at++;
        if (start == at) throw error("unexpected character '" + s.charAt(at) + "'");
        return Double.valueOf(s.substring(start, at));
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, at)) throw error("expected " + w);
        at += w.length();
        return v;
    }

    private void skipSpace() {
        while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++;
    }

    private char peek() {
        if (at >= s.length()) throw error("unexpected end");
        return s.charAt(at);
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw error("expected '" + c + "'");
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("malformed JSON at character " + at + ": " + what);
    }
}
