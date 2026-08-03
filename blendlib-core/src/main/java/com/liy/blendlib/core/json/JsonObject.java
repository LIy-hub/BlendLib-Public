package com.liy.blendlib.core.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable JSON object preserving the source key order for deterministic diagnostics. */
public final class JsonObject implements JsonValue {
    private final Map<String, JsonValue> values;

    public JsonObject(Map<String, ? extends JsonValue> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends JsonValue> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "object key"), Objects.requireNonNull(entry.getValue(), "object value"));
        }
        this.values = Collections.unmodifiableMap(copy);
    }

    public Map<String, JsonValue> values() {
        return values;
    }

    public JsonValue get(String key) {
        return values.get(key);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public int size() {
        return values.size();
    }
}
