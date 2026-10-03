package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void observingIsAClockFreeSnapshotOfStoredTimeRatherThanAnElapsedTimeProjection() {
        var nanos = new AtomicLong();
        var reads = new AtomicInteger();
        var playback = new ItemAnimationPlayback(IDLE, () -> { reads.incrementAndGet(); return nanos.get(); });
        var initial = playback.observe(-1);
        assertEquals(1, reads.get());
        assertEquals(IDLE, initial.animation());
        assertEquals(ItemAnimationPlayback.Mode.LOOP, initial.mode());
        assertEquals(1, initial.speed());
        assertTrue(initial.playing());
        assertEquals(0, initial.storedSeconds());
        assertTrue(initial.lastSample().isEmpty());
        assertFalse(initial.sampleCurrentGeneration());
        nanos.set(4_000_000_000L);
        assertEquals(initial, playback.observe(99));
        assertEquals(initial, playback.observe(99));
        assertEquals(1, reads.get());
        assertEquals(4, playback.sample(10), "observation must not consume elapsed time");
        assertEquals(4, playback.observe(99).storedSeconds());
        assertTrue(playback.observe(99).lastSample().isEmpty(), "sampling alone is not successful extraction");
    }

    @Test void pausedSeekAndChangedControlsDoNotRewriteTheHistoricalSuccessfulSample() {
        var model = BlendModelKey.parse("test:item");
        var attack = BlendAnimationKey.parse("test:attack");
        var playback = new ItemAnimationPlayback(IDLE, () -> 0L);
        playback.pause().seek(0.25);
        playback.sampled(model, 7, playback.sample(1), 1);
        var frozen = playback.observe(7);
        var sample = frozen.lastSample().orElseThrow();
        playback.seek(12);
        var sought = playback.observe(7);
        assertFalse(sought.playing());
        assertEquals(12, sought.storedSeconds(), "raw seek stays unclamped until extraction");
        assertEquals(0.25, sought.lastSample().orElseThrow().seconds());
        assertSame(sample, sought.lastSample().orElseThrow());
        assertTrue(sought.sampleCurrentGeneration(), "fresh generation does not imply fresh control state");
        playback.speed(2).play(attack, ItemAnimationPlayback.Mode.HOLD);
        var changed = playback.observe(7);
        assertEquals(attack, changed.animation());
        assertEquals(ItemAnimationPlayback.Mode.HOLD, changed.mode());
        assertEquals(2, changed.speed());
        assertTrue(changed.playing());
        assertEquals(0, changed.storedSeconds());
        assertEquals(IDLE, changed.lastSample().orElseThrow().animation());
        playback.stop();
        assertFalse(playback.observe(7).playing());
        assertSame(sample, playback.observe(7).lastSample().orElseThrow());
        assertEquals(IDLE, frozen.animation());
        assertEquals(1, frozen.speed());
        assertEquals(0.25, frozen.storedSeconds());
        assertFalse(frozen.playing());
        assertTrue(frozen.sampleCurrentGeneration());
    }

    @Test void generationChangesMarkHistoricalSamplesStaleUntilSuccessfulExtractionReplacesThem() {
        var model = BlendModelKey.parse("test:item");
        var playback = new ItemAnimationPlayback(IDLE, () -> 0L);
        playback.sampled(model, 7, 0.5, 1);
        var before = playback.observe(7);
        var stale = playback.observe(8);
        assertFalse(stale.sampleCurrentGeneration());
        assertEquals(before.lastSample(), stale.lastSample());
        assertEquals(7, stale.lastSample().orElseThrow().generation());
        assertFalse(playback.observe(-1).sampleCurrentGeneration());
        playback.sampled(model, 8, 0.75, 2);
        var after = playback.observe(8);
        assertTrue(after.sampleCurrentGeneration());
        assertEquals(8, after.lastSample().orElseThrow().generation());
        assertEquals(0.75, after.lastSample().orElseThrow().seconds());
        assertEquals(2, after.lastSample().orElseThrow().durationSeconds());
        assertEquals(0.5, before.lastSample().orElseThrow().seconds());
        assertTrue(before.sampleCurrentGeneration(), "earlier observations are immutable point-in-time values");
        playback.sampled(model, 8, 0.75, 2);
        assertEquals(after, playback.observe(8), "repeated successful samples have stable value semantics");
    }

}
