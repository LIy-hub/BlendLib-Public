package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityLayerCueCacheTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("test:model");
    private static final BlendResourceId UPPER = BlendResourceId.parse("test:upper");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("test:attack");
    private final EntityLayerCueCache cache = new EntityLayerCueCache();
    private final Object source = new Object();
    private final Object owner = new EqualEntity(42);
    private record EqualEntity(int id) { }
    private BlendEntityLayerCue cue(long sequence, long start, double speed) {
        return new BlendEntityLayerCue(UPPER, ATTACK, sequence, start, speed);
    }
    private List<AnimationV2Command> capture(Object owner, long generation, double ticks, BlendEntityLayerCue... cues) {
        return cache.capture(source, owner, BlendInstanceKey.entity("session", 42), MODEL, generation, ticks, List.of(cues));
    }
    @Test void freezesCatchupWithSpeedAndPartialTickForRepeatedSequence() {
        var first = capture(owner, 1, 32.5, cue(1, 30, 2)).getFirst();
        assertEquals(0.25, first.requestedPlayheadSeconds());
        assertSame(first, capture(owner, 1, 40, cue(1, 30, 2)).getFirst());
        assertSame(first, capture(owner, 1, 41, cue(1, 39, 1)).getFirst());
        assertEquals(0.0, capture(owner, 1, 40, cue(2, 100, 1)).getFirst().requestedPlayheadSeconds());
    }
    @Test void descriptorSpeedScalesClipLocalCatchupWithoutChangingCommandRate() {
        var command = cache.capture(source, owner, BlendInstanceKey.entity("session", 42), MODEL,
                1, 40, List.of(cue(1, 30, 2)), ignored -> 3.0).getFirst();
        assertEquals(3.0, command.requestedPlayheadSeconds());
        assertEquals(2.0, command.playbackSpeed());
    }
    @Test void staleCueDoesNotResetHighWaterOrOverwriteFrozenCommand() {
        var first = capture(owner, 1, 32, cue(5, 30, 1)).getFirst();
        assertTrue(capture(owner, 1, 40, cue(4, 30, 1)).isEmpty());
        assertSame(first, capture(owner, 1, 45, cue(5, 30, 1)).getFirst());
        assertTrue(capture(owner, 1, 46).isEmpty());
        assertSame(first, capture(owner, 1, 47, cue(5, 30, 1)).getFirst());
        assertEquals(0.1, capture(owner, 1, 50, cue(6, 48, 1)).getFirst().requestedPlayheadSeconds());
    }
    @Test void equalEntityIdsAndIndependentSourcesDoNotShareCaptures() {
        var first = capture(owner, 1, 32, cue(1, 30, 1)).getFirst();
        assertEquals(0.2, capture(new EqualEntity(42), 1, 34, cue(1, 30, 1)).getFirst().requestedPlayheadSeconds());
        assertEquals(2, cache.ownerCount());
        assertEquals(0.3, cache.capture(new Object(), owner, BlendInstanceKey.entity("session", 42), MODEL,
                1, 36, List.of(cue(1, 30, 1))).getFirst().requestedPlayheadSeconds());
        assertSame(first, capture(owner, 1, 50, cue(1, 30, 1)).getFirst());
    }
    @Test void reloadUnloadAndClearRecaptureAndReleaseOwners() {
        capture(owner, 1, 32, cue(1, 30, 1));
        cache.retainGeneration(2);
        assertEquals(0, cache.ownerCount());
        assertEquals(0.3, capture(owner, 2, 36, cue(1, 30, 1)).getFirst().requestedPlayheadSeconds());
        cache.retireEntity(41);
        assertEquals(1, cache.ownerCount());
        cache.retireEntity(42);
        assertEquals(0, cache.ownerCount());
        assertEquals(0.5, capture(owner, 2, 40, cue(1, 30, 1)).getFirst().requestedPlayheadSeconds());
        cache.clear();
        assertEquals(0, cache.ownerCount());
    }
    @Test void eachControllerHasIndependentSequenceAndDuplicateBatchIsRejectedAtomically() {
        var lower = new BlendEntityLayerCue(BlendResourceId.parse("test:lower"), ATTACK, 0, 30, 1);
        assertEquals(2, capture(owner, 1, 32, cue(9, 30, 1), lower).size());
        assertThrows(IllegalArgumentException.class, () -> capture(owner, 1, 36, cue(10, 30, 1), cue(10, 30, 1)));
        assertEquals(0.3, capture(owner, 1, 36, cue(10, 30, 1)).getFirst().requestedPlayheadSeconds());
        assertThrows(IllegalArgumentException.class, () -> cue(-1, 30, 1));
        assertThrows(IllegalArgumentException.class, () -> cue(1, 30, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> capture(owner, 1, Double.NaN, lower));
    }
    @Test void runtimeAutomaticallyRetiresCapturesAndLateDisconnectExtractionCannotRepopulate() {
        var runtime = new SkinnedAnimationRuntime(new ClientModelRegistry(), new ClientAnimationLifecycleBridge(16));
        var cues = List.of(cue(1, 30, 1));
        assertTrue(runtime.captureEntityLayerCues(source, owner, 42, MODEL, 1, 32, cues).isEmpty());
        runtime.onPlayInit();
        var first = runtime.captureEntityLayerCues(source, owner, 42, MODEL, 1, 32, cues).getFirst();
        assertSame(first, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 1, 34, cues).getFirst());
        runtime.onActiveGeneration(1);
        assertSame(first, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 1, 36, cues).getFirst());
        runtime.onActiveGeneration(2);
        assertEquals(0.4, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 2, 38, cues).getFirst().requestedPlayheadSeconds());
        runtime.onEntityUnload(42);
        assertEquals(0.5, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 2, 40, cues).getFirst().requestedPlayheadSeconds());
        runtime.retire(runtime.entityKey(42));
        assertEquals(0.55, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 2, 41, cues).getFirst().requestedPlayheadSeconds());
        runtime.onWorldDisconnect();
        assertTrue(runtime.captureEntityLayerCues(source, owner, 42, MODEL, 2, 42, cues).isEmpty());
        runtime.onPlayInit();
        assertEquals(0.7, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 2, 44, cues).getFirst().requestedPlayheadSeconds());
        runtime.onPlayInit();
        assertEquals(0.8, runtime.captureEntityLayerCues(source, owner, 42, MODEL, 2, 46, cues).getFirst().requestedPlayheadSeconds());
        assertTrue(runtime.captureEntityLayerCues(source, owner, -1, MODEL, 2, 46, cues).isEmpty());
    }
}
