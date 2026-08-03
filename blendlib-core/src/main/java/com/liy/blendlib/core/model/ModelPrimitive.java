package com.liy.blendlib.core.model;

import java.util.Objects;

/** Mesh primitive bound to one immutable scene node. */
public record ModelPrimitive(int nodeIndex, int meshIndex, int primitiveIndex, MeshPrimitive geometry) {
    public ModelPrimitive {
        if (nodeIndex < 0 || meshIndex < 0 || primitiveIndex < 0) {
            throw new IllegalArgumentException("Primitive binding indices must be non-negative");
        }
        geometry = Objects.requireNonNull(geometry, "geometry");
    }
}
