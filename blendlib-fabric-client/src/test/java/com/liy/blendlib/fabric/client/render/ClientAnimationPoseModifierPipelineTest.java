package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeResult;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientAnimationPoseModifierPipelineTest {
    private static final BlendModelKey SKINNED_MODEL = BlendModelKey.parse("modifier_pipeline:skinned");
    private static final BlendModelKey RIGID_MODEL = BlendModelKey.parse("modifier_pipeline:rigid");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("modifier_pipeline:idle");
    private static final BlendResourceId SOCKET = BlendResourceId.parse("modifier_pipeline:tail_socket");
    private static final Quaternion QUARTER_TURN_Z =
            new Quaternion(0.0F, 0.0F, 0.70710677F, 0.70710677F).normalized();

    @Test
    void unconfiguredSkinnedPathRemainsUnmodifiedAndConfiguredPathFeedsCpuSkinning() {
        SkinnedFixture fixture = skinnedFixture(11L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey instanceKey = BlendInstanceKey.entity("modifier-pipeline", 41);

        SkinnedAnimationRuntimeResult base = harness.runtime().extract(
                input(SKINNED_MODEL, instanceKey, 0L, fixture.handle())).orElseThrow();
        AtomicReference<ClientAnimationPoseContext> observedContext = new AtomicReference<>();
        SkinnedAnimationRuntimeResult modified = harness.runtime().extract(
                input(SKINNED_MODEL, instanceKey, 10L, fixture.handle()),
                (context, basePose) -> {
                    observedContext.set(context);
                    return rotate(basePose, context.rig().requireNodeIndex("TailBone"));
                }).orElseThrow();

        assertSame(fixture.handle(), base.frame().renderSnapshot().handle());
        assertEquals(new Vec3(1.0F, 0.0F, 0.0F), firstCapturedPosition(base.frame().renderSnapshot()));
        Vec3 modifiedPosition = firstCapturedPosition(modified.frame().renderSnapshot());
        assertEquals(0.0F, modifiedPosition.x(), 0.00001F);
        assertEquals(1.0F, modifiedPosition.y(), 0.00001F);
        assertEquals(0.0F, modifiedPosition.z(), 0.00001F);
        assertEquals(
                Transform.IDENTITY.rotation(),
                base.frame().socketTransform(SOCKET).orElseThrow().rotation());
        assertEquals(
                QUARTER_TURN_Z,
                modified.frame().socketTransform(SOCKET).orElseThrow().rotation());

        ClientAnimationPoseContext context = observedContext.get();
        assertEquals(instanceKey, context.instanceKey());
        assertEquals(SKINNED_MODEL, context.modelKey());
        assertEquals(11L, context.generation());
        assertEquals(IDLE, context.animationKey());
        assertEquals(0.5D, context.animationTimeSeconds(), 0.0000001D);
        assertEquals(10.0D, context.clientGameTimeInTicks(), 0.0000001D);
        assertEquals(1, context.rig().nodeIndex("TailBone").orElseThrow());
        assertEquals(0, context.rig().parentIndex("TailBone").orElseThrow());
    }

    @Test
    void unconfiguredRigidPathRemainsUnmodifiedAndConfiguredPathFeedsNodePalette() {
        RigidFixture fixture = rigidFixture(12L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey instanceKey = BlendInstanceKey.entity("modifier-pipeline", 42);

        ModelRenderSnapshot base = harness.runtime().extract(
                input(RIGID_MODEL, instanceKey, 0L, fixture.handle()))
                .orElseThrow().frame().renderSnapshot();
        ModelRenderSnapshot modified = harness.runtime().extract(
                input(RIGID_MODEL, instanceKey, 10L, fixture.handle()),
                (context, basePose) -> rotate(basePose, context.rig().requireNodeIndex("RigidRoot")))
                .orElseThrow().frame().renderSnapshot();

        assertSame(fixture.handle(), base.handle());
        assertEquals(Transform.IDENTITY.rotation(), base.rigidNodePalette().nodeTransform(0).rotation());
        assertEquals(QUARTER_TURN_Z, modified.rigidNodePalette().nodeTransform(0).rotation());
    }

    @Test
    void runtimeSourceKeepsCacheModifierAndPaletteCaptureInTheAcceptedOrder() throws IOException {
        Path clientRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java");
        String runtime = Files.readString(clientRoot.resolve(Path.of(
                "com", "liy", "blendlib", "fabric", "client", "animation", "runtime",
                "SkinnedAnimationRuntime.java")));
        int cache = runtime.indexOf("instances.preparePoseSnapshot(");
        int modifier = runtime.indexOf("instances.applyPoseModifier(");
        int paletteCapture = runtime.indexOf("ClientSkinnedExtractionBridge.extract(");
        assertTrue(cache >= 0 && cache < modifier);
        assertTrue(modifier < paletteCapture);

        String bridge = Files.readString(clientRoot.resolve(Path.of(
                "com", "liy", "blendlib", "fabric", "client", "animation", "extract",
                "ClientSkinnedExtractionBridge.java")));
        assertTrue(bridge.indexOf("NodePalette.fromCanonicalScene(") < bridge.indexOf("SkinPalette.from("));
        assertTrue(bridge.indexOf("SkinPalette.from(") < bridge.indexOf("CpuSkinner.skin("));
    }

    private static RuntimeHarness harness() {
        ClientModelRegistry models = new ClientModelRegistry();
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(16);
        return new RuntimeHarness(models, new SkinnedAnimationRuntime(models, lifecycle));
    }

    private static void publish(ClientModelRegistry registry, LoadedModelHandle loaded) {
        registry.publish(new ModelRegistryGeneration(
                loaded.generationId(), Map.of(loaded.key(), loaded), Map.of(), List.of()));
    }

    private static SkinnedAnimationRuntimeInput input(
            BlendModelKey modelKey,
            BlendInstanceKey instanceKey,
            long clientGameTick,
            ModelRenderHandle handle) {
        return new SkinnedAnimationRuntimeInput(
                modelKey,
                instanceKey,
                clientGameTick,
                0.0F,
                IDLE,
                Optional.empty(),
                AnimationUpdateBucket.VISIBLE_NEAR,
                new SkinnedExtractionRequest(
                        Transform.IDENTITY,
                        0x00F000F0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(handle.bounds(), true)));
    }

    private static LocalPose rotate(LocalPose basePose, int nodeIndex) {
        Map<Integer, Transform> transforms = new LinkedHashMap<>(basePose.transforms());
        Transform base = basePose.transform(nodeIndex);
        transforms.put(nodeIndex, new Transform(base.translation(), QUARTER_TURN_Z, base.scale()));
        return new LocalPose(transforms);
    }

    private static Vec3 firstCapturedPosition(ModelRenderSnapshot snapshot) {
        List<Vec3> positions = new ArrayList<>();
        snapshot.skinnedRenderSnapshot().meshes().getFirst().emit(
                (x, y, z, normalX, normalY, normalZ, u, v) -> positions.add(new Vec3(x, y, z)));
        return positions.getFirst();
    }

    private static SkinnedFixture skinnedFixture(long generation) {
        MeshPrimitive geometry = skinnedGeometry("SkinSurface");
        ModelAsset asset = new ModelAsset(
                SKINNED_MODEL.resourceId(),
                SKINNED_MODEL.descriptorResourceId(),
                generation,
                ModelProfile.SKINNED_V1,
                1.0D,
                Map.of("SkinSurface", material("skin")),
                animationDefinition(),
                List.of(
                        new ModelNode(0, "Root", Transform.IDENTITY, List.of(1), 0, 0, false),
                        new ModelNode(1, "TailBone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(new Skin("ModifierSkin", 1, List.of(1), identityMatrix()))),
                List.of(clip(1)),
                new SocketTable(Map.of(
                        SOCKET, new SocketTable.Socket(1, "Root/TailBone"))),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(SKINNED_MODEL, asset);
        return new SkinnedFixture(handle, new LoadedModelHandle(SKINNED_MODEL, asset, handle));
    }

    private static RigidFixture rigidFixture(long generation) {
        MeshPrimitive geometry = rigidGeometry("RigidSurface");
        ModelAsset asset = new ModelAsset(
                RIGID_MODEL.resourceId(),
                RIGID_MODEL.descriptorResourceId(),
                generation,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of("RigidSurface", material("rigid")),
                animationDefinition(),
                List.of(new ModelNode(0, "RigidRoot", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                null,
                List.of(clip(0)),
                new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepare(RIGID_MODEL, asset);
        return new RigidFixture(handle, new LoadedModelHandle(RIGID_MODEL, asset, handle));
    }

    private static AnimationDefinition animationDefinition() {
        return new AnimationDefinition(IDLE.resourceId(), Map.of(
                IDLE.resourceId(), new AnimationStateDefinition(
                        "idle", true, 1.0D, 0.0D, null, List.of())));
    }

    private static AnimationClip clip(int nodeIndex) {
        return new AnimationClip("idle", List.of(new AnimationChannel(
                nodeIndex,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F})));
    }

    private static MeshPrimitive rigidGeometry(String materialSlot) {
        return new MeshPrimitive(
                materialSlot,
                new float[] {1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                null,
                null);
    }

    private static MeshPrimitive skinnedGeometry(String materialSlot) {
        MeshPrimitive rigid = rigidGeometry(materialSlot);
        return new MeshPrimitive(
                materialSlot,
                rigid.positions(),
                rigid.normals(),
                rigid.texCoords(),
                rigid.indices(),
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F});
    }

    private static MaterialDefinition material(String name) {
        return new MaterialDefinition(
                BlendResourceId.parse("modifier_pipeline:textures/" + name + ".png"),
                MaterialDefinition.Mode.OPAQUE,
                false,
                false,
                null);
    }

    private static float[] identityMatrix() {
        return new float[] {
                1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F
        };
    }

    private record RuntimeHarness(ClientModelRegistry models, SkinnedAnimationRuntime runtime) {
    }

    private record SkinnedFixture(SkinnedRenderHandle handle, LoadedModelHandle loaded) {
    }

    private record RigidFixture(StaticRigidRenderHandle handle, LoadedModelHandle loaded) {
    }
}
