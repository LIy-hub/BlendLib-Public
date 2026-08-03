package com.liy.blendlib.fabric.client.animation;

/**
 * Deterministic cadence buckets for extraction-side animation updates.
 */
public enum AnimationUpdateBucket {
    VISIBLE_NEAR(1),
    VISIBLE_MID(2),
    VISIBLE_FAR(4),
    HIDDEN(8);

    private final int cadenceTicks;

    AnimationUpdateBucket(int cadenceTicks) {
        this.cadenceTicks = cadenceTicks;
    }

    public int cadenceTicks() {
        return cadenceTicks;
    }

    public boolean isDue(long extractionTick) {
        if (extractionTick < 0) {
            throw new IllegalArgumentException("extractionTick must be non-negative");
        }
        return extractionTick % cadenceTicks == 0;
    }
}
