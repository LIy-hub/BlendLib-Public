package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.testsupport.P3FixtureCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelAssetLoaderTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("fixture:model");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("fixture:blend_models/model.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("fixture:models3d/model.glb");

    @Test
    void selfAuthoredGlbFixturesProduceStableSuccessOrDiagnosticFamilies() {
        ModelAssetLoader loader = new ModelAssetLoader();
        for (P3FixtureCatalog.GlbFixture fixture : P3FixtureCatalog.GlbFixture.values()) {
            AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, descriptorJson("FixtureMaterial", "blendlib:rigid_v1"));
            AssetBytes glb = new AssetBytes(MESH_ID, P3FixtureCatalog.glb(fixture));
            if (fixture.metadata().valid()) {
                ModelAsset asset = loader.load(MODEL_KEY, descriptor, id -> glb);
                assertEquals(1, asset.primitives().size(), fixture.name());
                assertEquals(3, asset.primitives().getFirst().geometry().vertexCount(), fixture.name());
                continue;
            }
            BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                    () -> loader.load(MODEL_KEY, descriptor, id -> glb), fixture.name());
            String expected = fixture.metadata().expectedDiagnosticFamily();
            if (expected.endsWith("-001") || expected.endsWith("-004") || expected.endsWith("-006")) {
                assertEquals(expected, exception.diagnostic().code(), fixture.name());
            } else {
                assertEquals(true, exception.diagnostic().code().startsWith(expected), fixture.name());
            }
        }
    }

    @Test
    void canonicalP2StaticRigidAndSkinnedAssetsMatchGoldenCountsAndContainRestBounds() throws IOException {
        assertP2Asset("static_model", "blendlib:rigid_v1", 2, 1, 3, 3, 0,
                -0.5f, 0.0f, 0.0f, 0.5f, 1.0f, 2.0f);
        assertP2Asset("rigid_model", "blendlib:rigid_v1", 3, 2, 6, 6, 1,
                -0.5f, 0.0f, 0.0f, 0.5f, 1.6f, 1.0f);
        ModelAsset skinned = assertP2Asset("skinned_model", "blendlib:skinned_v1", 4, 1, 3, 3, 1,
                -0.4f, 0.0f, 0.0f, 0.4f, 1.0f, 1.0f);
        assertNotNull(skinned.skeleton());
        assertEquals(1, skinned.skeleton().skins().size());
    }

    @Test
    void cameraNodesAreIgnoredWithTheDedicatedStableWarningCode() {
        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, descriptorJson("FixtureMaterial", "blendlib:rigid_v1"));
        AssetBytes glb = new AssetBytes(MESH_ID, glbWithIgnoredCameraNode());
        ModelAsset asset = new ModelAssetLoader().load(MODEL_KEY, descriptor, id -> glb);
        assertTrue(asset.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(BlendDiagnosticCodes.SCENE_006)));
    }

    @Test
    void loadedAssetRetainsTheExactValidatedDefaultSceneRootsAsAnImmutableList() {
        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, descriptorJson("FixtureMaterial", "blendlib:rigid_v1"));
        AssetBytes glb = new AssetBytes(MESH_ID, glbWithOrderedSceneRoots());

        ModelAsset asset = new ModelAssetLoader().load(MODEL_KEY, descriptor, id -> glb);

        assertEquals(List.of(1, 0), asset.defaultSceneRoots());
        assertThrows(UnsupportedOperationException.class, () -> asset.defaultSceneRoots().add(2));
    }

    @Test
    void combinedLoadRejectsAnUndeclaredNextStateAfterTheReferencedClipHasDecoded() throws IOException {
        Path assetRoot = showcaseAssetRoot();
        BlendResourceId descriptorId = BlendResourceId.parse("blendlib_showcase:blend_models/fixtures/undeclared_next.json");
        BlendResourceId meshId = BlendResourceId.parse("blendlib_showcase:models3d/fixtures/rigid_model.glb");
        AssetBytes descriptor = new AssetBytes(descriptorId, ("""
                {"format_version":1,"profile":"blendlib:rigid_v1",
                 "mesh":"blendlib_showcase:models3d/fixtures/rigid_model.glb",
                 "materials":{"RigidSurface":{"base_color":"blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png"}},
                 "animation":{"initial_state":"blendlib_showcase:rigid_pulse","states":{
                   "blendlib_showcase:rigid_pulse":{"clip":"rigid_pulse","loop":false,"speed":1.0,
                    "next":"blendlib_showcase:undeclared_next"}}}}
                """).getBytes(StandardCharsets.UTF_8));
        AssetBytes glb = new AssetBytes(meshId, Files.readAllBytes(assetRoot.resolve("models3d/fixtures/rigid_model.glb")));

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> new ModelAssetLoader().load(BlendResourceId.parse("blendlib_showcase:fixtures/undeclared_next"), descriptor, id -> glb));

        assertEquals(BlendDiagnosticCodes.DESC_002, exception.diagnostic().code());
    }

    @Test
    void strictUsageRequiresExactPositionAndAnimationInputBounds() throws IOException {
        byte[] triangle = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        String triangleJson = jsonChunk(triangle);
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/0/min", () -> loadRigid(rewriteGlbJson(triangle,
                replaceRequired(triangleJson, "\"min\":[0,0,0],", ""))));
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/0/max", () -> loadRigid(rewriteGlbJson(triangle,
                replaceRequired(triangleJson, ",\"max\":[1,1,0]", ""))));

        byte[] rigid = p2Glb("rigid_model");
        String rigidJson = jsonChunk(rigid);
        String withoutInputBounds = replaceRequired(rigidJson,
                "\"max\":[0.8333333333333334],\"min\":[0.041666666666666664],\"type\":\"SCALAR\"",
                "\"type\":\"SCALAR\"");
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/7/min",
                () -> loadP2("rigid_model", rewriteGlbJson(rigid, withoutInputBounds)));
    }

    @Test
    void strictUsageRejectsNormalizedIndicesJointsAndFloatWeightsAtExactAccessorFields() throws IOException {
        byte[] triangle = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        String normalizedIndex = replaceRequired(jsonChunk(triangle),
                "\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"",
                "\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\",\"normalized\":true");
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/3/normalized",
                () -> loadRigid(rewriteGlbJson(triangle, normalizedIndex)));

        byte[] skinned = p2Glb("skinned_model");
        String skinnedJson = jsonChunk(skinned);
        String normalizedJoints = replaceRequired(skinnedJson,
                "\"bufferView\":3,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"",
                "\"bufferView\":3,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\",\"normalized\":true");
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/3/normalized",
                () -> loadP2("skinned_model", rewriteGlbJson(skinned, normalizedJoints)));

        String normalizedFloatWeights = replaceRequired(skinnedJson,
                "\"bufferView\":4,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"",
                "\"bufferView\":4,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\",\"normalized\":true");
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/4/normalized",
                () -> loadP2("skinned_model", rewriteGlbJson(skinned, normalizedFloatWeights)));
    }

    @Test
    void animatedEnvelopeOverflowUsesTheStableLimitDiagnosticBeforePublication() throws IOException {
        byte[] rigid = p2Glb("rigid_model");
        byte[] overflow = withBinaryFloats(
                rigid, 280, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);

        assertDiagnostic(
                BlendDiagnosticCodes.LIMIT_001,
                "/animations",
                () -> loadP2("rigid_model", overflow));
    }

    @Test
    void strictPreflightValidatesMalformedUnusedAccessorsAndAcceptsAValidUnusedControl() {
        byte[] triangle = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        String base = jsonChunk(triangle);

        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"normalized\":true}",
                BlendDiagnosticCodes.GLB_015, "/accessors/4/normalized");
        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"min\":0}",
                BlendDiagnosticCodes.GLB_015, "/accessors/4/min");
        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"min\":[0,0]}",
                BlendDiagnosticCodes.GLB_015, "/accessors/4/min");
        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"min\":[1e999]}",
                BlendDiagnosticCodes.GLB_015, "/accessors/4/min/0");
        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"min\":[1],\"max\":[0]}",
                BlendDiagnosticCodes.GLB_015, "/accessors/4/max/0");
        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"min\":[0],\"max\":[1]}",
                BlendDiagnosticCodes.GLB_015, "/accessors/4/max/0");
        assertUnusedAccessorFailure(triangle, base,
                "{\"bufferView\":0,\"byteOffset\":36,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\"}",
                BlendDiagnosticCodes.GLB_014, "/accessors/4");

        byte[] valid = appendUnusedAccessor(triangle, base,
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\",\"min\":[0],\"max\":[0]}");
        assertEquals(1, loadRigid(valid).primitives().size());

        byte[] atLimit = withAccessorCount(triangle, base, com.liy.blendlib.core.limits.BlendAssetLimits.MAX_DECLARED_ACCESSORS);
        assertEquals(1, loadRigid(atLimit).primitives().size());
        BlendAssetLoadException tooMany = assertThrows(BlendAssetLoadException.class, () -> loadRigid(withAccessorCount(
                triangle, base, com.liy.blendlib.core.limits.BlendAssetLimits.MAX_DECLARED_ACCESSORS + 1)));
        assertEquals(BlendDiagnosticCodes.GLB_002, tooMany.diagnostic().code());
        assertEquals("", tooMany.diagnostic().location());
        assertTrue(tooMany.getCause().getMessage().startsWith(
                "JSON array entry count exceeds configured limit at JSON character "));
    }

    private ModelAsset assertP2Asset(
            String name,
            String profile,
            int expectedNodes,
            int expectedPrimitives,
            int expectedVertices,
            int expectedIndices,
            int expectedClips,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) throws IOException {
        Path assetRoot = showcaseAssetRoot();
        BlendResourceId descriptorId = BlendResourceId.parse("blendlib_showcase:blend_models/fixtures/" + name + ".json");
        BlendResourceId meshId = BlendResourceId.parse("blendlib_showcase:models3d/fixtures/" + name + ".glb");
        AssetBytes descriptor = new AssetBytes(descriptorId,
                Files.readAllBytes(assetRoot.resolve("blend_models/fixtures/" + name + ".json")));
        AssetBytes glb = new AssetBytes(meshId, Files.readAllBytes(assetRoot.resolve("models3d/fixtures/" + name + ".glb")));
        ModelAsset asset = new ModelAssetLoader().load(BlendResourceId.parse("blendlib_showcase:fixtures/" + name), 4L, descriptor, id -> glb);
        assertEquals(profile, asset.profile().serializedName());
        assertEquals(expectedNodes, asset.nodes().size());
        assertEquals(expectedPrimitives, asset.primitives().size());
        assertEquals(expectedVertices, asset.primitives().stream().mapToInt(value -> value.geometry().vertexCount()).sum());
        assertEquals(expectedIndices, asset.primitives().stream().mapToInt(value -> value.geometry().indexCount()).sum());
        assertEquals(expectedClips, asset.clips().size());
        assertContainsBounds(asset.bounds(), minX, minY, minZ, maxX, maxY, maxZ);
        return asset;
    }

    private static ModelAsset loadRigid(byte[] glbBytes) {
        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, descriptorJson("FixtureMaterial", "blendlib:rigid_v1"));
        AssetBytes glb = new AssetBytes(MESH_ID, glbBytes);
        return new ModelAssetLoader().load(MODEL_KEY, descriptor, id -> glb);
    }

    private static ModelAsset loadP2(String name, byte[] glbBytes) throws IOException {
        Path assetRoot = showcaseAssetRoot();
        BlendResourceId descriptorId = BlendResourceId.parse("blendlib_showcase:blend_models/fixtures/" + name + ".json");
        BlendResourceId meshId = BlendResourceId.parse("blendlib_showcase:models3d/fixtures/" + name + ".glb");
        AssetBytes descriptor = new AssetBytes(descriptorId,
                Files.readAllBytes(assetRoot.resolve("blend_models/fixtures/" + name + ".json")));
        AssetBytes glb = new AssetBytes(meshId, glbBytes);
        return new ModelAssetLoader().load(BlendResourceId.parse("blendlib_showcase:fixtures/" + name), descriptor, id -> glb);
    }

    private static byte[] p2Glb(String name) throws IOException {
        return Files.readAllBytes(showcaseAssetRoot().resolve("models3d/fixtures/" + name + ".glb"));
    }

    private static Path showcaseAssetRoot() {
        Path repo = Path.of(System.getProperty("blendlib.projectDir")).getParent();
        return repo.resolve("blendlib-showcase/src/main/resources/assets/blendlib_showcase");
    }

    private static void assertContainsBounds(
            Bounds bounds, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        assertTrue(bounds.min().x() <= minX);
        assertTrue(bounds.min().y() <= minY);
        assertTrue(bounds.min().z() <= minZ);
        assertTrue(bounds.max().x() >= maxX);
        assertTrue(bounds.max().y() >= maxY);
        assertTrue(bounds.max().z() >= maxZ);
    }

    private static byte[] descriptorJson(String materialName, String profile) {
        return ("{\"format_version\":1,\"profile\":\"" + profile + "\",\"mesh\":\"" + MESH_ID.value()
                + "\",\"materials\":{\"" + materialName
                + "\":{\"base_color\":\"fixture:textures/fixture.png\"}}}").getBytes(StandardCharsets.UTF_8);
    }

    private static void assertDiagnostic(String code, String location, ThrowingRunnable action) {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, action::run);
        assertEquals(code, exception.diagnostic().code());
        assertEquals(location, exception.diagnostic().location());
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
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJson = (jsonBytes.length + 3) & ~3;
        int total = 12 + 8 + paddedJson + 8 + binary.length;
        ByteBuffer output = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(total);
        output.putInt(paddedJson).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + paddedJson) {
            output.put((byte) 0x20);
        }
        output.putInt(binary.length).putInt(0x004E4942).put(binary);
        return output.array();
    }

    private static byte[] withBinaryFloats(byte[] source, int binaryOffset, float... values) {
        byte[] copy = Arrays.copyOf(source, source.length);
        ByteBuffer output = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = output.getInt(12);
        int binaryPayload = 20 + jsonLength + 8;
        int writeOffset = binaryPayload + binaryOffset;
        if (binaryOffset < 0 || writeOffset + values.length * Float.BYTES > copy.length) {
            throw new AssertionError("Binary fixture offset is outside the GLB payload");
        }
        output.position(writeOffset);
        for (float value : values) {
            output.putFloat(value);
        }
        return copy;
    }

    private static String replaceRequired(String source, String target, String replacement) {
        String result = source.replace(target, replacement);
        if (result.equals(source)) {
            throw new AssertionError("Test fixture shape changed: " + target);
        }
        return result;
    }

    private static void assertUnusedAccessorFailure(
            byte[] source, String json, String unusedAccessor, String code, String location) {
        assertDiagnostic(code, location, () -> loadRigid(appendUnusedAccessor(source, json, unusedAccessor)));
    }

    private static byte[] appendUnusedAccessor(byte[] source, String json, String unusedAccessor) {
        String indexAccessor = "{\"bufferView\":3,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}";
        String expanded = replaceRequired(json, indexAccessor + "],", indexAccessor + "," + unusedAccessor + "],");
        return rewriteGlbJson(source, expanded);
    }

    private static byte[] withAccessorCount(byte[] source, String json, int count) {
        String marker = "\"accessors\":[";
        int start = json.indexOf(marker);
        int end = json.indexOf("],\"materials\"", start);
        if (start < 0 || end < 0 || count < 4) {
            throw new AssertionError("Baseline accessor fixture shape changed");
        }
        String original = json.substring(start + marker.length(), end);
        String unused = "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\"}";
        StringBuilder expanded = new StringBuilder(json.length() + Math.multiplyExact(count - 4, unused.length() + 1));
        expanded.append(json, 0, start + marker.length()).append(original);
        for (int accessorIndex = 4; accessorIndex < count; accessorIndex++) {
            expanded.append(',').append(unused);
        }
        expanded.append(json.substring(end));
        return rewriteGlbJson(source, expanded.toString());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static byte[] glbWithIgnoredCameraNode() {
        byte[] source = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        byte[] binary = Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":102}],
                "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
                {"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":6}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}],"materials":[{"name":"FixtureMaterial"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
                "nodes":[{"name":"CameraMesh","mesh":0,"camera":0}],"cameras":[{}],"scenes":[{"nodes":[0]}],"scene":0}
                """;
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJson = (jsonBytes.length + 3) & ~3;
        int total = 12 + 8 + paddedJson + 8 + binary.length;
        ByteBuffer output = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(total);
        output.putInt(paddedJson).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + paddedJson) {
            output.put((byte) 0x20);
        }
        output.putInt(binary.length).putInt(0x004E4942).put(binary);
        return output.array();
    }

    private static byte[] glbWithOrderedSceneRoots() {
        byte[] source = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        byte[] binary = Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":102}],
                "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
                {"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":6}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}],"materials":[{"name":"FixtureMaterial"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
                "nodes":[{"name":"MeshRoot","mesh":0},{"name":"EmptyRoot"}],"scenes":[{"nodes":[1,0]}],"scene":0}
                """;
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJson = (jsonBytes.length + 3) & ~3;
        int total = 12 + 8 + paddedJson + 8 + binary.length;
        ByteBuffer output = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(total);
        output.putInt(paddedJson).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + paddedJson) {
            output.put((byte) 0x20);
        }
        output.putInt(binary.length).putInt(0x004E4942).put(binary);
        return output.array();
    }
}
