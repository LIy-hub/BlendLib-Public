package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/** Immutable, model-and-generation-scoped X6 preparation diagnostic. */
public record X6Diagnostic(
        X6DiagnosticSeverity severity,
        X6DiagnosticCode code,
        BlendModelKey modelKey,
        long generation,
        Optional<BlendResourceId> subjectId,
        String message) {
    /** Bounds retained messages so malformed adapter-side manifests cannot create log-sized state. */
    public static final int MAX_MESSAGE_LENGTH = 512;

    public X6Diagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must be non-blank and at most " + MAX_MESSAGE_LENGTH + " characters");
        }
    }

    static X6Diagnostic error(
            X6DiagnosticCode code, BlendModelKey modelKey, long generation, BlendResourceId subjectId, String message) {
        return new X6Diagnostic(X6DiagnosticSeverity.ERROR, code, modelKey, generation, Optional.ofNullable(subjectId), message);
    }

    static X6Diagnostic warning(
            X6DiagnosticCode code, BlendModelKey modelKey, long generation, BlendResourceId subjectId, String message) {
        return new X6Diagnostic(X6DiagnosticSeverity.WARNING, code, modelKey, generation, Optional.ofNullable(subjectId), message);
    }
}
