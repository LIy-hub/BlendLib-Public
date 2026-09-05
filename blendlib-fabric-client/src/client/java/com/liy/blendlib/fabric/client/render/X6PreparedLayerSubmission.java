package com.liy.blendlib.fabric.client.render;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Fully executable layer submission frozen after final variant composition.
 *
 * <p>For a non-attachment this contains the target's final binding plus the final layer material,
 * semantics, standard route, and optional immutable per-bone subset. Submit performs no material
 * inheritance, part lookup, or bone resolution.</p>
 */
record X6PreparedLayerSubmission(
        X6ResolvedRenderLayer layer,
        X6PreparedDraw target,
        RenderMaterial material,
        X6LayerSemantics semantics,
        RenderType renderType,
        Optional<X6BoneInfluenceSubset> boneSubset) {
    X6PreparedLayerSubmission {
        layer = Objects.requireNonNull(layer, "layer");
        boneSubset = Objects.requireNonNull(boneSubset, "boneSubset");
        boolean attachment = layer.attachmentSnapshot().isPresent();
        if (attachment != (target == null && material == null && semantics == null && renderType == null)) {
            throw new IllegalArgumentException("An X6 attachment is the only layer without a frozen draw/material/render type");
        }
        if (!attachment && (target == null || material == null || semantics == null || renderType == null
                || layer.resolvedPartId().isEmpty())) {
            throw new IllegalArgumentException("A non-attachment X6 layer must retain frozen target material semantics and render type");
        }
        if (attachment && boneSubset.isPresent()) {
            throw new IllegalArgumentException("An X6 attachment cannot retain a per-bone geometry subset");
        }
        if (layer.type() == X6LayerType.PER_BONE_PART_TEXTURE != boneSubset.isPresent()) {
            throw new IllegalArgumentException("Only a per-bone X6 layer may retain a prepared bone subset");
        }
    }
}
