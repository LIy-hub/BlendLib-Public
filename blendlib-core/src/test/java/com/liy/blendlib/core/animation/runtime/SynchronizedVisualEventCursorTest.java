package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SynchronizedVisualEventCursorTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("fixture:idle");
    private static final BlendAnimationKey NEXT = BlendAnimationKey.parse("fixture:next");

    @Test
    void skipsInitialHistoryAndDeduplicatesExtractionAndClockRewind() {
        AnimationVisualEvent marker = event(0.5, "step");
        SynchronizedVisualEventCursor cursor = cursor(state(IDLE, 1, true, 1, null, List.of(marker)));
        assertTrue(cursor.accept(4, IDLE, 5.25));
        assertEquals(List.of(marker), cursor.advance(4, 5.5));
        assertEquals(List.of(), cursor.advance(4, 5.5));
        assertEquals(List.of(), cursor.advance(4, 4.0));
        assertEquals(List.of(), cursor.advance(4, 5.6));
        assertFalse(cursor.accept(4, IDLE, 0));
        assertFalse(cursor.accept(3, IDLE, 0));
        assertEquals(List.of(), cursor.advance(3, 6.5));
        assertEquals(List.of(), cursor.advance(5, 6.5));
        assertEquals(List.of(marker), cursor.advance(4, 6.5));
    }

    @Test
    void preservesLoopBoundaryAndMarkerOrderingAcrossSkippedSamples() {
        AnimationVisualEvent entry = event(0, "entry");
        AnimationVisualEvent middle = event(0.25, "middle");
        AnimationVisualEvent end = event(0.5, "end");
        SynchronizedVisualEventCursor cursor = cursor(state(IDLE, 0.5f, true, 1, null, List.of(entry, middle, end)));
        cursor.accept(0, IDLE, 0);
        assertEquals(List.of(middle, entry, end, middle, entry, end), cursor.advance(0, 1));
        assertEquals(List.of(), cursor.advance(0, 1));
    }

    @Test
    void resolvesNextTransitionsAndEachStatesDescriptorSpeed() {
        AnimationVisualEvent end = event(1, "end");
        AnimationVisualEvent entry = event(0, "entry");
        AnimationVisualEvent middle = event(0.25, "middle");
        AnimationState first = state(IDLE, 1, false, 2, NEXT, List.of(end));
        AnimationState next = state(NEXT, 1, true, 0.5, null, List.of(entry, middle));
        SynchronizedVisualEventCursor cursor = new SynchronizedVisualEventCursor(
                BlendInstanceKey.entity("test", 1),
                new AnimationControllerDefinition(IDLE, Map.of(IDLE, first, NEXT, next)));
        cursor.accept(0, IDLE, 0.25);
        assertEquals(List.of(end, entry, middle), cursor.advance(0, 1));
    }

    @Test
    void newSequenceSilentlyRebaselinesCorrectionsAndRestarts() {
        AnimationVisualEvent marker = event(0.5, "step");
        SynchronizedVisualEventCursor cursor = cursor(state(IDLE, 1, true, 1, null, List.of(marker)));
        cursor.accept(0, IDLE, 0);
        assertEquals(List.of(marker), cursor.advance(0, 0.75));
        assertTrue(cursor.accept(1, IDLE, 100.75));
        assertEquals(List.of(), cursor.advance(1, 100.8));
        assertTrue(cursor.accept(2, IDLE, 0.25));
        assertEquals(List.of(marker), cursor.advance(2, 0.5));
        cursor.deactivate();
        assertEquals(List.of(), cursor.advance(2, 1.5));
        assertFalse(cursor.accept(2, IDLE, 0));
        assertTrue(cursor.accept(3, IDLE, 0));
        assertEquals(List.of(marker), cursor.advance(3, 0.5));
    }

    @Test
    void boundsLongCullCatchUpToMostRecentSecond() {
        AnimationVisualEvent marker = event(0.5, "step");
        SynchronizedVisualEventCursor cursor = cursor(state(IDLE, 1, true, 1, null, List.of(marker)));
        cursor.accept(0, IDLE, 0);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertEquals(List.of(marker), cursor.advance(0, 1_000_000.75)));
        assertEquals(List.of(), cursor.advance(0, 1_000_000.75));
    }

    @Test
    void excessiveLoopOrEventBudgetDropsWholeIntervalAndMovesHighWater() {
        AnimationVisualEvent marker = event(0.00005, "step");
        SynchronizedVisualEventCursor cursor = cursor(state(IDLE, 0.0001f, true, 1, null, List.of(marker)));
        cursor.accept(0, IDLE, 0);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals(List.of(), cursor.advance(0, 1)));
        assertEquals(List.of(), cursor.advance(0, 1));
        assertEquals(1, cursor.advance(0, 1.0001).size());

        List<AnimationVisualEvent> dense = java.util.Collections.nCopies(4096, event(0.05, "dense"));
        SynchronizedVisualEventCursor denseCursor = cursor(state(IDLE, 0.1f, true, 1, null, dense));
        denseCursor.accept(0, IDLE, 0);
        assertEquals(List.of(), denseCursor.advance(0, 1));
        assertEquals(List.of(), denseCursor.advance(0, 1));
    }

    @Test
    void nonLoopEndOnlyEmitsOnceAndInvalidInputDoesNotChangeBaseline() {
        AnimationVisualEvent marker = event(1, "end");
        SynchronizedVisualEventCursor cursor = cursor(state(IDLE, 1, false, 1, null, List.of(marker)));
        cursor.accept(0, IDLE, 0.25);
        assertThrows(IllegalArgumentException.class, () -> cursor.advance(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> cursor.accept(-1, IDLE, 0));
        assertThrows(IllegalArgumentException.class, () -> cursor.accept(1, NEXT, 0));
        assertEquals(List.of(marker), cursor.advance(0, 1));
        assertEquals(List.of(), cursor.advance(0, 2));
    }

    private static SynchronizedVisualEventCursor cursor(AnimationState state) {
        return new SynchronizedVisualEventCursor(BlendInstanceKey.entity("test", 1),
                new AnimationControllerDefinition(IDLE, Map.of(IDLE, state)));
    }

    private static AnimationState state(BlendAnimationKey key, float duration, boolean loop, double speed,
            BlendAnimationKey next, List<AnimationVisualEvent> events) {
        AnimationClip clip = new AnimationClip(key.toString(), List.of(new AnimationChannel(
                0, AnimationPath.TRANSLATION, Interpolation.LINEAR, new float[] {0, duration},
                new float[] {0, 0, 0, 1, 0, 0})));
        return new AnimationState(key, clip, loop, speed, 0, next, events);
    }

    private static AnimationVisualEvent event(double time, String name) {
        return new AnimationVisualEvent(time, BlendResourceId.parse("fixture:" + name));
    }
}
