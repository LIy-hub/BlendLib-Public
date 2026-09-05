package com.liy.blendlib.spi.experimental;

/**
 * Requirement strength for an experimental capability request.
 *
 * <p>A required request rejects publication when no compatible offer is selected. An optional
 * request remains publishable only when it carries an explicit, host-validated
 * semantic-equivalent {@link CapabilityFallback}; optional does not permit discovery-order,
 * renderer, or version guessing.</p>
 */
@ExperimentalBlendLibSpi
public enum CapabilityRequirement {
    /** No publishable plan exists unless a compatible provider is selected. */
    REQUIRED,

    /** A predeclared semantic-equivalent fallback may be used when no provider can be selected. */
    OPTIONAL
}
