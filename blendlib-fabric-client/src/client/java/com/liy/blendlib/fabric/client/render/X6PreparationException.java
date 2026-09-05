package com.liy.blendlib.fabric.client.render;

/** Internal typed failure used to turn prepare-time validation into one stable X6 diagnostic. */
final class X6PreparationException extends IllegalArgumentException {
    @SuppressWarnings("serial")
    private static final long serialVersionUID = 1L;

    private final X6DiagnosticCode code;

    X6PreparationException(X6DiagnosticCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    X6PreparationException(X6DiagnosticCode code, String message) {
        this(code, message, null);
    }

    X6DiagnosticCode code() {
        return code;
    }
}
