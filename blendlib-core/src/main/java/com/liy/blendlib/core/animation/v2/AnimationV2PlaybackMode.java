package com.liy.blendlib.core.animation.v2;

/** Explicit v2 state-end behavior. */
public enum AnimationV2PlaybackMode {
    /** Repeats the state clip; a {@code next} key is forbidden. */
    LOOP,
    /** Reaches the end once, then follows {@code next} when one is configured. */
    ONCE,
    /** Reaches the end once and retains the terminal pose; a {@code next} key is forbidden. */
    HOLD
}
