package com.liy.blendlib.core.animation.runtime;

/** Outcome of applying one monotonically sequenced semantic animation correction. */
public enum AnimationCorrectionResult {
    STALE_DROPPED,
    APPLIED_BLEND,
    APPLIED_SNAP;

    public boolean applied() {
        return this != STALE_DROPPED;
    }
}
