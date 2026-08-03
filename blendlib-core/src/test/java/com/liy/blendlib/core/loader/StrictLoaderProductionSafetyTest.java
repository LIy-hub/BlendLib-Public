package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.testsupport.P3FixtureCatalog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Direct production-loader tests for the strict-profile and bounded-allocation gates. */
class StrictLoaderProductionSafetyTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("strict:model");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("strict:blend_models/model.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("strict:models3d/model.glb");

    @Test
    void rejectsU8IndicesAndMisalignedAccessorsWithGlb015() {
        String base = jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        String u8Index = replaceRequired(base, "\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"",
                "\"componentType\":5121,\"count\":3,\"type\":\"SCALAR\"");
        assertCode(BlendDiagnosticCodes.GLB_015, () -> loadRigid(rewriteGlbJson(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE), u8Index)));

        String misaligned = replaceRequired(base, "\"byteOffset\":96,\"byteLength\":6", "\"byteOffset\":95,\"byteLength\":6");
        assertCode(BlendDiagnosticCodes.GLB_015,
                () -> loadRigid(rewriteGlbJson(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE), misaligned)));

        ModelAsset u32 = loadRigid(u32IndexTriangle());
        assertEquals(3, u32.primitives().getFirst().geometry().indexCount());
    }

    @Test
    void rejectsSceneTransformNonfiniteOverflowAndUnsupportedScaleWithScene005() {
        byte[] fixture = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        String base = jsonChunk(fixture);
        assertCode(BlendDiagnosticCodes.SCENE_005, () -> loadRigid(rewriteGlbJson(fixture, replaceRequired(base,
                "{\"name\":\"FixtureNode\",\"mesh\":0}",
                "{\"name\":\"FixtureNode\",\"mesh\":0,\"translation\":[1e999,0,0]}"))));
        assertCode(BlendDiagnosticCodes.SCENE_005, () -> loadRigid(rewriteGlbJson(fixture, replaceRequired(base,
                "{\"name\":\"FixtureNode\",\"mesh\":0}",
                "{\"name\":\"FixtureNode\",\"mesh\":0,\"scale\":[2,1,1]}"))));

        String parentChild = replaceRequired(base, "\"nodes\":[{\"name\":\"FixtureNode\",\"mesh\":0}]",
                "\"nodes\":[{\"name\":\"Root\",\"scale\":[2,2,2],\"children\":[1]},"
                        + "{\"name\":\"FixtureNode\",\"mesh\":0,\"scale\":[3.4e38,3.4e38,3.4e38]}]")
                .replace("\"scenes\":[{\"nodes\":[0]}]", "\"scenes\":[{\"nodes\":[0]}]");
        assertCode(BlendDiagnosticCodes.SCENE_005, () -> loadRigid(rewriteGlbJson(fixture, parentChild)));
    }

    @Test
    void rejectsMultipleActiveBindingsForOneMesh() {
        byte[] fixture = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        String base = jsonChunk(fixture);
        String multipleBindings = replaceRequired(base, "\"nodes\":[{\"name\":\"FixtureNode\",\"mesh\":0}]",
                "\"nodes\":[{\"name\":\"FixtureNodeA\",\"mesh\":0},{\"name\":\"FixtureNodeB\",\"mesh\":0}]")
                .replace("\"scenes\":[{\"nodes\":[0]}]", "\"scenes\":[{\"nodes\":[0,1]}]");
        assertCode(BlendDiagnosticCodes.SCENE_005, () -> loadRigid(rewriteGlbJson(fixture, multipleBindings)));
    }

    @Test
    void validatesSkinnedNodeReferencesAndInverseBindLayoutBeforeReadAllocation() throws IOException {
        byte[] source = p2Glb("skinned_model");
        String base = jsonChunk(source);
        String nonMeshSkin = replaceRequired(base, "{\"children\":[2],\"name\":\"SkinnedRoot\"}",
                "{\"children\":[2],\"name\":\"SkinnedRoot\",\"skin\":0}");
        assertCode(BlendDiagnosticCodes.SKIN_001, () -> loadP2("skinned_model", rewriteGlbJson(source, nonMeshSkin)));

        String outOfRange = replaceRequired(base, "\"skin\":0", "\"skin\":999");
        assertCode(BlendDiagnosticCodes.SKIN_001, () -> loadP2("skinned_model", rewriteGlbJson(source, outOfRange)));

        String inverseBindMismatch = replaceRequired(base, "\"inverseBindMatrices\":6", "\"inverseBindMatrices\":5");
        assertCode(BlendDiagnosticCodes.SKIN_001, () -> loadP2("skinned_model", rewriteGlbJson(source, inverseBindMismatch)));
    }

    @Test
    void rejectsCubicSplineAndRetainsDescriptorMaterialMetadataInModelAsset() throws IOException {
        byte[] rigidSource = p2Glb("rigid_model");
        String cubicSpline = replaceRequired(jsonChunk(rigidSource), "\"interpolation\":\"LINEAR\"",
                "\"interpolation\":\"CUBICSPLINE\"");
        assertCode(BlendDiagnosticCodes.ANIM_007, () -> loadP2("rigid_model", rewriteGlbJson(rigidSource, cubicSpline)));

        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, ("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"strict:models3d/model.glb","units_per_block":2.5,
                 "materials":{
                   "FixtureMaterial":{"base_color":"strict:textures/cut.png","mode":"cutout","cutout_threshold":0.4},
                   "AdditiveExtra":{"base_color":"strict:textures/add.png","mode":"additive","emissive":true}
                 },"sockets":{"strict:tip":{"node":"FixtureNode"}}}
                """).getBytes(StandardCharsets.UTF_8));
        AssetBytes glb = new AssetBytes(MESH_ID, P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        ModelAsset asset = new ModelAssetLoader().load(MODEL_KEY, 7L, descriptor, id -> glb);
        assertEquals(DESCRIPTOR_ID, asset.descriptorId());
        assertEquals(2.5, asset.unitsPerBlock());
        assertEquals(0.4, asset.materials().get("FixtureMaterial").cutoutThreshold());
        assertEquals("additive", asset.materials().get("AdditiveExtra").mode().serializedName());
        assertEquals(0, asset.sockets().get(BlendResourceId.parse("strict:tip")).nodeIndex());
    }

    @Test
    void preflightsConfiguredVertexAndAnimationLimitsBeforePayloadAllocation() throws IOException {
        BlendAssetLimits defaults = BlendAssetLimits.DEFAULT;
        BlendAssetLimits twoVertexLimit = new BlendAssetLimits(
                defaults.maxGlbBytes(), 2, defaults.maxIndices(), defaults.maxNodes(), defaults.maxRigidNodes(), defaults.maxSkinJoints(),
                defaults.maxHierarchyDepth(), defaults.maxClips(), defaults.maxKeyframeSamples(), defaults.maxClipDurationSeconds(),
                defaults.maxMaterialSlots(), defaults.maxSockets());
        AssetBytes descriptor = rigidDescriptor();
        AssetBytes glb = new AssetBytes(MESH_ID, P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        assertCode(BlendDiagnosticCodes.LIMIT_001, () -> new ModelAssetLoader(twoVertexLimit).load(MODEL_KEY, descriptor, id -> glb));

        BlendAssetLimits oneAnimationSampleLimit = new BlendAssetLimits(
                defaults.maxGlbBytes(), defaults.maxVertices(), defaults.maxIndices(), defaults.maxNodes(), defaults.maxRigidNodes(),
                defaults.maxSkinJoints(), defaults.maxHierarchyDepth(), defaults.maxClips(), 1, defaults.maxClipDurationSeconds(),
                defaults.maxMaterialSlots(), defaults.maxSockets());
        assertCode(BlendDiagnosticCodes.LIMIT_001,
                () -> loadP2WithLoader("rigid_model", p2Glb("rigid_model"), new ModelAssetLoader(oneAnimationSampleLimit)));
    }

    @Test
    void reverseNumberedHierarchyUsesStructuralRootDepthRatherThanNodeIterationOrder() {
        ModelAsset atFrozenLimit = loadRigid(reverseNumberedHierarchyGlb(256));
        assertEquals(256, atFrozenLimit.nodes().size());

        assertCode(BlendDiagnosticCodes.LIMIT_001, () -> loadRigid(reverseNumberedHierarchyGlb(257)));
    }

    @Test
    void animationScaleKeysRequirePositiveUniformBakedScaleWithAnim007() {
        assertCode(BlendDiagnosticCodes.ANIM_007, () -> loadRigid(scaleAnimationGlb(1, 1, 1, -1, 1, 1)));
        assertCode(BlendDiagnosticCodes.ANIM_007, () -> loadRigid(scaleAnimationGlb(1, 1, 1, 0, 0, 0)));
        assertCode(BlendDiagnosticCodes.ANIM_007, () -> loadRigid(scaleAnimationGlb(1, 1, 1, 2, 1, 1)));

        ModelAsset valid = loadRigid(scaleAnimationGlb(1, 1, 1, 2, 2, 2));
        assertEquals(1, valid.clips().size());
        assertEquals("scale", valid.clips().getFirst().name());
    }

    @Test
    void rejectsDuplicateAnimationTargetsAndMatrixTargetedNodesWithAnim007() {
        String duplicateTranslationChannels = "["
                + "{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}},"
                + "{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]";
        assertCode(BlendDiagnosticCodes.ANIM_007, () -> loadRigid(translationAnimationGlb(
                "{\"name\":\"FixtureNode\",\"mesh\":0}", duplicateTranslationChannels)));

        String oneTranslationChannel = "[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]";
        assertCode(BlendDiagnosticCodes.ANIM_007, () -> loadRigid(translationAnimationGlb(
                "{\"name\":\"FixtureNode\",\"mesh\":0,\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}",
                oneTranslationChannel)));
    }

    private static void assertCode(String expected, ThrowingRunnable action) {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, action::run);
        assertEquals(expected, exception.diagnostic().code());
    }

    private static ModelAsset loadRigid(byte[] glbBytes) {
        AssetBytes descriptor = rigidDescriptor();
        AssetBytes glb = new AssetBytes(MESH_ID, glbBytes);
        return new ModelAssetLoader().load(MODEL_KEY, descriptor, id -> glb);
    }

    private static AssetBytes rigidDescriptor() {
        return new AssetBytes(DESCRIPTOR_ID, ("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"strict:models3d/model.glb",
                 "materials":{"FixtureMaterial":{"base_color":"strict:textures/base.png"}}}
                """).getBytes(StandardCharsets.UTF_8));
    }

    private static ModelAsset loadP2(String name, byte[] glbBytes) throws IOException {
        return loadP2WithLoader(name, glbBytes, new ModelAssetLoader());
    }

    private static ModelAsset loadP2WithLoader(String name, byte[] glbBytes, ModelAssetLoader loader) throws IOException {
        Path assetRoot = repositoryRoot().resolve("blendlib-showcase/src/main/resources/assets/blendlib_showcase");
        BlendResourceId descriptorId = BlendResourceId.parse("blendlib_showcase:blend_models/fixtures/" + name + ".json");
        BlendResourceId meshId = BlendResourceId.parse("blendlib_showcase:models3d/fixtures/" + name + ".glb");
        AssetBytes descriptor = new AssetBytes(descriptorId,
                Files.readAllBytes(assetRoot.resolve("blend_models/fixtures/" + name + ".json")));
        AssetBytes glb = new AssetBytes(meshId, glbBytes);
        return loader.load(BlendResourceId.parse("blendlib_showcase:strict/" + name), descriptor, id -> glb);
    }

    private static byte[] p2Glb(String name) throws IOException {
        return Files.readAllBytes(repositoryRoot().resolve("blendlib-showcase/src/main/resources/assets/blendlib_showcase/models3d/fixtures/" + name + ".glb"));
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent();
    }

    private static String jsonChunk(byte[] glb) {
        ByteBuffer input = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        return new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();
    }

    private static byte[] rewriteGlbJson(byte[] source, String json) {
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        byte[] binary = Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
        return buildGlb(json, binary);
    }

    private static byte[] u32IndexTriangle() {
        byte[] source = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        byte[] binary = binaryChunk(source);
        byte[] u32Binary = Arrays.copyOf(binary, 108);
        ByteBuffer.wrap(u32Binary).order(ByteOrder.LITTLE_ENDIAN).putInt(96, 0).putInt(100, 1).putInt(104, 2);
        String json = jsonChunk(source);
        json = replaceRequired(json, "\"buffers\":[{\"byteLength\":102}]", "\"buffers\":[{\"byteLength\":108}]");
        json = replaceRequired(json, "\"byteOffset\":96,\"byteLength\":6", "\"byteOffset\":96,\"byteLength\":12");
        json = replaceRequired(json, "\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"",
                "\"componentType\":5125,\"count\":3,\"type\":\"SCALAR\"");
        return buildGlb(json, u32Binary);
    }

    private static byte[] reverseNumberedHierarchyGlb(int nodeCount) {
        if (nodeCount < 1) {
            throw new IllegalArgumentException("nodeCount must be positive");
        }
        StringBuilder nodes = new StringBuilder();
        for (int index = 0; index < nodeCount; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{\"name\":\"Node").append(index).append('\"');
            if (index == 0) {
                nodes.append(",\"mesh\":0");
            } else {
                nodes.append(",\"children\":[").append(index - 1).append("]");
            }
            nodes.append('}');
        }
        byte[] binary = binaryChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        String json = triangleJson(binary.length, nodes.toString(), nodeCount - 1, "");
        return buildGlb(json, binary);
    }

    private static byte[] scaleAnimationGlb(float... keyValues) {
        if (keyValues.length != 6) {
            throw new IllegalArgumentException("Scale animation test data requires two VEC3 keys");
        }
        byte[] base = binaryChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        byte[] binary = Arrays.copyOf(base, 136);
        ByteBuffer payload = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        payload.position(104).putFloat(0.0f).putFloat(1.0f);
        payload.position(112);
        for (float keyValue : keyValues) {
            payload.putFloat(keyValue);
        }
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":%d}],
                "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
                {"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":6},
                {"buffer":0,"byteOffset":104,"byteLength":8},{"buffer":0,"byteOffset":112,"byteLength":24}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"},{"bufferView":4,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
                {"bufferView":5,"componentType":5126,"count":2,"type":"VEC3"}],"materials":[{"name":"FixtureMaterial"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
                "nodes":[{"name":"FixtureNode","mesh":0}],"animations":[{"name":"scale",
                "samplers":[{"input":4,"output":5,"interpolation":"LINEAR"}],
                "channels":[{"sampler":0,"target":{"node":0,"path":"scale"}}]}],"scenes":[{"nodes":[0]}],"scene":0}
                """.formatted(binary.length);
        return buildGlb(json, binary);
    }

    private static byte[] translationAnimationGlb(String nodeJson, String channelsJson) {
        byte[] base = binaryChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        byte[] binary = Arrays.copyOf(base, 136);
        ByteBuffer payload = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        payload.position(104).putFloat(0.0f).putFloat(1.0f);
        payload.position(112).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f).putFloat(0.0f).putFloat(0.0f);
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":%d}],
                "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
                {"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":6},
                {"buffer":0,"byteOffset":104,"byteLength":8},{"buffer":0,"byteOffset":112,"byteLength":24}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"},{"bufferView":4,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
                {"bufferView":5,"componentType":5126,"count":2,"type":"VEC3"}],"materials":[{"name":"FixtureMaterial"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
                "nodes":[%s],"animations":[{"name":"translation",
                "samplers":[{"input":4,"output":5,"interpolation":"LINEAR"}],"channels":%s}],"scenes":[{"nodes":[0]}],"scene":0}
                """.formatted(binary.length, nodeJson, channelsJson);
        return buildGlb(json, binary);
    }

    private static String triangleJson(int bufferLength, String nodes, int sceneRoot, String extras) {
        return "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + bufferLength + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":72,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":96,\"byteLength\":6}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"materials\":[{\"name\":\"FixtureMaterial\"}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,"
                + "\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0}]}],\"nodes\":[" + nodes
                + "],\"scenes\":[{\"nodes\":[" + sceneRoot + "]}],\"scene\":0" + extras + "}";
    }

    private static byte[] binaryChunk(byte[] source) {
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        return Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
    }

    private static byte[] buildGlb(String json, byte[] binary) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = (jsonBytes.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJsonLength + 8 + binary.length;
        ByteBuffer output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(totalLength);
        output.putInt(paddedJsonLength).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + paddedJsonLength) {
            output.put((byte) 0x20);
        }
        output.putInt(binary.length).putInt(0x004E4942).put(binary);
        return output.array();
    }

    private static String replaceRequired(String source, String target, String replacement) {
        String result = source.replace(target, replacement);
        if (result.equals(source)) {
            throw new AssertionError("Test fixture shape changed; replacement target was not found: " + target);
        }
        return result;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
