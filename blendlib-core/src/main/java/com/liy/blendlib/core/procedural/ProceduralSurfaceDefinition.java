package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;

/** Frozen mesh or primitive binding to its owning bone slot. */
public record ProceduralSurfaceDefinition(ProceduralSurfaceKind kind, BlendResourceId id, int boneIndex) {
    public ProceduralSurfaceDefinition {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        ProceduralSupport.requireId(id, "surface id");
        if (boneIndex < -1) {
            throw new IllegalArgumentException("surface bone index must be -1 or non-negative");
        }
    }
}
