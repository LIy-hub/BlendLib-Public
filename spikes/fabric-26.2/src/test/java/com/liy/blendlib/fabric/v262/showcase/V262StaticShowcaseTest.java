package com.liy.blendlib.fabric.v262.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.fabric.v262.client.V262StaticPreparedHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

/** Controlled, no-client-launch compatibility proof for the separate 26.2 static adapter spike. */
class V262StaticShowcaseTest {
    private static final BlendModelKey MODEL_KEY = BlendModelKey.parse("blendlib_showcase:fixtures/static_model");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("blendlib_showcase:models3d/fixtures/static_model.glb");
    private static final String STATIC_GLB_SHA256 = "68747b5c6076728dc1ad367608e9fe5e95c621a2da5ed02271e0fb021a4a365c";
    private static final String STATIC_DESCRIPTOR_SHA256 = "613a53d799af75d2e5223ae8b601f67ad12ebe13105e1c3ad234858dbb50696a";

    @Test
    void unchangedV1StaticFixtureLoadsThenSubmitsThroughPublic262CollectorCallback() throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path descriptorPath = repositoryRoot.resolve(
                "blendlib-showcase/src/main/resources/assets/blendlib_showcase/blend_models/fixtures/static_model.json");
        Path glbPath = repositoryRoot.resolve(
                "blendlib-showcase/src/main/resources/assets/blendlib_showcase/models3d/fixtures/static_model.glb");

        // These frozen hashes and absence check demonstrate that this project references, rather than copies or
        // alters, the v1 descriptor/GLB fixture used by 26.1.2.
        assertEquals(STATIC_DESCRIPTOR_SHA256, sha256(descriptorPath));
        assertEquals(STATIC_GLB_SHA256, sha256(glbPath));
        assertFalse(Files.exists(Path.of("src/main/resources/assets/blendlib_showcase/models3d/fixtures/static_model.glb")));
        assertFalse(Files.exists(Path.of("src/main/resources/assets/blendlib_showcase/blend_models/fixtures/static_model.json")));

        ModelAsset asset = loadFixture(descriptorPath, glbPath);
        assertEquals(MODEL_KEY.resourceId(), asset.modelKey());
        assertEquals(262L, asset.generation());
        assertEquals("blendlib:rigid_v1", asset.profile().serializedName());

        V262StaticShowcase showcase = new V262StaticShowcase();
        V262StaticPreparedHandle handle = showcase.prepare(asset);
        assertEquals(MODEL_KEY.resourceId(), handle.modelKey());
        assertEquals(262L, handle.generation());
        assertEquals(asset.primitives().size(), handle.primitiveCount());

        RecordingCollector collector = new RecordingCollector();
        showcase.submit(handle, new PoseStack(), collector);

        assertEquals(handle.primitiveCount(), collector.submissionCount);
        assertNotNull(collector.renderType);
        int expectedVertices = asset.primitives().stream().mapToInt(primitive -> primitive.geometry().indexCount()).sum();
        assertEquals(expectedVertices, collector.vertices.vertices.size());
        assertTrue(collector.vertices.vertices.stream().allMatch(vertex -> Float.isFinite(vertex.x())
                && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z())));
    }

    @Test
    void isolatedSpikeSourcesDoNotNameThe2612RuntimeAdapterOrRawOpenGl() throws IOException {
        Path sourceRoot = Path.of("src/client/java/com/liy/blendlib/fabric/v262");
        String source;
        try (var paths = Files.walk(sourceRoot)) {
            source = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .reduce("", String::concat);
        }
        for (String forbidden : List.of(
                "Minecraft2612",
                "blendlib-fabric-client",
                "blendlib-fabric-common",
                "glBind",
                "GL11",
                "RenderSystem")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("SubmitNodeCollector"));
        assertTrue(source.contains("submitCustomGeometry"));
        assertTrue(source.contains("RenderTypes.entitySolid"));
    }

    private static ModelAsset loadFixture(Path descriptorPath, Path glbPath) throws IOException {
        AssetBytes descriptor = new AssetBytes(MODEL_KEY.descriptorResourceId(), Files.readAllBytes(descriptorPath));
        AssetBytes glb = new AssetBytes(MESH_ID, Files.readAllBytes(glbPath));
        return new ModelAssetLoader().load(MODEL_KEY.resourceId(), 262L, descriptor, requested -> {
            assertEquals(MESH_ID, requested);
            return glb;
        });
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent();
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 must exist in the Java runtime", exception);
        }
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Unable to read spike source " + path, exception);
        }
    }

    /** A fully typed public-API collector double that executes only the custom-geometry callback under test. */
    private static final class RecordingCollector implements SubmitNodeCollector {
        private final RecordingVertexConsumer vertices = new RecordingVertexConsumer();
        private int submissionCount;
        private RenderType renderType;

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack, RenderType submittedRenderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            submissionCount++;
            renderType = submittedRenderType;
            renderer.render(poseStack.last(), vertices);
        }

        /** Fabric API 0.153.0+26.2 augments the public collector contract with custom render phases. */
        @Override
        public <T extends net.minecraft.client.renderer.feature.submit.SubmitNode> void submitCustom(
                net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase<T> phase, T node) {
            unexpected();
        }

        @Override
        public void submitShadow(
                PoseStack poseStack,
                float strength,
                List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> pieces) {
            unexpected();
        }

        @Override
        public void submitNameTag(
                PoseStack poseStack,
                net.minecraft.world.phys.Vec3 offset,
                int packedLight,
                net.minecraft.network.chat.Component text,
                boolean seeThrough,
                int lineHeight,
                net.minecraft.client.renderer.state.level.CameraRenderState camera) {
            unexpected();
        }

        @Override
        public void submitText(
                PoseStack poseStack,
                float x,
                float y,
                net.minecraft.util.FormattedCharSequence text,
                boolean seeThrough,
                net.minecraft.client.gui.Font.DisplayMode displayMode,
                int color,
                int backgroundColor,
                int packedLight,
                int outlineColor) {
            unexpected();
        }

        @Override
        public void submitFlame(
                PoseStack poseStack,
                net.minecraft.client.renderer.entity.state.EntityRenderState state,
                Quaternionf cameraOrientation) {
            unexpected();
        }

        @Override
        public void submitLeash(
                PoseStack poseStack,
                net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState leashState) {
            unexpected();
        }

        @Override
        public <S> void submitModel(
                net.minecraft.client.model.Model<? super S> model,
                S state,
                PoseStack poseStack,
                RenderType submittedRenderType,
                int packedLight,
                int packedOverlay,
                int color,
                net.minecraft.client.renderer.texture.TextureAtlasSprite texture,
                int outlineColor,
                net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            unexpected();
        }

        @Override
        public void submitMovingBlock(
                PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState state, int packedLight) {
            unexpected();
        }

        @Override
        public void submitBlockModel(
                PoseStack poseStack,
                RenderType submittedRenderType,
                List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                int[] tintLayers,
                int packedLight,
                int packedOverlay,
                int color) {
            unexpected();
        }

        @Override
        public void submitBreakingBlockModel(
                PoseStack poseStack, List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int packedLight) {
            unexpected();
        }

        @Override
        public void submitShapeOutline(
                PoseStack poseStack,
                net.minecraft.world.phys.shapes.VoxelShape shape,
                RenderType submittedRenderType,
                int color,
                float lineWidth,
                boolean disableDepthTest) {
            unexpected();
        }

        @Override
        public void submitItem(
                PoseStack poseStack,
                net.minecraft.world.item.ItemDisplayContext displayContext,
                int packedLight,
                int packedOverlay,
                int color,
                int[] tintLayers,
                List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,
                net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foilType) {
            unexpected();
        }

        @Override
        public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState state) {
            unexpected();
        }

        @Override
        public void submitGizmoPrimitives(
                net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group group,
                net.minecraft.client.renderer.state.level.CameraRenderState camera,
                boolean depthTest) {
            unexpected();
        }

        private static void unexpected() {
            throw new AssertionError("The controlled 26.2 spike collector received an unrelated submission");
        }
    }

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vertex(x, y, z));
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

    private record Vertex(float x, float y, float z) {
    }
}
