package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.animation.runtime.EntityLocomotionRuleCacheTest.*;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.animation.rules.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SkinnedLocomotionRuntimeTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("locomotion:actor");
    private static final BlendResourceId BASE = BlendResourceId.parse("locomotion:base");
    private static final BlendResourceId UPPER = BlendResourceId.parse("locomotion:upper");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("locomotion:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("locomotion:walk");
    private static final BlendAnimationKey RUN = BlendAnimationKey.parse("locomotion:run");
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(
            new ModelAnimationLayers.Layer(BASE, 0, AnimationV2LayerMode.OVERRIDE, 1, List.of(), IDLE),
            new ModelAnimationLayers.Layer(UPPER, 1, AnimationV2LayerMode.ADDITIVE, 0.5F, List.of(), IDLE));
    private ClientModelRegistry models = new ClientModelRegistry();
    private SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models, new ClientAnimationLifecycleBridge(16));
    private final Object source = new EqualIdentity(1);
    private final Object owner = new EqualIdentity(42);
    private record EqualIdentity(int id) {}

    @Test
    void repeatedCaptureReusesSequenceObjectAndLayeredPlaybackAdvancesBesideIndependentUpperCue() {
        publish(1, rules(0, standardRules()));
        var upper = new AnimationV2Command(UPPER, RUN, 7, 0.2, 1);
        var first = capture(42, 1, 0, inputs(1), List.of(upper));
        extract(42, 0, first);
        var replay = capture(42, 1, 4, inputs(1), List.of(upper));
        assertSame(first.getLast(), replay.getLast());
        extract(42, 4, replay);
        var snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
        assertEquals(0.2, snapshot.playheads().get(BASE).timeSeconds(), 1e-9);
        assertEquals(first.getLast().sequence(), snapshot.playheads().get(BASE).acceptedSequence());
        assertEquals(0.4, snapshot.playheads().get(UPPER).timeSeconds(), 1e-9);
        assertEquals(7, snapshot.playheads().get(UPPER).acceptedSequence());
        var changed = capture(42, 1, 5, inputs(3), List.of(upper));
        assertEquals(first.getLast().sequence() + 1, changed.getLast().sequence());
        extract(42, 5, changed);
        snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
        assertEquals(RUN, snapshot.playheads().get(BASE).state());
        assertEquals(0, snapshot.playheads().get(BASE).timeSeconds(), 1e-9);
        assertEquals(0.45, snapshot.playheads().get(UPPER).timeSeconds(), 1e-9);
    }

    @Test
    void invalidInputsPreserveCurrentPlaybackAndBoundWarningToModelGeneration() {
        publish(1, rules(0, standardRules()));
        var first = capture(42, 1, 0, inputs(1), List.of());
        extract(42, 0, first);
        for (var bad : List.of(new LocomotionInputs(Map.of(), Map.of()),
                new LocomotionInputs(Map.of("speed", true), Map.of()), inputs(Double.NaN), inputs(Double.NEGATIVE_INFINITY))) {
            assertSame(first.getFirst(), capture(42, 1, 4, bad, List.of()).getFirst());
            capture(43, 1, 4, bad, List.of());
        }
        assertEquals(1, runtime.invalidLocomotionInputDiagnosticCount());
        extract(42, 4, capture(42, 1, 4, inputs(Double.NaN), List.of()));
        assertEquals(0.2, runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow()
                .playheads().get(BASE).timeSeconds(), 1e-9);
        publishGeneration(2, rules(0, standardRules()));
        assertTrue(capture(42, 2, 5, inputs(Double.NaN), List.of()).isEmpty());
        assertEquals(1, runtime.invalidLocomotionInputDiagnosticCount(), "old-generation warning entries are retired");
        assertEquals(0, capture(42, 2, 6, inputs(3), List.of()).getFirst().sequence());
    }

    @Test
    void firstInvalidCaptureBindsOwnerSoFallbackPlaybackDoesNotRestartEveryFrame() {
        publish(1, rules(0, standardRules()));
        var invalid = inputs(Double.NaN);
        assertTrue(capture(42, 1, 0, invalid, List.of()).isEmpty());
        assertEquals(1, runtime.trackedLocomotionCount());
        extract(42, 0, List.of());
        extract(42, 4, capture(42, 1, 4, invalid, List.of()));
        assertEquals(0.2, runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow()
                .playheads().get(BASE).timeSeconds(), 1e-9);
        assertEquals(0, capture(42, 1, 5, inputs(1), List.of()).getFirst().sequence());
    }

    @Test
    void missingRulesPreserveExplicitCommandsWithoutReadingInputsAndValidRulesRejectCompetition() {
        publish(1, null);
        var command = new AnimationV2Command(BASE, WALK, 50, 0.4, 1);
        var explicit = new ArrayList<>(List.of(command));
        var retained = runtime.captureEntityLocomotionRules(source, owner, 42, MODEL, 1, 0, BASE,
                () -> { throw new AssertionError("missing optional sidecar must not invoke inputs"); }, () -> explicit);
        explicit.clear();
        assertEquals(List.of(command), retained);
        assertSame(command, retained.getFirst());
        assertThrows(UnsupportedOperationException.class, retained::clear);
        publish(2, rules(0, standardRules()));
        assertThrows(IllegalArgumentException.class, () -> capture(42, 2, 1, inputs(3), List.of(command)));
        assertEquals(0, runtime.trackedLocomotionCount());
        assertEquals(0, capture(42, 2, 2, inputs(1), List.of()).getFirst().sequence());
    }

    @Test
    void equalButDifferentOwnersAndSourcesRetireOldAcceptedSequenceWhileSharedEntitiesStayIndependent() {
        for (String changed : List.of("owner", "source")) {
            publish(1, rules(200, standardRules()));
            var initial = capture(42, 1, 0, inputs(3), List.of());
            extract(42, 0, initial);
            extract(42, 4, capture(42, 1, 4, inputs(3), List.of()));
            var independent = capture(43, 1, 4, inputs(0), List.of());
            assertEquals(IDLE, independent.getFirst().animationKey());
            var fresh = runtime.captureEntityLocomotionRules(
                    changed.equals("source") ? new EqualIdentity(1) : source,
                    changed.equals("owner") ? new EqualIdentity(42) : owner,
                    42, MODEL, 1, 5, BASE, () -> inputs(0), List::of);
            assertEquals(IDLE, fresh.getFirst().animationKey(), changed);
            assertEquals(0, fresh.getFirst().sequence(), changed);
            assertTrue(runtime.layeredSnapshot(runtime.entityKey(42)).isEmpty(), changed);
            extract(42, 5, fresh);
            var snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertEquals(IDLE, snapshot.playheads().get(BASE).state(), changed);
            assertEquals(0, snapshot.playheads().get(BASE).timeSeconds(), 1e-9, changed);
            assertSame(independent.getFirst(), capture(43, 1, 6, inputs(3), List.of()).getFirst());
        }
    }

    @Test
    void rejectedReplacementOrThrowingInputPreservesExactOriginalCommandAndExistingFrame() {
        for (String changed : List.of("owner", "source")) {
            publish(1, rules(0, standardRules()));
            capture(42, 1, 0, inputs(0), List.of());
            var original = capture(42, 1, 1, inputs(1), List.of());
            assertTrue(original.getFirst().sequence() > 0);
            extract(42, 1, original);
            extract(42, 5, capture(42, 1, 5, inputs(1), List.of()));
            var key = runtime.entityKey(42);
            var frame = runtime.layeredSnapshot(key).orElseThrow();
            Object nextSource = changed.equals("source") ? new EqualIdentity(1) : source;
            Object nextOwner = changed.equals("owner") ? new EqualIdentity(42) : owner;
            var competing = new AnimationV2Command(BASE, RUN, 999, 0, 1);
            assertThrows(IllegalArgumentException.class, () -> runtime.captureEntityLocomotionRules(
                    nextSource, nextOwner, 42, MODEL, 1, 6, BASE,
                    () -> { throw new AssertionError("competition must be rejected before inputs"); },
                    () -> List.of(competing)));
            assertSame(frame, runtime.layeredSnapshot(key).orElseThrow());
            assertSame(original.getFirst(), capture(42, 1, 6, inputs(1), List.of()).getFirst());
            var failure = new IllegalStateException("input callback failure");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> runtime.captureEntityLocomotionRules(
                    nextSource, nextOwner, 42, MODEL, 1, 7, BASE, () -> { throw failure; }, List::of)));
            assertSame(frame, runtime.layeredSnapshot(key).orElseThrow());
            var replay = capture(42, 1, 8, inputs(1), List.of());
            assertSame(original.getFirst(), replay.getFirst());
            extract(42, 8, replay);
            assertEquals(0.35, runtime.layeredSnapshot(key).orElseThrow().playheads().get(BASE).timeSeconds(), 1e-9);
        }
    }

    @Test
    void replacementKeepsNewOwnersExactUpperCueCaptureAndContinuesWithoutSequenceConflict() {
        publish(1, rules(0, standardRules()));
        capture(42, 1, 0, inputs(0), List.of());
        var oldUpper = new AnimationV2Command(UPPER, RUN, 77, 0.4, 1);
        extract(42, 99, capture(42, 1, 99, inputs(1), List.of(oldUpper)));
        Object newOwner = new EqualIdentity(42);
        Object newCueSource = new EqualIdentity(10);
        var cue = new com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue(UPPER, RUN, 1, 100, 1);
        java.util.function.LongFunction<List<AnimationV2Command>> frame = tick ->
                runtime.captureEntityLocomotionRules(source, newOwner, 42, MODEL, 1, tick, BASE,
                        () -> inputs(0), () -> runtime.captureEntityLayerCues(
                                newCueSource, newOwner, 42, MODEL, 1, tick, List.of(cue)));
        var first = frame.apply(100);
        assertEquals(UPPER, first.getFirst().controllerId());
        assertEquals(1, first.getFirst().sequence());
        assertEquals(0, first.getFirst().requestedPlayheadSeconds(), 1e-9);
        extract(42, 100, first);
        var replay = frame.apply(101);
        assertSame(first.getFirst(), replay.getFirst(), "replacement must retain this frame's frozen cue object");
        assertSame(first.getLast(), replay.getLast());
        extract(42, 101, replay);
        var snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
        assertEquals(1, snapshot.playheads().get(UPPER).acceptedSequence());
        assertEquals(0.05, snapshot.playheads().get(UPPER).timeSeconds(), 1e-9);
        assertEquals(0.05, snapshot.playheads().get(BASE).timeSeconds(), 1e-9);
    }

    @Test
    void unloadReloadDisconnectRepeatedPlayInitAndExplicitRetirementClearSelectionAndLayeredState() {
        for (String reset : List.of("unload", "reload", "disconnect", "play_init", "retire")) {
            publish(1, rules(200, standardRules()));
            var oldKey = runtime.entityKey(42);
            var initial = capture(42, 1, 0, inputs(3), List.of());
            extract(42, 0, initial);
            switch (reset) {
                case "unload" -> runtime.onEntityUnload(42);
                case "reload" -> { publishGeneration(2, rules(200, standardRules())); runtime.captureExtractionLifecycleRevision(); }
                case "disconnect" -> {
                    runtime.onWorldDisconnect();
                    assertTrue(runtime.captureEntityLocomotionRules(source, owner, 42, MODEL, 1, 1, BASE,
                            () -> { throw new AssertionError(); }, () -> { throw new AssertionError(); }).isEmpty());
                    runtime.onPlayInit();
                }
                case "play_init" -> { runtime.onPlayInit(); runtime.onPlayInit(); }
                case "retire" -> runtime.retire(oldKey);
            }
            assertEquals(0, runtime.trackedLocomotionCount(), reset);
            assertTrue(runtime.layeredSnapshot(oldKey).isEmpty(), reset);
            var fresh = capture(42, models.current().generationId(), 1, inputs(0), List.of());
            assertEquals(IDLE, fresh.getFirst().animationKey(), reset);
            assertEquals(0, fresh.getFirst().sequence(), reset);
            assertNotSame(initial.getFirst(), fresh.getFirst(), reset);
        }
    }

    @Test
    void recursiveCaptureFromEitherCallbackFailsWithoutChangingSelectionAndGuardIsReleased() {
        publish(1, rules(0, standardRules()));
        var first = capture(42, 1, 0, inputs(1), List.of()).getFirst();
        for (boolean fromInputs : List.of(false, true)) {
            Supplier<LocomotionInputs> inputCallback = () -> {
                if (fromInputs) capture(43, 1, 1, inputs(3), List.of());
                return inputs(3);
            };
            Supplier<List<AnimationV2Command>> commandCallback = () -> {
                if (!fromInputs) capture(43, 1, 1, inputs(3), List.of());
                return List.of();
            };
            assertThrows(IllegalStateException.class, () -> runtime.captureEntityLocomotionRules(
                    source, owner, 42, MODEL, 1, 1, BASE, inputCallback, commandCallback));
            assertSame(first, capture(42, 1, 2, inputs(1), List.of()).getFirst());
            assertEquals(1, runtime.trackedLocomotionCount());
        }
    }

    @Test
    void lifecycleOrGenerationMutationInsideEitherCallbackCannotPublishStaleCapture() {
        for (String mutation : List.of("unload", "retire", "disconnect", "play_init", "generation")) {
            for (boolean fromInputs : List.of(false, true)) {
                publish(1, rules(0, standardRules()));
                capture(42, 1, 0, inputs(1), List.of());
                Runnable invalidate = () -> {
                    switch (mutation) {
                        case "unload" -> runtime.onEntityUnload(42);
                        case "retire" -> runtime.retire(runtime.entityKey(42));
                        case "disconnect" -> runtime.onWorldDisconnect();
                        case "play_init" -> runtime.onPlayInit();
                        case "generation" -> publishGeneration(2, rules(0, standardRules()));
                    }
                };
                var result = runtime.captureEntityLocomotionRules(source, owner, 42, MODEL, 1, 1, BASE,
                        () -> { if (fromInputs) invalidate.run(); return inputs(3); },
                        () -> { if (!fromInputs) invalidate.run(); return List.of(); });
                assertTrue(result.isEmpty(), mutation + "/inputs=" + fromInputs);
                if (!mutation.equals("generation")) assertEquals(0, runtime.trackedLocomotionCount());
                runtime.onPlayInit();
                var fresh = capture(42, models.current().generationId(), 2, inputs(0), List.of());
                assertEquals(IDLE, fresh.getFirst().animationKey());
                assertEquals(0, fresh.getFirst().sequence());
            }
        }
    }

    @Test
    void commandAndInputSnapshotsAreCapturedOnceAndCannotBeChangedByLaterCallbacks() {
        publish(1, rules(0, standardRules()));
        var upper = new AnimationV2Command(UPPER, RUN, 8, 0.4, 1);
        var mutableCommands = new ArrayList<>(List.of(upper));
        var mutableNumbers = new HashMap<>(Map.of("speed", 1.0));
        var capturedInputs = new LocomotionInputs(Map.of(), mutableNumbers);
        int[] calls = {0, 0};
        var result = runtime.captureEntityLocomotionRules(source, owner, 42, MODEL, 1, 0, BASE,
                () -> { calls[0]++; mutableCommands.clear(); mutableNumbers.put("speed", 3.0); return capturedInputs; },
                () -> { calls[1]++; return mutableCommands; });
        assertArrayEquals(new int[] {1, 1}, calls);
        assertSame(upper, result.getFirst());
        assertEquals(WALK, result.getLast().animationKey());
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertThrows(NullPointerException.class, () -> runtime.captureEntityLocomotionRules(source, owner,
                42, MODEL, 1, 1, BASE, () -> null, List::of));
        assertSame(result.getLast(), capture(42, 1, 2, inputs(1), List.of()).getFirst());
    }

    @Test
    void inactiveStaleAndSyntheticEntityCapturesDoNotInvokeCallbacksOrRepopulateState() {
        publishGeneration(1, rules(0, standardRules()));
        assertInactive(42, 1);
        runtime.onPlayInit();
        assertInactive(-1, 1);
        assertInactive(42, 0);
        models.close();
        assertInactive(42, 1);
        assertEquals(0, runtime.trackedLocomotionCount());
        assertEquals(0, runtime.invalidLocomotionInputDiagnosticCount());
    }

    private void assertInactive(int entityId, long generation) {
        assertTrue(runtime.captureEntityLocomotionRules(source, owner, entityId, MODEL, generation, 0, BASE,
                () -> { throw new AssertionError("inactive inputs"); },
                () -> { throw new AssertionError("inactive commands"); }).isEmpty());
    }

    private List<AnimationV2Command> capture(int id, long generation, double tick, LocomotionInputs inputs,
            List<AnimationV2Command> commands) {
        return runtime.captureEntityLocomotionRules(source, owner, id, MODEL, generation, tick, BASE,
                () -> inputs, () -> commands);
    }

    private void publish(long generation, LocomotionRules rules) {
        if (models.current().generationId() >= generation) {
            models = new ClientModelRegistry();
            runtime = new SkinnedAnimationRuntime(models, new ClientAnimationLifecycleBridge(16));
        }
        runtime.onPlayInit();
        publishGeneration(generation, rules);
    }

    private void publishGeneration(long generation, LocomotionRules rules) {
        var geometry = new MeshPrimitive("Skin", new float[] {0,0,0, 1,0,0, 0,1,0},
                new float[] {0,0,1, 0,0,1, 0,0,1}, new float[] {0,0, 1,0, 0,1}, new int[] {0,1,2},
                new int[12], new float[] {1,0,0,0, 1,0,0,0, 1,0,0,0});
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), generation,
                ModelProfile.SKINNED_V1, 1, Map.of("Skin", new MaterialDefinition(
                        BlendResourceId.parse("locomotion:textures/skin.png"), MaterialDefinition.Mode.OPAQUE, false, false, null)),
                definition(), List.of(new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0), List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(new Skin("Rig", 1, List.of(1), new float[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}))),
                List.of(clip("idle", 0), clip("walk", 1), clip("run", 2)), new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()), List.of());
        var loaded = new LoadedModelHandle(MODEL, asset, SkinnedRenderHandle.prepare(MODEL, asset));
        models.publish(new ModelRegistryGeneration(generation, Map.of(MODEL, loaded), Map.of(), List.of(),
                rules == null ? Map.of() : Map.of(MODEL, rules)));
    }

    private static AnimationClip clip(String name, float endX) {
        return new AnimationClip(name, List.of(new AnimationChannel(1, AnimationPath.TRANSLATION,
                Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, endX, 0, 0})));
    }

    private void extract(int entityId, long tick, List<AnimationV2Command> commands) {
        var handle = models.find(MODEL).orElseThrow().renderHandle();
        var input = new SkinnedAnimationRuntimeInput(MODEL, runtime.entityKey(entityId), tick, 0, IDLE,
                Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                new SkinnedExtractionRequest(Transform.IDENTITY, 0, 0, 0xFFFFFFFF, RenderVisibility.VISIBLE,
                        new CullingMetadata(handle.bounds(), true)));
        assertTrue(runtime.extractLayered(input, LAYERS, commands, null).isPresent());
    }
}
