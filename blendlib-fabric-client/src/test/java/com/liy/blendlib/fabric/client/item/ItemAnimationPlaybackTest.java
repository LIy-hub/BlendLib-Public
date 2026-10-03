package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendAnimationKey;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ItemAnimationPlaybackTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("test:idle");

    @Test void controlsPreserveTimeAndPauseDoesNotAccumulateHiddenElapsedTime() {
        AtomicLong clock = new AtomicLong();
        var playback = new ItemAnimationPlayback(IDLE, clock::get);
        clock.set(1_000_000_000L);
        assertEquals(1, playback.sample(10));
        playback.pause();
        clock.set(5_000_000_000L);
        assertEquals(1, playback.sample(10));
        playback.resume().speed(2);
        clock.set(6_000_000_000L);
        assertEquals(3, playback.sample(10));
        playback.seek(7);
        assertEquals(7, playback.sample(10));
        playback.stop();
        assertEquals(0, playback.sample(10));
        assertFalse(playback.playing());
    }

    @Test void loopOnceAndHoldHaveDistinctExactEndpointBehavior() {
        AtomicLong clock = new AtomicLong();
        var playback = new ItemAnimationPlayback(IDLE, clock::get);
        playback.seek(5);
        assertEquals(1, playback.sample(2));
        playback.play(IDLE, ItemAnimationPlayback.Mode.ONCE).seek(2);
        assertEquals(0, playback.sample(2));
        assertFalse(playback.playing());
        playback.play(IDLE, ItemAnimationPlayback.Mode.HOLD).seek(2);
        assertEquals(2, playback.sample(2));
        assertFalse(playback.playing());
        clock.set(100_000_000_000L);
        assertEquals(2, playback.sample(2));
        playback.play(IDLE, ItemAnimationPlayback.Mode.HOLD);
        assertEquals(0, playback.sample(0));
    }

    @Test void invalidTimesAndSpeedsFailWithoutMutation() {
        var playback = new ItemAnimationPlayback(IDLE, () -> 0L);
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> playback.seek(invalid));
            assertThrows(IllegalArgumentException.class, () -> playback.speed(invalid));
        }
        assertEquals(1, playback.speed());
        assertEquals(0, playback.sample(10));
    }
}
