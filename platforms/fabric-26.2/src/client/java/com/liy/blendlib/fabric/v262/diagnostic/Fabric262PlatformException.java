package com.liy.blendlib.fabric.v262.diagnostic;

import java.util.Objects;

/**
 * Fail-closed runtime exception retaining one structured Fabric 26.2 adapter diagnostic.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. It is used only at platform
 * boundaries such as resource access and installation receipt validation, never as a replacement
 * for core descriptor/GLB diagnostics.</p>
 */
@SuppressWarnings("serial")
public final class Fabric262PlatformException extends IllegalStateException {
    private final Fabric262Diagnostic diagnostic;

    /**
     * Creates an exception from a non-null platform diagnostic.
     *
     * @param diagnostic structured failure information
     */
    public Fabric262PlatformException(Fabric262Diagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().value() + ": " + diagnostic.message());
        this.diagnostic = diagnostic;
    }

    /**
     * Creates an exception retaining a local implementation cause.
     *
     * @param diagnostic structured failure information
     * @param cause local implementation cause; it is never propagated through pure API/core
     */
    public Fabric262PlatformException(Fabric262Diagnostic diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code().value() + ": " + diagnostic.message(), cause);
        this.diagnostic = diagnostic;
    }

    /**
     * Returns the immutable platform diagnostic rather than requiring message parsing.
     *
     * @return non-null structured diagnostic
     */
    public Fabric262Diagnostic diagnostic() {
        return diagnostic;
    }
}
