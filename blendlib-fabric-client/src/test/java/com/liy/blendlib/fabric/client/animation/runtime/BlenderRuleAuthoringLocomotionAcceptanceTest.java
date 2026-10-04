package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.rules.LocomotionCondition;
import com.liy.blendlib.core.animation.rules.LocomotionInputs;
import com.liy.blendlib.core.animation.rules.LocomotionRuleParser;
import com.liy.blendlib.core.animation.rules.LocomotionRules;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.animation.v2.AnimationV2DiagnosticCode;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Real Blender rule editor -> exported descriptor/GLB/sidecar -> existing client runtime.
 * The fixture is loaded unchanged: no test-built clips, rule objects, or replacement JSON.
 * Locomotion in the class name also includes this test in the official Minecraft 26.3 source set.
 */
class BlenderRuleAuthoringLocomotionAcceptanceTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_rules:actor");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_rules:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("blendlib_rules:walk");
    private static final BlendAnimationKey RUN = BlendAnimationKey.parse("blendlib_rules:run");
    private static final BlendResourceId BASE = BlendResourceId.parse("blendlib_rules:base");
    private static final double EPSILON = 1.0e-6;
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(
            new ModelAnimationLayers.Layer(BASE, 0, AnimationV2LayerMode.OVERRIDE, 1, List.of(), IDLE));

    @Test
    void genuineRuleEditorExportLoadsThreeDistinctLoopingClipsAndStrictOrderedTypedConditions() throws IOException {
        ModelAsset asset = loadAsset(1);
        assertEquals(ModelProfile.RIGID_V1, asset.profile());
        assertEquals(1, asset.generation());
        assertFalse(asset.primitives().isEmpty());
        assertTrue(asset.clips().stream().map(AnimationClip::name).toList().containsAll(
                List.of("Idle", "Walk", "Run")));
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        assertEquals(IDLE, definition.initialState());
        assertTrue(definition.states().keySet().containsAll(Set.of(IDLE, WALK, RUN)));
        var sampler = PoseSampler.fromModelAsset(asset);
        for (var state : List.of(IDLE, WALK, RUN)) {
            assertTrue(definition.state(state).loop());
            assertNull(definition.state(state).next());
            assertEquals(1, definition.state(state).speed(), EPSILON);
            assertEquals(0, definition.state(state).blendSeconds(), EPSILON);
            assertEquals(1, definition.state(state).clip().durationSeconds(), EPSILON);
        }
        assertEquals("Idle", definition.state(IDLE).clip().name());
        assertEquals("Walk", definition.state(WALK).clip().name());
        assertEquals("Run", definition.state(RUN).clip().name());
        assertEquals(3, Set.copyOf(List.of(
                sampler.sample(definition.state(IDLE), 0.5).transforms(),
                sampler.sample(definition.state(WALK), 0.5).transforms(),
                sampler.sample(definition.state(RUN), 0.5).transforms())).size(),
                "all three authored clips must contribute different real exported poses");

        LocomotionRules rules = loadRules(asset);
        assertEquals(IDLE, rules.defaultAnimation());
        assertEquals(4, rules.minimumIntervalTicks());
        assertEquals(List.of(RUN, WALK), rules.rules().stream().map(rule -> rule.animation()).toList());
        assertEquals(List.of(new LocomotionCondition.BooleanEquals("grounded", true),
                        new LocomotionCondition.Minimum("speed", 2.0, 1.5),
                        new LocomotionCondition.Maximum("slope", 0.5, 0.75)),
                rules.rules().get(0).conditions());
        assertEquals(List.of(new LocomotionCondition.BooleanEquals("grounded", true),
                        new LocomotionCondition.Minimum("speed", 0.1, 0.05)),
                rules.rules().get(1).conditions());
    }

    @Test
    void exportedPriorityAndMinimumMaximumHysteresisAreInclusiveAtEveryAuthoredBoundary() throws IOException {
        LocomotionRules rules = loadRules(loadAsset(1));
        assertSelection(rules, -1, 0, 0, true, IDLE);
        assertSelection(rules, -1, Math.nextDown(0.1), 0, true, IDLE);
        assertSelection(rules, -1, 0.1, 0, true, WALK);
        assertSelection(rules, -1, 0.075, 0, true, IDLE);
        assertSelection(rules, 1, 0.075, 0, true, WALK);
        assertSelection(rules, 1, 0.05, 0, true, WALK);
        assertSelection(rules, 1, Math.nextDown(0.05), 0, true, IDLE);

        assertSelection(rules, -1, Math.nextDown(2.0), 0.5, true, WALK);
        assertSelection(rules, -1, 2.0, 0.5, true, RUN);
        assertSelection(rules, 1, 2.0, 0.5, true, RUN);
        assertTrue(rules.rules().get(1).conditions().stream()
                        .allMatch(condition -> condition.matches(inputs(2.0, 0.5, true), false)),
                "run must win over an independently matching lower-priority walk rule");
        assertSelection(rules, -1, 1.75, 0.5, true, WALK);
        assertSelection(rules, 0, 1.75, 0.5, true, RUN);
        assertSelection(rules, 0, 1.5, 0.5, true, RUN);
        assertSelection(rules, 0, Math.nextDown(1.5), 0.5, true, WALK);

        assertSelection(rules, -1, 2.0, Math.nextUp(0.5), true, WALK);
        assertSelection(rules, -1, 2.0, 0.625, true, WALK);
        assertSelection(rules, 0, 2.0, 0.625, true, RUN);
        assertSelection(rules, 0, 2.0, 0.75, true, RUN);
        assertSelection(rules, 0, 2.0, Math.nextUp(0.75), true, WALK);
        for (int current : List.of(-1, 0, 1)) {
            assertSelection(rules, current, 3.0, 0, false, IDLE);
        }
    }

    @Test
    void exportedRulesDriveClientPlaybackAndOnlySwitchAtOrAfterMinimumInterval() throws IOException {
        try (var harness = new Harness()) {
            var idle = harness.frame(0, 0, 0, true);
            harness.assertPlayback(IDLE, 0);
            assertSame(idle, harness.frame(3, 0.1, 0, true));
            harness.assertPlayback(IDLE, 0.15);
            var walk = harness.frame(4, 0.1, 0, true);
            assertTransition(idle, walk, WALK);
            harness.assertPlayback(WALK, 0);
            assertSame(walk, harness.frame(7, 2.0, 0.5, true));
            harness.assertPlayback(WALK, 0.15);
            var run = harness.frame(8, 2.0, 0.5, true);
            assertTransition(walk, run, RUN);
            harness.assertPlayback(RUN, 0);
            assertSame(run, harness.frame(10, 1.75, 0.625, true));
            harness.assertPlayback(RUN, 0.1);
            assertSame(run, harness.frame(12, 1.5, 0.75, true));
            harness.assertPlayback(RUN, 0.2);
            assertSame(run, harness.frame(12, 1.5, 0.75, true));
            harness.assertPlayback(RUN, 0.2);

            var downhill = harness.frame(13, 2.0, Math.nextUp(0.75), true);
            assertTransition(run, downhill, WALK);
            harness.assertPlayback(WALK, 0);
            assertSame(downhill, harness.frame(16, 0, 0, true));
            harness.assertPlayback(WALK, 0.15);
            assertSame(downhill, harness.frame(17, 0.05, 0, true));
            harness.assertPlayback(WALK, 0.2);
            var stopped = harness.frame(18, Math.nextDown(0.05), 0, true);
            assertTransition(downhill, stopped, IDLE);
            harness.assertPlayback(IDLE, 0);
            var restarted = harness.frame(22, 2.0, 0.5, true);
            assertTransition(stopped, restarted, RUN);
            var slowed = harness.frame(26, Math.nextDown(1.5), 0.5, true);
            assertTransition(restarted, slowed, WALK);
            var airborne = harness.frame(30, 3.0, 0, false);
            assertTransition(slowed, airborne, IDLE);
            harness.assertPlayback(IDLE, 0);
            assertEquals(1, harness.runtime.trackedLocomotionCount());
            assertEquals(0, harness.runtime.invalidLocomotionInputDiagnosticCount());
        }
    }

    @Test
    void missingWrongTypeAndNonFiniteInputsPreserveLastAuthoredSelectionAndPlayback() throws IOException {
        try (var harness = new Harness()) {
            var run = harness.frame(0, 2.0, 0.5, true);
            assertEquals(RUN, run.animationKey());
            var invalidInputs = List.of(
                    new LocomotionInputs(Map.of("grounded", true), Map.of("speed", 0.0)),
                    new LocomotionInputs(Map.of(), Map.of("grounded", 1.0, "speed", 0.0, "slope", 0.0)),
                    new LocomotionInputs(Map.of("grounded", true, "speed", false), Map.of("slope", 0.0)),
                    inputs(0, Double.NaN, true),
                    inputs(Double.POSITIVE_INFINITY, 0, true));
            long tick = 4;
            for (var invalid : invalidInputs) {
                assertFalse(harness.rules.inputsComplete(invalid));
                assertSame(run, harness.frame(tick, invalid));
                harness.assertPlayback(RUN, tick / 20.0);
                tick++;
            }
            assertEquals(1, harness.runtime.invalidLocomotionInputDiagnosticCount(),
                    "invalid inputs are diagnosed once per model generation");
            var idle = harness.frame(9, 2.0, 0.5, false);
            assertTransition(run, idle, IDLE);
            harness.assertPlayback(IDLE, 0);
        }
    }

    @Test
    void independentlyReloadedDescriptorAndRulesRetireOldPriorityHysteresisIntervalAndSequence() throws IOException {
        try (var harness = new Harness()) {
            var firstAsset = harness.asset;
            var firstRules = harness.rules;
            var idle = harness.frame(100, 0, 0, true);
            var run = harness.frame(104, 2.0, 0.5, true);
            assertTransition(idle, run, RUN);
            assertSame(run, harness.frame(105, 1.75, 0.625, true));
            harness.assertPlayback(RUN, 0.05);

            harness.reload(2);
            assertNotSame(firstAsset, harness.asset);
            assertNotSame(firstRules, harness.rules);
            assertEquals(2, harness.asset.generation());
            assertEquals(firstRules.defaultAnimation(), harness.rules.defaultAnimation());
            assertEquals(firstRules.rules(), harness.rules.rules());
            harness.runtime.captureExtractionLifecycleRevision();
            assertEquals(0, harness.runtime.trackedLocomotionCount());
            assertTrue(harness.runtime.layeredSnapshot(harness.runtime.entityKey(42)).isEmpty());
            var walk = harness.frame(105, 1.75, 0.625, true);
            assertEquals(WALK, walk.animationKey(),
                    "fresh rules use entry thresholds rather than the retired run's exit thresholds");
            assertEquals(0, walk.sequence());
            assertNotSame(run, walk);
            harness.assertPlayback(WALK, 0);
            assertSame(walk, harness.frame(108, 0, 0, true));
            harness.assertPlayback(WALK, 0.15);
            var stopped = harness.frame(109, 0, 0, true);
            assertTransition(walk, stopped, IDLE);
            harness.assertPlayback(IDLE, 0);
            assertEquals(0, harness.runtime.invalidLocomotionInputDiagnosticCount());
        }
    }

    private static void assertSelection(LocomotionRules rules, int currentRule, double speed, double slope,
            boolean grounded, BlendAnimationKey expected) {
        assertEquals(expected, rules.animationForRule(rules.selectRule(inputs(speed, slope, grounded), currentRule)),
                () -> "current rule=" + currentRule + ", speed=" + speed + ", slope=" + slope
                        + ", grounded=" + grounded);
    }

    private static void assertTransition(AnimationV2Command previous, AnimationV2Command next,
            BlendAnimationKey expected) {
        assertNotSame(previous, next);
        assertEquals(expected, next.animationKey());
        assertEquals(previous.sequence() + 1, next.sequence());
        assertEquals(0, next.requestedPlayheadSeconds(), EPSILON);
    }

    private static LocomotionInputs inputs(double speed, double slope, boolean grounded) {
        return new LocomotionInputs(Map.of("grounded", grounded), Map.of("speed", speed, "slope", slope));
    }

    private static final class Harness implements AutoCloseable {
        private final ClientModelRegistry models = new ClientModelRegistry();
        private final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(
                models, new ClientAnimationLifecycleBridge(16));
        private final Object source = new Object();
        private final Object owner = new Object();
        private ModelAsset asset;
        private LocomotionRules rules;
        private StaticRigidRenderHandle handle;

        private Harness() throws IOException {
            reload(1);
            runtime.onPlayInit();
        }

        private void reload(long generation) throws IOException {
            asset = loadAsset(generation);
            rules = loadRules(asset);
            handle = StaticRigidRenderHandle.prepare(MODEL, asset);
            models.publish(new ModelRegistryGeneration(generation,
                    Map.of(MODEL, new LoadedModelHandle(MODEL, asset, handle)), Map.of(), List.of(),
                    Map.of(MODEL, rules)));
        }

        private AnimationV2Command frame(long tick, double speed, double slope, boolean grounded) {
            return frame(tick, inputs(speed, slope, grounded));
        }

        private AnimationV2Command frame(long tick, LocomotionInputs inputs) {
            var commands = runtime.captureEntityLocomotionRules(source, owner, 42, MODEL, asset.generation(),
                    tick, BASE, () -> inputs, List::of);
            assertEquals(1, commands.size());
            var input = new SkinnedAnimationRuntimeInput(MODEL, runtime.entityKey(42), tick, 0, IDLE,
                    Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, 0, 0, 0xFFFFFFFF, RenderVisibility.VISIBLE,
                            new CullingMetadata(handle.bounds(), true)));
            assertTrue(runtime.extractLayered(input, LAYERS, commands, null).isPresent());
            return commands.getFirst();
        }

        private void assertPlayback(BlendAnimationKey state, double time) {
            var snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertEquals(state, snapshot.playheads().get(BASE).state());
            assertEquals(time, snapshot.playheads().get(BASE).timeSeconds(), EPSILON);
            assertTrue(snapshot.diagnostics().stream().allMatch(diagnostic ->
                            diagnostic.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED),
                    () -> "Only unchanged-command replay is expected: " + snapshot.diagnostics());
            var expected = PoseSampler.fromModelAsset(asset).sample(
                    AnimationControllerDefinition.fromModelAsset(asset).state(state), time);
            var actual = new ModelAnimationLayers(asset, LAYERS).localPose(snapshot.pose());
            assertEquals(expected.transforms().keySet(), actual.transforms().keySet());
            for (int node : expected.transforms().keySet()) {
                assertTransform(expected.transform(node), actual.transform(node));
            }
        }

        @Override
        public void close() {
            models.close();
        }
    }

    private static void assertTransform(Transform expected, Transform actual) {
        assertEquals(expected.translation().x(), actual.translation().x(), EPSILON);
        assertEquals(expected.translation().y(), actual.translation().y(), EPSILON);
        assertEquals(expected.translation().z(), actual.translation().z(), EPSILON);
        assertEquals(expected.scale().x(), actual.scale().x(), EPSILON);
        assertEquals(expected.scale().y(), actual.scale().y(), EPSILON);
        assertEquals(expected.scale().z(), actual.scale().z(), EPSILON);
        var a = expected.rotation();
        var b = actual.rotation();
        assertEquals(1, Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w()), EPSILON);
    }

    private static ModelAsset loadAsset(long generation) {
        return new ModelAssetLoader().load(MODEL.resourceId(), generation, resource(MODEL.descriptorResourceId()),
                BlenderRuleAuthoringLocomotionAcceptanceTest::resource);
    }

    private static LocomotionRules loadRules(ModelAsset asset) throws IOException {
        return LocomotionRuleParser.parse(Files.readAllBytes(resourcePath(
                BlendResourceId.parse("blendlib_rules:blend_animation_rules/actor.json"))), asset.animationDefinition());
    }

    private static AssetBytes resource(BlendResourceId id) {
        try {
            return new AssetBytes(id, Files.readAllBytes(resourcePath(id)));
        } catch (IOException exception) {
            throw new UncheckedIOException("Missing genuine Blender rule editor export " + id, exception);
        }
    }

    private static Path resourcePath(BlendResourceId id) {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent()
                .resolve("test-assets/blender-rules/exported/assets").resolve(id.namespace()).resolve(id.path());
    }
}
