package com.liy.blendlib.core.animation.v2;

import java.util.Objects;

/** One immutable full-pose keyframe in an internal v2 clip. */
public record AnimationV2Keyframe(double timeSeconds, AnimationV2Pose pose) {
    public AnimationV2Keyframe {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0D) {
            throw new IllegalArgumentException("keyframe time must be finite and non-negative");
        }
        pose = Objects.requireNonNull(pose, "pose");
    }
}
