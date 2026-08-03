package com.liy.blendlib.core.profile.experimental;

/**
 * Proposed X9 asset profiles. These names are intentionally isolated from the
 * frozen v1 {@code ModelProfile} enum and do not change the v1 acceptance set.
 */
public enum ExperimentalProfile {
    SKINNED_V2("blendlib:skinned_v2"),
    MORPH_V1("blendlib:morph_v1");

    private final String serializedName;

    ExperimentalProfile(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static ExperimentalProfile fromSerializedName(String serializedName) {
        for (ExperimentalProfile profile : values()) {
            if (profile.serializedName.equals(serializedName)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unsupported X9 experimental profile: " + serializedName);
    }
}
