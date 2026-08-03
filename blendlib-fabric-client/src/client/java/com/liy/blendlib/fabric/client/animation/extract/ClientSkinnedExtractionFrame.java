package com.liy.blendlib.fabric.client.animation.extract;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable extraction result containing a render snapshot and canonical-palette socket transforms. */
public record ClientSkinnedExtractionFrame(
        ModelRenderSnapshot renderSnapshot,
        Map<BlendResourceId, Transform> socketTransforms) {
    public ClientSkinnedExtractionFrame {
        renderSnapshot = Objects.requireNonNull(renderSnapshot, "renderSnapshot");
        socketTransforms = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(socketTransforms, "socketTransforms")));
    }

    /** Resolves one descriptor-declared socket in model space from this exact sampled palette. */
    public Optional<Transform> socketTransform(BlendResourceId socketKey) {
        return Optional.ofNullable(socketTransforms.get(Objects.requireNonNull(socketKey, "socketKey")));
    }
}
