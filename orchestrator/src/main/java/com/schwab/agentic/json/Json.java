package com.schwab.agentic.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and writer with no external dependencies.
 *
 * The orchestrator is not allowed to depend on Jackson, Gson or any other JSON library
 * (CLAUDE.md, zero dependencies). Without this class every other piece of the model that
 * needs to serialize state, audit events or agent request bodies would have no way to do
 * so, since {@code WorkflowState.toJson}, {@code fromJson}, and the agent layer's request
 * and response bodies all go through here.
 *
 * Values round trip as plain Java objects: {@code String}, {@code Double} for numbers,
 * {@code Boolean}, {@code null}, {@code List<Object>} for arrays and
 * {@code Map<String,Object>} for objects, preserving insertion order via
 * {@link LinkedHashMap}. This is deliberately not a typed binding layer: callers build
 * and read plain maps and lists, which is what lets {@code AuditEvent.details} carry
 * arbitrary nested structure without this class knowing about any particular shape.
 */
public final class Json {

    private Json() {
    }

    /**
     * Serializes a value tree (String, Number, Boolean, null, List, Map) to a JSON string.
     * An unsupported value type throws rather than silently stringifying it, since a
     * silently-wrong serialization would corrupt a persisted run without any signal.
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b.toString());
        } else if (value instanceof Number n) {
            writeNumber(n, out);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out);
        } else if (value instanceof List<?> list) {
            writeArray(list, out);
        } else {
            throw new IllegalArgumentException(
                "Cannot serialize value of type " + value.getClass().getName()
                    + " to JSON: only String, Number, Boolean, null, List and Map are supported");
        }
    }

    private static void writeNumber(Number n, StringBuilder out) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
            throw new IllegalArgumentException("Cannot serialize non-finite double to JSON: " + d);
        }
        if (n instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            out.append(d.longValue());
        } else {
            out.append(n.toString());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(String.valueOf(entry.getKey()), out);
            out.append(':');
            writeValue(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(item, out);
        }
        out.append(']');
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Parses a JSON document into plain Java objects (String, Double, Boolean, null,
     * List, Map). Throws {@link JsonParseException} naming the offending position on any
     * malformed input, since a run's persisted state or a fixture that fails to parse
     * silently would surface as a much more confusing failure much later.
     */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object result = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonParseException("Unexpected trailing content", parser.position, text);
        }
        return result;
    }

    /** Thrown when {@link Json#parse} encounters input that is not valid JSON. */
    public static final class JsonParseException extends RuntimeException {
        public JsonParseException(String message, int position, String source) {
            super(message + " at position " + position + " in: " + excerpt(source, position));
        }

        private static String excerpt(String source, int position) {
            int start = Math.max(0, position - 20);
            int end = Math.min(source.length(), position + 20);
            return source.substring(start, end);
        }
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw new JsonParseException("Unexpected end of input", position, text);
            }
            return text.charAt(position);
        }

        void expect(char c) {
            if (atEnd() || text.charAt(position) != c) {
                throw new JsonParseException("Expected '" + c + "'", position, text);
            }
            position++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    position++;
                } else if (next == '}') {
                    position++;
                    break;
                } else {
                    throw new JsonParseException("Expected ',' or '}'", position, text);
                }
            }
            return result;
        }

        List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    position++;
                } else if (next == ']') {
                    position++;
                    break;
                } else {
                    throw new JsonParseException("Expected ',' or ']'", position, text);
                }
            }
            return result;
        }

        String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (true) {
                char c = peek();
                position++;
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char escaped = peek();
                    position++;
                    switch (escaped) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'u' -> {
                            String hex = text.substring(position, position + 4);
                            result.append((char) Integer.parseInt(hex, 16));
                            position += 4;
                        }
                        default -> throw new JsonParseException(
                            "Unknown escape sequence '\\" + escaped + "'", position, text);
                    }
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }

        Boolean parseBoolean() {
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw new JsonParseException("Expected 'true' or 'false'", position, text);
        }

        Object parseNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new JsonParseException("Expected 'null'", position, text);
        }

        Double parseNumber() {
            int start = position;
            if (!atEnd() && text.charAt(position) == '-') {
                position++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            if (!atEnd() && text.charAt(position) == '.') {
                position++;
                while (!atEnd() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (!atEnd() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                position++;
                if (!atEnd() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                    position++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (position == start) {
                throw new JsonParseException("Expected a number", position, text);
            }
            return Double.parseDouble(text.substring(start, position));
        }
    }
}
