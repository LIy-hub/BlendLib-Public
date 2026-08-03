package com.liy.blendlib.api;

/**
 * Stable error-code assignments for the X1 public registration facade.
 *
 * <p>These codes are separate from the frozen v1 descriptor and GLB diagnostic allocations.
 * They describe API registration only and do not reinterpret any existing descriptor payload.</p>
 */
public enum BlendApiDiagnosticCode {
    /** No controlled platform adapter is available to accept a completed registration. */
    PLATFORM_ADAPTER_UNAVAILABLE("BLENDLIB-X1-REG-001"),

    /** A registration was completed without assigning a semantic model key. */
    MODEL_MISSING("BLENDLIB-X1-REG-002"),

    /** A registration was completed without assigning a semantic animation source. */
    ANIMATION_MISSING("BLENDLIB-X1-REG-003"),

    /** The same host category and host value were registered more than once in one adapter scope. */
    DUPLICATE_TARGET("BLENDLIB-X1-REG-004"),

    /** A controlled platform adapter rejected a validated semantic registration. */
    PLATFORM_ADAPTER_FAILURE("BLENDLIB-X1-REG-005"),

    /** A controlled platform adapter returned a receipt inconsistent with the submitted specification. */
    INVALID_PLATFORM_RECEIPT("BLENDLIB-X1-REG-006"),

    /** An item registration requested stateful one-shot or hold playback without item-instance identity. */
    UNSUPPORTED_ITEM_ANIMATION("BLENDLIB-X1-REG-007");

    private final String code;

    BlendApiDiagnosticCode(String code) {
        this.code = code;
    }

    /**
     * Returns the machine-readable diagnostic code.
     *
     * @return stable code text
     */
    public String code() {
        return code;
    }
}
