package com.liy.blendlib.fabric.client.render.x7gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused T4 proof for bounded staging, CPU predicates, and the independent float-parity gate. */
class X7SkinnedSourceTexelStagingTest {
    private static final float TOTAL_EPSILON = 1.0e-8F;
    private static final float MATRIX_EPSILON = 1.0e-12F;

    @Test
    void capturesExactlyEightKiBOfBoundedColumnMajorPositionAndNormalPalettes() {
        SkinPalette palette = palette(matrix(2.0F, 3.0F, 4.0F, 1.0F));
        X7GpuSkinPaletteSnapshot.Attempt attempt = X7GpuSkinPaletteSnapshot.tryCapture(palette);

        assertTrue(attempt.eligible());
        X7GpuSkinPaletteSnapshot snapshot = attempt.snapshotOrNull();
        assertSame(palette, snapshot.exactPaletteIdentity());
        assertTrue(snapshot.matchesExactly(palette));
        assertEquals(1, snapshot.jointCount());
        ByteBuffer bytes = snapshot.bytesForUpload();
        assertEquals(X7GpuSkinPaletteSnapshot.TOTAL_BYTES, bytes.remaining());
        assertEquals(2.0F, bytes.getFloat(0));
        assertEquals(3.0F, bytes.getFloat(5 * Float.BYTES));
        assertEquals(4.0F, bytes.getFloat(10 * Float.BYTES));
        assertEquals(0.5F, bytes.getFloat(X7GpuSkinPaletteSnapshot.POSITION_PALETTE_BYTES));
        assertEquals(1.0F / 3.0F, bytes.getFloat(X7GpuSkinPaletteSnapshot.POSITION_PALETTE_BYTES + 5 * Float.BYTES));
        assertEquals(0.25F, bytes.getFloat(X7GpuSkinPaletteSnapshot.POSITION_PALETTE_BYTES + 10 * Float.BYTES));
        snapshot.close();
        assertThrows(IllegalStateException.class, snapshot::bytesForUpload);
    }

    @Test
    void determinantThresholdUsesStrictAbsWithBothSigns() {
        assertPaletteEligibility(MATRIX_EPSILON, false);
        assertPaletteEligibility(Math.nextDown(MATRIX_EPSILON), false);
        assertPaletteEligibility(Math.nextUp(MATRIX_EPSILON), true);
        assertPaletteEligibility(-MATRIX_EPSILON, false);
        assertPaletteEligibility(Math.nextUp(-MATRIX_EPSILON), false);
        assertPaletteEligibility(Math.nextDown(-MATRIX_EPSILON), true);
    }

    @Test
    void totalWeightAndHomogeneousThresholdsUseTheCpuStrictBoundary() {
        X7GpuSkinPaletteSnapshot identity = snapshot(identityPalette());
        assertSourceEligibility(identity, source(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                new float[] {TOTAL_EPSILON, 0.0F, 0.0F, 0.0F}), false);
        assertSourceEligibility(identity, source(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                new float[] {Math.nextDown(TOTAL_EPSILON), 0.0F, 0.0F, 0.0F}), false);
        assertSourceEligibility(identity, source(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                new float[] {Math.nextUp(TOTAL_EPSILON), 0.0F, 0.0F, 0.0F}), true);

        assertHomogeneousEligibility(MATRIX_EPSILON, false);
        assertHomogeneousEligibility(Math.nextDown(MATRIX_EPSILON), false);
        assertHomogeneousEligibility(Math.nextUp(MATRIX_EPSILON), true);
        assertHomogeneousEligibility(-MATRIX_EPSILON, false);
        assertHomogeneousEligibility(Math.nextUp(-MATRIX_EPSILON), false);
        assertHomogeneousEligibility(Math.nextDown(-MATRIX_EPSILON), true);
    }

    @Test
    void normalLengthAtOrBelowEpsilonWritesZeroInsteadOfRejecting() {
        X7GpuSkinPaletteSnapshot snapshot = snapshot(identityPalette());
        assertZeroNormal(snapshot, Math.nextDown(TOTAL_EPSILON));
        assertZeroNormal(snapshot, TOTAL_EPSILON);

        X7SkinnedSourceTexelStaging.Attempt above = X7SkinnedSourceTexelStaging.tryStage(
                source(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Math.nextUp(TOTAL_EPSILON), oneWeight()), snapshot);
        assertTrue(above.eligible());
        assertTrue(above.stagingOrNull().gpuNormalsForTest()[2] > 0.0F);
        above.stagingOrNull().close();
    }

    @Test
    void productionGeometryUsesTheCoreResultAndTheGpuFloatParityGateBeforeUpload() {
        PreparedSkinnedGeometry geometry = PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                "skinned",
                new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F}));
        SkinPalette palette = identityPalette();

        X7SkinnedSourceTexelStaging.Attempt attempt = X7SkinnedSourceTexelStaging.tryStage(geometry, palette);

        assertTrue(attempt.eligible());
        X7SkinnedSourceTexelStaging staging = attempt.stagingOrNull();
        assertEquals(3, staging.vertexCount());
        assertEquals(3 * X7SkinnedSourceTexelStaging.RECORD_BYTES, staging.sourceByteCount());
        assertArrayEquals(CpuSkinner.skin(geometry, palette).positions(), staging.cpuPositionsForTest(), 1.0e-6F);
        assertArrayEquals(CpuSkinner.skin(geometry, palette).normals(), staging.cpuNormalsForTest(), 1.0e-6F);
        assertArrayEquals(staging.cpuPositionsForTest(), staging.gpuPositionsForTest(), 1.0e-5F);
        assertArrayEquals(staging.cpuNormalsForTest(), staging.gpuNormalsForTest(), 1.0e-5F);

        ByteBuffer bytes = staging.bytesForUpload();
        assertEquals(X7SkinnedSourceTexelStaging.RECORD_BYTES * 3, bytes.remaining());
        assertEquals(0, Short.toUnsignedInt(bytes.getShort(0)));
        assertEquals(1.0F, bytes.getFloat(X7SkinnedSourceTexelStaging.WEIGHT_OFFSET_BYTES));
        assertEquals(1.0F, bytes.getFloat(X7SkinnedSourceTexelStaging.NORMAL_OFFSET_BYTES + 2 * Float.BYTES));
        staging.close();
    }

    @Test
    void invalidSourceOrDeviceBoundReturnsCpuFallbackWithoutPublishingBytes() {
        X7GpuSkinPaletteSnapshot snapshot = snapshot(identityPalette());
        X7SkinnedSourceTexelStaging.Attempt invalidJoint = X7SkinnedSourceTexelStaging.tryStage(
                new RawSource(1, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                        new int[] {1, 0, 0, 0}, oneWeight()), snapshot);
        assertFalse(invalidJoint.eligible());
        assertTrue(invalidJoint.fallbackCauseOrNull() instanceof IllegalArgumentException);

        X7SkinnedSourceTexelStaging.Attempt tooLarge = X7SkinnedSourceTexelStaging.tryStage(
                new RawSource(X7SkinnedSourceTexelStaging.MAX_VERTICES + 1, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                        new int[] {0, 0, 0, 0}, oneWeight()), snapshot);
        assertFalse(tooLarge.eligible());
        assertTrue(tooLarge.fallbackCauseOrNull() instanceof IllegalArgumentException);
    }

    private static void assertPaletteEligibility(float determinant, boolean eligible) {
        assertEquals(eligible, X7GpuSkinPaletteSnapshot.tryCapture(palette(matrix(determinant, 1.0F, 1.0F, 1.0F))).eligible());
    }

    private static void assertHomogeneousEligibility(float w, boolean eligible) {
        X7GpuSkinPaletteSnapshot snapshot = snapshot(palette(matrix(1.0F, 1.0F, 1.0F, w)));
        assertSourceEligibility(snapshot,
                source(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, oneWeight()), eligible);
    }

    private static void assertSourceEligibility(
            X7GpuSkinPaletteSnapshot snapshot, X7SkinnedSourceTexelStaging.GeometrySource source, boolean eligible) {
        X7SkinnedSourceTexelStaging.Attempt attempt = X7SkinnedSourceTexelStaging.tryStage(source, snapshot);
        assertEquals(eligible, attempt.eligible());
        if (attempt.eligible()) {
            attempt.stagingOrNull().close();
        }
    }

    private static void assertZeroNormal(X7GpuSkinPaletteSnapshot snapshot, float z) {
        X7SkinnedSourceTexelStaging.Attempt attempt = X7SkinnedSourceTexelStaging.tryStage(
                source(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, z, oneWeight()), snapshot);
        assertTrue(attempt.eligible());
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.0F}, attempt.stagingOrNull().cpuNormalsForTest());
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.0F}, attempt.stagingOrNull().gpuNormalsForTest());
        attempt.stagingOrNull().close();
    }

    private static X7GpuSkinPaletteSnapshot snapshot(SkinPalette palette) {
        X7GpuSkinPaletteSnapshot.Attempt attempt = X7GpuSkinPaletteSnapshot.tryCapture(palette);
        assertTrue(attempt.eligible());
        return attempt.snapshotOrNull();
    }

    private static SkinPalette identityPalette() {
        return palette(matrix(1.0F, 1.0F, 1.0F, 1.0F));
    }

    private static SkinPalette palette(float[] inverseBind) {
        Skin skin = new Skin("skin", 0, List.of(0), inverseBind);
        NodePalette nodes = NodePalette.from(
                new LocalPose(Map.of(0, Transform.IDENTITY)),
                List.of(new ModelNode(0, "joint", Transform.IDENTITY, List.of(), -1, -1, false)));
        return SkinPalette.from(skin, nodes);
    }

    private static float[] matrix(float m00, float m11, float m22, float m33) {
        return new float[] {
            m00, 0.0F, 0.0F, 0.0F,
            0.0F, m11, 0.0F, 0.0F,
            0.0F, 0.0F, m22, 0.0F,
            0.0F, 0.0F, 0.0F, m33
        };
    }

    private static RawSource source(
            float positionX, float positionY, float positionZ, float normalX, float normalY, float normalZ, float[] weights) {
        return new RawSource(1, positionX, positionY, positionZ, normalX, normalY, normalZ,
                new int[] {0, 0, 0, 0}, weights);
    }

    private static float[] oneWeight() {
        return new float[] {1.0F, 0.0F, 0.0F, 0.0F};
    }

    private record RawSource(
            int vertexCount,
            float positionX,
            float positionY,
            float positionZ,
            float normalX,
            float normalY,
            float normalZ,
            int[] joints,
            float[] weights) implements X7SkinnedSourceTexelStaging.GeometrySource {
        @Override
        public float positionX(int vertex) {
            return positionX;
        }

        @Override
        public float positionY(int vertex) {
            return positionY;
        }

        @Override
        public float positionZ(int vertex) {
            return positionZ;
        }

        @Override
        public float normalX(int vertex) {
            return normalX;
        }

        @Override
        public float normalY(int vertex) {
            return normalY;
        }

        @Override
        public float normalZ(int vertex) {
            return normalZ;
        }

        @Override
        public int joint(int vertex, int influence) {
            return joints[influence];
        }

        @Override
        public float weight(int vertex, int influence) {
            return weights[influence];
        }
    }
}
