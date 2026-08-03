package com.liy.blendlib.core.tooling;

import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import java.util.Objects;

/** Deterministic CLI-facing diagnostic; it may preserve a strict-core code. */
public record AssetValidationDiagnostic(DiagnosticSeverity severity, String code, String location, String message) {
    private static final int MAX_TEXT_LENGTH = 1_024;

    public AssetValidationDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = bounded(code, "code");
        location = bounded(location, "location");
        message = bounded(message, "message");
        if (code.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("X5 validation diagnostics require a code and message");
        }
    }

    private static String bounded(String value, String name) {
        value = Objects.requireNonNull(value, name);
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }
}
