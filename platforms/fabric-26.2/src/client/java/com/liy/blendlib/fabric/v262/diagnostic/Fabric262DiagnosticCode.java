package com.liy.blendlib.fabric.v262.diagnostic;

/**
 * Machine-readable diagnostics owned by the Minecraft 26.2 Fabric adapter.
 *
 * <p><strong>Stable boundary:</strong> codes identify platform integration outcomes only. Core
 * parser and profile failures remain available through their original immutable diagnostics.</p>
 */
public enum Fabric262DiagnosticCode {
    /** A descriptor or its referenced strict GLB could not be resolved during reload. */
    RESOURCE_LOAD_FAILURE("BLENDLIB-F262-001"),

    /** A decoded profile or material intent has no implemented standard Fabric 26.2 submit path. */
    UNSUPPORTED_RENDER_PROFILE("BLENDLIB-F262-002"),

    /** A request targeted a model that is absent from the published reload generation. */
    MODEL_NOT_PUBLISHED("BLENDLIB-F262-003"),

    /** A native host token does not match the semantic host category supplied to the stable facade. */
    HOST_TRANSLATION_FAILURE("BLENDLIB-F262-004"),

    /** A caller attempted to close a runtime with a receipt from another installation epoch. */
    INSTALLATION_RECEIPT_MISMATCH("BLENDLIB-F262-005"),

    /** A caller requested lifecycle work after the owning runtime became terminal. */
    RUNTIME_CLOSED("BLENDLIB-F262-006"),

    /** A selected reload resource exceeded its strict bounded read limit before allocation. */
    RESOURCE_SIZE_LIMIT("BLENDLIB-F262-007"),

    /** A reload plan became older than the generation already published by this coordinator. */
    STALE_RELOAD_PLAN("BLENDLIB-F262-008"),

    /** Cleanup has started but an exact close receipt may still retry a failed terminal step. */
    RUNTIME_CLOSING("BLENDLIB-F262-009"),

    /** A stable host animation source or generation-bound pose sample failed during extraction. */
    ANIMATION_REQUEST_FAILURE("BLENDLIB-F262-010");

    private final String value;

    Fabric262DiagnosticCode(String value) {
        this.value = value;
    }

    /**
     * Returns the stable text value persisted in logs and integration reports.
     *
     * @return non-blank adapter diagnostic code
     */
    public String value() {
        return value;
    }
}
