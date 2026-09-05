package com.liy.blendlib.fabric.client.render;

/** Stable, X6-local diagnostics. They do not allocate or alter frozen v1 error-code meanings. */
public enum X6DiagnosticCode {
    MANIFEST_INVALID("BLENDLIB-X6-MANIFEST-001"),
    VARIANT_LIMIT("BLENDLIB-X6-VAR-001"),
    VARIANT_DUPLICATE("BLENDLIB-X6-VAR-002"),
    VARIANT_UNKNOWN("BLENDLIB-X6-VAR-003"),
    VARIANT_CONFLICT("BLENDLIB-X6-VAR-004"),
    VARIANT_NO_MATCH("BLENDLIB-X6-VAR-005"),
    LAYER_LIMIT("BLENDLIB-X6-LAYER-001"),
    LAYER_DUPLICATE("BLENDLIB-X6-LAYER-002"),
    LAYER_CONFLICT("BLENDLIB-X6-LAYER-003"),
    LAYER_TARGET_MISSING("BLENDLIB-X6-LAYER-004"),
    LAYER_UNSUPPORTED("BLENDLIB-X6-LAYER-005"),
    MATERIAL_UNSUPPORTED("BLENDLIB-X6-MAT-001"),
    CAPABILITY_FAILURE("BLENDLIB-X6-CAP-001"),
    PROVIDER_FAILURE("BLENDLIB-X6-CAP-002"),
    GENERATION_MISMATCH("BLENDLIB-X6-SNAPSHOT-001"),
    SNAPSHOT_LEASE_CLOSED("BLENDLIB-X6-SNAPSHOT-002"),
    GEOMETRY_MISMATCH("BLENDLIB-X6-GEOMETRY-001"),
    LIFECYCLE_OWNER_REQUIRED("BLENDLIB-X6-LIFECYCLE-001");

    private final String code;

    X6DiagnosticCode(String code) {
        this.code = code;
    }

    /** Returns the stable machine-readable X6 code. */
    public String code() {
        return code;
    }
}
