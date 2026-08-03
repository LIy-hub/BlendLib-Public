package com.liy.blendlib.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable semantic request to play one animation for a model instance.
 *
 * <p>The bounded speed and transition values are semantic inputs. A runtime may map them to a
 * local clock only after it has selected a generation; this type never exposes a pose or render state.</p>
 *
 * @param animation semantic animation key
 * @param playbackMode requested playback behavior
 * @param speed bounded semantic speed multiplier
 * @param transition bounded requested transition duration
 */
public record AnimationRequest(
        BlendAnimationKey animation,
        PlaybackMode playbackMode,
        double speed,
        Duration transition) {

    /** Smallest supported positive semantic speed multiplier. */
    public static final double MIN_SPEED = 1.0D / 64.0D;

    /** Largest supported semantic speed multiplier. */
    public static final double MAX_SPEED = 64.0D;

    /** Longest supported semantic transition duration. */
    public static final Duration MAX_TRANSITION = Duration.ofSeconds(60L);

    /** Validates immutable animation request data. */
    public AnimationRequest {
        animation = Objects.requireNonNull(animation, "animation");
        playbackMode = Objects.requireNonNull(playbackMode, "playbackMode");
        transition = Objects.requireNonNull(transition, "transition");
        if (!Double.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new IllegalArgumentException("speed must be finite and in [" + MIN_SPEED + ", " + MAX_SPEED + "]");
        }
        if (transition.isNegative() || transition.compareTo(MAX_TRANSITION) > 0) {
            throw new IllegalArgumentException("transition must be in [PT0S, " + MAX_TRANSITION + "]");
        }
    }

    /**
     * Creates a looping request with unit speed and no transition.
     *
     * @param animation non-null animation identity
     * @return immutable looping semantic request
     */
    public static AnimationRequest loop(BlendAnimationKey animation) {
        return new AnimationRequest(animation, PlaybackMode.LOOP, 1.0D, Duration.ZERO);
    }

    /**
     * Creates a one-shot request with unit speed and no transition.
     *
     * @param animation non-null animation identity
     * @return immutable one-shot semantic request
     */
    public static AnimationRequest once(BlendAnimationKey animation) {
        return new AnimationRequest(animation, PlaybackMode.ONCE, 1.0D, Duration.ZERO);
    }

    /**
     * Creates a hold-at-end request with unit speed and no transition.
     *
     * @param animation non-null animation identity
     * @return immutable hold-at-end semantic request
     */
    public static AnimationRequest hold(BlendAnimationKey animation) {
        return new AnimationRequest(animation, PlaybackMode.HOLD, 1.0D, Duration.ZERO);
    }

    /**
     * Returns an immutable copy with a newly validated speed multiplier.
     *
     * @param requestedSpeed bounded replacement speed
     * @return immutable request copy
     */
    public AnimationRequest withSpeed(double requestedSpeed) {
        return new AnimationRequest(animation, playbackMode, requestedSpeed, transition);
    }

    /**
     * Returns an immutable copy with a newly validated transition duration.
     *
     * @param requestedTransition bounded replacement transition
     * @return immutable request copy
     */
    public AnimationRequest withTransition(Duration requestedTransition) {
        return new AnimationRequest(animation, playbackMode, speed, requestedTransition);
    }
}
