package com.liy.blendlib.fabric.client.animation.extract;

import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;

/** Immutable extraction inputs carried from an entity or block-entity adapter before rendering. */
public record SkinnedExtractionRequest(
        Transform rootTransform,
        int packedLight,
        int packedOverlay,
        int tintArgb,
        RenderVisibility visibility,
        CullingMetadata culling) {
    public SkinnedExtractionRequest {
        rootTransform = Objects.requireNonNull(rootTransform, "rootTransform");
        visibility = Objects.requireNonNull(visibility, "visibility");
        culling = Objects.requireNonNull(culling, "culling");
    }
}
