package com.liy.blendlib.core.json;

import java.util.Objects;

/** JSON numeric token retained as text until a typed loader consumer validates it. */
public record JsonNumber(String raw) implements JsonValue {
    public JsonNumber {
        raw = Objects.requireNonNull(raw, "raw");
    }

    public double asDouble() {
        return Double.parseDouble(raw);
    }

    public int asIntExact() {
        try {
            long value = Long.parseLong(raw);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new NumberFormatException("outside int range");
            }
            return (int) value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a 32-bit integer JSON number: " + raw, exception);
        }
    }
}
