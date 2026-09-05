package com.liy.blendlib.neoforge.v262;

import java.util.Objects;

/**
 * Fail-closed exception retaining one structured pure NeoForge 26.2 bridge diagnostic.
 *
 * <p><strong>WAITING boundary:</strong> this exception represents only pure bridge failures. It
 * neither implies that a NeoForge loader is present nor exposes a native exception or registry
 * type before the official 26.2 binding exists.</p>
 */
@SuppressWarnings("serial")
public final class NeoForge262BridgeException extends IllegalStateException {
    private final NeoForge262Diagnostic diagnostic;

    /**
     * Creates a diagnostic-carrying bridge failure.
     *
     * @param diagnostic non-null structured bridge diagnostic
     */
    public NeoForge262BridgeException(NeoForge262Diagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().value() + ": " + diagnostic.message());
        this.diagnostic = diagnostic;
    }

    /**
     * Creates a diagnostic-carrying bridge failure with a local source cause.
     *
     * @param diagnostic non-null structured bridge diagnostic
     * @param cause local pure bridge or supplied-resolver failure
     */
    public NeoForge262BridgeException(NeoForge262Diagnostic diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().value() + ": " + diagnostic.message(), cause);
        this.diagnostic = diagnostic;
    }

    /**
     * Returns the structured failure without requiring message parsing.
     *
     * @return non-null immutable bridge diagnostic
     */
    public NeoForge262Diagnostic diagnostic() {
        return diagnostic;
    }
}
