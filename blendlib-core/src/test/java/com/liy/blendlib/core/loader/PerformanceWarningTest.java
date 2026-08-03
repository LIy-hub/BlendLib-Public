package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import com.liy.blendlib.core.model.ModelAsset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Direct checks that advisory performance thresholds remain non-fatal diagnostics. */
class PerformanceWarningTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("perf:model");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("perf:blend_models/model.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("perf:models3d/model.glb");
    private static final int VERTEX_WARNING_THRESHOLD = 100_000;
    private static final int SKIN_JOINT_WARNING_THRESHOLD = 128;

    @Test
    void emitsNonFatalPerf001WarnWhenTotalVerticesExceedThreshold() {
        ModelAsset asset = load(rigidDescriptor(), highVertexRigidGlb(VERTEX_WARNING_THRESHOLD + 1));

        assertPerformanceWarning(asset, "/meshes");
    }

    @Test
    void emitsNonFatalPerf001WarnWhenRelevantSkinJointsExceedThreshold() {
        ModelAsset asset = load(skinnedDescriptor(), highJointSkinnedGlb(SKIN_JOINT_WARNING_THRESHOLD + 1));

        assertPerformanceWarning(asset, "/skins");
    }

    @Test
    void doesNotEmitPerf001AtTheExactFrozenThresholds() {
        assertNoPerformanceWarning(load(rigidDescriptor(), highVertexRigidGlb(VERTEX_WARNING_THRESHOLD)));
        assertNoPerformanceWarning(load(skinnedDescriptor(), highJointSkinnedGlb(SKIN_JOINT_WARNING_THRESHOLD)));
    }

    private static void assertPerformanceWarning(ModelAsset asset, String expectedLocation) {
        assertTrue(hasPerformanceWarning(asset, expectedLocation));
    }

    private static void assertNoPerformanceWarning(ModelAsset asset) {
        assertFalse(asset.diagnostics().stream().anyMatch(diagnostic -> BlendDiagnosticCodes.PERF_001.equals(diagnostic.code())));
    }

    private static boolean hasPerformanceWarning(ModelAsset asset, String expectedLocation) {
        return asset.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.WARN
                && BlendDiagnosticCodes.PERF_001.equals(diagnostic.code())
                && expectedLocation.equals(diagnostic.location()));
    }

    private static ModelAsset load(AssetBytes descriptor, byte[] glbBytes) {
        AssetBytes glb = new AssetBytes(MESH_ID, glbBytes);
        return new ModelAssetLoader().load(MODEL_KEY, descriptor, resourceId -> glb);
    }

    private static AssetBytes rigidDescriptor() {
        return descriptor("blendlib:rigid_v1");
    }

    private static AssetBytes skinnedDescriptor() {
        return descriptor("blendlib:skinned_v1");
    }

    private static AssetBytes descriptor(String profile) {
        String json = "{\"format_version\":1,\"profile\":\"" + profile + "\",\"mesh\":\"" + MESH_ID.value()
                + "\",\"materials\":{\"FixtureMaterial\":{\"base_color\":\"perf:textures/fixture.png\"}}}";
        return new AssetBytes(DESCRIPTOR_ID, json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] highVertexRigidGlb(int vertexCount) {
        int positionsLength = Math.multiplyExact(vertexCount, 3 * Float.BYTES);
        int normalsOffset = positionsLength;
        int normalsLength = positionsLength;
        int uvOffset = Math.addExact(normalsOffset, normalsLength);
        int uvLength = Math.multiplyExact(vertexCount, 2 * Float.BYTES);
        int indexOffset = Math.addExact(uvOffset, uvLength);
        int indexLength = 3 * Short.BYTES;
        byte[] binary = new byte[align4(Math.addExact(indexOffset, indexLength))];
        ByteBuffer payload = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            payload.putFloat(vertex % 3).putFloat(0.0f).putFloat(0.0f);
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            payload.putFloat(0.0f).putFloat(1.0f).putFloat(0.0f);
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            payload.putFloat(0.0f).putFloat(0.0f);
        }
        payload.putShort((short) 0).putShort((short) 1).putShort((short) 2);

        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":%d}],
                "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":%d},{"buffer":0,"byteOffset":%d,"byteLength":%d},
                {"buffer":0,"byteOffset":%d,"byteLength":%d},{"buffer":0,"byteOffset":%d,"byteLength":%d}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":%d,"type":"VEC3","min":[0,0,0],"max":[2,0,0]},
                {"bufferView":1,"componentType":5126,"count":%d,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":%d,"type":"VEC2"},
                {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}],"materials":[{"name":"FixtureMaterial"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
                "nodes":[{"name":"FixtureNode","mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """.formatted(binary.length, positionsLength, normalsOffset, normalsLength, uvOffset, uvLength, indexOffset, indexLength,
                vertexCount, vertexCount, vertexCount);
        return buildGlb(json, binary);
    }

    private static byte[] highJointSkinnedGlb(int jointCount) {
        int positionsOffset = 0;
        int positionsLength = 3 * 3 * Float.BYTES;
        int normalsOffset = positionsOffset + positionsLength;
        int normalsLength = positionsLength;
        int uvOffset = normalsOffset + normalsLength;
        int uvLength = 3 * 2 * Float.BYTES;
        int jointsOffset = uvOffset + uvLength;
        int jointsLength = 3 * 4;
        int weightsOffset = jointsOffset + jointsLength;
        int weightsLength = 3 * 4 * Float.BYTES;
        int indexOffset = weightsOffset + weightsLength;
        int indexLength = 3 * Short.BYTES;
        int inverseBindOffset = align4(indexOffset + indexLength);
        int inverseBindLength = Math.multiplyExact(jointCount, 16 * Float.BYTES);
        byte[] binary = new byte[align4(Math.addExact(inverseBindOffset, inverseBindLength))];
        ByteBuffer payload = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        payload.position(positionsOffset);
        payload.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        payload.putFloat(1.0f).putFloat(0.0f).putFloat(0.0f);
        payload.putFloat(0.0f).putFloat(1.0f).putFloat(0.0f);
        payload.position(normalsOffset);
        for (int vertex = 0; vertex < 3; vertex++) {
            payload.putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        }
        payload.position(uvOffset);
        payload.putFloat(0.0f).putFloat(0.0f);
        payload.putFloat(1.0f).putFloat(0.0f);
        payload.putFloat(0.0f).putFloat(1.0f);
        payload.position(jointsOffset);
        for (int vertex = 0; vertex < 3; vertex++) {
            payload.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        payload.position(weightsOffset);
        for (int vertex = 0; vertex < 3; vertex++) {
            payload.putFloat(1.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        }
        payload.position(indexOffset);
        payload.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        payload.position(inverseBindOffset);
        for (int joint = 0; joint < jointCount; joint++) {
            putIdentityMatrix(payload);
        }

        String joints = numericArray(jointCount);
        String nodes = skinNodes(jointCount);
        String sceneRoots = "0," + jointCount;
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":%d}],
                "bufferViews":[{"buffer":0,"byteOffset":%d,"byteLength":%d},{"buffer":0,"byteOffset":%d,"byteLength":%d},
                {"buffer":0,"byteOffset":%d,"byteLength":%d},{"buffer":0,"byteOffset":%d,"byteLength":%d},
                {"buffer":0,"byteOffset":%d,"byteLength":%d},{"buffer":0,"byteOffset":%d,"byteLength":%d},
                {"buffer":0,"byteOffset":%d,"byteLength":%d}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5121,"count":3,"type":"VEC4"},{"bufferView":4,"componentType":5126,"count":3,"type":"VEC4"},
                {"bufferView":5,"componentType":5123,"count":3,"type":"SCALAR"},{"bufferView":6,"componentType":5126,"count":%d,"type":"MAT4"}],
                "materials":[{"name":"FixtureMaterial"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"JOINTS_0":3,"WEIGHTS_0":4},"indices":5,"material":0}]}],
                "nodes":[%s],"skins":[{"name":"LargeSkin","skeleton":0,"joints":[%s],"inverseBindMatrices":6}],
                "scenes":[{"nodes":[%s]}],"scene":0}
                """.formatted(binary.length,
                positionsOffset, positionsLength, normalsOffset, normalsLength, uvOffset, uvLength, jointsOffset, jointsLength,
                weightsOffset, weightsLength, indexOffset, indexLength, inverseBindOffset, inverseBindLength,
                jointCount, nodes, joints, sceneRoots);
        return buildGlb(json, binary);
    }

    private static String skinNodes(int jointCount) {
        StringBuilder result = new StringBuilder();
        for (int node = 0; node < jointCount; node++) {
            if (node > 0) {
                result.append(',');
            }
            result.append("{\"name\":\"Joint").append(node).append('"');
            if (node + 1 < jointCount) {
                result.append(",\"children\":[").append(node + 1).append(']');
            }
            result.append('}');
        }
        result.append(",{\"name\":\"SkinnedMesh\",\"mesh\":0,\"skin\":0}");
        return result.toString();
    }

    private static String numericArray(int count) {
        StringBuilder result = new StringBuilder();
        for (int value = 0; value < count; value++) {
            if (value > 0) {
                result.append(',');
            }
            result.append(value);
        }
        return result.toString();
    }

    private static void putIdentityMatrix(ByteBuffer payload) {
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                payload.putFloat(row == column ? 1.0f : 0.0f);
            }
        }
    }

    private static byte[] buildGlb(String json, byte[] binary) {
        if (binary.length % 4 != 0) {
            throw new IllegalArgumentException("GLB binary chunk must be four-byte aligned");
        }
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonLength = align4(jsonBytes.length);
        int totalLength = Math.addExact(12 + 8 + jsonLength, 8 + binary.length);
        ByteBuffer output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(totalLength);
        output.putInt(jsonLength).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + jsonLength) {
            output.put((byte) 0x20);
        }
        output.putInt(binary.length).putInt(0x004E4942).put(binary);
        return output.array();
    }

    private static int align4(int value) {
        return Math.addExact(value, 3) & ~3;
    }
}
