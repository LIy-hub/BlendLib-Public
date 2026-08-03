package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;

import java.util.Objects;

/**
 * Identifies one sampled pose within a concrete instance model binding and generation.
 */
public record PoseCacheKey(
        BlendInstanceKey instanceKey,
        BlendModelKey modelKey,
        long generation,
        BlendAnimationKey animationKey,
        long sampleRevision
) {
    public PoseCacheKey {
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        animationKey = Objects.requireNonNull(animationKey, "animationKey");
        if (sampleRevision < 0) {
            throw new IllegalArgumentException("sampleRevision must be non-negative");
        }
    }
}
