package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure extraction input for one typed skinned animation instance.
 *
 * <p>The caller resolves game objects, distance, lighting, culling, transform data, and the
 * active client game clock before constructing this value. It deliberately carries neither an
 * entity nor a world object. The optional semantic state is consumed entirely during extraction;
 * the fallback animation remains available when no semantic state is present.</p>
 */
public record SkinnedAnimationRuntimeInput(
        BlendModelKey modelKey,
        BlendInstanceKey instanceKey,
        long clientGameTick,
        float partialTick,
        BlendAnimationKey fallbackAnimation,
        Optional<SyncedAnimationState> syncedAnimation,
        AnimationUpdateBucket updateBucket,
        SkinnedExtractionRequest extractionRequest) {
    public SkinnedAnimationRuntimeInput {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        if (clientGameTick < 0L) {
            throw new IllegalArgumentException("clientGameTick must be non-negative");
        }
        if (!Float.isFinite(partialTick) || partialTick < 0.0F) {
            throw new IllegalArgumentException("partialTick must be finite and non-negative");
        }
        fallbackAnimation = Objects.requireNonNull(fallbackAnimation, "fallbackAnimation");
        syncedAnimation = Objects.requireNonNull(syncedAnimation, "syncedAnimation");
        updateBucket = Objects.requireNonNull(updateBucket, "updateBucket");
        extractionRequest = Objects.requireNonNull(extractionRequest, "extractionRequest");
    }

    /** Returns the extraction-side client game time in ticks, including the render partial tick. */
    public double clientGameTimeInTicks() {
        return clientGameTick + (double) partialTick;
    }
}
