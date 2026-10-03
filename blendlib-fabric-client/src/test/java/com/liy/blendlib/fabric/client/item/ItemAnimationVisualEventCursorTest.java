package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ItemAnimationVisualEventCursorTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("events:item");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("events:idle");
    private static final AnimationVisualEvent ZERO = event(0, "zero");
    private static final AnimationVisualEvent MID = event(0.5, "mid");
    private static final AnimationVisualEvent END = event(1, "end");
    private static AnimationVisualEvent event(double time, String name) {
        return new AnimationVisualEvent(time, BlendResourceId.parse("events:" + name));
    }
    private static ItemAnimationPlayback.EventInterval interval(double start, double end) {
        return new ItemAnimationPlayback.EventInterval(start, end, 0, ItemAnimationPlayback.Mode.LOOP);
    }
    private static ItemVisualEventCursor cursor() {
        var cursor = new ItemVisualEventCursor();
        assertTrue(cursor.consume(MODEL, 1, interval(0, 0), 1, List.of(ZERO, MID, END)).isEmpty());
        return cursor;
    }

    @Test void rawIntervalSurvivesLoopNormalizationAndOnceReset() {
        for (var mode : ItemAnimationPlayback.Mode.values()) {
            var clock = new AtomicLong();
            var playback = new ItemAnimationPlayback(IDLE, clock::get).play(IDLE, mode);
            assertEquals(0, playback.sample(1));
            var cursor = playback.events();
            assertTrue(cursor.consume(MODEL, 1, playback.consumeEventInterval(), 1, List.of(ZERO, MID, END)).isEmpty());
            clock.set(1_000_000_000);
            assertEquals(mode == ItemAnimationPlayback.Mode.HOLD ? 1 : 0, playback.sample(1));
            assertEquals(mode == ItemAnimationPlayback.Mode.LOOP ? List.of(MID, END, ZERO) : List.of(MID, END),
                    cursor.consume(MODEL, 1, playback.consumeEventInterval(), 1, List.of(ZERO, MID, END)));
            playback.sample(1);
            assertTrue(cursor.consume(MODEL, 1, playback.consumeEventInterval(), 1, List.of(ZERO, MID, END)).isEmpty());
        }
    }

    @Test void seekRebaseAndCheckpointDoNotInventAdvancement() {
        var clock = new AtomicLong();
        var playback = new ItemAnimationPlayback(IDLE, clock::get);
        playback.sample(1);
        playback.consumeEventInterval();
        clock.set(500_000_000);
        var checkpoint = playback.checkpoint();
        playback.sample(1);
        playback.restore(checkpoint);
        assertEquals(0, playback.observe(1).storedSeconds());
        assertEquals(0.5, playback.sample(1));
        assertEquals(interval(0, 0.5), playback.consumeEventInterval());
        playback.seek(0.25);
        playback.sample(1);
        assertEquals(new ItemAnimationPlayback.EventInterval(0.25, 0.25, 1, ItemAnimationPlayback.Mode.LOOP),
                playback.consumeEventInterval());
    }

    @Test void catchUpAndAtomicBudgetsAreBoundedAndRecoveryRebases() {
        var cursor = cursor();
        assertEquals(List.of(MID, END, ZERO), cursor.consume(MODEL, 1, interval(0, 10), 1, List.of(ZERO, MID, END)));
        assertTrue(cursor.consume(MODEL, 1, interval(0, 1), 1e-9, List.of(ZERO)).isEmpty());
        assertTrue(cursor.consume(MODEL, 1, interval(0, Double.MAX_VALUE), 1, List.of(MID)).isEmpty());
        assertTrue(cursor.consume(MODEL, 1, interval(0, 1), 0.1,
                Collections.nCopies(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE, ZERO)).isEmpty());
        cursor.invalidate();
        assertTrue(cursor.consume(MODEL, 1, interval(0, 0.5), 1, List.of(MID)).isEmpty());
        assertTrue(cursor.consume(MODEL, 2, interval(0, 0.5), 1, List.of(MID)).isEmpty());
        assertEquals(List.of(MID), cursor.consume(MODEL, 2, interval(0, 0.5), 1, List.of(MID)));
    }

    @Test void normalizedPoseRoundingCannotReplayAnAlreadyConsumedLoopMarker() {
        double duration = (double) 0.1F;
        var marker = event(0.025, "no_rounding_replay");
        var clock = new AtomicLong();
        var playback = new ItemAnimationPlayback(IDLE, clock::get).speed(duration + 0.025);
        playback.sample(duration);
        playback.events().consume(MODEL, 1, playback.consumeEventInterval(), duration, List.of(marker));
        clock.set(1_000_000_000);
        playback.sample(duration);
        assertEquals(List.of(marker, marker), playback.events().consume(MODEL, 1,
                playback.consumeEventInterval(), duration, List.of(marker)));
        clock.incrementAndGet();
        playback.sample(duration);
        assertTrue(playback.events().consume(MODEL, 1,
                playback.consumeEventInterval(), duration, List.of(marker)).isEmpty());
    }

    @Test void inclusiveEndpointWorksThroughRealPlaybackWithFloatAuthoredDuration() {
        double duration = (double) 0.1F;
        var marker = event(0.05, "rounded_playback");
        var clock = new AtomicLong();
        var playback = new ItemAnimationPlayback(IDLE, clock::get).speed(duration + 0.05);
        playback.sample(duration);
        playback.events().consume(MODEL, 1, playback.consumeEventInterval(), duration, List.of(marker));
        clock.set(1_000_000_000);
        playback.sample(duration);
        assertEquals(List.of(marker, marker), playback.events().consume(MODEL, 1,
                playback.consumeEventInterval(), duration, List.of(marker)));
    }

    @Test void exactRightEndpointSurvivesRoundedDivisionInNonBinaryClipLengths() {
        double duration = 0.13854549825191498;
        double time = 0.11266639253954491;
        var marker = event(time, "rounded");
        double end = 12 * duration + time;
        var events = cursor().consume(MODEL, 1, interval(0.029681854201569345, end), duration, List.of(marker));
        assertEquals(8, events.size()); // cycles 5 through 12 in the final clip-time second
        assertTrue(events.stream().allMatch(marker::equals));
    }

    @Test void zeroDurationAndOutOfClipMarkersAreSilent() {
        var cursor = cursor();
        assertTrue(cursor.consume(MODEL, 1, interval(0, 1), 0, List.of(ZERO)).isEmpty());
        assertTrue(cursor.consume(MODEL, 1, interval(0, 1), 1, List.of(event(1.5, "outside"))).isEmpty());
    }
}
