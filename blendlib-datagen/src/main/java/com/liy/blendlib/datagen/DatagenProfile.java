package com.liy.blendlib.datagen;

/**
 * Strict descriptor profile emitted by the data generator.
 *
 * <p><strong>Stable boundary:</strong> serialized values match the frozen v1 descriptor contract.
 * A generated descriptor references only strict GLB data; it never reads or emits Blender, FBX,
 * OBJ, or a platform-specific model type.</p>
 */
public enum DatagenProfile {
    /** Strict rigid GLB profile with optional node-based animation. */
    RIGID_V1("blendlib:rigid_v1"),

    /** Strict skinned GLB profile with validated four-weight skin data. */
    SKINNED_V1("blendlib:skinned_v1");

    private final String serializedName;

    DatagenProfile(String serializedName) {
        this.serializedName = serializedName;
    }

    /**
     * Returns the exact frozen descriptor profile text.
     *
     * @return strict serialized profile name
     */
    public String serializedName() {
        return serializedName;
    }
}
