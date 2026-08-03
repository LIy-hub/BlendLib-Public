package com.liy.blendlib.core.animation;

/** Supported glTF node transform targets. */
public enum AnimationPath {
    TRANSLATION("translation", 3),
    ROTATION("rotation", 4),
    SCALE("scale", 3);

    private final String serializedName;
    private final int components;

    AnimationPath(String serializedName, int components) {
        this.serializedName = serializedName;
        this.components = components;
    }

    public String serializedName() {
        return serializedName;
    }

    public int components() {
        return components;
    }

    public static AnimationPath fromSerializedName(String value) {
        for (AnimationPath path : values()) {
            if (path.serializedName.equals(value)) {
                return path;
            }
        }
        throw new IllegalArgumentException("Unsupported animation target path: " + value);
    }
}
