package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import java.util.Objects;

/**
 * An extraction-time cue on the client game-tick clock (20 ticks per second).
 * The sequence increases independently per controller. A sequence identifies one immutable cue:
 * change it to retrigger or change timing, animation, or speed. Zero is a valid sequence;
 * return an empty cue list when no action is requested.
 *
 * <p>The adapter freezes the first elapsed-time capture for a sequence, compensates for late
 * observation using playbackSpeed times descriptor state speed, and captures again after reload
 * or entity retracking. Catch-up is clip-local; it does not replay historical next-state
 * chains or blends.
 * Future start ticks clamp to zero; they do not schedule a future start.</p>
 */
public record BlendEntityLayerCue(BlendResourceId controllerId, BlendAnimationKey animationKey,
        long sequence, long startTick, double playbackSpeed) {
    public BlendEntityLayerCue {
        Objects.requireNonNull(controllerId, "controllerId");
        Objects.requireNonNull(animationKey, "animationKey");
        if (controllerId.value().length() > AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS
                || animationKey.value().length() > AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS) {
            throw new IllegalArgumentException("Cue identifier exceeds the command identifier limit");
        }
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        if (!AnimationV2Limits.isValidPlaybackSpeed(playbackSpeed)) {
            throw new IllegalArgumentException("playbackSpeed must be finite and within the v2 speed bounds");
        }
    }
}
