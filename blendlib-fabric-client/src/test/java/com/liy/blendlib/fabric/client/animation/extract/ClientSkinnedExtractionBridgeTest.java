package com.liy.blendlib.fabric.client.animation.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationState;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistry;
import com.liy.blendlib.fabric.client.animation.ClientAnimationPoseSnapshot;
import com.liy.blendlib.fabric.client.animation.PoseCacheKey;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientSkinnedExtractionBridgeTest {
    private static final BlendModelKey SKINNED_MODEL = BlendModelKey.parse("bridge_test:skinned/actor");
    private static final BlendModelKey RIGID_MODEL = BlendModelKey.parse("bridge_test:rigid/prop");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("bridge_test:idle");
    private static final BlendResourceId SOCKET = BlendResourceId.parse("bridge_test:hand");
    private static final BlendResourceId UNKNOWN_SOCKET = BlendResourceId.parse("bridge_test:unknown");

    @Test
    void extractsCpuSkinnedFrameFromTheCurrentCanonicalScenePoseAndSocketPalette() {
        SkinnedFixture fixture = skinnedFixture(17L);
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        ClientAnimationPoseSnapshot pose = bindAndSample(registry, fixture.asset(), SKINNED_MODEL, fixture.clip(), 7);

        ClientSkinnedExtractionFrame frame = ClientSkinnedExtractionBridge.extract(
                registry, fixture.loaded(), pose, request(fixture.handle()));

        assertSame(fixture.handle(), frame.renderSnapshot().handle());
        assertEquals(SKINNED_MODEL, frame.renderSnapshot().handle().modelKey());
        assertEquals(17L, frame.renderSnapshot().generation());
        assertTrue(frame.renderSnapshot().handle().skinned());
        assertEquals(1, frame.socketTransforms().size());
        assertEquals(
                new Transform(new Vec3(1.0f, 2.0f, 0.0f), Transform.IDENTITY.rotation(), Vec3.ONE),
                frame.socketTransform(SOCKET).orElseThrow());
        assertFalse(frame.socketTransform(UNKNOWN_SOCKET).isPresent());

        // Node 2 structurally parents default-scene root 0 but is outside that scene. The socket
        // would be translated by +50 on X if the bridge used the all-structure palette instead.
        assertEquals(1.0f, frame.socketTransform(SOCKET).orElseThrow().translation().x());
        assertThrows(UnsupportedOperationException.class,
                () -> frame.socketTransforms().put(UNKNOWN_SOCKET, Transform.IDENTITY));
    }

    @Test
    void extractionFrameDefensivelyCopiesItsSocketTransformMap() {
        SkinnedFixture fixture = skinnedFixture(17L);
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        ClientAnimationPoseSnapshot pose = bindAndSample(registry, fixture.asset(), SKINNED_MODEL, fixture.clip(), 7);
        ClientSkinnedExtractionFrame extracted = ClientSkinnedExtractionBridge.extract(
                registry, fixture.loaded(), pose, request(fixture.handle()));
        Transform retained = extracted.socketTransform(SOCKET).orElseThrow();
        Map<BlendResourceId, Transform> mutable = new LinkedHashMap<>();
        mutable.put(SOCKET, retained);

        ClientSkinnedExtractionFrame copied = new ClientSkinnedExtractionFrame(extracted.renderSnapshot(), mutable);
        mutable.put(SOCKET, Transform.IDENTITY);
        mutable.put(UNKNOWN_SOCKET, Transform.IDENTITY);

        assertEquals(retained, copied.socketTransform(SOCKET).orElseThrow());
        assertFalse(copied.socketTransform(UNKNOWN_SOCKET).isPresent());
        assertThrows(UnsupportedOperationException.class,
                () -> copied.socketTransforms().put(UNKNOWN_SOCKET, Transform.IDENTITY));
    }

    @Test
    void rejectsStalePoseBeforeItCanBeCombinedWithCpuSkinningData() {
        SkinnedFixture fixture = skinnedFixture(17L);
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        ClientAnimationPoseSnapshot stale = bindAndSample(registry, fixture.asset(), SKINNED_MODEL, fixture.clip(), 8);
        BlendInstanceKey instanceKey = stale.instanceKey();
        registry.bind(instanceKey, SKINNED_MODEL, 18L, definition(fixture.clip()));

        assertThrows(IllegalArgumentException.class, () -> ClientSkinnedExtractionBridge.extract(
                registry, fixture.loaded(), stale, request(fixture.handle())));
    }

    @Test
    void extractsRigidPaletteFrameInsteadOfFallingBackToRestGeometry() {
        RigidFixture fixture = rigidFixture(23L);
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        ClientAnimationPoseSnapshot pose = bindAndSample(registry, fixture.asset(), RIGID_MODEL, fixture.clip(), 9);

        ClientSkinnedExtractionFrame frame = ClientSkinnedExtractionBridge.extract(
                registry, fixture.loaded(), pose, request(fixture.handle()));

        assertSame(fixture.handle(), frame.renderSnapshot().handle());
        assertFalse(frame.renderSnapshot().handle().skinned());
        assertEquals(RIGID_MODEL, frame.renderSnapshot().handle().modelKey());
        assertEquals(23L, frame.renderSnapshot().generation());
        assertTrue(frame.socketTransforms().isEmpty());
    }

    private static ClientAnimationPoseSnapshot bindAndSample(
            ClientAnimationInstanceRegistry registry,
            ModelAsset asset,
            BlendModelKey modelKey,
            AnimationClip clip,
            int entityId) {
        BlendInstanceKey instanceKey = BlendInstanceKey.entity("bridge-session", entityId);
        registry.bind(instanceKey, modelKey, asset.generation(), definition(clip));
        return registry.preparePoseSnapshot(
                new PoseCacheKey(instanceKey, modelKey, asset.generation(), IDLE, 1L),
                PoseSampler.fromModelAsset(asset));
    }

    private static SkinnedExtractionRequest request(SkinnedRenderHandle handle) {
        return new SkinnedExtractionRequest(
                Transform.IDENTITY,
                0x000A000B,
                7,
                0xFFFFFFFF,
                com.liy.blendlib.fabric.client.render.RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }

    private static SkinnedExtractionRequest request(StaticRigidRenderHandle handle) {
        return new SkinnedExtractionRequest(
                Transform.IDENTITY,
                0x000A000B,
                7,
                0xFFFFFFFF,
                com.liy.blendlib.fabric.client.render.RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }

    private static SkinnedFixture skinnedFixture(long generation) {
        MeshPrimitive geometry = new MeshPrimitive(
                "SkinSurface",
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f});
        AnimationClip clip = clip(1, 0.0f, 2.0f, 0.0f);
        ModelAsset asset = new ModelAsset(
                SKINNED_MODEL.resourceId(),
                SKINNED_MODEL.descriptorResourceId(),
                generation,
                ModelProfile.SKINNED_V1,
                1.0d,
                Map.of("SkinSurface", material("skin")),
                null,
                List.of(
                        new ModelNode(0, "SceneMesh", translation(1.0f, 0.0f, 0.0f), List.of(1), 0, 0, false),
                        new ModelNode(1, "Bone", translation(0.0f, 2.0f, 0.0f), List.of(), -1, -1, false),
                        new ModelNode(2, "DetachedStructuralParent", translation(50.0f, 0.0f, 0.0f), List.of(0), -1, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(new Skin("BridgeSkin", 1, List.of(1), identityMatrix()))),
                List.of(clip),
                new SocketTable(Map.of(SOCKET, new SocketTable.Socket(1, "SceneMesh/Bone"))),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(SKINNED_MODEL, asset);
        return new SkinnedFixture(asset, handle, new LoadedModelHandle(SKINNED_MODEL, asset, handle), clip);
    }

    private static RigidFixture rigidFixture(long generation) {
        MeshPrimitive geometry = new MeshPrimitive(
                "RigidSurface",
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                null,
                null);
        AnimationClip clip = clip(0, 0.25f, 0.0f, 0.0f);
        ModelAsset asset = new ModelAsset(
                RIGID_MODEL.resourceId(),
                RIGID_MODEL.descriptorResourceId(),
                generation,
                ModelProfile.RIGID_V1,
                1.0d,
                Map.of("RigidSurface", material("rigid")),
                null,
                List.of(new ModelNode(0, "RigidRoot", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                null,
                List.of(clip),
                new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepare(RIGID_MODEL, asset);
        return new RigidFixture(asset, handle, new LoadedModelHandle(RIGID_MODEL, asset, handle), clip);
    }

    private static AnimationControllerDefinition definition(AnimationClip clip) {
        AnimationState state = new AnimationState(IDLE, clip, true, 1.0d, 0.0d, null, List.of());
        return new AnimationControllerDefinition(IDLE, Map.of(IDLE, state));
    }

    private static AnimationClip clip(int nodeIndex, float x, float y, float z) {
        return new AnimationClip("idle", List.of(new AnimationChannel(
                nodeIndex,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0f, 1.0f},
                new float[] {x, y, z, x, y, z})));
    }

    private static MaterialDefinition material(String name) {
        return new MaterialDefinition(
                BlendResourceId.parse("bridge_test:textures/" + name + ".png"),
                MaterialDefinition.Mode.OPAQUE,
                false,
                false,
                null);
    }

    private static Transform translation(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), Transform.IDENTITY.rotation(), Vec3.ONE);
    }

    private static float[] identityMatrix() {
        return new float[] {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private record SkinnedFixture(
            ModelAsset asset,
            SkinnedRenderHandle handle,
            LoadedModelHandle loaded,
            AnimationClip clip) {
    }

    private record RigidFixture(
            ModelAsset asset,
            StaticRigidRenderHandle handle,
            LoadedModelHandle loaded,
            AnimationClip clip) {
    }
}
