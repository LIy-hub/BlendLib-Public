package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/**
 * Immutable last extraction attempt, independent of current playback controls and historical samples.
 * A current-generation attempt can still precede a control change. Fallback describes the extraction
 * handoff, not a promise that rendering was submitted successfully. No resource or stack is retained.
 */
public record ItemAnimationExtractionStatus(BlendModelKey model, BlendAnimationKey requestedAnimation,
        long generation, Outcome outcome, Fallback fallback, boolean currentGeneration) {
    public enum Outcome { ANIMATED, ANIMATION_UNAVAILABLE, MODEL_UNAVAILABLE, EXTRACTION_UNAVAILABLE }
    public enum Fallback { NONE, STATIC_MODEL, MISSING_MODEL }

    public ItemAnimationExtractionStatus {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(requestedAnimation, "requestedAnimation");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(fallback, "fallback");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        if ((outcome == Outcome.ANIMATED) != (fallback == Fallback.NONE)) {
            throw new IllegalArgumentException("Only animated extraction has no fallback");
        }
    }
}
