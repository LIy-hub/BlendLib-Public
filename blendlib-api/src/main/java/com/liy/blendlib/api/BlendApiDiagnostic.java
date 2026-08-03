package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable diagnostic emitted by the stable registration facade.
 *
 * @param code stable registration error code
 * @param severity diagnostic severity
 * @param message bounded human-readable explanation
 */
public record BlendApiDiagnostic(
        BlendApiDiagnosticCode code,
        BlendDiagnosticSeverity severity,
        String message) {

    /** Maximum number of UTF-16 code units retained in a public diagnostic message. */
    public static final int MAX_MESSAGE_LENGTH = 512;

    /** Validates that a diagnostic has a code, severity, and bounded non-blank message. */
    public BlendApiDiagnostic {
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must be non-blank and at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        message = sanitize(message);
    }

    private static String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                sanitized.append('?');
            } else {
                sanitized.appendCodePoint(codePoint);
            }
        }
        return sanitized.toString();
    }
}
