package com.liy.blendlib.fabric.common.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable server-authored animation semantics transported to a client.
 *
 * <p>This intentionally contains no model, GLB, matrix, material, entity, or world object. The client derives
 * presentation time locally from {@link #startGameTick()} and its own partial tick.</p>
 */
public record SyncedAnimationState(
        BlendAnimationKey animationKey,
        long startGameTick,
        long sequence,
        float speed,
        long seed,
        boolean persistent) {
    /** The maximum encoded UTF-8 length accepted for one semantic animation key. */
    public static final int MAX_ANIMATION_KEY_UTF8_BYTES = 256;

    /** A defensive upper bound that prevents a malformed packet from causing pathological local time advancement. */
    public static final float MAX_SPEED = 64.0F;

    public SyncedAnimationState {
        animationKey = Objects.requireNonNull(animationKey, "animationKey");
        if (animationKey.value().getBytes(StandardCharsets.UTF_8).length > MAX_ANIMATION_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException("animationKey exceeds " + MAX_ANIMATION_KEY_UTF8_BYTES + " UTF-8 bytes");
        }
        if (startGameTick < 0L) {
            throw new IllegalArgumentException("startGameTick must be non-negative");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        if (!Float.isFinite(speed) || speed <= 0.0F || speed > MAX_SPEED) {
            throw new IllegalArgumentException("speed must be finite, positive, and at most " + MAX_SPEED);
        }
    }

    /** Returns the same semantic command with a later server-assigned sequence. */
    public SyncedAnimationState withSequence(long replacementSequence) {
        return new SyncedAnimationState(animationKey, startGameTick, replacementSequence, speed, seed, persistent);
    }

    /** Returns this command marked as a persistent replayable state. */
    public SyncedAnimationState asPersistent() {
        return persistent ? this : new SyncedAnimationState(animationKey, startGameTick, sequence, speed, seed, true);
    }
}
