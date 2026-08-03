package com.liy.blendlib.core.animation;

/** The only interpolation modes accepted by the strict v1 profile. */
public enum Interpolation {
    LINEAR,
    STEP;

    public static Interpolation fromSerializedName(String value) {
        return switch (value) {
            case "LINEAR" -> LINEAR;
            case "STEP" -> STEP;
            default -> throw new IllegalArgumentException("Unsupported v1 interpolation: " + value);
        };
    }
}
