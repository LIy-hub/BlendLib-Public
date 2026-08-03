package com.liy.blendlib.fabric.client.render;

/** Explicit P4 material failures that must not be silently remapped to another layer. */
public enum MaterialRejectionReason {
    ADDITIVE_UNSUPPORTED_IN_P4("mode"),
    OPAQUE_DOUBLE_SIDED_UNSUPPORTED("double_sided"),
    TRANSLUCENT_SINGLE_SIDED_UNSUPPORTED("double_sided"),
    CUTOUT_THRESHOLD_UNSUPPORTED("cutout_threshold");

    private final String descriptorField;

    MaterialRejectionReason(String descriptorField) {
        this.descriptorField = descriptorField;
    }

    /** JSON field whose descriptor intent the 26.1.2 public path cannot represent. */
    public String descriptorField() {
        return descriptorField;
    }
}
