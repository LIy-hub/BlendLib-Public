package com.liy.blendlib.core.model;

/** Immutable finite two-dimensional vector in canonical asset space. */
public record Vec2(float x, float y) {
    public Vec2 {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }
}
