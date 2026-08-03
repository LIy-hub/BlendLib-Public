package com.liy.blendlib.core.profile.experimental;

/** Explicit handling for an optional X9 capability that cannot be negotiated. */
public enum OptionalCapabilityFallback {
    /** Only metadata marked as non-rendering may be ignored, with a warning diagnostic. */
    METADATA_IGNORE("metadata_ignore"),
    /** The asset is represented by the standard missing-model path; no visual behavior is silently changed. */
    MISSING_MODEL("missing_model"),
    /** Required capabilities always fail closed. */
    FAIL_CLOSED("fail_closed");

    private final String serializedName;

    OptionalCapabilityFallback(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static OptionalCapabilityFallback fromSerializedName(String serializedName) {
        for (OptionalCapabilityFallback fallback : values()) {
            if (fallback.serializedName.equals(serializedName)) {
                return fallback;
            }
        }
        throw new IllegalArgumentException("Unsupported optional capability fallback: " + serializedName);
    }
}
