package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable selection plan consumed by X6 compilation; it carries no parser or context reference. */
public final class X6VariantSelectionPlan {
    private final BlendModelKey modelKey;
    private final long generation;
    private final Map<BlendResourceId, X6VariantDefinition> variantsById;
    private final List<X6VariantSelection> selections;

    X6VariantSelectionPlan(
            BlendModelKey modelKey,
            long generation,
            Map<BlendResourceId, X6VariantDefinition> variantsById,
            List<X6VariantSelection> selections) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Map<BlendResourceId, X6VariantDefinition> orderedVariants = new LinkedHashMap<>();
        Objects.requireNonNull(variantsById, "variantsById").entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> orderedVariants.put(
                        X6Ids.requireId(entry.getKey(), "variant key"),
                        Objects.requireNonNull(entry.getValue(), "variant definition")));
        this.variantsById = X6Ids.immutableOrderedMap(orderedVariants);
        this.selections = List.copyOf(Objects.requireNonNull(selections, "selections").stream()
                .sorted(Comparator.comparing(selection -> selection.selectorId().value()))
                .toList());
        this.generation = generation;
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public List<X6VariantSelection> selections() {
        return selections;
    }

    public Map<BlendResourceId, X6VariantDefinition> variantsById() {
        return variantsById;
    }

    public Optional<X6VariantDefinition> selectedVariant(BlendResourceId selectorId) {
        Objects.requireNonNull(selectorId, "selectorId");
        return selections.stream()
                .filter(selection -> selection.selectorId().equals(selectorId))
                .flatMap(selection -> selection.variantId().stream())
                .map(variantsById::get)
                .findFirst();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof X6VariantSelectionPlan that)) {
            return false;
        }
        return generation == that.generation
                && modelKey.equals(that.modelKey)
                && variantsById.equals(that.variantsById)
                && selections.equals(that.selections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelKey, generation, variantsById, selections);
    }
}
