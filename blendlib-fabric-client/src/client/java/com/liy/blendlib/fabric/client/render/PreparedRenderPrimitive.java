package com.liy.blendlib.fabric.client.render;

import java.util.Objects;

/** Immutable rest-pose primitive binding prepared outside the render submit path. */
public record PreparedRenderPrimitive(int nodeIndex, StaticGeometry geometry, RenderMaterial material) {
    public PreparedRenderPrimitive {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("nodeIndex must be non-negative");
        }
        geometry = Objects.requireNonNull(geometry, "geometry");
        material = Objects.requireNonNull(material, "material");
    }
}
