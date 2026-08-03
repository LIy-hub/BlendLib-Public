package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Bounds;
import java.util.Objects;

/** Immutable culling input prepared before submit; the backend only honors the prepared decision. */
public record CullingMetadata(Bounds worldBounds, boolean cullable) {
    public CullingMetadata {
        worldBounds = Objects.requireNonNull(worldBounds, "worldBounds");
    }
}
