package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Isolated, versioned X6 selection input. It is not a v1 descriptor extension or runtime profile. */
public record X6VariantManifest(
        int formatVersion,
        List<X6VariantDefinition> variants,
        List<X6VariantRule> rules,
        Map<BlendResourceId, BlendResourceId> defaults) {
    public static final int FORMAT_VERSION = 1;

    public X6VariantManifest {
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        Objects.requireNonNull(defaults, "defaults");
        Map<BlendResourceId, BlendResourceId> sortedDefaults = new LinkedHashMap<>();
        defaults.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> sortedDefaults.put(
                        X6Ids.requireId(entry.getKey(), "default selector"),
                        X6Ids.requireId(entry.getValue(), "default variant")));
        defaults = X6Ids.immutableOrderedMap(sortedDefaults);
    }
}
