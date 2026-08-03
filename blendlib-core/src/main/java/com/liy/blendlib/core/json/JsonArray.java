package com.liy.blendlib.core.json;

import java.util.List;
import java.util.Objects;

/** Immutable JSON array. */
public record JsonArray(List<JsonValue> values) implements JsonValue {
    public JsonArray {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    public int size() {
        return values.size();
    }

    public JsonValue get(int index) {
        return values.get(index);
    }
}
