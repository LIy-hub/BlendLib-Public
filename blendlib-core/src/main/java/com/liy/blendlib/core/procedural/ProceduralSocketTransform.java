package com.liy.blendlib.core.procedural;

import com.liy.blendlib.core.model.Transform;
import java.util.Objects;

/** Immutable model-space socket transform derived only from the final procedural hierarchy. */
public record ProceduralSocketTransform(int boneIndex, Transform transform) {
    public ProceduralSocketTransform {
        if (boneIndex < 0) {
            throw new IllegalArgumentException("socket bone index must be non-negative");
        }
        transform = Objects.requireNonNull(transform, "transform");
    }
}
