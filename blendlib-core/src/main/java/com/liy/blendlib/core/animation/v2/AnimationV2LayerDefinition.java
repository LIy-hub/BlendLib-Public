package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Frozen layer metadata. Clip selection belongs to a controller state, not this hot-path value. */
public record AnimationV2LayerDefinition(
        BlendResourceId id,
        int priority,
        AnimationV2LayerMode mode,
        float weight,
        BoneMask mask,
        boolean exclusive) {
    public AnimationV2LayerDefinition {
        id = Objects.requireNonNull(id, "id");
        AnimationV2Limits.requireCanonicalIdLength(id.value(), "layer id");
        AnimationV2Limits.requirePriority(priority, "layer priority");
        mode = Objects.requireNonNull(mode, "mode");
        if (!Float.isFinite(weight) || weight < 0.0F || weight > 1.0F) {
            throw new IllegalArgumentException("layer weight must be finite and in [0, 1]");
        }
        mask = Objects.requireNonNull(mask, "mask");
    }
}
