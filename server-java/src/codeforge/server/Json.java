package codeforge.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Object parse(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return new Parser(text).parseValue();
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder();
        writePrettyValue(out, value, 0);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return new ArrayList<>();
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }

    public static List<Object> list(Object... values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    public static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static long lon(Map<String, Object> map, String key, long fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double decimal(Map<String, Object> map, String key, double fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
                out.append("null");
            } else {
                out.append(n.toString());
            }
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof java.nio.file.Path || value instanceof java.io.File) {
            writeString(out, String.valueOf(value));
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writePrettyValue(StringBuilder out, Object value, int depth) {
        String pad = "  ".repeat(depth);
        String innerPad = "  ".repeat(depth + 1);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(",\n");
                first = false;
                out.append(innerPad);
                writeString(out, String.valueOf(entry.getKey()));
                out.append(": ");
                writePrettyValue(out, entry.getValue(), depth + 1);
            }
            out.append('\n').append(pad).append('}');
        } else if (value instanceof Iterable<?> iterable) {
            java.util.Iterator<?> iterator = iterable.iterator();
            if (!iterator.hasNext()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            boolean first = true;
            while (iterator.hasNext()) {
                if (!first) out.append(",\n");
                first = false;
                out.append(innerPad);
                writePrettyValue(out, iterator.next(), depth + 1);
            }
            out.append('\n').append(pad).append(']');
        } else {
            writeValue(out, value);
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String source;
        private int position = 0;

        Parser(String source) {
            this.source = source;
        }

        Object parseValue() {
            skipWhitespace();
            if (position >= source.length()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            char c = source.charAt(position);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') return parseLiteral("true", Boolean.TRUE);
            if (c == 'f') return parseLiteral("false", Boolean.FALSE);
            if (c == 'n') return parseLiteral("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw new IllegalArgumentException("unexpected character in json");
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            ++position;
            skipWhitespace();
            if (peek('}')) {
                ++position;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw new IllegalArgumentException("expected object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (peek(',')) {
                    ++position;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            ++position;
            skipWhitespace();
            if (peek(']')) {
                ++position;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    ++position;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!source.startsWith(literal, position)) {
                throw new IllegalArgumentException("invalid literal");
            }
            position += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = position;
            if (peek('-')) ++position;
            while (position < source.length() &&
                   Character.isDigit(source.charAt(position))) {
                ++position;
            }
            boolean decimal = false;
            if (position < source.length() && source.charAt(position) == '.') {
                decimal = true;
                ++position;
                while (position < source.length() &&
                       Character.isDigit(source.charAt(position))) {
                    ++position;
                }
            }
            if (position < source.length() &&
                (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                decimal = true;
                ++position;
                if (position < source.length() &&
                    (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                    ++position;
                }
                while (position < source.length() &&
                       Character.isDigit(source.charAt(position))) {
                    ++position;
                }
            }
            String token = source.substring(start, position);
            if (token.isEmpty() || token.equals("-")) {
                throw new IllegalArgumentException("invalid number");
            }
            if (decimal) {
                return Double.parseDouble(token);
            }
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                return Double.parseDouble(token);
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (position >= source.length()) {
                    break;
                }
                char escape = source.charAt(position++);
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (position + 4 > source.length()) {
                            throw new IllegalArgumentException("invalid unicode escape");
                        }
                        out.append((char) Integer.parseInt(
                                source.substring(position, position + 4), 16));
                        position += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("invalid escape");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private void skipWhitespace() {
            while (position < source.length()) {
                char c = source.charAt(position);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    ++position;
                } else {
                    break;
                }
            }
        }

        private boolean peek(char c) {
            return position < source.length() && source.charAt(position) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("expected '" + c + "'");
            }
            ++position;
        }
    }
}
