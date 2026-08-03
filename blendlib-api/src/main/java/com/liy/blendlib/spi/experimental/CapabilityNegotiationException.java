package com.liy.blendlib.spi.experimental;

import java.util.Objects;

/** Fail-closed exception carrying a structured capability diagnostic. */
@ExperimentalBlendLibSpi
@SuppressWarnings("serial")
public final class CapabilityNegotiationException extends IllegalStateException {
    /** Structured local diagnostic retained with this exception. */
    private final CapabilityDiagnostic diagnostic;

    /**
     * Creates an exception from a non-null experimental capability diagnostic.
     *
     * @param diagnostic structured failure information
     */
    public CapabilityNegotiationException(CapabilityDiagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().code() + ": " + diagnostic.message());
        this.diagnostic = diagnostic;
    }

    /**
     * Returns the structured failure information.
     *
     * @return immutable capability diagnostic
     */
    public CapabilityDiagnostic diagnostic() {
        return diagnostic;
    }
}
