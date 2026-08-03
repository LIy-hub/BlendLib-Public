package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable, adapter-ready material data prepared before the render submit path. */
public record RenderMaterial(
        BlendResourceId textureId,
        RenderLayer layer,
        boolean emissive,
        boolean doubleSided,
        int argbTint,
        boolean missingModelMaterial) {
    public RenderMaterial {
        textureId = Objects.requireNonNull(textureId, "textureId");
        layer = Objects.requireNonNull(layer, "layer");
    }

    /** Creates a fixed-color missing-model material that never references the failed asset. */
    public static RenderMaterial missing(int argbTint) {
        return new RenderMaterial(
                BlendResourceId.of("minecraft", "textures/block/white_concrete.png"),
                RenderLayer.SOLID,
                false,
                false,
                argbTint,
                true);
    }
}
