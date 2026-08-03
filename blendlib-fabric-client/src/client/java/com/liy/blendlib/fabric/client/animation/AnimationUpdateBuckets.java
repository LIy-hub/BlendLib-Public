package com.liy.blendlib.fabric.client.animation;

/**
 * Maps extraction visibility and squared distance to a stable update cadence.
 */
public final class AnimationUpdateBuckets {
    static final double NEAR_DISTANCE_SQUARED = 32.0D * 32.0D;
    static final double MID_DISTANCE_SQUARED = 96.0D * 96.0D;

    private AnimationUpdateBuckets() {
    }

    public static AnimationUpdateBucket select(boolean visible, double distanceSquared) {
        if (!visible || !Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
            return AnimationUpdateBucket.HIDDEN;
        }
        if (distanceSquared <= NEAR_DISTANCE_SQUARED) {
            return AnimationUpdateBucket.VISIBLE_NEAR;
        }
        if (distanceSquared <= MID_DISTANCE_SQUARED) {
            return AnimationUpdateBucket.VISIBLE_MID;
        }
        return AnimationUpdateBucket.VISIBLE_FAR;
    }
}
