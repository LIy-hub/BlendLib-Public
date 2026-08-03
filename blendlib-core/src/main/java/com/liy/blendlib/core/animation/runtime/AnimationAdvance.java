package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.List;
import java.util.Objects;

/** Immutable result of one controller advance, including presentation-only events crossed during it. */
public record AnimationAdvance(BlendAnimationKey state, double timeSeconds, List<AnimationVisualEvent> visualEvents) {
    public AnimationAdvance {
        state = Objects.requireNonNull(state, "state");
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Animation time must be finite and non-negative");
        }
        visualEvents = List.copyOf(Objects.requireNonNull(visualEvents, "visualEvents"));
    }
}
