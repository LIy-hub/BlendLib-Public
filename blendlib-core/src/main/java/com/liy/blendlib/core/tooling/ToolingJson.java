package com.liy.blendlib.core.tooling;

import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNull;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small canonical JSON renderer shared only by X5 local tooling. */
final class ToolingJson {
    private ToolingJson() {
    }

    static String canonical(Object value) {
        StringBuilder output = new StringBuilder();
        appendObject(output, value);
        return output.toString();
    }

    static String canonical(JsonValue value) {
        StringBuilder output = new StringBuilder();
        appendJsonValue(output, value);
        return output.toString();
    }

    private static void appendObject(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            appendString(output, text);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            output.append(value);
        } else if (value instanceof Float number) {
            appendNumber(output, BigDecimal.valueOf(number.doubleValue()).toPlainString());
        } else if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Canonical JSON does not allow non-finite numbers");
            }
            appendNumber(output, BigDecimal.valueOf(number).toPlainString());
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical JSON object keys must be strings");
                }
                ordered.put(key, entry.getValue());
            }
            output.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendString(output, entry.getKey());
                output.append(':');
                appendObject(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof Iterable<?> values) {
            output.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendObject(output, item);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported canonical JSON value: " + value.getClass().getName());
        }
    }

    private static void appendJsonValue(StringBuilder output, JsonValue value) {
        if (value instanceof JsonObject object) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonValue> entry : new TreeMap<>(object.values()).entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendString(output, entry.getKey());
                output.append(':');
                appendJsonValue(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof JsonArray array) {
            output.append('[');
            for (int index = 0; index < array.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                appendJsonValue(output, array.get(index));
            }
            output.append(']');
        } else if (value instanceof JsonString string) {
            appendString(output, string.value());
        } else if (value instanceof JsonNumber number) {
            appendNumber(output, number.raw());
        } else if (value instanceof JsonBoolean bool) {
            output.append(bool.value());
        } else if (value == JsonNull.INSTANCE) {
            output.append("null");
        } else {
            throw new IllegalArgumentException("Unsupported strict JSON value");
        }
    }

    private static void appendNumber(StringBuilder output, String raw) {
        BigDecimal normalized = new BigDecimal(raw).stripTrailingZeros();
        output.append(normalized.signum() == 0 ? "0" : normalized.toPlainString());
    }

    private static void appendString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
