package com.liy.blendlib.spi.experimental;

/** Requirement strength for an experimental capability request. */
@ExperimentalBlendLibSpi
public enum CapabilityRequirement {
    /** No publishable plan exists unless a compatible provider is selected. */
    REQUIRED,

    /** A predeclared semantic-equivalent fallback may be used when no provider can be selected. */
    OPTIONAL
}
