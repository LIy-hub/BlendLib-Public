package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepared layer selector with a frozen part/bone target or attachment reference.
 *
 * <p>{@code preparedMaterial} is an explicit layer override only. Inherited material semantics
 * are deliberately resolved later from the final variant draw, after mesh/material/skin effects
 * have composed; this object therefore cannot accidentally retain a catalog primitive material.</p>
 */
public record X6ResolvedRenderLayer(
        BlendResourceId layerId,
        X6LayerType type,
        X6LayerSemantics semantics,
        Optional<BlendResourceId> resolvedPartId,
        Optional<BlendResourceId> canonicalBoneId,
        Optional<RenderMaterial> preparedMaterial,
        Optional<ModelRenderSnapshot> attachmentSnapshot,
        X6LayerPresentation presentation,
        int presentationArgb) {
    public X6ResolvedRenderLayer {
        layerId = X6Ids.requireId(layerId, "layerId");
        type = Objects.requireNonNull(type, "type");
        semantics = Objects.requireNonNull(semantics, "semantics");
        resolvedPartId = Objects.requireNonNull(resolvedPartId, "resolvedPartId");
        canonicalBoneId = Objects.requireNonNull(canonicalBoneId, "canonicalBoneId");
        canonicalBoneId.ifPresent(value -> X6Ids.requireId(value, "canonicalBoneId"));
        preparedMaterial = Objects.requireNonNull(preparedMaterial, "preparedMaterial");
        attachmentSnapshot = Objects.requireNonNull(attachmentSnapshot, "attachmentSnapshot");
        presentation = Objects.requireNonNull(presentation, "presentation");
    }
}
