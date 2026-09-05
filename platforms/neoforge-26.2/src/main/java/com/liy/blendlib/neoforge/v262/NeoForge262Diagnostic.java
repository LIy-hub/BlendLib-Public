package com.liy.blendlib.neoforge.v262;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable diagnostic emitted by the NeoForge 26.2 pure bridge.
 *
 * <p><strong>WAITING boundary:</strong> this data reports a bridge outcome without exposing a
 * NeoForge error type or native identifier to pure API/core.</p>
 *
 * @param code bridge diagnostic code
 * @param model optional affected strict model
 * @param message bounded explanation
 */
public record NeoForge262Diagnostic(
        NeoForge262DiagnosticCode code,
        Optional<BlendModelKey> model,
        String message) {
    /**
     * Validates a bridge diagnostic.
     */
    public NeoForge262Diagnostic {
        code = Objects.requireNonNull(code, "code");
        model = Objects.requireNonNull(model, "model");
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 512) {
            throw new IllegalArgumentException("message must be non-blank and at most 512 characters");
        }
    }

    /**
     * Creates a model-scoped bridge diagnostic.
     *
     * @param code machine-readable bridge code
     * @param model strict affected model key
     * @param message bounded explanation
     * @return immutable model-scoped diagnostic
     */
    public static NeoForge262Diagnostic model(
            NeoForge262DiagnosticCode code,
            BlendModelKey model,
            String message) {
        return new NeoForge262Diagnostic(code, Optional.of(Objects.requireNonNull(model, "model")), message);
    }

    /**
     * Creates a bridge-wide diagnostic.
     *
     * @param code machine-readable bridge code
     * @param message bounded explanation
     * @return immutable bridge-wide diagnostic
     */
    public static NeoForge262Diagnostic global(NeoForge262DiagnosticCode code, String message) {
        return new NeoForge262Diagnostic(code, Optional.empty(), message);
    }
}
