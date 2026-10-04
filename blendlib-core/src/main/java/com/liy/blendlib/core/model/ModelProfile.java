package com.liy.blendlib.core.model;

/** Strict GLB capability profile selected by an explicit versioned descriptor. */
public enum ModelProfile {
    RIGID_V1("blendlib:rigid_v1"),
    SKINNED_V1("blendlib:skinned_v1"),
    SKINNED_CUBIC_V1("blendlib:skinned_cubic_v1");

    private final String serializedName;

    ModelProfile(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public boolean skinned() { return this == SKINNED_V1 || this == SKINNED_CUBIC_V1; }

    /** Frozen v1 parser: the cubic profile requires the separate version-2 dispatcher. */
    public static ModelProfile fromSerializedName(String serializedName) {
        for (ModelProfile profile : values()) {
            if (profile != SKINNED_CUBIC_V1 && profile.serializedName.equals(serializedName)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unsupported BlendLib v1 profile: " + serializedName);
    }
}
