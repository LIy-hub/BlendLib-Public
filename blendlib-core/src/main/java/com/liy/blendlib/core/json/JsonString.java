package com.liy.blendlib.core.json;

import java.util.Objects;

/** JSON string value. */
public record JsonString(String value) implements JsonValue {
    public JsonString {
        value = Objects.requireNonNull(value, "value");
    }
}
