package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigView;
import java.util.Objects;

/** Immutable entity-extraction context supplied to a configured procedural pose modifier. */
public record BlendEntityPoseContext(
        BlendEntitySnapshotRequest extractionRequest,
        ClientAnimationPoseContext animationContext) {
    public BlendEntityPoseContext {
        extractionRequest = Objects.requireNonNull(extractionRequest, "extractionRequest");
        animationContext = Objects.requireNonNull(animationContext, "animationContext");
        if (!extractionRequest.modelKey().equals(animationContext.modelKey())) {
            throw new IllegalArgumentException("Entity extraction and animation pose contexts must use the same model key");
        }
    }

    public BlendInstanceKey instanceKey() {
        return animationContext.instanceKey();
    }

    public BlendModelKey modelKey() {
        return animationContext.modelKey();
    }

    public long generation() {
        return animationContext.generation();
    }

    /** Returns the actual post-advance controller state sampled for this extraction. */
    public BlendAnimationKey animationKey() {
        return animationContext.animationKey();
    }

    /** Returns the actual post-advance local controller time sampled for this extraction. */
    public double animationTimeSeconds() {
        return animationContext.animationTimeSeconds();
    }

    /** Returns the immutable client extraction clock, including the render partial tick. */
    public double clientGameTimeInTicks() {
        return animationContext.clientGameTimeInTicks();
    }

    public ClientAnimationRigView rig() {
        return animationContext.rig();
    }
}
