package com.liy.blendlib.fabric.v262.diagnostic;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable diagnostic emitted by the Minecraft 26.2 Fabric adapter.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. The optional model key makes a
 * failure attributable without exposing a Minecraft {@code Identifier} through pure API/core.
 * Message text is bounded so untrusted resource paths cannot produce unbounded log payloads.</p>
 *
 * @param code adapter-owned code
 * @param severity local severity
 * @param model optional affected semantic model
 * @param message bounded explanation safe for logs
 */
public record Fabric262Diagnostic(
        Fabric262DiagnosticCode code,
        Fabric262DiagnosticSeverity severity,
        Optional<BlendModelKey> model,
        String message) {
    /** Maximum message length retained by this platform diagnostic. */
    public static final int MAX_MESSAGE_LENGTH = 512;

    /**
     * Validates and canonicalizes an immutable adapter diagnostic.
     */
    public Fabric262Diagnostic {
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        model = Objects.requireNonNull(model, "model");
        message = sanitize(Objects.requireNonNull(message, "message"));
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
        }
    }

    /**
     * Creates a model-scoped error diagnostic.
     *
     * @param code adapter error code
     * @param model affected strict model key
     * @param message bounded explanation
     * @return immutable error diagnostic
     */
    public static Fabric262Diagnostic error(
            Fabric262DiagnosticCode code,
            BlendModelKey model,
            String message) {
        return new Fabric262Diagnostic(code, Fabric262DiagnosticSeverity.ERROR,
                Optional.of(Objects.requireNonNull(model, "model")), message);
    }

    /**
     * Creates a non-model-specific error diagnostic.
     *
     * @param code adapter error code
     * @param message bounded explanation
     * @return immutable error diagnostic
     */
    public static Fabric262Diagnostic error(Fabric262DiagnosticCode code, String message) {
        return new Fabric262Diagnostic(code, Fabric262DiagnosticSeverity.ERROR, Optional.empty(), message);
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            result.append(Character.isISOControl(codePoint) ? '?' : Character.toChars(codePoint));
        }
        return result.toString();
    }
}
