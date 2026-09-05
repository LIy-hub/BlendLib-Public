package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable mesh/primitive material intent prepared outside the v1 descriptor and submit paths. */
public record X6MaterialIntent(
        BlendResourceId textureId,
        X6MaterialMode mode,
        boolean emissive,
        boolean doubleSided,
        Double cutoutThreshold) {
    public X6MaterialIntent {
        textureId = X6Ids.requireId(textureId, "textureId");
        mode = Objects.requireNonNull(mode, "mode");
        if (mode == X6MaterialMode.CUTOUT) {
            if (cutoutThreshold != null && (!Double.isFinite(cutoutThreshold) || cutoutThreshold < 0.0D || cutoutThreshold > 1.0D)) {
                throw new IllegalArgumentException("cutoutThreshold must be finite and in [0, 1]");
            }
        } else if (cutoutThreshold != null) {
            throw new IllegalArgumentException("Only CUTOUT material intent may carry a cutout threshold");
        }
    }
}
