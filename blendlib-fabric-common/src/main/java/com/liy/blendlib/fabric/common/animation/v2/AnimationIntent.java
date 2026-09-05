package com.liy.blendlib.fabric.common.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import java.util.Comparator;
import java.util.Objects;

/** Immutable semantic instruction accepted only inside its exact ownership scope. */
public record AnimationIntent(
        AnimationIntentScope scope,
        BlendResourceId controllerId,
        BlendAnimationKey animationKey,
        long startGameTick,
        long sequence,
        float speed,
        AnimationIntentMode mode) {
    /**
     * Stable total ordering for reconciliation. Controller and sequence deliberately lead so every equal-sequence
     * conflict is examined as one complete group before any command can be retained or applied.
     */
    public static final Comparator<AnimationIntent> CANONICAL_ORDER = Comparator
            .comparing((AnimationIntent intent) -> intent.controllerId().value())
            .thenComparingLong(AnimationIntent::sequence)
            .thenComparing(AnimationIntent::scope, AnimationIntentScope.CANONICAL_ORDER)
            .thenComparing(intent -> intent.animationKey().value())
            .thenComparingLong(AnimationIntent::startGameTick)
            .thenComparingDouble(intent -> intent.speed())
            .thenComparing(intent -> intent.mode().name());

    public AnimationIntent {
        scope = Objects.requireNonNull(scope, "scope");
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        animationKey = Objects.requireNonNull(animationKey, "animationKey");
        if (controllerId.value().length() > AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS
                || animationKey.value().length() > AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS) {
            throw new IllegalArgumentException("semantic id exceeds the v2 bound");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        if (!Float.isFinite(speed)
                || speed < AnimationV2Limits.MIN_PLAYBACK_SPEED
                || speed > AnimationV2Limits.MAX_PLAYBACK_SPEED) {
            throw new IllegalArgumentException("speed must be finite and within the v2 bound");
        }
        mode = Objects.requireNonNull(mode, "mode");
    }

    /** Returns whether this accepted intent must be retained for the current scope's replay. */
    public boolean persistent() {
        return mode == AnimationIntentMode.PERSISTENT;
    }

    /** Carries the semantic instruction through a generation-only resource rebind. */
    public AnimationIntent rebindScope(AnimationIntentScope replacementScope) {
        return new AnimationIntent(replacementScope, controllerId, animationKey, startGameTick, sequence, speed, mode);
    }
}
