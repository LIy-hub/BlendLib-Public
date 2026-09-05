package com.liy.blendlib.neoforge.v262;

/**
 * Machine-readable diagnostics for the pure NeoForge 26.2 bridge.
 *
 * <p><strong>WAITING boundary:</strong> these diagnostics describe bridge preparation and missing
 * binding behavior without importing a NeoForge class or claiming loader compatibility.</p>
 */
public enum NeoForge262DiagnosticCode {
    /** A strict descriptor or GLB resource could not be prepared by the supplied bridge source. */
    RESOURCE_PREPARATION_FAILURE("BLENDLIB-NF262-001"),

    /** A requested model has no prepared asset in the published bridge generation. */
    MODEL_NOT_PUBLISHED("BLENDLIB-NF262-002"),

    /** The unresolved official loader/mappings/event binding prevents runtime adapter installation. */
    OFFICIAL_BINDING_WAITING("BLENDLIB-NF262-003"),

    /** A host identity resolver could not reduce a native future host token to a semantic identity. */
    HOST_IDENTITY_FAILURE("BLENDLIB-NF262-004"),

    /** A prepared bridge generation is older than one already made active. */
    STALE_GENERATION("BLENDLIB-NF262-005"),

    /** A generation was issued by a different bridge or was not bridge-owned data. */
    FOREIGN_GENERATION("BLENDLIB-NF262-006"),

    /** A prepared asset did not retain the same generation as its enclosing bridge plan. */
    ASSET_GENERATION_MISMATCH("BLENDLIB-NF262-007"),

    /** A bridge generation was already claimed for an earlier apply attempt. */
    GENERATION_ALREADY_APPLIED("BLENDLIB-NF262-008"),

    /** The monotonic bridge counter reached its reserved maximum sentinel. */
    GENERATION_EXHAUSTED("BLENDLIB-NF262-009");

    private final String value;

    NeoForge262DiagnosticCode(String value) {
        this.value = value;
    }

    /**
     * Returns the stable text value for logs and handoff documentation.
     *
     * @return non-blank diagnostic code
     */
    public String value() {
        return value;
    }
}
