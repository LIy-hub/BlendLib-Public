package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
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
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SkinnedRenderBackendContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("render_test:skinned/example");
    private static final BlendModelKey OTHER_KEY = BlendModelKey.parse("render_test:skinned/other");

    @Test
    void skinnedHandlePreparesOnlyStrictSkinnedGeometryAndReusesExactP4MaterialRouting() {
        Fixture fixture = fixture(KEY, 13L, material(MaterialDefinition.Mode.CUTOUT, false));
        SkinnedRenderHandle handle = fixture.handle();

        assertTrue(handle.skinned());
        assertTrue(handle.primitives().isEmpty());
        assertEquals(1, handle.skinnedPrimitives().size());
        PreparedSkinnedRenderPrimitive primitive = handle.skinnedPrimitives().getFirst();
        assertEquals(0, primitive.nodeIndex());
        assertEquals(0, primitive.skinIndex());
        assertEquals(RenderLayer.CUTOUT, primitive.material().layer());
        assertEquals(
                Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_CUTOUT_CULL,
                Minecraft2612StaticRigidRenderBackend.renderTypePathFor(primitive.material()));

        assertThrows(
                UnsupportedRenderMaterialException.class,
                () -> fixture(KEY, 13L, material(MaterialDefinition.Mode.ADDITIVE, false)));
    }

    @Test
    void capturedCpuOutputExpandsStrictTrianglesForTheQuadPipelineWithoutSubmitTimeCopies() {
        Fixture fixture = fixture(KEY, 13L, material(MaterialDefinition.Mode.CUTOUT, false));
        SkinnedRenderSnapshot skinned = SkinnedRenderSnapshot.capture(fixture.handle(), List.of(fixture.output()));
        ModelRenderSnapshot snapshot = ModelRenderSnapshot.skinned(
                fixture.handle(),
                Transform.IDENTITY,
                0x000A000B,
                7,
                0xFF80FF80,
                RenderVisibility.VISIBLE,
                new CullingMetadata(fixture.handle().bounds(), true),
                skinned);

        assertSame(skinned, snapshot.skinnedRenderSnapshot());
        SkinnedMeshSnapshot mesh = skinned.meshes().getFirst();
        assertEquals(3, mesh.vertexCount());
        assertEquals(3, mesh.indexCount());
        assertEquals(RenderLayer.CUTOUT, mesh.material().layer());

        List<CapturedVertex> emitted = emitted(mesh);
        assertEquals(4, emitted.size());
        assertVertex(emitted.get(0), 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.50f, 0.60f);
        assertVertex(emitted.get(1), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.10f, 0.20f);
        assertVertex(emitted.get(2), 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.30f, 0.40f);
        assertVertex(emitted.get(3), 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.30f, 0.40f);

        float[] callerOwnedCopy = fixture.output().positions();
        callerOwnedCopy[0] = 99.0f;
        assertVertex(emitted(mesh).get(1), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.10f, 0.20f);
    }

    @Test
    void staleHandleOrForeignCpuTopologyIsRejectedBeforeRenderSubmit() {
        Fixture original = fixture(KEY, 13L, material(MaterialDefinition.Mode.OPAQUE, false));
        Fixture newerGeneration = fixture(KEY, 14L, material(MaterialDefinition.Mode.OPAQUE, false));
        Fixture otherModel = fixture(OTHER_KEY, 13L, material(MaterialDefinition.Mode.OPAQUE, false));
        SkinnedRenderSnapshot originalSnapshot = SkinnedRenderSnapshot.capture(original.handle(), List.of(original.output()));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRenderSnapshot.skinned(
                        newerGeneration.handle(),
                        Transform.IDENTITY,
                        0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(newerGeneration.handle().bounds(), true),
                        originalSnapshot));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRenderSnapshot.skinned(
                        otherModel.handle(),
                        Transform.IDENTITY,
                        0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(otherModel.handle().bounds(), true),
                        originalSnapshot));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkinnedRenderSnapshot.capture(original.handle(), List.of(newerGeneration.output())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelRenderSnapshot(
                        original.handle(),
                        Transform.IDENTITY,
                        0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(original.handle().bounds(), true)));
    }

    @Test
    void presentationSocketMarkerBindsOnlyOneCapturedSocketAndPreservesSkinnedSnapshotIdentity() {
        Fixture fixture = fixture(KEY, 13L, material(MaterialDefinition.Mode.OPAQUE, false), 2.0d);
        SkinnedRenderSnapshot skinned = SkinnedRenderSnapshot.capture(fixture.handle(), List.of(fixture.output()));
        ModelRenderSnapshot captured = ModelRenderSnapshot.skinned(
                fixture.handle(),
                translation(10.0f, 20.0f, 30.0f),
                0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(fixture.handle().bounds(), true),
                skinned);
        Transform tip = translation(4.0f, 6.0f, 8.0f);
        BlendResourceId tipKey = BlendResourceId.parse("render_test:tip");
        BlendResourceId unknownKey = BlendResourceId.parse("render_test:unknown");
        ClientSkinnedExtractionFrame frame = new ClientSkinnedExtractionFrame(captured, Map.of(tipKey, tip));

        ModelRenderSnapshot marked = frame.socketTransform(tipKey)
                .map(captured::withPresentationSocketTransform)
                .orElse(captured);
        ModelRenderSnapshot unknown = frame.socketTransform(unknownKey)
                .map(captured::withPresentationSocketTransform)
                .orElse(captured);

        assertSame(captured.handle(), marked.handle());
        assertEquals(captured.generation(), marked.generation());
        assertSame(captured.skinnedRenderSnapshot(), marked.skinnedRenderSnapshot());
        assertEquals(tip, marked.presentationSocketTransformOrNull());
        assertTrue(SkinnedSocketMarkerSubmitter.hasPreparedMarker(marked));
        assertSame(captured, unknown);
        assertFalse(SkinnedSocketMarkerSubmitter.hasPreparedMarker(unknown));

        ModelRenderSnapshot culled = ModelRenderSnapshot.skinned(
                        fixture.handle(),
                        Transform.IDENTITY,
                        0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.CULLED,
                        new CullingMetadata(fixture.handle().bounds(), true),
                        skinned)
                .withPresentationSocketTransform(tip);
        assertFalse(SkinnedSocketMarkerSubmitter.hasPreparedMarker(culled));

        PoseStack poseStack = new PoseStack();
        SkinnedSocketMarkerSubmitter.applyMarkerTransform(
                poseStack, marked, marked.presentationSocketTransformOrNull());
        Vector3f transformedOrigin = poseStack.last().pose().transformPosition(new Vector3f());
        assertEquals(12.0f, transformedOrigin.x(), 0.0001f);
        assertEquals(23.0f, transformedOrigin.y(), 0.0001f);
        assertEquals(34.0f, transformedOrigin.z(), 0.0001f);

        MissingModelRenderHandle missing = new MissingModelRenderHandle(KEY, 13L);
        ModelRenderSnapshot missingSnapshot = new ModelRenderSnapshot(
                missing,
                Transform.IDENTITY,
                0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(missing.bounds(), true));
        assertThrows(IllegalStateException.class, () -> missingSnapshot.withPresentationSocketTransform(tip));
    }

    @Test
    void minecraft2612LinesRejectsVertexWithoutLineWidth() {
        assertEquals(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, RenderTypes.lines().format());
        assertEquals(VertexFormat.Mode.LINES, RenderTypes.lines().mode());

        try (ByteBufferBuilder backingBuffer = new ByteBufferBuilder(256)) {
            BufferBuilder consumer = new BufferBuilder(
                    backingBuffer, RenderTypes.lines().mode(), RenderTypes.lines().format());
            consumer.addVertex(0.0F, 0.0F, 0.0F)
                    .setColor(0xFFFFFFFF)
                    .setNormal(1.0F, 0.0F, 0.0F);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> consumer.addVertex(1.0F, 0.0F, 0.0F));
            assertTrue(failure.getMessage().contains("LineWidth"), failure::getMessage);
        }
    }

    @Test
    void presentationSocketMarkerCompletesEveryMinecraft2612LineVertex() {
        Fixture fixture = fixture(KEY, 13L, material(MaterialDefinition.Mode.OPAQUE, false));
        SkinnedRenderSnapshot skinned = SkinnedRenderSnapshot.capture(fixture.handle(), List.of(fixture.output()));
        ModelRenderSnapshot marked = ModelRenderSnapshot.skinned(
                        fixture.handle(),
                        Transform.IDENTITY,
                        0,
                        0,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(fixture.handle().bounds(), true),
                        skinned)
                .withPresentationSocketTransform(translation(1.0F, 2.0F, 3.0F));
        SubmitNodeStorage collector = new SubmitNodeStorage();
        SkinnedSocketMarkerSubmitter.submit(marked, new PoseStack(), collector);

        SubmitNodeCollection submits = collector.getSubmitsPerOrder().get(0);
        assertTrue(submits.wasUsed());
        try (ByteBufferBuilder backingBuffer = new ByteBufferBuilder(256)) {
            BufferBuilder consumer = new BufferBuilder(
                    backingBuffer, RenderTypes.lines().mode(), RenderTypes.lines().format());
            new CustomFeatureRenderer().renderTranslucent(
                    submits, new SingleConsumerBufferSource(backingBuffer, consumer));
            try (MeshData mesh = consumer.buildOrThrow()) {
                assertEquals(12, mesh.drawState().vertexCount());
                assertEquals(RenderTypes.lines().format(), mesh.drawState().format());
                assertEquals(RenderTypes.lines().mode(), mesh.drawState().mode());
            }
        }
    }

    @Test
    void backendSubmitPathConsumesOnlyCapturedSkinnedSnapshot() throws IOException {
        String backend = Files.readString(renderSource("Minecraft2612StaticRigidRenderBackend.java"));
        String marker = Files.readString(renderSource("SkinnedSocketMarkerSubmitter.java"));

        assertTrue(backend.contains("snapshot.skinnedRenderSnapshot()"));
        assertTrue(backend.contains("submitCapturedSkinned"));
        assertTrue(backend.contains("mesh.emit("));
        for (String forbidden : List.of(
                "CpuSkinner",
                "SkinPalette",
                "ModelAssetLoader",
                "AssetResolver",
                "GlbReader",
                "StrictJsonParser",
                "ResourceManager",
                "java.nio.file.",
                "java.io.",
                "Minecraft.getInstance")) {
            assertFalse(backend.contains(forbidden), forbidden);
            assertFalse(marker.contains(forbidden), forbidden);
        }
        assertTrue(marker.contains("RenderTypes.lines()"));
        assertTrue(marker.contains("submitCustomGeometry"));
        assertTrue(marker.contains("applyTransform(checkedPoseStack, checkedSnapshot.rootTransform())"));
        assertTrue(marker.contains("checkedPoseStack.scale(unitsToBlocksScale, unitsToBlocksScale, unitsToBlocksScale)"));
        assertTrue(marker.contains("applyTransform(checkedPoseStack, checkedSocketTransform)"));
    }

    private static Fixture fixture(BlendModelKey key, long generation, MaterialDefinition material) {
        return fixture(key, generation, material, 1.0d);
    }

    private static Fixture fixture(
            BlendModelKey key,
            long generation,
            MaterialDefinition material,
            double unitsPerBlock) {
        MeshPrimitive geometry = new MeshPrimitive(
                "SkinSurface",
                new float[] {
                    1.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 1.0f
                },
                new float[] {
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f
                },
                new float[] {
                    0.10f, 0.20f,
                    0.30f, 0.40f,
                    0.50f, 0.60f
                },
                new int[] {2, 0, 1},
                new int[] {
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0
                },
                new float[] {
                    1.0f, 0.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 0.0f
                });
        Skin skin = new Skin("FixtureSkin", 1, List.of(1), identityMatrices(1));
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false));
        ModelAsset asset = new ModelAsset(
                key.resourceId(),
                key.descriptorResourceId(),
                generation,
                ModelProfile.SKINNED_V1,
                unitsPerBlock,
                Map.of("SkinSurface", material),
                null,
                nodes,
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(skin)),
                List.of(),
                new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(key, asset);
        LocalPose pose = new LocalPose(Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY));
        SkinPalette palette = SkinPalette.from(skin, NodePalette.from(pose, nodes));
        CpuSkinnedMesh output = CpuSkinner.skin(handle.skinnedPrimitives().getFirst().geometry(), palette);
        return new Fixture(handle, output);
    }

    private static MaterialDefinition material(MaterialDefinition.Mode mode, boolean doubleSided) {
        return new MaterialDefinition(
                BlendResourceId.parse("render_test:textures/skinned.png"), mode, false, doubleSided, null);
    }

    private static Transform translation(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), Transform.IDENTITY.rotation(), Vec3.ONE);
    }

    private static float[] identityMatrices(int count) {
        float[] result = new float[count * 16];
        for (int index = 0; index < count; index++) {
            int offset = index * 16;
            result[offset] = 1.0f;
            result[offset + 5] = 1.0f;
            result[offset + 10] = 1.0f;
            result[offset + 15] = 1.0f;
        }
        return result;
    }

    private static List<CapturedVertex> emitted(SkinnedMeshSnapshot mesh) {
        List<CapturedVertex> result = new ArrayList<>();
        mesh.emit((x, y, z, normalX, normalY, normalZ, u, v) ->
                result.add(new CapturedVertex(x, y, z, normalX, normalY, normalZ, u, v)));
        return result;
    }

    private static void assertVertex(
            CapturedVertex actual,
            float x,
            float y,
            float z,
            float normalX,
            float normalY,
            float normalZ,
            float u,
            float v) {
        assertEquals(x, actual.x());
        assertEquals(y, actual.y());
        assertEquals(z, actual.z());
        assertEquals(normalX, actual.normalX());
        assertEquals(normalY, actual.normalY());
        assertEquals(normalZ, actual.normalZ());
        assertEquals(u, actual.u());
        assertEquals(v, actual.v());
    }

    private static Path renderSource(String name) {
        return Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "client",
                "java",
                "com",
                "liy",
                "blendlib",
                "fabric",
                "client",
                "render",
                name);
    }

    /** Supplies one real 26.1.2 {@link BufferBuilder} without a GPU upload in this unit test. */
    private static final class SingleConsumerBufferSource extends MultiBufferSource.BufferSource {
        private final VertexConsumer consumer;

        private SingleConsumerBufferSource(ByteBufferBuilder backingBuffer, VertexConsumer consumer) {
            super(backingBuffer, new LinkedHashMap<>());
            this.consumer = consumer;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return consumer;
        }
    }

    private record Fixture(SkinnedRenderHandle handle, CpuSkinnedMesh output) {
    }

    private record CapturedVertex(float x, float y, float z, float normalX, float normalY, float normalZ, float u, float v) {
    }
}
