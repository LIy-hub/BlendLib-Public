package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import java.util.List;
import java.util.Objects;

/** Immutable executable result of applying all selected X6 variants to prepared geometry. */
public final class X6VariantApplicationPlan {
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<X6DrawPrimitive> draws;
    private final List<ModelRenderSnapshot> equipmentSnapshots;

    X6VariantApplicationPlan(
            BlendModelKey modelKey, long generation, List<X6DrawPrimitive> draws, List<ModelRenderSnapshot> equipmentSnapshots) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.draws = List.copyOf(Objects.requireNonNull(draws, "draws"));
        this.equipmentSnapshots = List.copyOf(Objects.requireNonNull(equipmentSnapshots, "equipmentSnapshots"));
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public List<X6DrawPrimitive> draws() {
        return draws;
    }

    public List<ModelRenderSnapshot> equipmentSnapshots() {
        return equipmentSnapshots;
    }
}
