package com.liy.blendlib.core.diagnostic;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Immutable, bounded diagnostic data for one asset-validation outcome.
 *
 * <p>{@code modelKey} and {@code resourceId} are nullable only when the
 * failing input has not yet established them. The remaining fields are always
 * present so callers do not need to recover structure from message text.</p>
 */
public record BlendDiagnostic(
        DiagnosticSeverity severity,
        String code,
        BlendResourceId modelKey,
        BlendResourceId resourceId,
        String location,
        String message,
        String causeSummary) {
    private static final int MAX_TEXT_LENGTH = 1_024;

    public BlendDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = requireText(code, "code");
        location = boundText(location, "location");
        message = boundText(message, "message");
        causeSummary = boundText(causeSummary, "causeSummary");
    }

    /** Creates an error diagnostic without exposing an arbitrary throwable graph. */
    public static BlendDiagnostic error(
            String code,
            BlendResourceId modelKey,
            BlendResourceId resourceId,
            String location,
            String message) {
        return new BlendDiagnostic(DiagnosticSeverity.ERROR, code, modelKey, resourceId, location, message, "");
    }

    /** Returns a copy with a bounded cause summary added for diagnostic consumers. */
    public BlendDiagnostic withCause(Throwable cause) {
        if (cause == null) {
            return this;
        }
        String type = cause.getClass().getSimpleName();
        String detail = cause.getMessage();
        return new BlendDiagnostic(severity, code, modelKey, resourceId, location, message,
                detail == null || detail.isBlank() ? type : type + ": " + detail);
    }

    private static String requireText(String value, String name) {
        String result = boundText(value, name);
        if (result.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }

    private static String boundText(String value, String name) {
        Objects.requireNonNull(value, name);
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }
}
