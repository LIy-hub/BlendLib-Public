package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

class RenderContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("render_test:rigid/example");

    @Test
    void snapshotCarriesImmutableGenerationHandleTransformAndCullingMetadata() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 7L);
        CullingMetadata culling = new CullingMetadata(handle.bounds(), true);
        Transform root = transform(3.0f, 4.0f, 5.0f);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle, root, 0x00F000F0, 0, 0xFF80FF80, RenderVisibility.VISIBLE, culling);

        assertEquals(KEY, snapshot.handle().modelKey());
        assertEquals(7L, snapshot.generation());
        assertEquals(root, snapshot.rootTransform());
        assertEquals(RenderVisibility.VISIBLE, snapshot.visibility());
        assertEquals(handle.bounds(), snapshot.culling().worldBounds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.handle().primitives().add(null));
    }

    @Test
    void rigidPaletteSnapshotFailsFastForMismatchedHandleIdentityOrGeneration() {
        StaticRigidRenderHandle handle = rigidHandle(5L);
        RigidNodePaletteSnapshot matching = RigidNodePaletteSnapshot.copyOf(
                KEY, 5L, Map.of(1, transform(11.0f, 12.0f, 13.0f)));

        ModelRenderSnapshot snapshot = paletteSnapshot(handle, matching);
        assertEquals(KEY, snapshot.rigidNodePalette().modelKey());
        assertEquals(5L, snapshot.rigidNodePalette().generation());

        RigidNodePaletteSnapshot wrongKey = RigidNodePaletteSnapshot.copyOf(
                BlendModelKey.parse("render_test:rigid/other"), 5L, Map.of(1, Transform.IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> paletteSnapshot(handle, wrongKey));

        RigidNodePaletteSnapshot wrongGeneration = RigidNodePaletteSnapshot.copyOf(
                KEY, 6L, Map.of(1, Transform.IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> paletteSnapshot(handle, wrongGeneration));
    }

    @Test
    void matchingRigidPaletteSuppliesPrimitiveTransformAndAbsentPaletteKeepsRestPose() {
        StaticRigidRenderHandle handle = rigidHandle(5L);
        PreparedRenderPrimitive primitive = handle.primitives().getFirst();
        Transform restPose = handle.nodeTransform(primitive.nodeIndex());
        Transform posed = transform(11.0f, 12.0f, 13.0f);

        ModelRenderSnapshot restSnapshot = restSnapshot(handle);
        assertEquals(restPose, Minecraft2612StaticRigidRenderBackend.nodeTransformFor(restSnapshot, primitive.nodeIndex()));

        ModelRenderSnapshot posedSnapshot = paletteSnapshot(
                handle, RigidNodePaletteSnapshot.copyOf(KEY, 5L, Map.of(primitive.nodeIndex(), posed)));
        assertEquals(posed, Minecraft2612StaticRigidRenderBackend.nodeTransformFor(posedSnapshot, primitive.nodeIndex()));
    }

    @Test
    void rigidPaletteSnapshotCopiesInputMapAndDoesNotExposeMutability() {
        Map<Integer, Transform> mutable = new LinkedHashMap<>();
        Transform retained = transform(11.0f, 12.0f, 13.0f);
        mutable.put(1, retained);
        RigidNodePaletteSnapshot palette = RigidNodePaletteSnapshot.copyOf(KEY, 5L, mutable);
        mutable.put(1, transform(99.0f, 98.0f, 97.0f));
        mutable.put(2, Transform.IDENTITY);

        assertEquals(retained, palette.nodeTransform(1));
        assertFalse(palette.worldTransforms().containsKey(2));
        assertThrows(UnsupportedOperationException.class, () -> palette.worldTransforms().put(2, Transform.IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> paletteSnapshot(rigidHandle(5L), RigidNodePaletteSnapshot.copyOf(KEY, 5L, Map.of())));
    }

    @Test
    void preparedGeometryDefensivelyCopiesOnceAndMissingModelHasFiniteMagentaBlackFallback() {
        float[] positions = {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f};
        StaticGeometry geometry = StaticGeometry.of(
                positions,
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2});
        positions[0] = 99.0f;
        AtomicReference<Float> firstX = new AtomicReference<>();
        geometry.emit((x, y, z, normalX, normalY, normalZ, u, v) -> firstX.compareAndSet(null, x));
        assertEquals(0.0f, firstX.get());

        MissingModelRenderHandle missing = new MissingModelRenderHandle(KEY, 3L);
        assertTrue(missing.missingModel());
        assertEquals(2, missing.primitives().size());
        assertEquals(MissingModelRenderHandle.MAGENTA_ARGB, missing.primitives().getFirst().material().argbTint());
        assertEquals(MissingModelRenderHandle.BLACK_ARGB, missing.primitives().get(1).material().argbTint());
        assertTrue(Float.isFinite(missing.bounds().min().x()));
        assertTrue(Float.isFinite(missing.bounds().max().y()));
    }

    @Test
    void strictTrianglesExpandToDegenerateQuadsWithoutChangingSourceWinding() {
        StaticGeometry geometry = StaticGeometry.of(
                new float[] {
                    0.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f,
                    2.0f, 0.0f, 0.0f,
                    3.0f, 0.0f, 0.0f
                },
                new float[] {
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f
                },
                new float[] {
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f,
                    0.0f, 1.0f
                },
                new int[] {0, 1, 2, 2, 3, 0});
        List<Float> emittedX = new ArrayList<>();

        geometry.emit((x, y, z, normalX, normalY, normalZ, u, v) -> emittedX.add(x));

        assertEquals(List.of(0.0f, 1.0f, 2.0f, 2.0f, 2.0f, 3.0f, 0.0f, 0.0f), emittedX);
    }

    @Test
    void materialMappingUsesOnlyExactPublicP4PathsAndRejectsUnsupportedIntent() {
        assertLayer(MaterialDefinition.Mode.OPAQUE, false, RenderLayer.SOLID);
        assertLayer(MaterialDefinition.Mode.CUTOUT, false, RenderLayer.CUTOUT);
        assertLayer(MaterialDefinition.Mode.CUTOUT, true, RenderLayer.CUTOUT);
        assertLayer(MaterialDefinition.Mode.TRANSLUCENT, true, RenderLayer.TRANSLUCENT);

        assertRejected(
                MaterialDefinition.Mode.OPAQUE,
                true,
                MaterialRejectionReason.OPAQUE_DOUBLE_SIDED_UNSUPPORTED);
        assertRejected(
                MaterialDefinition.Mode.TRANSLUCENT,
                false,
                MaterialRejectionReason.TRANSLUCENT_SINGLE_SIDED_UNSUPPORTED);

        MaterialMapping additive = MaterialRenderMapper.map(material(MaterialDefinition.Mode.ADDITIVE));
        assertTrue(additive instanceof MaterialMapping.Rejected);
        assertEquals(MaterialRejectionReason.ADDITIVE_UNSUPPORTED_IN_P4, ((MaterialMapping.Rejected) additive).reason());
        assertThrows(UnsupportedRenderMaterialException.class,
                () -> StaticRigidRenderHandle.prepare(KEY, asset(1L, 1.0d, material(MaterialDefinition.Mode.ADDITIVE), List.of())));
    }

    @Test
    void emissiveMaterialsUseFullbrightVertexLightWithoutRepurposingOutlineFlags() throws IOException {
        RenderMaterial emissive = new RenderMaterial(
                BlendResourceId.parse("render_test:textures/emissive.png"), RenderLayer.TRANSLUCENT, true, false, 0xFFFFFFFF, false);
        RenderMaterial lit = new RenderMaterial(
                BlendResourceId.parse("render_test:textures/lit.png"), RenderLayer.CUTOUT, false, true, 0xFFFFFFFF, false);

        assertEquals(Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                Minecraft2612StaticRigidRenderBackend.packedLightFor(emissive, 0x000A000B));
        assertEquals(0x000A000B, Minecraft2612StaticRigidRenderBackend.packedLightFor(lit, 0x000A000B));

        String backendSource = Files.readString(renderSource("Minecraft2612StaticRigidRenderBackend.java"));
        assertFalse(backendSource.contains("entityCutout(texture, material.doubleSided())"));
        assertFalse(backendSource.contains("entityTranslucent(texture, material.doubleSided())"));
    }

    @Test
    void staticRigidHandleUsesPreparedAllClipBoundsAndDescriptorUnitsWithoutHandleTimeSampling() throws IOException {
        List<AnimationClip> clips = List.of(new AnimationClip("never_sampled", List.of(new AnimationChannel(
                1, AnimationPath.TRANSLATION, Interpolation.LINEAR, new float[] {0.0f, 1.0f}, new float[] {0, 0, 0, 9, 0, 0}))));
        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepare(KEY, asset(5L, 2.0d, material(MaterialDefinition.Mode.OPAQUE), clips));

        assertEquals(KEY, handle.modelKey());
        assertEquals(5L, handle.generation());
        assertEquals(0.5f, handle.unitsToBlocksScale());
        assertEquals(1.0f, handle.nodeTransform(1).translation().x());
        assertEquals(2.0f, handle.nodeTransform(1).translation().y());
        assertTrue(handle.bounds().max().x() >= 6.0f);
        assertTrue(handle.bounds().max().y() >= 6.0f);
        assertEquals(3, handle.primitives().getFirst().geometry().vertexCount());

        String handleSource = Files.readString(renderSource("StaticRigidRenderHandle.java"));
        assertFalse(handleSource.contains(".clips()"));
        assertFalse(handleSource.contains("sample("));
    }

    @Test
    void renderSubmitSourcesCannotReferenceLoadersParsersOrFileIo() throws IOException {
        Path sourceRoot = renderSource("");
        String source;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            source = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .reduce("", String::concat);
        }
        for (String forbidden : List.of(
                "ModelAssetLoader", "AssetResolver", "GlbReader", "StrictJsonParser", "ResourceManager", "java.nio.file.", "java.io.",
                "AnimationController", "ClientAnimationInstanceRegistry", ".sample(", ".advance(")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    @Test
    void actual2612CollectorCopiesBackendPoseBeforeTheBackendPopsItsStack() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 9L);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        PoseStack poseStack = new PoseStack();
        poseStack.translate(3.0f, 4.0f, 5.0f);
        SubmitNodeStorage collector = new SubmitNodeStorage();

        new Minecraft2612StaticRigidRenderBackend().submit(snapshot, new RenderSubmissionContext(poseStack, collector));

        SubmitNodeCollection submits = collector.getSubmitsPerOrder().get(0);
        assertTrue(submits.wasUsed());
        assertEquals(
                VertexFormat.Mode.QUADS,
                new Minecraft2612StaticRigidRenderBackend()
                        .renderTypeFor(handle.primitives().getFirst().material())
                        .mode());
        CapturingVertexConsumer consumer = new CapturingVertexConsumer();
        try (ByteBufferBuilder backingBuffer = new ByteBufferBuilder(256)) {
            new CustomFeatureRenderer().renderSolid(submits, new CapturingBufferSource(backingBuffer, consumer));
        }

        assertEquals(8, consumer.positions.size());
        assertPosition(consumer.positions.getFirst(), 2.75f, 3.75f, 5.0f);
        assertPosition(consumer.positions.get(3), 3.25f, 4.25f, 5.0f);
        assertPosition(consumer.positions.get(4), 2.75f, 3.75f, 5.0f);
    }

    private void assertLayer(MaterialDefinition.Mode mode, boolean doubleSided, RenderLayer expectedLayer) {
        MaterialMapping mapping = MaterialRenderMapper.map(material(mode, doubleSided, null));
        assertTrue(mapping instanceof MaterialMapping.Supported);
        assertEquals(expectedLayer, ((MaterialMapping.Supported) mapping).material().layer());
    }

    private void assertRejected(
            MaterialDefinition.Mode mode, boolean doubleSided, MaterialRejectionReason expectedReason) {
        MaterialMapping mapping = MaterialRenderMapper.map(material(mode, doubleSided, null));
        assertTrue(mapping instanceof MaterialMapping.Rejected);
        assertEquals(expectedReason, ((MaterialMapping.Rejected) mapping).reason());
    }

    private static ModelAsset asset(
            long generation, double unitsPerBlock, MaterialDefinition material, List<AnimationClip> clips) {
        MeshPrimitive primitive = new MeshPrimitive(
                "Base",
                new float[] {0.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.0f, 0.0f, 2.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                null,
                null);
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "Root", transform(1.0f, 0.0f, 0.0f), List.of(1), -1, -1, false),
                new ModelNode(1, "Mesh", transform(0.0f, 2.0f, 0.0f), List.of(), 0, -1, false));
        return new ModelAsset(
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                generation,
                ModelProfile.RIGID_V1,
                unitsPerBlock,
                Map.of("Base", material),
                null,
                nodes,
                List.of(0),
                List.of(new ModelPrimitive(1, 0, 0, primitive)),
                null,
                clips,
                new SocketTable(Map.of()),
                new Bounds(Vec3.ZERO, new Vec3(2.0f, 2.0f, 0.0f)),
                List.of());
    }

    private static MaterialDefinition material(MaterialDefinition.Mode mode) {
        return material(mode, false, null);
    }

    private static MaterialDefinition material(MaterialDefinition.Mode mode, boolean doubleSided, Double cutoutThreshold) {
        return new MaterialDefinition(
                BlendResourceId.parse("render_test:textures/base.png"), mode, false, doubleSided, cutoutThreshold);
    }

    private static Transform transform(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), Quaternion.IDENTITY, Vec3.ONE);
    }

    private static StaticRigidRenderHandle rigidHandle(long generation) {
        return StaticRigidRenderHandle.prepare(KEY, asset(generation, 1.0d, material(MaterialDefinition.Mode.OPAQUE), List.of()));
    }

    private static ModelRenderSnapshot restSnapshot(ModelRenderHandle handle) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }

    private static ModelRenderSnapshot paletteSnapshot(ModelRenderHandle handle, RigidNodePaletteSnapshot palette) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true),
                palette);
    }

    private static Path renderSource(String name) {
        return Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "render", name);
    }

    private static void assertPosition(float[] actual, float x, float y, float z) {
        assertEquals(x, actual[0], 1.0e-5f);
        assertEquals(y, actual[1], 1.0e-5f);
        assertEquals(z, actual[2], 1.0e-5f);
    }

    /** Captures the public {@link VertexConsumer} calls made by the 26.1.2 custom-geometry feature renderer. */
    private static final class CapturingVertexConsumer implements VertexConsumer {
        private final List<float[]> positions = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            positions.add(new float[] {x, y, z});
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    /** Buffer source which exercises the feature renderer without uploading a mesh to the GPU in a unit test. */
    private static final class CapturingBufferSource extends MultiBufferSource.BufferSource {
        private final VertexConsumer consumer;

        private CapturingBufferSource(ByteBufferBuilder backingBuffer, VertexConsumer consumer) {
            super(backingBuffer, new LinkedHashMap<>());
            this.consumer = consumer;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return consumer;
        }
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new AssertionError("Unable to read render source " + path, exception);
        }
    }
}
