package com.liy.blendlib.core.diagnostic;

import java.util.Objects;

/** Controlled failure raised by the strict, pure-Java asset loader. */
public final class BlendAssetLoadException extends IllegalArgumentException {
    private final BlendDiagnostic diagnostic;

    public BlendAssetLoadException(BlendDiagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").message());
        this.diagnostic = diagnostic;
    }

    public BlendAssetLoadException(BlendDiagnostic diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").message(), cause);
        this.diagnostic = diagnostic.withCause(cause);
    }

    public BlendDiagnostic diagnostic() {
        return diagnostic;
    }
}
