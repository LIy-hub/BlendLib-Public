package com.liy.blendlib.api;

/**
 * Pure semantic playback intent for an {@link AnimationRequest}.
 *
 * <p>Playback intent does not carry sampled poses, clocks, or renderer state. Those details
 * remain owned by a per-instance runtime and its immutable snapshots.</p>
 */
public enum PlaybackMode {
    /** Repeats the requested animation until a later semantic request supersedes it. */
    LOOP,

    /** Plays the requested animation once, then lets the runtime apply its declared next-state rule. */
    ONCE,

    /** Plays once and retains its final semantic state until superseded. */
    HOLD
}
