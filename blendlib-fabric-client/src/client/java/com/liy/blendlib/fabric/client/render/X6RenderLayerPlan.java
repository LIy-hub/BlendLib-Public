package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import java.util.List;
import java.util.Objects;

/** Immutable ordered render-layer plan pinned to one model/resource generation. */
public final class X6RenderLayerPlan {
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<X6ResolvedRenderLayer> layers;

    X6RenderLayerPlan(BlendModelKey modelKey, long generation, List<X6ResolvedRenderLayer> layers) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public List<X6ResolvedRenderLayer> layers() {
        return layers;
    }
}
