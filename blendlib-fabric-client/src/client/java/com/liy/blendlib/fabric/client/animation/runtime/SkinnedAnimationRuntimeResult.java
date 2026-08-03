package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.AnimationAdvance;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import java.util.Objects;

/** Immutable observation from one extraction-side skinned animation update. */
public record SkinnedAnimationRuntimeResult(
        BlendInstanceKey instanceKey,
        ClientSkinnedExtractionFrame frame,
        AnimationAdvance advance) {
    public SkinnedAnimationRuntimeResult {
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        frame = Objects.requireNonNull(frame, "frame");
        advance = Objects.requireNonNull(advance, "advance");
    }
}
