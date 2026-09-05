package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/** Immutable requested X6 layer. All resource references are resolved before this object is submitted. */
public record X6LayerEntry(
        BlendResourceId layerId,
        X6LayerType type,
        X6RenderPhase phase,
        int order,
        X6LayerTargetSelector target,
        Optional<RenderMaterial> preparedMaterial,
        Optional<ModelRenderSnapshot> attachmentSnapshot,
        int presentationArgb) {
    public X6LayerEntry {
        layerId = X6Ids.requireId(layerId, "layerId");
        type = Objects.requireNonNull(type, "type");
        phase = Objects.requireNonNull(phase, "phase");
        if (order < 0 || order > 1_024) {
            throw new IllegalArgumentException("order must be in [0, 1024]");
        }
        target = Objects.requireNonNull(target, "target");
        preparedMaterial = Objects.requireNonNull(preparedMaterial, "preparedMaterial");
        attachmentSnapshot = Objects.requireNonNull(attachmentSnapshot, "attachmentSnapshot");
    }

    /** Uses the audited fixed presentation tint for legacy code-owned layer declarations. */
    public X6LayerEntry(
            BlendResourceId layerId,
            X6LayerType type,
            X6RenderPhase phase,
            int order,
            X6LayerTargetSelector target,
            Optional<RenderMaterial> preparedMaterial,
            Optional<ModelRenderSnapshot> attachmentSnapshot) {
        this(layerId, type, phase, order, target, preparedMaterial, attachmentSnapshot, defaultPresentationArgb(type));
    }

    private static int defaultPresentationArgb(X6LayerType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case DAMAGE_FLASH -> 0xFFFF4040;
            case OUTLINE -> 0xFF101010;
            case SHADOW -> 0x66000000;
            default -> 0xFFFFFFFF;
        };
    }
}
