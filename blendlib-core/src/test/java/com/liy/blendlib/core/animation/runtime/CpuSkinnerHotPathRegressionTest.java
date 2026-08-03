package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.core.model.Matrix4;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CpuSkinnerHotPathRegressionTest {
    private static final double EPSILON = 1.0e-8;

    @Test
    void preservesLegacyColumnMajorAndInverseTransposeNumerics() {
        float halfRoot = (float) Math.sqrt(0.5d);
        Transform rotatedScaledJoint = new Transform(
                new Vec3(2.0f, -1.0f, 0.5f),
                new Quaternion(0.0f, 0.0f, halfRoot, halfRoot),
                new Vec3(2.0f, 2.0f, 2.0f));
        Transform translatedScaledJoint = new Transform(
                new Vec3(-4.0f, 1.0f, 2.0f),
                Quaternion.IDENTITY,
                new Vec3(0.5f, 0.5f, 0.5f));
        SkinPalette palette = palette(rotatedScaledJoint, translatedScaledJoint);
        PreparedSkinnedGeometry geometry = PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                "fixture_material",
                new float[] {
                    1.0f, 2.0f, 3.0f,
                    2.0f, -1.0f, 0.5f,
                    0.0f, 0.0f, 0.0f
                },
                new float[] {
                    1.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 1.0f,
                    1.0f, 0.0f, 0.0f
                },
                new float[] {
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.0f, 1.0f
                },
                new int[] {0, 1, 2},
                new int[] {
                    0, 1, 0, 0,
                    0, 1, 0, 0,
                    1, 0, 0, 0
                },
                new float[] {
                    1.0f, 0.0f, 0.0f, 0.0f,
                    0.25f, 0.75f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 0.0f
                }));

        CpuSkinnedMesh expectedLegacyOutput = legacySkin(geometry, palette);
        CpuSkinnedMesh actual = CpuSkinner.skin(geometry, palette);

        assertArrayEquals(expectedLegacyOutput.positions(), actual.positions());
        assertArrayEquals(expectedLegacyOutput.normals(), actual.normals());
        assertEquals(-2.0f, actual.positions()[0], 1.0e-5f);
        assertEquals(1.0f, actual.positions()[1], 1.0e-5f);
        assertEquals(6.5f, actual.positions()[2], 1.0e-5f);
        assertEquals((float) (1.0d / Math.sqrt(2.0d)), actual.normals()[0], 1.0e-5f);
        assertEquals((float) (-1.0d / Math.sqrt(2.0d)), actual.normals()[1], 1.0e-5f);
        assertEquals(0.0f, actual.normals()[2], 1.0e-5f);

        CpuSkinnedMesh repeated = CpuSkinner.skin(geometry, palette);
        assertArrayEquals(actual.positions(), repeated.positions());
        assertArrayEquals(actual.normals(), repeated.normals());
    }

    @Test
    void cpuSkinningUsesPrimitiveScratchInsteadOfPerInfluenceVec3Allocations() throws IOException {
        Path runtimeSource = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "main",
                "java",
                "com",
                "liy",
                "blendlib",
                "core",
                "animation",
                "runtime");
        String skinningSource = Files.readString(runtimeSource.resolve("CpuSkinner.java"));
        String paletteSource = Files.readString(runtimeSource.resolve("SkinPalette.java"));

        assertTrue(skinningSource.contains("new float[6]"));
        assertTrue(skinningSource.contains("palette.transformPointInto("));
        assertTrue(skinningSource.contains("palette.transformNormalInto("));
        assertFalse(skinningSource.contains("new Vec3"));
        assertFalse(skinningSource.contains("palette.transformPoint("));
        assertFalse(skinningSource.contains("palette.transformNormal("));
        assertTrue(paletteSource.contains("void transformPointInto("));
        assertTrue(paletteSource.contains("void transformNormalInto("));
        assertFalse(paletteSource.contains("new Vec3"));
    }

    private static SkinPalette palette(Transform first, Transform second) {
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "first", Transform.IDENTITY, List.of(), -1, -1, false),
                new ModelNode(1, "second", Transform.IDENTITY, List.of(), -1, -1, false));
        LocalPose pose = new LocalPose(Map.of(0, first, 1, second));
        return SkinPalette.from(new Skin("fixture", 0, List.of(0, 1), identityMatrices(2)), NodePalette.from(pose, nodes));
    }

    private static float[] identityMatrices(int count) {
        float[] values = new float[count * 16];
        for (int index = 0; index < count; index++) {
            int offset = index * 16;
            values[offset] = 1.0f;
            values[offset + 5] = 1.0f;
            values[offset + 10] = 1.0f;
            values[offset + 15] = 1.0f;
        }
        return values;
    }

    private static CpuSkinnedMesh legacySkin(PreparedSkinnedGeometry geometry, SkinPalette palette) {
        float[] sourcePositions = geometry.positionsForSkinning();
        float[] sourceNormals = geometry.normalsForSkinning();
        int[] joints = geometry.jointsForSkinning();
        float[] weights = geometry.weightsForSkinning();
        float[] positions = new float[sourcePositions.length];
        float[] normals = new float[sourceNormals.length];
        for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
            int positionOffset = vertex * 3;
            int jointOffset = vertex * 4;
            Vec3 sourcePosition = new Vec3(
                    sourcePositions[positionOffset], sourcePositions[positionOffset + 1], sourcePositions[positionOffset + 2]);
            Vec3 sourceNormal = new Vec3(
                    sourceNormals[positionOffset], sourceNormals[positionOffset + 1], sourceNormals[positionOffset + 2]);
            double totalWeight = 0.0;
            double positionX = 0.0;
            double positionY = 0.0;
            double positionZ = 0.0;
            double normalX = 0.0;
            double normalY = 0.0;
            double normalZ = 0.0;
            for (int influence = 0; influence < 4; influence++) {
                float weight = weights[jointOffset + influence];
                if (!Float.isFinite(weight) || weight < 0.0f) {
                    throw new IllegalArgumentException("Skin weights must be finite and non-negative");
                }
                if (weight <= 0.0f) {
                    continue;
                }
                int joint = joints[jointOffset + influence];
                if (joint < 0 || joint >= palette.jointCount()) {
                    throw new IllegalArgumentException("Skin joint slot is outside the palette: " + joint);
                }
                Vec3 posedPosition = legacyTransformPoint(palette.matrix(joint), sourcePosition);
                Vec3 posedNormal = legacyTransformNormal(palette.matrix(joint), sourceNormal);
                totalWeight += weight;
                positionX += weight * posedPosition.x();
                positionY += weight * posedPosition.y();
                positionZ += weight * posedPosition.z();
                normalX += weight * posedNormal.x();
                normalY += weight * posedNormal.y();
                normalZ += weight * posedNormal.z();
            }
            if (!Double.isFinite(totalWeight) || totalWeight <= EPSILON) {
                throw new IllegalArgumentException("Skinned vertex must have a positive finite total weight");
            }
            positions[positionOffset] = finiteCpu(positionX / totalWeight);
            positions[positionOffset + 1] = finiteCpu(positionY / totalWeight);
            positions[positionOffset + 2] = finiteCpu(positionZ / totalWeight);
            double normalLength = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            if (!Double.isFinite(normalLength) || normalLength <= EPSILON) {
                normals[positionOffset] = 0.0f;
                normals[positionOffset + 1] = 0.0f;
                normals[positionOffset + 2] = 0.0f;
            } else {
                normals[positionOffset] = finiteCpu(normalX / normalLength);
                normals[positionOffset + 1] = finiteCpu(normalY / normalLength);
                normals[positionOffset + 2] = finiteCpu(normalZ / normalLength);
            }
        }
        return new CpuSkinnedMesh(geometry.topology(), positions, normals);
    }

    private static Vec3 legacyTransformPoint(Matrix4 matrix, Vec3 point) {
        double x = matrix.get(0, 0) * point.x() + matrix.get(1, 0) * point.y() + matrix.get(2, 0) * point.z()
                + matrix.get(3, 0);
        double y = matrix.get(0, 1) * point.x() + matrix.get(1, 1) * point.y() + matrix.get(2, 1) * point.z()
                + matrix.get(3, 1);
        double z = matrix.get(0, 2) * point.x() + matrix.get(1, 2) * point.y() + matrix.get(2, 2) * point.z()
                + matrix.get(3, 2);
        double w = matrix.get(0, 3) * point.x() + matrix.get(1, 3) * point.y() + matrix.get(2, 3) * point.z()
                + matrix.get(3, 3);
        if (!Double.isFinite(w) || Math.abs(w) <= 1.0e-12) {
            throw new IllegalArgumentException("Skin point transform produced a non-finite or zero homogeneous coordinate");
        }
        return new Vec3(finitePalette(x / w), finitePalette(y / w), finitePalette(z / w));
    }

    private static Vec3 legacyTransformNormal(Matrix4 matrix, Vec3 normal) {
        double a00 = matrix.get(0, 0);
        double a01 = matrix.get(1, 0);
        double a02 = matrix.get(2, 0);
        double a10 = matrix.get(0, 1);
        double a11 = matrix.get(1, 1);
        double a12 = matrix.get(2, 1);
        double a20 = matrix.get(0, 2);
        double a21 = matrix.get(1, 2);
        double a22 = matrix.get(2, 2);
        double c00 = a11 * a22 - a12 * a21;
        double c01 = a12 * a20 - a10 * a22;
        double c02 = a10 * a21 - a11 * a20;
        double c10 = a02 * a21 - a01 * a22;
        double c11 = a00 * a22 - a02 * a20;
        double c12 = a01 * a20 - a00 * a21;
        double c20 = a01 * a12 - a02 * a11;
        double c21 = a02 * a10 - a00 * a12;
        double c22 = a00 * a11 - a01 * a10;
        double determinant = a00 * c00 + a01 * c10 + a02 * c20;
        if (!Double.isFinite(determinant) || Math.abs(determinant) <= 1.0e-12) {
            throw new IllegalArgumentException("Skin normal transform requires an invertible joint matrix");
        }
        return new Vec3(
                finitePalette((c00 * normal.x() + c01 * normal.y() + c02 * normal.z()) / determinant),
                finitePalette((c10 * normal.x() + c11 * normal.y() + c12 * normal.z()) / determinant),
                finitePalette((c20 * normal.x() + c21 * normal.y() + c22 * normal.z()) / determinant));
    }

    private static float finiteCpu(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("CPU skinning produced a non-finite component");
        }
        return result;
    }

    private static float finitePalette(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("Skin matrix calculation produced a non-finite component");
        }
        return result;
    }
}
