package com.liy.blendlib.fabric.common.animation.v2;

/** Lifetime requested for one semantic animation intent. */
public enum AnimationIntentMode {
    /** The intent is applied once and does not survive a later tracking replay. */
    ONE_SHOT,

    /** The intent replaces the remembered state for its controller and is replayable while its scope is active. */
    PERSISTENT
}
