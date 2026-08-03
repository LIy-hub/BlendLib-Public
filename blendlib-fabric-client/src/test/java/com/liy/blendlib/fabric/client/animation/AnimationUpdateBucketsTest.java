package com.liy.blendlib.fabric.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationUpdateBucketsTest {
    @Test
    void selectionIsFiniteSafeAndPrioritizesVisibleNearInstances() {
        assertEquals(AnimationUpdateBucket.VISIBLE_NEAR, AnimationUpdateBuckets.select(true, 0.0D));
        assertEquals(AnimationUpdateBucket.VISIBLE_NEAR,
                AnimationUpdateBuckets.select(true, AnimationUpdateBuckets.NEAR_DISTANCE_SQUARED));
        assertEquals(AnimationUpdateBucket.VISIBLE_MID,
                AnimationUpdateBuckets.select(true, AnimationUpdateBuckets.NEAR_DISTANCE_SQUARED + 1.0D));
        assertEquals(AnimationUpdateBucket.VISIBLE_MID,
                AnimationUpdateBuckets.select(true, AnimationUpdateBuckets.MID_DISTANCE_SQUARED));
        assertEquals(AnimationUpdateBucket.VISIBLE_FAR,
                AnimationUpdateBuckets.select(true, AnimationUpdateBuckets.MID_DISTANCE_SQUARED + 1.0D));
        assertEquals(AnimationUpdateBucket.HIDDEN, AnimationUpdateBuckets.select(false, 0.0D));
        assertEquals(AnimationUpdateBucket.HIDDEN, AnimationUpdateBuckets.select(true, Double.NaN));
        assertEquals(AnimationUpdateBucket.HIDDEN, AnimationUpdateBuckets.select(true, Double.POSITIVE_INFINITY));
        assertEquals(AnimationUpdateBucket.HIDDEN, AnimationUpdateBuckets.select(true, -1.0D));
    }

    @Test
    void cadenceIsDeterministicAndFarOrHiddenNeverUseNearRate() {
        assertEquals(1, AnimationUpdateBucket.VISIBLE_NEAR.cadenceTicks());
        assertTrue(AnimationUpdateBucket.VISIBLE_MID.cadenceTicks() > AnimationUpdateBucket.VISIBLE_NEAR.cadenceTicks());
        assertTrue(AnimationUpdateBucket.VISIBLE_FAR.cadenceTicks() > AnimationUpdateBucket.VISIBLE_MID.cadenceTicks());
        assertTrue(AnimationUpdateBucket.HIDDEN.cadenceTicks() > AnimationUpdateBucket.VISIBLE_FAR.cadenceTicks());

        assertTrue(AnimationUpdateBucket.VISIBLE_NEAR.isDue(7L));
        assertTrue(AnimationUpdateBucket.VISIBLE_FAR.isDue(0L));
        assertFalse(AnimationUpdateBucket.VISIBLE_FAR.isDue(1L));
        assertTrue(AnimationUpdateBucket.VISIBLE_FAR.isDue(4L));
        assertTrue(AnimationUpdateBucket.HIDDEN.isDue(0L));
        assertFalse(AnimationUpdateBucket.HIDDEN.isDue(1L));
        assertTrue(AnimationUpdateBucket.HIDDEN.isDue(8L));
        assertThrows(IllegalArgumentException.class, () -> AnimationUpdateBucket.HIDDEN.isDue(-1L));
    }
}
