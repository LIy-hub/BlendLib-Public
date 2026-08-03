package com.liy.blendlib.showcase.perf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P7ReferenceAssetGeneratorPerfTest {
    @Test
    void generatorCreatesExactRigidAndSkinnedGeometryContracts() {
        P7ReferenceAssetGenerator.GeneratedAsset rigid = P7ReferenceAssetGenerator.generate(asset(P7ReferenceScenario.Kind.RIGID));
        P7ReferenceAssetGenerator.GeneratedAsset skinned = P7ReferenceAssetGenerator.generate(asset(P7ReferenceScenario.Kind.SKINNED));

        assertEquals(30_000, rigid.vertexCount());
        assertEquals(30_000, rigid.indexCount());
        assertEquals(0, rigid.skinJointCount());
        assertEquals(60_000, skinned.vertexCount());
        assertEquals(60_000, skinned.indexCount());
        assertEquals(64, skinned.skinJointCount());
        assertGlb(rigid.glb(), "\"count\":30000", "\"indices\":3", "P7RigidSurface");
        assertGlb(skinned.glb(), "\"count\":60000", "\"JOINTS_0\":3", "\"joints\":[1,2,3,4");
        assertTrue(json(rigid.glb()).contains("\"min\":[0.0,0.0,0.0]"));
        assertTrue(json(skinned.glb()).contains("\"min\":[0.0],\"max\":[1.0]"));
        assertTrue(json(skinned.glb()).contains("\"inverseBindMatrices\":6"));
        assertTrue(json(skinned.glb()).contains("\"animations\""));
    }

    @Test
    void skinnedGeneratorMakesEveryWeightedJointExplicitlyInvertible() {
        P7ReferenceAssetGenerator.GeneratedAsset skinned = P7ReferenceAssetGenerator.generate(asset(P7ReferenceScenario.Kind.SKINNED));
        String generatedJson = json(skinned.glb());
        int expectedNodeCount = skinned.skinJointCount() + 1;
        assertEquals(expectedNodeCount, occurrences(generatedJson, "\"translation\":[0.0,0.0,0.0]"));
        assertEquals(expectedNodeCount, occurrences(generatedJson, "\"rotation\":[0.0,0.0,0.0,1.0]"));
        assertEquals(expectedNodeCount, occurrences(generatedJson, "\"scale\":[1.0,1.0,1.0]"));

        ByteBuffer binary = binary(skinned.glb());
        int vertices = skinned.vertexCount();
        int jointsOffset = Math.addExact(
                Math.addExact(vertices * 3 * Float.BYTES, vertices * 3 * Float.BYTES),
                vertices * 2 * Float.BYTES);
        int weightsOffset = Math.addExact(jointsOffset, vertices * 4);
        int inverseBindOffset = Math.addExact(
                Math.addExact(weightsOffset, vertices * 4 * Float.BYTES),
                vertices * Short.BYTES);
        for (int vertex = 0; vertex < vertices; vertex++) {
            int jointOffset = jointsOffset + vertex * 4;
            assertEquals(vertex % skinned.skinJointCount(), Byte.toUnsignedInt(binary.get(jointOffset)));
            assertEquals(0, Byte.toUnsignedInt(binary.get(jointOffset + 1)));
            assertEquals(0, Byte.toUnsignedInt(binary.get(jointOffset + 2)));
            assertEquals(0, Byte.toUnsignedInt(binary.get(jointOffset + 3)));
            int weightOffset = weightsOffset + vertex * 4 * Float.BYTES;
            assertEquals(1.0f, binary.getFloat(weightOffset));
            assertEquals(0.0f, binary.getFloat(weightOffset + Float.BYTES));
            assertEquals(0.0f, binary.getFloat(weightOffset + 2 * Float.BYTES));
            assertEquals(0.0f, binary.getFloat(weightOffset + 3 * Float.BYTES));
        }
        for (int joint = 0; joint < skinned.skinJointCount(); joint++) {
            int matrixOffset = inverseBindOffset + joint * 16 * Float.BYTES;
            assertEquals(1.0d, determinant3x3(binary, matrixOffset), 1.0e-7d,
                    "inverse-bind determinant for joint slot " + joint);
        }
        int animationTimesOffset = inverseBindOffset + skinned.skinJointCount() * 16 * Float.BYTES;
        int animatedRotationOffset = animationTimesOffset + 2 * Float.BYTES + 4 * Float.BYTES;
        double x = binary.getFloat(animatedRotationOffset);
        double y = binary.getFloat(animatedRotationOffset + Float.BYTES);
        double z = binary.getFloat(animatedRotationOffset + 2 * Float.BYTES);
        double w = binary.getFloat(animatedRotationOffset + 3 * Float.BYTES);
        assertEquals(1.0d, x * x + y * y + z * z + w * w, 1.0e-6d);
        assertEquals(1.0d, rotationDeterminant(x, y, z, w), 1.0e-6d);
    }

    @Test
    void generatorIsDeterministicAndWritesOnlyTheExplicitBundleRoot(@TempDir Path temporaryDirectory) throws Exception {
        P7ReferenceScenario.Asset rigidAsset = asset(P7ReferenceScenario.Kind.RIGID);
        assertArrayEquals(P7ReferenceAssetGenerator.generate(rigidAsset).glb(), P7ReferenceAssetGenerator.generate(rigidAsset).glb());

        P7ReferenceAssetGenerator.writeBundle(temporaryDirectory);

        Path namespaceRoot = temporaryDirectory.resolve("assets/blendlib_showcase");
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("pack.mcmeta")));
        String packMetadata = Files.readString(temporaryDirectory.resolve("pack.mcmeta"));
        assertTrue(packMetadata.contains("\"pack_format\": 84"));
        assertTrue(packMetadata.contains("\"min_format\": 84"));
        assertTrue(packMetadata.contains("\"max_format\": 84"));
        assertTrue(Files.isRegularFile(namespaceRoot.resolve("p7/reference-scene.json")));
        assertTrue(Files.isRegularFile(namespaceRoot.resolve("blend_models/p7/rigid_10k.json")));
        assertTrue(Files.isRegularFile(namespaceRoot.resolve("blend_models/p7/skinned_20k_64j.json")));
        assertTrue(Files.isRegularFile(namespaceRoot.resolve("models3d/p7/rigid_10k.glb")));
        assertTrue(Files.isRegularFile(namespaceRoot.resolve("models3d/p7/skinned_20k_64j.glb")));
        assertEquals(P7ReferenceScenario.standard().canonicalManifestJson(),
                Files.readString(namespaceRoot.resolve("p7/reference-scene.json")));
        assertTrue(Files.readString(namespaceRoot.resolve("blend_models/p7/skinned_20k_64j.json"))
                .contains("\"clip\": \"p7_loop\""));
    }

    private static P7ReferenceScenario.Asset asset(P7ReferenceScenario.Kind kind) {
        return P7ReferenceScenario.standard().assets().stream()
                .filter(asset -> asset.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static void assertGlb(byte[] glb, String... expectedJsonFragments) {
        ByteBuffer buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x46546C67, buffer.getInt());
        assertEquals(2, buffer.getInt());
        assertEquals(glb.length, buffer.getInt());
        int jsonLength = buffer.getInt();
        assertEquals(0x4E4F534A, buffer.getInt());
        byte[] json = new byte[jsonLength];
        buffer.get(json);
        int binaryLength = buffer.getInt();
        assertEquals(0x004E4942, buffer.getInt());
        assertEquals(binaryLength, buffer.remaining());
        String decoded = new String(json, StandardCharsets.UTF_8).stripTrailing();
        for (String expected : expectedJsonFragments) {
            assertTrue(decoded.contains(expected), () -> "Missing GLB JSON fragment: " + expected);
        }
    }

    private static String json(byte[] glb) {
        ByteBuffer buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(12);
        int jsonLength = buffer.getInt();
        buffer.getInt();
        byte[] json = new byte[jsonLength];
        buffer.get(json);
        return new String(json, StandardCharsets.UTF_8).stripTrailing();
    }

    private static ByteBuffer binary(byte[] glb) {
        ByteBuffer source = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        source.position(12);
        int jsonLength = source.getInt();
        source.getInt();
        source.position(source.position() + jsonLength);
        int binaryLength = source.getInt();
        assertEquals(0x004E4942, source.getInt());
        byte[] binary = new byte[binaryLength];
        source.get(binary);
        return ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(fragment, offset)) >= 0; offset += fragment.length()) {
            count++;
        }
        return count;
    }

    private static double determinant3x3(ByteBuffer binary, int matrixOffset) {
        double a00 = binary.getFloat(matrixOffset);
        double a01 = binary.getFloat(matrixOffset + 4 * Float.BYTES);
        double a02 = binary.getFloat(matrixOffset + 8 * Float.BYTES);
        double a10 = binary.getFloat(matrixOffset + Float.BYTES);
        double a11 = binary.getFloat(matrixOffset + 5 * Float.BYTES);
        double a12 = binary.getFloat(matrixOffset + 9 * Float.BYTES);
        double a20 = binary.getFloat(matrixOffset + 2 * Float.BYTES);
        double a21 = binary.getFloat(matrixOffset + 6 * Float.BYTES);
        double a22 = binary.getFloat(matrixOffset + 10 * Float.BYTES);
        return a00 * (a11 * a22 - a12 * a21)
                - a01 * (a10 * a22 - a12 * a20)
                + a02 * (a10 * a21 - a11 * a20);
    }

    private static double rotationDeterminant(double x, double y, double z, double w) {
        double xx = x * x;
        double yy = y * y;
        double zz = z * z;
        double xy = x * y;
        double xz = x * z;
        double yz = y * z;
        double wx = w * x;
        double wy = w * y;
        double wz = w * z;
        double a00 = 1.0d - 2.0d * (yy + zz);
        double a01 = 2.0d * (xy - wz);
        double a02 = 2.0d * (xz + wy);
        double a10 = 2.0d * (xy + wz);
        double a11 = 1.0d - 2.0d * (xx + zz);
        double a12 = 2.0d * (yz - wx);
        double a20 = 2.0d * (xz - wy);
        double a21 = 2.0d * (yz + wx);
        double a22 = 1.0d - 2.0d * (xx + yy);
        return a00 * (a11 * a22 - a12 * a21)
                - a01 * (a10 * a22 - a12 * a20)
                + a02 * (a10 * a21 - a11 * a20);
    }

}
