package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.List;
import java.util.Objects;

/** Immutable descriptor state-to-GLB-clip mapping. */
public record AnimationStateDefinition(
        String clip,
        boolean loop,
        double speed,
        double blendSeconds,
        BlendResourceId nextState,
        List<AnimationEventDefinition> events) {
    public AnimationStateDefinition {
        clip = requireClip(clip);
        if (!Double.isFinite(speed) || speed <= 0.0 || speed > BlendAssetLimits.MAX_ANIMATION_SPEED) {
            throw new IllegalArgumentException("Animation speed must be finite, positive, and at most "
                    + BlendAssetLimits.MAX_ANIMATION_SPEED);
        }
        if (!Double.isFinite(blendSeconds) || blendSeconds < 0.0) {
            throw new IllegalArgumentException("Animation blend seconds must be finite and non-negative");
        }
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.size() > BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE) {
            throw new IllegalArgumentException("Animation state visual-event limit exceeded");
        }
    }

    private static String requireClip(String clip) {
        Objects.requireNonNull(clip, "clip");
        if (clip.isBlank()) {
            throw new IllegalArgumentException("Animation clip must not be blank");
        }
        return clip;
    }
}
