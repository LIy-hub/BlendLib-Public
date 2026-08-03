package com.liy.blendlib.core.model;

/** Strict GLB capability profile selected by a v1 descriptor. */
public enum ModelProfile {
    RIGID_V1("blendlib:rigid_v1"),
    SKINNED_V1("blendlib:skinned_v1");

    private final String serializedName;

    ModelProfile(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static ModelProfile fromSerializedName(String serializedName) {
        for (ModelProfile profile : values()) {
            if (profile.serializedName.equals(serializedName)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unsupported BlendLib v1 profile: " + serializedName);
    }
}
