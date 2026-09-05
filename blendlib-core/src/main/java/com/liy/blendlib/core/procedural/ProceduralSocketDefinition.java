package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.util.Objects;

/** Frozen semantic socket target: the numeric bone slot is resolved before frame evaluation. */
public record ProceduralSocketDefinition(BlendResourceId id, int boneIndex, Transform localTransform) {
    public ProceduralSocketDefinition {
        ProceduralSupport.requireId(id, "socket id");
        if (boneIndex < 0) {
            throw new IllegalArgumentException("socket bone index must be non-negative");
        }
        localTransform = Objects.requireNonNull(localTransform, "localTransform");
    }
}
