package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.entity.BlendEntityLocomotionContractTest.allocate;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.animation.rules.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/** Real entity snapshot capture must not revive a binding cancelled inside consumer callbacks. */
@Isolated("Temporarily installs a CPU-only client service facade and restores it")
class BlendEntityLocomotionLifecycleTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("locomotion:actor");
    private static final BlendResourceId BASE = BlendResourceId.parse("locomotion:base");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("locomotion:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("locomotion:walk");
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(
            new ModelAnimationLayers.Layer(BASE, 0, AnimationV2LayerMode.OVERRIDE, 1, List.of(), IDLE));

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void lifecycleMutationFromLocomotionInputsAbandonsWholeFactoryFrameAndNeverRebindsRuntime() throws Exception {
        for (String mutation : List.of("unload", "retire", "disconnect", "play_init", "reload")) {
            withServices((models, lifecycle, runtime) -> {
                models.publish(generation(1));
                Entity entity = allocate(Marker.class);
                entity.setId(42);
                var oldKey = runtime.entityKey(42);
                int[] calls = {0};
                var factory = factory((ignored, request) -> {
                    calls[0]++;
                    switch (mutation) {
                        case "unload" -> runtime.onEntityUnload(42);
                        case "retire" -> runtime.retire(oldKey);
                        case "disconnect" -> runtime.onWorldDisconnect();
                        case "play_init" -> runtime.onPlayInit();
                        case "reload" -> models.publish(generation(2));
                    }
                    return inputs(1);
                });
                var result = factory.create(entity, request(0));
                assertEquals(1, calls[0], mutation);
                assertTrue(result.handle().missingModel(), mutation);
                assertEquals(0, lifecycle.registry().size(), "cancelled frame rebound runtime: " + mutation);
                assertTrue(runtime.layeredSnapshot(oldKey).isEmpty(), mutation);
                runtime.onPlayInit();
                var fresh = factory((ignored, request) -> inputs(1)).create(entity, request(1));
                assertFalse(fresh.handle().missingModel(), "fresh frame must recover: " + mutation);
                assertEquals(1, lifecycle.registry().size(), mutation);
                assertEquals(WALK, runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(BASE).state());
            });
        }
    }

    @Test
    void replacementSourceAndEqualNumericIdOwnerProduceUsableFirstFactoryFrameWithoutOldPlayback() throws Exception {
        withServices((models, lifecycle, runtime) -> {
            models.publish(generation(1));
            Entity firstOwner = allocate(Marker.class);
            firstOwner.setId(42);
            Entity nextOwner = allocate(Marker.class);
            nextOwner.setId(42);
            var firstFactory = factory((ignored, request) -> inputs(request.clientGameTick() == 0 ? 0 : 1));
            assertFalse(firstFactory.create(firstOwner, request(0)).handle().missingModel());
            assertFalse(firstFactory.create(firstOwner, request(1)).handle().missingModel());
            assertFalse(firstFactory.create(firstOwner, request(5)).handle().missingModel());
            var prior = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertTrue(prior.playheads().get(BASE).acceptedSequence() > 0);
            assertEquals(0.2, prior.playheads().get(BASE).timeSeconds(), 1e-9);
            var nextFactory = factory((ignored, request) -> inputs(0));
            assertFalse(nextFactory.create(firstOwner, request(6)).handle().missingModel(), "replacement source first frame");
            assertEquals(IDLE, runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(BASE).state());
            assertFalse(firstFactory.create(nextOwner, request(7)).handle().missingModel(), "replacement owner first frame");
            var fresh = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(BASE);
            assertEquals(WALK, fresh.state());
            assertEquals(0, fresh.acceptedSequence());
            assertEquals(0, fresh.timeSeconds(), 1e-9);
            assertEquals(1, lifecycle.registry().size());
        });
    }

    private static SkinnedAnimationEntitySnapshotFactory<Entity> factory(BlendEntityLocomotionInputs<Entity> inputs) {
        return new SkinnedAnimationEntitySnapshotFactory<>(MODEL, (entity, request) -> IDLE,
                null, null, null, (entity, request) -> BlendEntityRotation.IDENTITY, null, LAYERS,
                BlendEntityRendererBuilder.captureLocomotionCommands((entity, request) -> List.of(), BASE, inputs),
                null, null, null);
    }

    private static BlendEntitySnapshotRequest request(long tick) {
        return new BlendEntitySnapshotRequest(MODEL, 0, 0, tick, 0, 0, 0, tick, true, 0);
    }

    private static LocomotionInputs inputs(double speed) { return new LocomotionInputs(Map.of(), Map.of("speed", speed)); }

    private static ModelRegistryGeneration generation(long generation) {
        var definition = new AnimationDefinition(IDLE.resourceId(), Map.of(
                IDLE.resourceId(), new AnimationStateDefinition("idle", true, 1, 0, null, List.of()),
                WALK.resourceId(), new AnimationStateDefinition("walk", true, 1, 0, null, List.of())));
        var rules = LocomotionRuleParser.parse("""
                {"schema_version":1,"default":"locomotion:idle","rules":[
                  {"animation":"locomotion:walk","conditions":[{"input":"speed","enter_min":1,"exit_min":0.5}]}]}
                """.getBytes(StandardCharsets.UTF_8), definition);
        var geometry = new MeshPrimitive("Skin", new float[] {0,0,0, 1,0,0, 0,1,0},
                new float[] {0,0,1, 0,0,1, 0,0,1}, new float[] {0,0, 1,0, 0,1}, new int[] {0,1,2},
                new int[12], new float[] {1,0,0,0, 1,0,0,0, 1,0,0,0});
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), generation,
                ModelProfile.SKINNED_V1, 1, Map.of("Skin", new MaterialDefinition(
                        BlendResourceId.parse("locomotion:textures/skin.png"), MaterialDefinition.Mode.OPAQUE, false, false, null)),
                definition, List.of(new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0), List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(new Skin("Rig", 1, List.of(1), new float[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}))),
                List.of(clip("idle", 0), clip("walk", 1)), new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()), List.of());
        var loaded = new LoadedModelHandle(MODEL, asset, SkinnedRenderHandle.prepare(MODEL, asset));
        return new ModelRegistryGeneration(generation, Map.of(MODEL, loaded), Map.of(), List.of(), Map.of(MODEL, rules));
    }

    private static AnimationClip clip(String name, float endX) {
        return new AnimationClip(name, List.of(new AnimationChannel(1, AnimationPath.TRANSLATION,
                Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, endX, 0, 0})));
    }

    @SuppressWarnings("unchecked")
    private static void withServices(CheckedTest test) throws Exception {
        Field field = BlendLibClientServices.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        AtomicReference<Object> active = (AtomicReference<Object>) field.get(null);
        Object previous = active.getAndSet(null);
        try {
            var registry = new ClientModelRegistry();
            var lifecycle = new ClientAnimationLifecycleBridge(16);
            var runtime = new SkinnedAnimationRuntime(registry, lifecycle);
            runtime.onPlayInit();
            BlendLibClientServices.initialize(registry, (snapshot, context) -> { throw new AssertionError("unexpected submit"); }, runtime);
            test.run(registry, lifecycle, runtime);
        } finally {
            active.set(previous);
        }
    }

    @FunctionalInterface
    private interface CheckedTest {
        void run(ClientModelRegistry registry, ClientAnimationLifecycleBridge lifecycle, SkinnedAnimationRuntime runtime) throws Exception;
    }
}
