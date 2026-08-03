package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.Objects;

/** Immutable semantic correction input; network adapters remain outside this pure core type. */
public record AnimationCorrection(
        BlendAnimationKey animationKey, double timeSeconds, long sequence, double snapThresholdSeconds) {
    public AnimationCorrection {
        animationKey = Objects.requireNonNull(animationKey, "animationKey");
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Correction time must be finite and non-negative");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("Correction sequence must be non-negative");
        }
        if (!Double.isFinite(snapThresholdSeconds) || snapThresholdSeconds < 0.0) {
            throw new IllegalArgumentException("Correction snap threshold must be finite and non-negative");
        }
    }
}
