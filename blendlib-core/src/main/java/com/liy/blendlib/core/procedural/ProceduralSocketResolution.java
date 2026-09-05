package com.liy.blendlib.core.procedural;

import com.liy.blendlib.core.model.Transform;
import java.util.Optional;

/** Snapshot-only SocketQuery result; a missing transform is paired with a structured diagnostic. */
public record ProceduralSocketResolution(Optional<Transform> transform, Optional<ProceduralDiagnostic> diagnostic) {
    public ProceduralSocketResolution {
        transform = transform == null ? Optional.empty() : transform;
        diagnostic = diagnostic == null ? Optional.empty() : diagnostic;
        if (transform.isPresent() == diagnostic.isPresent()) {
            throw new IllegalArgumentException("a socket resolution has exactly one of transform or diagnostic");
        }
    }

    static ProceduralSocketResolution found(Transform transform) {
        return new ProceduralSocketResolution(Optional.of(transform), Optional.empty());
    }

    static ProceduralSocketResolution rejected(ProceduralDiagnostic diagnostic) {
        return new ProceduralSocketResolution(Optional.empty(), Optional.of(diagnostic));
    }
}
