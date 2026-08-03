package com.liy.blendlib.spi.experimental;

/** Stable diagnostic/error-code assignments for the separately versioned X1 capability protocol. */
@ExperimentalBlendLibSpi
public enum CapabilityErrorCode {
    /** A provider identity was registered more than once. */
    DUPLICATE_PROVIDER_ID("BLENDLIB-X1-CAP-001"),

    /** One provider advertised the same capability more than once. */
    DUPLICATE_PROVIDER_CAPABILITY("BLENDLIB-X1-CAP-002"),

    /** No registered provider advertised a required capability. */
    REQUIRED_UNSUPPORTED("BLENDLIB-X1-CAP-003"),

    /** Providers advertised the capability, but none supplied a compatible protocol version. */
    VERSION_MISMATCH("BLENDLIB-X1-CAP-004"),

    /** Multiple compatible providers tied for the highest priority claim. */
    TOP_PRIORITY_CONFLICT("BLENDLIB-X1-CAP-005"),

    /** An optional request selected its explicit semantic-equivalent fallback. */
    OPTIONAL_FALLBACK("BLENDLIB-X1-CAP-006"),

    /** A registration, discovery, or freeze mutation was attempted after the registry was frozen. */
    REGISTRY_FROZEN("BLENDLIB-X1-CAP-007"),

    /** Lifecycle methods were invoked outside the registered discovery/freeze/prepare/apply order. */
    INVALID_LIFECYCLE_STATE("BLENDLIB-X1-CAP-008"),

    /** A selected provider failed while preparing immutable generation data. */
    PROVIDER_PREPARE_FAILURE("BLENDLIB-X1-CAP-009"),

    /** A selected provider failed while applying adapter-bound generation data. */
    PROVIDER_APPLY_FAILURE("BLENDLIB-X1-CAP-010"),

    /** A provider threw during its one terminal close attempt. */
    PROVIDER_CLOSE_FAILURE("BLENDLIB-X1-CAP-011"),

    /** The supplied providers cannot realize the selected immutable plan. */
    PLAN_PROVIDER_MISSING("BLENDLIB-X1-CAP-012"),

    /** Provider metadata did not match the provider's canonical identity or contained an invalid claim. */
    INVALID_PROVIDER_OFFER("BLENDLIB-X1-CAP-013"),

    /** A selected provider failed while retiring its generation-scoped resources. */
    PROVIDER_RETIRE_FAILURE("BLENDLIB-X1-CAP-014"),

    /** A provider instance is already terminally closed and cannot acquire a new owner. */
    PROVIDER_OWNERSHIP_CONFLICT("BLENDLIB-X1-CAP-015"),

    /** A purported frozen plan failed registry-snapshot or cross-field invariant validation. */
    INVALID_FROZEN_PLAN("BLENDLIB-X1-CAP-016"),

    /** Caller-supplied capability-request metadata is invalid, unbounded, or failed traversal. */
    INVALID_CAPABILITY_REQUEST("BLENDLIB-X1-CAP-017");

    private final String code;

    CapabilityErrorCode(String code) {
        this.code = code;
    }

    /**
     * Returns the machine-readable experimental capability diagnostic code.
     *
     * @return stable experimental code text
     */
    public String code() {
        return code;
    }
}
