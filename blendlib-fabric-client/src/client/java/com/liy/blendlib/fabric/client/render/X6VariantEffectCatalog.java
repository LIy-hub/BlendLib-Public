package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable code-side effects corresponding to manifest-selected X6 variant ids. */
public final class X6VariantEffectCatalog {
    private final BlendModelKey modelKey;
    private final long generation;
    private final Map<BlendResourceId, X6VariantEffect> effects;

    public X6VariantEffectCatalog(
            BlendModelKey modelKey, long generation, Map<BlendResourceId, ? extends X6VariantEffect> effects) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(effects, "effects");
        Map<BlendResourceId, X6VariantEffect> ordered = new LinkedHashMap<>();
        effects.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> ordered.put(
                        X6Ids.requireId(entry.getKey(), "variant id"),
                        Objects.requireNonNull(entry.getValue(), "variant effect")));
        this.generation = generation;
        this.effects = X6Ids.immutableOrderedMap(ordered);
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public X6VariantEffect effect(BlendResourceId variantId) {
        X6VariantEffect effect = effects.get(Objects.requireNonNull(variantId, "variantId"));
        if (effect == null) {
            throw new IllegalArgumentException("No prepared X6 effect exists for variant " + variantId);
        }
        return effect;
    }
}
