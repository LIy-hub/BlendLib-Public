package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/** Immutable client-extraction metadata supplied to one procedural pose modifier invocation. */
public record ClientAnimationPoseContext(
        BlendInstanceKey instanceKey,
        BlendModelKey modelKey,
        long generation,
        BlendAnimationKey animationKey,
        double animationTimeSeconds,
        double clientGameTimeInTicks,
        ClientAnimationRigView rig) {
    public ClientAnimationPoseContext {
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        animationKey = Objects.requireNonNull(animationKey, "animationKey");
        if (!Double.isFinite(animationTimeSeconds) || animationTimeSeconds < 0.0d) {
            throw new IllegalArgumentException("animationTimeSeconds must be finite and non-negative");
        }
        if (!Double.isFinite(clientGameTimeInTicks) || clientGameTimeInTicks < 0.0d) {
            throw new IllegalArgumentException("clientGameTimeInTicks must be finite and non-negative");
        }
        rig = Objects.requireNonNull(rig, "rig");
    }
}
