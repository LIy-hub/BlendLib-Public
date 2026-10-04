package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.reload.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the production controller/extraction/CPU capture path with deliberately overshooting cubic data. */
class NativeCubicRuntimeIntegrationTest {
    static final BlendModelKey MODEL = BlendModelKey.parse("native_test:actor");
    static final BlendAnimationKey IDLE = key("idle"), PULSE = key("pulse"), RUN = key("run");
    static final BlendResourceId BASE = id("base"), FORWARD = id("forward"), LEFT = id("left"),
            BACK = id("back"), UPPER = id("upper"), SOCKET = id("tip"), STEP = id("step");
    static final List<ModelAnimationLayers.Layer> LAYERS = List.of(
            layer(BASE, IDLE), layer(FORWARD, PULSE), layer(LEFT, RUN), layer(BACK, RUN));
    static final AnimationBlendSpace1D SPACE = new AnimationBlendSpace1D(List.of(
            new AnimationBlendSpace1D.Sample(0, BASE), new AnimationBlendSpace1D.Sample(1, FORWARD),
            new AnimationBlendSpace1D.Sample(2, LEFT)), 2);
    static final AnimationBlendSpace2D DIRECTIONAL = new AnimationBlendSpace2D(BASE, List.of(
            new AnimationBlendSpace2D.Direction(0, FORWARD), new AnimationBlendSpace2D.Direction(2*Math.PI/3, LEFT),
            new AnimationBlendSpace2D.Direction(4*Math.PI/3, BACK)), 1, 2);
    final ClientModelRegistry models = new ClientModelRegistry();
    final ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(16);
    final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models, lifecycle);
    final Object source = new Object(), owner = new Object();
    final List<LayerAnimationVisualEvent> events = new ArrayList<>();

    NativeCubicRuntimeIntegrationTest() { runtime.onPlayInit(); publish(1); }

    @Test void controllerSamplesOvershootIntoExistingCpuMeshAndSocketWithinConservativeBounds() {
        var handle = handle();
        assertTrue(handle.skinned());
        assertTrue(handle.primitives().isEmpty());
        runtime.extract(input(42, 0, PULSE)).orElseThrow();
        for (int tick = 1; tick < 20; tick++) {
            var result = runtime.extract(input(42, tick, PULSE)).orElseThrow();
            double t = tick / 20.0;
            assertPose(result, (float) (8*t*(1-t)*Math.min(1, t/.1)));
            if (tick == 5) assertEquals(STEP, result.advance().visualEvents().getFirst().eventKey());
            for (var position : positions(result.frame().renderSnapshot())) {
                assertInside(handle.bounds(), position);
            }
        }
        assertTrue(handle.bounds().max().x() > 3,
                "bounds must include off-key cubic overshoot beyond the rest/key geometry");
    }

    @Test void maskedAdditiveLayerAndDynamicWeightShareCubicPoseWithFrozenAppearanceCapture() {
        var layers = List.of(layer(BASE, PULSE), new ModelAnimationLayers.Layer(UPPER, 1,
                AnimationV2LayerMode.ADDITIVE, .5F, List.of(new BoneMask.NamedWeight("Bone", .5F)), RUN));
        var commands = List.of(new AnimationV2Command(BASE, PULSE, 1, .5, 1),
                new AnimationV2Command(UPPER, RUN, 1, .25, 1));
        var weights = new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(UPPER, UPPER), .5F));
        var result = runtime.extractLayered(input(42, 0, IDLE), layers, commands, weights, null).orElseThrow();
        assertPose(result, 2.5F); // 2 + 4 * configured .5 * mask .5 * dynamic .5
        var frozen = result.frame().renderSnapshot();
        var appearance = new HashMap<String, MaterialSlotAppearance>();
        appearance.put("Skin", new MaterialSlotAppearance(0x4080FF, false));
        var selected = frozen.withMaterialAppearance(appearance);
        appearance.clear();
        assertSame(frozen.skinnedRenderSnapshot(), selected.skinnedRenderSnapshot());
        assertEquals(new MaterialSlotAppearance(0x4080FF, false), selected.materialAppearance(0));
        assertEquals(MaterialSlotAppearance.unchanged(), frozen.materialAppearance(0));
        runtime.extractLayered(input(42, 4, IDLE), layers, List.of(), null).orElseThrow();
        assertPose(result, 2.5F);
        assertEquals(positions(frozen), positions(selected));
        assertThrows(UnsupportedOperationException.class, () -> result.frame().socketTransforms().clear());
    }

    @Test void crossfadeUsesCubicTargetAndDoesNotMutateEarlierCapture() {
        var layers = List.of(layer(BASE, IDLE));
        runtime.extractLayered(input(42, 0, IDLE), layers, List.of(), null).orElseThrow();
        var command = List.of(new AnimationV2Command(BASE, PULSE, 7, .5, 1));
        var start = runtime.extractLayered(input(42, 0, IDLE), layers, command, null).orElseThrow();
        var half = runtime.extractLayered(input(42, 1, IDLE), layers, command, null).orElseThrow();
        var complete = runtime.extractLayered(input(42, 2, IDLE), layers, command, null).orElseThrow();
        assertPose(start, 0);
        assertPose(half, .99F); // half of cubic at .55 seconds: 8*.55*.45
        assertPose(complete, 1.92F);
        assertPose(half, .99F);
        assertEquals(7, runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(BASE).acceptedSequence());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cubicBlendspacesIntegrateDynamicCadenceKeepPhasesAndDeliverOnlyActiveLayerEvents(boolean directional) {
        frame(directional, 42, 0, 1);
        var quarter = frame(directional, 42, 10, 2);
        assertPose(quarter, 1.5F);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().allMatch(event -> event.controllerId().equals(FORWARD)));
        assertTrue(events.stream().allMatch(event -> event.event().eventKey().equals(STEP)));
        events.clear();
        var threeQuarters = frame(directional, 42, 20, .5);
        assertPose(threeQuarters, 1.5F);
        var snapshot = runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
        for (var member : directional ? DIRECTIONAL.memberLayerIds() : SPACE.memberLayerIds()) {
            double duration = member.equals(BASE) ? 2 : member.equals(FORWARD) ? 1 : .5;
            assertEquals(.75, snapshot.playheads().get(member).timeSeconds()/duration, 1e-9);
            assertEquals(0, snapshot.playheads().get(member).acceptedSequence());
        }
        var independent = frame(directional, 43, 20, 1);
        assertPose(independent, 0);
        assertPose(quarter, 1.5F);
        assertPose(frame(directional, 42, 30, 1), .875F);
    }

    @Test void reloadAndLifecycleRebindCubicDataWhileRejectingStaleCaptureHandles() {
        frame(true, 42, 0, 1);
        var frozen = frame(true, 42, 10, 2).frame().renderSnapshot();
        var oldHandle = handle();
        publish(2);
        assertTrue(runtime.layeredSnapshot(runtime.entityKey(42)).isEmpty());
        events.clear();
        var fresh = frame(true, 42, 20, 1);
        assertPose(fresh, 1.5F);
        assertTrue(events.isEmpty(), "reload silently baselines event cursors");
        assertNotSame(oldHandle, handle());
        assertEquals(2, fresh.frame().renderSnapshot().handle().generation());
        assertThrows(IllegalArgumentException.class, () -> ModelRenderSnapshot.skinned(handle(),
                frozen.rootTransform(), frozen.packedLight(), frozen.packedOverlay(), frozen.tintArgb(),
                frozen.visibility(), frozen.culling(), frozen.skinnedRenderSnapshot()));
        assertEquals(1.5F, positions(frozen).getFirst().x(), 1e-6F);
        for (String reset : List.of("unload", "retire", "disconnect", "play_init")) {
            switch (reset) {
                case "unload" -> runtime.onEntityUnload(42);
                case "retire" -> runtime.retire(runtime.entityKey(42));
                case "disconnect" -> { runtime.onWorldDisconnect(); runtime.onPlayInit(); }
                case "play_init" -> runtime.onPlayInit();
            }
            assertPose(frame(true, 42, 100, 1), 0);
            assertPose(frame(true, 42, 110, 1), 1.5F);
        }
    }

    private SkinnedAnimationRuntimeResult frame(boolean directional, int entity, long tick, double cadence) {
        if (directional) return runtime.extractBlendSpace2D(input(entity,tick,IDLE), LAYERS, List.of(),
                AnimationV2LayerWeights.empty(), DIRECTIONAL, new AnimationBlendSpace2D.Input(1,0), cadence,
                source, owner, null, events::add).orElseThrow();
        return runtime.extractBlendSpace(input(entity,tick,IDLE), LAYERS.subList(0,3), List.of(),
                AnimationV2LayerWeights.empty(), SPACE, 1, cadence, source, owner, null, events::add).orElseThrow();
    }
    private SkinnedAnimationRuntimeInput input(int entity, long tick, BlendAnimationKey fallback) {
        return new SkinnedAnimationRuntimeInput(MODEL, runtime.entityKey(entity), tick, 0, fallback, Optional.empty(),
                AnimationUpdateBucket.VISIBLE_NEAR, new SkinnedExtractionRequest(Transform.IDENTITY, 0xA000B, 7,
                0xFFFFFFFF, RenderVisibility.VISIBLE, new CullingMetadata(handle().bounds(), true)));
    }
    private SkinnedRenderHandle handle() { return (SkinnedRenderHandle) models.find(MODEL).orElseThrow().renderHandle(); }
    private void publish(long generation) {
        var geometry = new MeshPrimitive("Skin", new float[]{0,0,0,1,0,0,0,1,0},
                new float[]{0,0,1,0,0,1,0,0,1}, new float[]{0,0,1,0,0,1}, new int[]{0,1,2},
                new int[12], new float[]{1,0,0,0,1,0,0,0,1,0,0,0});
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), generation,
                ModelProfile.SKINNED_CUBIC_V1, 1, Map.of("Skin", new MaterialDefinition(id("textures/skin.png"),
                MaterialDefinition.Mode.OPAQUE, false, false, null)),
                new AnimationDefinition(IDLE.resourceId(), Map.of(IDLE.resourceId(), state("idle",2),
                        PULSE.resourceId(), state("pulse",1), RUN.resourceId(), state("run",.5))),
                List.of(new ModelNode(0,"Mesh",Transform.IDENTITY,List.of(1),0,0,false),
                        new ModelNode(1,"Bone",Transform.IDENTITY,List.of(),-1,-1,false)), List.of(0),
                List.of(new ModelPrimitive(0,0,0,geometry)), new Skeleton(List.of(new Skin("Rig",1,List.of(1),
                        new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}))),
                List.of(clip("idle",2,0),clip("pulse",1,1),clip("run",.5F,2)),
                new SocketTable(Map.of(SOCKET,new SocketTable.Socket(1,"Mesh/Bone"))),
                Bounds.fromPositions(geometry.positions()), List.of());
        var loaded = new LoadedModelHandle(MODEL,asset,SkinnedRenderHandle.prepare(MODEL,asset));
        models.publish(new ModelRegistryGeneration(generation, Map.of(MODEL,loaded), Map.of(), List.of()));
    }
    private static AnimationStateDefinition state(String clip, double duration) {
        return new AnimationStateDefinition(clip,true,1,.1,null,List.of(new AnimationEventDefinition(duration*.25,STEP)));
    }
    private static AnimationClip clip(String name,float duration,float amplitude) {
        float derivative = 8*amplitude/duration;
        return new AnimationClip(name,List.of(AnimationChannel.forCubicProfile(1,AnimationPath.TRANSLATION,
                Interpolation.CUBICSPLINE,new float[]{0,duration},new float[6],
                new float[]{0,0,0,-derivative,0,0},new float[]{derivative,0,0,0,0,0})));
    }
    private static ModelAnimationLayers.Layer layer(BlendResourceId id,BlendAnimationKey state) {
        return new ModelAnimationLayers.Layer(id,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),state);
    }
    private static BlendResourceId id(String path) { return BlendResourceId.parse("native_test:"+path); }
    private static BlendAnimationKey key(String path) { return BlendAnimationKey.parse("native_test:"+path); }
    private static void assertPose(SkinnedAnimationRuntimeResult result,float x) {
        assertEquals(x,result.frame().socketTransform(SOCKET).orElseThrow().translation().x(),1e-5F);
        var vertices = positions(result.frame().renderSnapshot());
        assertEquals(4,vertices.size());
        assertEquals(x,vertices.get(0).x(),1e-5F);
        assertEquals(x+1,vertices.get(1).x(),1e-5F);
        assertEquals(1,vertices.get(2).y(),1e-5F);
    }
    private static List<Vec3> positions(ModelRenderSnapshot frame) {
        var vertices = new ArrayList<Vec3>();
        frame.skinnedRenderSnapshot().meshes().forEach(mesh -> mesh.emit((x,y,z,nx,ny,nz,u,v) -> vertices.add(new Vec3(x,y,z))));
        return List.copyOf(vertices);
    }
    private static void assertInside(Bounds bounds,Vec3 p) {
        assertTrue(p.x()>=bounds.min().x() && p.x()<=bounds.max().x());
        assertTrue(p.y()>=bounds.min().y() && p.y()<=bounds.max().y());
        assertTrue(p.z()>=bounds.min().z() && p.z()<=bounds.max().z());
    }
}
