package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;

/** Immutable semantic texture/material replacement; resource/provider lookup belongs to a future X6 integration. */
public record SemanticSurfaceOverride(BlendResourceId textureId, BlendResourceId materialId) {
    public SemanticSurfaceOverride {
        if (textureId == null && materialId == null) {
            throw new IllegalArgumentException("a semantic surface override must replace texture, material, or both");
        }
        if (textureId != null) {
            ProceduralSupport.requireId(textureId, "texture id");
        }
        if (materialId != null) {
            ProceduralSupport.requireId(materialId, "material id");
        }
    }
}
