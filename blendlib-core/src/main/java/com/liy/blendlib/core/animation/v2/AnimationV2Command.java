package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Internal semantic command already reconciled by an adapter; it contains neither payload nor render data. */
public record AnimationV2Command(
        BlendResourceId controllerId,
        BlendAnimationKey animationKey,
        long sequence,
        double requestedPlayheadSeconds,
        double playbackSpeed) {
    public AnimationV2Command {
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        animationKey = Objects.requireNonNull(animationKey, "animationKey");
        AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "command controller id");
        AnimationV2Limits.requireCanonicalIdLength(animationKey.value(), "command animation key");
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        if (!Double.isFinite(requestedPlayheadSeconds) || requestedPlayheadSeconds < 0.0D) {
            throw new IllegalArgumentException("requestedPlayheadSeconds must be finite and non-negative");
        }
        AnimationV2Limits.requireSpeed(playbackSpeed, "command playbackSpeed");
    }
}
