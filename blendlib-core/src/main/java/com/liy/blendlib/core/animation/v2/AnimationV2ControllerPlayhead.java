package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable observer-only controller state, separate from the mutable owner playhead. */
public record AnimationV2ControllerPlayhead(
        BlendResourceId controllerId,
        BlendAnimationKey state,
        double timeSeconds,
        BlendAnimationKey previousState,
        double transitionProgress,
        long acceptedSequence) {
    public AnimationV2ControllerPlayhead {
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        state = Objects.requireNonNull(state, "state");
        AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "playhead controller id");
        AnimationV2Limits.requireCanonicalIdLength(state.value(), "playhead state key");
        if (previousState != null) {
            AnimationV2Limits.requireCanonicalIdLength(previousState.value(), "previous playhead state key");
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0D) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        if (!Double.isFinite(transitionProgress) || transitionProgress < 0.0D || transitionProgress > 1.0D) {
            throw new IllegalArgumentException("transitionProgress must be finite and in [0, 1]");
        }
        if (acceptedSequence < -1L) {
            throw new IllegalArgumentException("acceptedSequence must be -1 or non-negative");
        }
    }
}
