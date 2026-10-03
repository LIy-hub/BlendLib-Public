package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import java.util.Objects;

/**
 * An immutable descriptor marker crossed by one real layered controller occurrence.
 * This is presentation-only metadata, never authority for damage, collision, or other gameplay.
 *
 * <p>The effective weight is the configured layer weight times its multiplier in the emitting publication,
 * before bone masks, priority, or override normalization. It does not measure final pose influence.</p>
 */
public record LayerAnimationVisualEvent(
        BlendResourceId controllerId,
        BlendResourceId layerId,
        BlendAnimationKey animation,
        AnimationVisualEvent event,
        long loopEpoch,
        long occurrence,
        float effectiveWeight) {
    public LayerAnimationVisualEvent {
        Objects.requireNonNull(controllerId, "controllerId");
        Objects.requireNonNull(layerId, "layerId");
        Objects.requireNonNull(animation, "animation");
        Objects.requireNonNull(event, "event");
        AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "event controller id");
        AnimationV2Limits.requireCanonicalIdLength(layerId.value(), "event layer id");
        AnimationV2Limits.requireCanonicalIdLength(animation.value(), "event animation");
        if (loopEpoch < 0L || occurrence < 0L) {
            throw new IllegalArgumentException("event loop epoch and occurrence must be non-negative");
        }
        if (!Float.isFinite(effectiveWeight) || effectiveWeight < 0.0F || effectiveWeight > 1.0F) {
            throw new IllegalArgumentException("event effective weight must be finite and in [0, 1]");
        }
    }
}
