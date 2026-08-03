package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Fail-closed exception raised when a public registration cannot be accepted safely.
 *
 * <p>Callers can inspect {@link #diagnostic()} instead of parsing an exception message.</p>
 */
@SuppressWarnings("serial")
public final class BlendRegistrationException extends IllegalStateException {
    /** Structured local diagnostic retained with this exception. */
    private final BlendApiDiagnostic diagnostic;

    /**
     * Creates an exception with the supplied structured diagnostic.
     *
     * @param diagnostic the non-null registration diagnostic
     */
    public BlendRegistrationException(BlendApiDiagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().code() + ": " + diagnostic.message());
        this.diagnostic = diagnostic;
    }

    /**
     * Creates an exception with a structured diagnostic and a caller-managed local cause.
     * Platform/provider boundaries do not expose an untrusted callback throwable through this constructor.
     *
     * @param diagnostic the non-null registration diagnostic
     * @param cause local cause retained by the caller
     */
    public BlendRegistrationException(BlendApiDiagnostic diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().code() + ": " + diagnostic.message(), cause);
        this.diagnostic = diagnostic;
    }

    /**
     * Returns the structured error rather than requiring callers to parse text.
     *
     * @return immutable registration diagnostic
     */
    public BlendApiDiagnostic diagnostic() {
        return diagnostic;
    }
}
