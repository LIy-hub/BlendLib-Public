package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Strict-profile regressions for skin hierarchy, influences, and inverse-bind matrices. */
class StrictSkinValidationTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("strict:skin_test");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("strict:blend_models/skin_test.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("strict:models3d/skin_test.glb");

    private static final String VALID_NODES = """
            [{"children":[1],"name":"JointRoot"},{"name":"JointChild"},
             {"mesh":0,"name":"SkinnedMesh","skin":0},{"children":[0,2],"name":"SceneRoot"}]
            """;
    private static final String VALID_SKIN =
            "[{\"inverseBindMatrices\":6,\"joints\":[0,1],\"skeleton\":3,\"name\":\"ValidSkin\"}]";
    private static final int[] PADDED_JOINTS = {
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0
    };
    private static final float[] PADDED_WEIGHTS = {
        1, 0, 0, 0,
        1, 0, 0, 0,
        1, 0, 0, 0
    };

    @Test
    void acceptsOneHierarchyAncestorSkeletonAndZeroWeightPaddingDuplicates() {
        ModelAsset asset = load(skinnedGlb(
                VALID_NODES, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2)));

        assertEquals(1, asset.skeleton().skins().size());
        assertEquals(3, asset.skeleton().skins().getFirst().skeletonRoot());
        assertEquals(Arrays.asList(0, 1), asset.skeleton().skins().getFirst().joints());
    }

    @Test
    void rejectsJointsFromDisjointTreesAtTheSkinJointList() {
        String nodes = """
                [{"name":"JointA"},{"name":"JointB"},
                 {"mesh":0,"name":"SkinnedMesh","skin":0}]
                """;
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/joints",
                () -> load(skinnedGlb(nodes, "[0,1,2]",
                        "[{\"inverseBindMatrices\":6,\"joints\":[0,1]}]", 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void rejectsSkeletonThatIsNotTheCommonRootOrItsAncestor() {
        String nodes = """
                [{"children":[1],"name":"JointRoot"},{"name":"JointChild"},
                 {"mesh":0,"name":"SkinnedMesh","skin":0},{"children":[0,2],"name":"SceneRoot"},
                 {"name":"UnrelatedRoot"}]
                """;
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/skeleton",
                () -> load(skinnedGlb(nodes, "[3,4]",
                        "[{\"inverseBindMatrices\":6,\"joints\":[0,1],\"skeleton\":4}]", 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void rejectsDuplicateJointDeclarationAtTheRepeatedElement() {
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/joints/1",
                () -> load(skinnedGlb(VALID_NODES, "[3]",
                        "[{\"inverseBindMatrices\":6,\"joints\":[0,0]}]", 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void rejectsInvalidJointNodeReferenceAtTheExactListElement() {
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/joints/1",
                () -> load(skinnedGlb(VALID_NODES, "[3]",
                        "[{\"inverseBindMatrices\":6,\"joints\":[0,99]}]", 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void validatesUnusedSkinsAndInactiveNodeSkinReferences() {
        String nodesWithDetachedJoints = """
                [{"children":[1],"name":"JointRoot"},{"name":"JointChild"},
                 {"mesh":0,"name":"SkinnedMesh","skin":0},{"children":[0,2],"name":"SceneRoot"},
                 {"name":"DetachedA"},{"name":"DetachedB"}]
                """;
        String skins = """
                [{"inverseBindMatrices":6,"joints":[0,1]},
                 {"inverseBindMatrices":6,"joints":[4,5]}]
                """;
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/1/joints",
                () -> load(skinnedGlb(nodesWithDetachedJoints, "[3,4,5]", skins, 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));

        String nodesWithInactiveReference = VALID_NODES.substring(0, VALID_NODES.lastIndexOf(']'))
                + ",{\"mesh\":0,\"name\":\"InactiveMesh\",\"skin\":99}]";
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/nodes/4/skin",
                () -> load(skinnedGlb(nodesWithInactiveReference, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void rejectsDuplicatePositiveInfluenceButAllowsZeroWeightDuplicates() {
        float[] duplicatePositive = PADDED_WEIGHTS.clone();
        duplicatePositive[0] = 0.5f;
        duplicatePositive[1] = 0.5f;
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/meshes/0/primitives/0/attributes/JOINTS_0",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                        PADDED_JOINTS, duplicatePositive, identityMatrices(2))));

        assertDoesNotThrow(() -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void rejectsOutOfRangePaletteIndexEvenInZeroWeightPadding() {
        int[] invalidPaletteIndex = PADDED_JOINTS.clone();
        invalidPaletteIndex[1] = 2;
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/meshes/0/primitives/0/attributes/JOINTS_0",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                        invalidPaletteIndex, PADDED_WEIGHTS, identityMatrices(2))));
    }

    @Test
    void enforcesRequiredFloatMat4AndExactInverseBindCount() {
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/inverseBindMatrices",
                () -> load(skinnedGlb(VALID_NODES, "[3]", "[{\"joints\":[0,1]}]",
                        2, "MAT4", 5126, PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(2))));
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/inverseBindMatrices",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN,
                        2, "VEC4", 5126, PADDED_JOINTS, PADDED_WEIGHTS, new float[8])));
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/inverseBindMatrices",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN,
                        2, "MAT4", 5123, PADDED_JOINTS, PADDED_WEIGHTS, null)));
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/inverseBindMatrices",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN,
                        1, "MAT4", 5126, PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(1))));
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/inverseBindMatrices",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN,
                        3, "MAT4", 5126, PADDED_JOINTS, PADDED_WEIGHTS, identityMatrices(3))));
    }

    @Test
    void rejectsNonAffineAndNonFiniteInverseBindMatrices() {
        float[] nonAffine = identityMatrices(2);
        nonAffine[3] = 0.25f;
        assertDiagnostic(BlendDiagnosticCodes.SKIN_001, "/skins/0/inverseBindMatrices",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, nonAffine)));

        float[] nonFinite = identityMatrices(2);
        nonFinite[5] = Float.NaN;
        assertDiagnostic(BlendDiagnosticCodes.GLB_015, "/accessors/6",
                () -> load(skinnedGlb(VALID_NODES, "[3]", VALID_SKIN, 2, "MAT4", 5126,
                        PADDED_JOINTS, PADDED_WEIGHTS, nonFinite)));
    }

    @Test
    void modelPrimitiveDefensivelyRejectsDuplicatePositiveInfluences() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] uvs = {0, 0, 1, 0, 0, 1};
        int[] indices = {0, 1, 2};
        float[] duplicatePositive = PADDED_WEIGHTS.clone();
        duplicatePositive[0] = 0.5f;
        duplicatePositive[1] = 0.5f;

        assertThrows(IllegalArgumentException.class,
                () -> new MeshPrimitive("SkinMaterial", positions, normals, uvs, indices, PADDED_JOINTS, duplicatePositive));
        assertDoesNotThrow(
                () -> new MeshPrimitive("SkinMaterial", positions, normals, uvs, indices, PADDED_JOINTS, PADDED_WEIGHTS));
    }

    private static ModelAsset load(byte[] glbBytes) {
        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, """
                {"format_version":1,"profile":"blendlib:skinned_v1","mesh":"strict:models3d/skin_test.glb",
                 "materials":{"SkinMaterial":{"base_color":"strict:textures/skin.png"}}}
                """.getBytes(StandardCharsets.UTF_8));
        AssetBytes glb = new AssetBytes(MESH_ID, glbBytes);
        return new ModelAssetLoader().load(MODEL_KEY, descriptor, requested -> glb);
    }

    private static void assertDiagnostic(String code, String location, ThrowingRunnable action) {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, action::run);
        assertEquals(code, exception.diagnostic().code());
        assertEquals(location, exception.diagnostic().location());
    }

    private static byte[] skinnedGlb(
            String nodes,
            String sceneRoots,
            String skins,
            int inverseBindCount,
            String inverseBindType,
            int inverseBindComponentType,
            int[] jointValues,
            float[] weights,
            float[] inverseBindValues) {
        if (jointValues.length != 12 || weights.length != 12) {
            throw new IllegalArgumentException("Fixture requires three VEC4 skin vertices");
        }
        int inverseBindComponents = switch (inverseBindType) {
            case "MAT4" -> 16;
            case "VEC4" -> 4;
            default -> throw new IllegalArgumentException("Unsupported fixture IBM type: " + inverseBindType);
        };
        int inverseBindComponentBytes = switch (inverseBindComponentType) {
            case 5126 -> Float.BYTES;
            case 5123 -> Short.BYTES;
            default -> throw new IllegalArgumentException("Unsupported fixture IBM component type: " + inverseBindComponentType);
        };
        int inverseBindOffset = 164;
        int inverseBindByteLength = Math.multiplyExact(Math.multiplyExact(inverseBindCount, inverseBindComponents), inverseBindComponentBytes);
        byte[] binary = new byte[inverseBindOffset + inverseBindByteLength];
        ByteBuffer payload = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        putFloats(payload,
                0, 0, 0, 1, 0, 0, 0, 1, 0,
                0, 0, 1, 0, 0, 1, 0, 0, 1,
                0, 0, 1, 0, 0, 1);
        payload.position(96);
        for (int joint : jointValues) {
            if (joint < 0 || joint > 255) {
                throw new IllegalArgumentException("Fixture joint is outside U8 range");
            }
            payload.put((byte) joint);
        }
        payload.position(108);
        putFloats(payload, weights);
        payload.position(156).putShort((short) 0).putShort((short) 1).putShort((short) 2);
        payload.position(inverseBindOffset);
        if (inverseBindComponentType == 5126) {
            if (inverseBindValues == null || inverseBindValues.length != inverseBindCount * inverseBindComponents) {
                throw new IllegalArgumentException("Fixture FLOAT IBM value count does not match its accessor");
            }
            putFloats(payload, inverseBindValues);
        }

        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":%d}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},
                  {"buffer":0,"byteOffset":36,"byteLength":36},{"buffer":0,"byteOffset":72,"byteLength":24},
                  {"buffer":0,"byteOffset":96,"byteLength":12},{"buffer":0,"byteOffset":108,"byteLength":48},
                  {"buffer":0,"byteOffset":156,"byteLength":6},{"buffer":0,"byteOffset":164,"byteLength":%d}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                  {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                  {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                  {"bufferView":3,"componentType":5121,"count":3,"type":"VEC4"},
                  {"bufferView":4,"componentType":5126,"count":3,"type":"VEC4"},
                  {"bufferView":5,"componentType":5123,"count":3,"type":"SCALAR"},
                  {"bufferView":6,"componentType":%d,"count":%d,"type":"%s"}],
                 "materials":[{"name":"SkinMaterial"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"JOINTS_0":3,"WEIGHTS_0":4},
                  "indices":5,"material":0}]}],"nodes":%s,"scenes":[{"nodes":%s}],"scene":0,"skins":%s}
                """.formatted(binary.length, inverseBindByteLength, inverseBindComponentType, inverseBindCount,
                inverseBindType, nodes, sceneRoots, skins);
        return buildGlb(json, binary);
    }

    private static float[] identityMatrices(int count) {
        float[] matrices = new float[count * 16];
        for (int matrix = 0; matrix < count; matrix++) {
            int offset = matrix * 16;
            matrices[offset] = 1.0f;
            matrices[offset + 5] = 1.0f;
            matrices[offset + 10] = 1.0f;
            matrices[offset + 15] = 1.0f;
        }
        return matrices;
    }

    private static void putFloats(ByteBuffer output, float... values) {
        for (float value : values) {
            output.putFloat(value);
        }
    }

    private static byte[] buildGlb(String json, byte[] binary) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = (jsonBytes.length + 3) & ~3;
        int paddedBinaryLength = (binary.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJsonLength + 8 + paddedBinaryLength;
        ByteBuffer output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(totalLength);
        output.putInt(paddedJsonLength).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + paddedJsonLength) {
            output.put((byte) 0x20);
        }
        output.putInt(paddedBinaryLength).putInt(0x004E4942).put(binary);
        while (output.hasRemaining()) {
            output.put((byte) 0);
        }
        return output.array();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
