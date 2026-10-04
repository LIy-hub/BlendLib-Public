package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.rules.LocomotionCondition;
import com.liy.blendlib.core.animation.rules.LocomotionInputs;
import com.liy.blendlib.core.animation.rules.LocomotionRuleParser;
import com.liy.blendlib.core.animation.rules.LocomotionRules;
import com.liy.blendlib.core.animation.runtime.AnimationController;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.animation.v2.AnimationV2DiagnosticCode;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights;
import com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.event.VisualEventDispatcher;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * End-to-end acceptance of the checked-in genuine Blender export. No test-built GLB,
 * synthetic animation definition, or substitute locomotion JSON participates in the happy path.
 * The name also selects this test in the official Minecraft 26.3 locomotion test source set.
 */
class BlenderAuthoringLocomotionAcceptanceTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_authoring:actor");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_authoring:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("blendlib_authoring:walk");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("blendlib_authoring:attack");
    private static final BlendResourceId HAND = BlendResourceId.parse("blendlib_authoring:hand");
    private static final BlendResourceId BASE = BlendResourceId.parse("blendlib_authoring:base");
    private static final AnimationVisualEvent FOOTSTEP = new AnimationVisualEvent(
            0.5, BlendResourceId.parse("blendlib_authoring:footstep"));
    private static final AnimationVisualEvent IMPACT = new AnimationVisualEvent(
            0.25, BlendResourceId.parse("blendlib_authoring:impact"));
    private static final double EPSILON = 1.0e-6;
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(
            new ModelAnimationLayers.Layer(BASE, 0, AnimationV2LayerMode.OVERRIDE, 1, List.of(), IDLE));

    @Test
    void realExportLoadsAsStrictRigidV1WithThreeNormalizedActionsAndReadableMaterialTextures() throws IOException {
        ModelAsset asset = loadAsset();
        assertEquals(ModelProfile.RIGID_V1, asset.profile());
        assertEquals(1, asset.generation());
        assertEquals(1, asset.unitsPerBlock(), EPSILON);
        assertNull(asset.skeleton());
        assertFalse(asset.primitives().isEmpty());
        assertEquals(Set.of("Idle", "Walk", "Attack"), new HashSet<>(asset.clips().stream()
                .map(AnimationClip::name).toList()));
        assertEquals(3, asset.clips().size());
        for (AnimationClip clip : asset.clips()) {
            assertEquals(1, clip.durationSeconds(), EPSILON, clip.name());
            assertEquals(0, clip.channels().stream().mapToDouble(channel -> channel.times()[0]).min().orElseThrow(),
                    EPSILON, "frame 10 must become clip-local zero in " + clip.name());
        }

        ModelNode root = node(asset, "Root");
        assertEquals(List.of(root.index()), asset.defaultSceneRoots());
        assertEquals(Set.of(node(asset, "Body").index(), node(asset, "Hand").index()),
                new HashSet<>(root.children()));
        assertFalse(asset.materials().isEmpty());
        for (var material : asset.materials().values()) {
            BlendResourceId texture = material.baseColor();
            assertEquals("blendlib_authoring", texture.namespace());
            assertTrue(texture.path().startsWith("textures/blendlib/"));
            var image = ImageIO.read(resourcePath(texture).toFile());
            assertNotNull(image, () -> "Exported material must reference a readable image: " + texture);
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
        }
        var handle = StaticRigidRenderHandle.prepare(MODEL, asset);
        assertEquals(asset.primitives().size(), handle.primitives().size());
        assertFalse(handle.missingModel());
    }

    @Test
    void authoredStateMetadataAndActualWalkPoseSurviveDescriptorAndGlbLoading() {
        ModelAsset asset = loadAsset();
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        assertEquals(IDLE, definition.initialState());
        assertEquals(Set.of(IDLE, WALK, ATTACK), definition.states().keySet());
        assertEquals("Idle", definition.state(IDLE).clip().name());
        assertEquals("Walk", definition.state(WALK).clip().name());
        assertEquals("Attack", definition.state(ATTACK).clip().name());
        for (var key : List.of(IDLE, WALK, ATTACK)) {
            assertEquals(1, definition.state(key).speed(), EPSILON);
        }
        assertTrue(definition.state(IDLE).loop());
        assertTrue(definition.state(WALK).loop());
        assertFalse(definition.state(ATTACK).loop());
        assertNull(definition.state(IDLE).next());
        assertNull(definition.state(WALK).next());
        assertEquals(IDLE, definition.state(ATTACK).next());
        assertEquals(0.1, definition.state(ATTACK).blendSeconds(), EPSILON);
        assertTrue(definition.state(IDLE).events().isEmpty());
        assertEquals(List.of(FOOTSTEP), definition.state(WALK).events());
        assertEquals(List.of(IMPACT), definition.state(ATTACK).events());

        var sampler = PoseSampler.fromModelAsset(asset);
        int body = node(asset, "Body").index();
        assertEquals(0, sampler.sample(definition.state(WALK), 0).transform(body).translation().x(), EPSILON);
        assertEquals(1, sampler.sample(definition.state(WALK), 1).transform(body).translation().x(), EPSILON,
                "frame 34's Blender X translation must be retained in the actual GLB channel");
    }

    @Test
    void controllerDispatchesOnlyCrossedMarkersLoopsWalkAndReturnsOneShotAttackToIdle() {
        ModelAsset asset = loadAsset();
        var instance = BlendInstanceKey.entity("blender-authoring-acceptance", 42);
        var controller = new AnimationController(instance, AnimationControllerDefinition.fromModelAsset(asset));
        var dispatcher = new VisualEventDispatcher();
        var received = new ArrayList<AnimationVisualEvent>();
        java.util.function.DoubleConsumer advance = seconds -> dispatcher.dispatch(
                instance, controller.advance(seconds), (forwardedInstance, event) -> {
                    assertEquals(instance, forwardedInstance);
                    received.add(event);
                });
        assertEquals(IDLE, controller.currentState());
        advance.accept(0.2);
        assertTrue(received.isEmpty());
        controller.trigger(WALK);
        advance.accept(0.49);
        assertTrue(received.isEmpty());
        advance.accept(0.01);
        assertEquals(List.of(FOOTSTEP), received);
        advance.accept(0);
        assertEquals(List.of(FOOTSTEP), received, "repeated extraction cannot replay the same marker");
        advance.accept(0.5);
        assertEquals(WALK, controller.currentState());
        assertEquals(0, controller.currentTimeSeconds(), EPSILON);
        advance.accept(0.5);
        assertEquals(List.of(FOOTSTEP, FOOTSTEP), received);

        controller.trigger(ATTACK);
        assertEquals(WALK, controller.previousState(), "authored attack cross-fade must actually start");
        advance.accept(0.249);
        assertEquals(2, received.size());
        advance.accept(0.001);
        assertEquals(List.of(FOOTSTEP, FOOTSTEP, IMPACT), received);
        assertNull(controller.previousState(), "the 0.1-second cross-fade has finished");
        advance.accept(0.75);
        assertEquals(IDLE, controller.currentState());
        assertEquals(0, controller.currentTimeSeconds(), EPSILON);
        advance.accept(0.2);
        assertEquals(IDLE, controller.currentState());
        assertEquals(0.2, controller.currentTimeSeconds(), EPSILON);
        assertEquals(List.of(FOOTSTEP, FOOTSTEP, IMPACT), received, "one-shot impact must not loop");
    }

    @Test
    void exactAuthoredSocketResolvesThroughTheExportedHierarchyAndSampledPalette() {
        ModelAsset asset = loadAsset();
        var socket = asset.sockets().get(HAND);
        assertNotNull(socket);
        assertEquals("Root/Hand", socket.nodePath());
        assertEquals(node(asset, "Hand").index(), socket.nodeIndex());
        assertEquals(Set.of(HAND), asset.sockets().entries().keySet());
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        var sampler = PoseSampler.fromModelAsset(asset);
        for (var key : List.of(IDLE, WALK, ATTACK)) {
            for (double time : List.of(0.0, 0.25, 0.5, 1.0)) {
                var palette = NodePalette.fromCanonicalScene(sampler.sample(definition.state(key), time),
                        asset.nodes(), asset.defaultSceneRoots());
                assertHand(SocketWorldTransform.query(asset, palette, HAND).orElseThrow());
            }
        }
    }

    @Test
    void exportedSidecarPassesStrictParserAndCannotTargetTheAuthoredOneShot() throws IOException {
        ModelAsset asset = loadAsset();
        LocomotionRules rules = loadRules(asset);
        assertEquals(IDLE, rules.defaultAnimation());
        assertEquals(0, rules.minimumIntervalTicks());
        assertEquals(1, rules.rules().size());
        assertEquals(WALK, rules.rules().getFirst().animation());
        assertEquals(Set.of(new LocomotionCondition.BooleanEquals("grounded", true),
                        new LocomotionCondition.Minimum("speed", 0.1, 0.05)),
                new HashSet<>(rules.rules().getFirst().conditions()));
        String json = Files.readString(sidecarPath());
        assertThrows(IllegalArgumentException.class, () -> LocomotionRuleParser.parse(
                json.replace(WALK.value(), ATTACK.value()).getBytes(StandardCharsets.UTF_8), asset.animationDefinition()));
        assertThrows(IllegalArgumentException.class, () -> LocomotionRuleParser.parse(
                json.replaceFirst("\\{", "{\"unexpected\":true,").getBytes(StandardCharsets.UTF_8),
                asset.animationDefinition()));
    }

    @Test
    void exportedRulesDriveRealClientSelectionHysteresisPlaybackPoseAndVisualEventCallbacks() throws IOException {
        ModelAsset asset = loadAsset();
        var models = new ClientModelRegistry();
        try {
            var handle = StaticRigidRenderHandle.prepare(MODEL, asset);
            models.publish(new ModelRegistryGeneration(1,
                    Map.of(MODEL, new LoadedModelHandle(MODEL, asset, handle)), Map.of(), List.of(),
                    Map.of(MODEL, loadRules(asset))));
            var runtime = new SkinnedAnimationRuntime(models, new ClientAnimationLifecycleBridge(16));
            runtime.onPlayInit();
            var harness = new Harness(runtime, asset, handle);

            var idle = harness.frame(0, 0, true);
            harness.assertPlayback(IDLE, 0);
            assertSame(idle, harness.frame(4, 0, true));
            harness.assertPlayback(IDLE, 0.2);
            assertSame(idle, harness.frame(5, 0.09, true));
            harness.assertPlayback(IDLE, 0.25);
            var walk = harness.frame(6, 0.1, true);
            assertEquals(idle.sequence() + 1, walk.sequence());
            harness.assertPlayback(WALK, 0);
            assertSame(walk, harness.frame(16, 0.075, true));
            harness.assertPlayback(WALK, 0.5);
            assertEquals(List.of(FOOTSTEP), harness.events.stream().map(LayerAnimationVisualEvent::event).toList());
            assertEquals(WALK, harness.events.getFirst().animation());
            assertEquals(BASE, harness.events.getFirst().controllerId());
            assertEquals(0, harness.events.getFirst().loopEpoch());
            assertSame(walk, harness.frame(16, 0.075, true));
            assertEquals(1, harness.events.size(), "same-frame extraction cannot replay a footstep");
            assertSame(walk, harness.frame(17, 0.05, true));
            harness.assertPlayback(WALK, 0.55);
            var stopped = harness.frame(18, 0.049, true);
            assertEquals(walk.sequence() + 1, stopped.sequence());
            harness.assertPlayback(IDLE, 0);
            assertSame(stopped, harness.frame(20, 1, false));
            harness.assertPlayback(IDLE, 0.1);
            var restarted = harness.frame(21, 1, true);
            assertEquals(stopped.sequence() + 1, restarted.sequence());
            harness.assertPlayback(WALK, 0);
            assertEquals(1, runtime.trackedLocomotionCount());
            assertEquals(0, runtime.invalidLocomotionInputDiagnosticCount());
        } finally {
            models.close();
        }
    }

    private static final class Harness {
        private final SkinnedAnimationRuntime runtime;
        private final ModelAsset asset;
        private final StaticRigidRenderHandle handle;
        private final Object source = new Object();
        private final Object owner = new Object();
        private final List<LayerAnimationVisualEvent> events = new ArrayList<>();

        private Harness(SkinnedAnimationRuntime runtime, ModelAsset asset, StaticRigidRenderHandle handle) {
            this.runtime = runtime;
            this.asset = asset;
            this.handle = handle;
        }

        private AnimationV2Command frame(long tick, double speed, boolean grounded) {
            var commands = runtime.captureEntityLocomotionRules(source, owner, 42, MODEL, 1, tick, BASE,
                    () -> new LocomotionInputs(Map.of("grounded", grounded), Map.of("speed", speed)), List::of);
            assertEquals(1, commands.size());
            var input = new SkinnedAnimationRuntimeInput(MODEL, runtime.entityKey(42), tick, 0, IDLE,
                    Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, 0, 0, 0xFFFFFFFF, RenderVisibility.VISIBLE,
                            new CullingMetadata(handle.bounds(), true)));
            var result = runtime.extractLayered(input, LAYERS, commands,
                    AnimationV2LayerWeights.empty(), null, events::add).orElseThrow();
            assertHand(result.frame().socketTransform(HAND).orElseThrow());
            return commands.getFirst();
        }

        private void assertPlayback(BlendAnimationKey state, double time) {
            var snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertEquals(state, snapshot.playheads().get(BASE).state());
            assertEquals(time, snapshot.playheads().get(BASE).timeSeconds(), EPSILON);
            assertTrue(snapshot.diagnostics().stream().allMatch(diagnostic ->
                            diagnostic.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED),
                    () -> "unchanged locomotion commands may only produce the expected duplicate diagnostic: "
                            + snapshot.diagnostics());
            var expected = PoseSampler.fromModelAsset(asset).sample(
                    AnimationControllerDefinition.fromModelAsset(asset).state(state), time);
            var actual = new ModelAnimationLayers(asset, LAYERS).localPose(snapshot.pose());
            assertEquals(expected.transform(node(asset, "Body").index()).translation().x(),
                    actual.transform(node(asset, "Body").index()).translation().x(), EPSILON,
                    "the selected authored clip must contribute its real exported pose");
        }
    }

    private static ModelAsset loadAsset() {
        return new ModelAssetLoader().load(MODEL.resourceId(), 1, resource(MODEL.descriptorResourceId()),
                BlenderAuthoringLocomotionAcceptanceTest::resource);
    }

    private static AssetBytes resource(BlendResourceId id) {
        try {
            return new AssetBytes(id, Files.readAllBytes(resourcePath(id)));
        } catch (IOException exception) {
            throw new UncheckedIOException("Missing genuine Blender export resource " + id, exception);
        }
    }

    private static Path resourcePath(BlendResourceId id) {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent()
                .resolve("test-assets/blender-authoring/exported/assets").resolve(id.namespace()).resolve(id.path());
    }

    private static Path sidecarPath() {
        return resourcePath(BlendResourceId.parse("blendlib_authoring:blend_animation_rules/actor.json"));
    }

    private static LocomotionRules loadRules(ModelAsset asset) throws IOException {
        return LocomotionRuleParser.parse(Files.readAllBytes(sidecarPath()), asset.animationDefinition());
    }

    private static ModelNode node(ModelAsset asset, String name) {
        var matches = asset.nodes().stream().filter(node -> node.name().equals(name)).toList();
        assertEquals(1, matches.size(), "fixture must contain one exact " + name + " node");
        return matches.getFirst();
    }

    private static void assertHand(Transform transform) {
        assertEquals(1, transform.translation().x(), EPSILON);
        assertEquals(1, transform.translation().y(), EPSILON);
        assertEquals(0, transform.translation().z(), EPSILON);
    }
}
