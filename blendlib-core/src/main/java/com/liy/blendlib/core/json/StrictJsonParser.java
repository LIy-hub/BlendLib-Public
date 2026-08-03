package com.liy.blendlib.core.json;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Small strict JSON parser for controlled asset input.
 *
 * <p>It rejects malformed UTF-8, duplicate keys, trailing content, and nesting
 * beyond a caller-provided bound. Number tokens are retained until a typed
 * loader validates range and finiteness.</p>
 */
public final class StrictJsonParser {
    private static final Limits DEFAULT_LIMITS = new Limits(256, 16_384, 16_384, 16_384, 100_000, 1_000_000, 64 * 1024 * 1024);
    private static final int MAX_NUMBER_LENGTH = 128;

    private StrictJsonParser() {
    }

    public static JsonValue parse(byte[] bytes) {
        return parse(bytes, DEFAULT_LIMITS);
    }

    public static JsonValue parse(byte[] bytes, int maxDepth) {
        return parse(bytes, DEFAULT_LIMITS.withMaxDepth(maxDepth));
    }

    /** Parses bytes with explicit AST allocation limits before allocating object/array contents. */
    public static JsonValue parse(byte[] bytes, Limits limits) {
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        limits = java.util.Objects.requireNonNull(limits, "limits");
        if (bytes.length > limits.maxInputBytes()) {
            throw new IllegalArgumentException("JSON input exceeds configured byte limit");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return parse(decoded.toString(), limits);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("JSON must be valid UTF-8", exception);
        }
    }

    public static JsonValue parse(String json) {
        return parse(json, DEFAULT_LIMITS);
    }

    public static JsonValue parse(String json, int maxDepth) {
        return parse(json, DEFAULT_LIMITS.withMaxDepth(maxDepth));
    }

    /** Parses text with explicit depth, collection, string, and total-value limits. */
    public static JsonValue parse(String json, Limits limits) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        limits = java.util.Objects.requireNonNull(limits, "limits");
        if (json.length() > limits.maxInputBytes()) {
            throw new IllegalArgumentException("JSON input exceeds configured character limit");
        }
        Parser parser = new Parser(json, limits);
        JsonValue value = parser.value(0);
        parser.whitespace();
        if (!parser.done()) {
            throw parser.error("Unexpected trailing JSON content");
        }
        return value;
    }

    /** Bounded allocation limits for the small internal JSON AST. */
    public record Limits(
            int maxDepth,
            int maxObjectFields,
            int maxArrayEntries,
            int maxStringLength,
            int maxValues,
            int maxTotalStringCharacters,
            int maxInputBytes) {
        public Limits {
            if (maxDepth <= 0 || maxObjectFields <= 0 || maxArrayEntries <= 0 || maxStringLength <= 0 || maxValues <= 0
                    || maxTotalStringCharacters <= 0 || maxInputBytes <= 0) {
                throw new IllegalArgumentException("JSON limits must be positive");
            }
        }

        Limits withMaxDepth(int replacement) {
            return new Limits(replacement, maxObjectFields, maxArrayEntries, maxStringLength, maxValues, maxTotalStringCharacters,
                    maxInputBytes);
        }
    }

    private static final class Parser {
        private final String source;
        private final Limits limits;
        private int index;
        private int valueCount;
        private int totalStringCharacters;

        Parser(String source, Limits limits) {
            this.source = source;
            this.limits = limits;
        }

        JsonValue value(int depth) {
            if (depth > limits.maxDepth()) {
                throw error("JSON nesting exceeds configured limit");
            }
            if (++valueCount > limits.maxValues()) {
                throw error("JSON value count exceeds configured limit");
            }
            whitespace();
            if (done()) {
                throw error("Expected a JSON value");
            }
            return switch (peek()) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> new JsonString(string());
                case 't' -> literal("true", new JsonBoolean(true));
                case 'f' -> literal("false", new JsonBoolean(false));
                case 'n' -> literal("null", JsonNull.INSTANCE);
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> throw error("Unexpected JSON token");
            };
        }

        private JsonObject object(int depth) {
            expect('{');
            whitespace();
            LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>();
            if (consume('}')) {
                return new JsonObject(values);
            }
            while (true) {
                whitespace();
                if (done() || peek() != '"') {
                    throw error("Expected an object key");
                }
                String key = string();
                if (values.containsKey(key)) {
                    throw error("Duplicate JSON object key: " + key);
                }
                if (values.size() >= limits.maxObjectFields()) {
                    throw error("JSON object field count exceeds configured limit");
                }
                whitespace();
                expect(':');
                values.put(key, value(depth));
                whitespace();
                if (consume('}')) {
                    return new JsonObject(values);
                }
                expect(',');
            }
        }

        private JsonArray array(int depth) {
            expect('[');
            whitespace();
            List<JsonValue> values = new ArrayList<>();
            if (consume(']')) {
                return new JsonArray(values);
            }
            while (true) {
                if (values.size() >= limits.maxArrayEntries()) {
                    throw error("JSON array entry count exceeds configured limit");
                }
                values.add(value(depth));
                whitespace();
                if (consume(']')) {
                    return new JsonArray(values);
                }
                expect(',');
            }
        }

        private JsonValue literal(String expected, JsonValue value) {
            if (!source.startsWith(expected, index)) {
                throw error("Invalid JSON literal");
            }
            index += expected.length();
            return value;
        }

        private JsonNumber number() {
            int start = index;
            consume('-');
            if (consume('0')) {
                // Leading zero is complete; any following digit is rejected below.
            } else {
                digits("Expected a digit after '-' in JSON number");
            }
            if (consume('.')) {
                digits("Expected a fractional digit in JSON number");
            }
            if (!done() && (peek() == 'e' || peek() == 'E')) {
                index++;
                if (!done() && (peek() == '+' || peek() == '-')) {
                    index++;
                }
                digits("Expected an exponent digit in JSON number");
            }
            if (!done() && Character.isDigit(peek())) {
                throw error("Leading zeros are not valid JSON numbers");
            }
            if (index - start > MAX_NUMBER_LENGTH) {
                throw error("JSON number token exceeds configured bound");
            }
            return new JsonNumber(source.substring(start, index));
        }

        private void digits(String errorMessage) {
            int start = index;
            while (!done() && Character.isDigit(peek())) {
                index++;
            }
            if (index == start) {
                throw error(errorMessage);
            }
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!done()) {
                char current = source.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current < 0x20) {
                    throw error("Control character in JSON string");
                }
                if (current != '\\') {
                    appendStringCharacter(value, current);
                    continue;
                }
                if (done()) {
                    throw error("Unterminated JSON escape");
                }
                switch (source.charAt(index++)) {
                    case '"' -> appendStringCharacter(value, '"');
                    case '\\' -> appendStringCharacter(value, '\\');
                    case '/' -> appendStringCharacter(value, '/');
                    case 'b' -> appendStringCharacter(value, '\b');
                    case 'f' -> appendStringCharacter(value, '\f');
                    case 'n' -> appendStringCharacter(value, '\n');
                    case 'r' -> appendStringCharacter(value, '\r');
                    case 't' -> appendStringCharacter(value, '\t');
                    case 'u' -> appendStringCharacter(value, unicodeEscape());
                    default -> throw error("Invalid JSON escape");
                }
            }
            throw error("Unterminated JSON string");
        }

        private void appendStringCharacter(StringBuilder value, char character) {
            if (value.length() >= limits.maxStringLength() || ++totalStringCharacters > limits.maxTotalStringCharacters()) {
                throw error("JSON string allocation exceeds configured limit");
            }
            value.append(character);
        }

        private char unicodeEscape() {
            if (index + 4 > source.length()) {
                throw error("Incomplete JSON unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(source.charAt(index++), 16);
                if (digit < 0) {
                    throw error("Invalid JSON unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        void whitespace() {
            while (!done()) {
                char current = peek();
                if (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        boolean consume(char expected) {
            if (!done() && peek() == expected) {
                index++;
                return true;
            }
            return false;
        }

        void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        boolean done() {
            return index >= source.length();
        }

        char peek() {
            return source.charAt(index);
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON character " + index);
        }
    }
}
