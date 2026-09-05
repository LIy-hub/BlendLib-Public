package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import java.util.Objects;

/** Structured missing-model evidence coupled to the exact immutable lookup generation. */
public record X4MissingModelDiagnostic(long generation, ClientDiagnostic diagnostic) {
    public X4MissingModelDiagnostic {
        if (generation < 0L) {
            throw new IllegalArgumentException("Missing-model diagnostic generation must be non-negative");
        }
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Verifies that this diagnostic is evidence for precisely one captured model/generation. */
    public void requireFor(BlendModelKey modelKey, long expectedGeneration) {
        BlendModelKey checkedKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation != expectedGeneration || !checkedKey.resourceId().equals(diagnostic.modelKey())) {
            throw new IllegalArgumentException("Missing-model diagnostic must match the exact model key and generation");
        }
    }
}
