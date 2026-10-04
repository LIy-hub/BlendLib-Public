package com.liy.blendlib.core.animation;

/** Runtime interpolation modes. The serialized-name parser intentionally remains strict v1. */
public enum Interpolation {
    LINEAR,
    STEP,
    CUBICSPLINE;

    public static Interpolation fromSerializedName(String value) {
        return switch (value) {
            case "LINEAR" -> LINEAR;
            case "STEP" -> STEP;
            default -> throw new IllegalArgumentException("Unsupported v1 interpolation: " + value);
        };
    }
}
