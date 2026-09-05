package com.liy.blendlib.fabric.client.render;

import java.util.Objects;

/** Fully explicit layer behavior handed to the standard public-path submit seam. */
public record X6LayerSemantics(
        X6RenderPhase phase,
        int order,
        X6BlendSemantic blend,
        X6CullingSemantic culling,
        X6LightSemantic light,
        X6TextureTarget textureTarget) {
    public X6LayerSemantics {
        phase = Objects.requireNonNull(phase, "phase");
        if (order < 0) {
            throw new IllegalArgumentException("order must be non-negative");
        }
        blend = Objects.requireNonNull(blend, "blend");
        culling = Objects.requireNonNull(culling, "culling");
        light = Objects.requireNonNull(light, "light");
        textureTarget = Objects.requireNonNull(textureTarget, "textureTarget");
    }
}
