package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable code-side context consumed by the same selector as a prepared data manifest. */
public record X6VariantContext(Map<BlendResourceId, String> values) {
    public X6VariantContext {
        Objects.requireNonNull(values, "values");
        Map<BlendResourceId, String> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> sorted.put(
                        X6Ids.requireId(entry.getKey(), "context key"),
                        X6Ids.requireContextValue(entry.getValue(), "context value")));
        values = X6Ids.immutableOrderedMap(sorted);
    }

    /** Returns an empty context that deterministically selects defaults or baseline fallback. */
    public static X6VariantContext empty() {
        return new X6VariantContext(Map.of());
    }
}
