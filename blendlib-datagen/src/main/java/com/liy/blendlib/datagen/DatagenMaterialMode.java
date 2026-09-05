package com.liy.blendlib.datagen;

/**
 * Strict v1 material mode emitted in a generated descriptor.
 *
 * <p><strong>Stable boundary:</strong> values map to the frozen descriptor enum and contain no
 * renderer implementation detail. Runtime platform capability selection remains separate.</p>
 */
public enum DatagenMaterialMode {
    /** Opaque material mode. */
    OPAQUE("opaque"),

    /** Threshold-cutout material mode. */
    CUTOUT("cutout"),

    /** Alpha-blended material mode. */
    TRANSLUCENT("translucent"),

    /** Additive material mode. */
    ADDITIVE("additive");

    private final String serializedName;

    DatagenMaterialMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /**
     * Returns the exact frozen descriptor mode text.
     *
     * @return strict serialized material mode
     */
    public String serializedName() {
        return serializedName;
    }
}
