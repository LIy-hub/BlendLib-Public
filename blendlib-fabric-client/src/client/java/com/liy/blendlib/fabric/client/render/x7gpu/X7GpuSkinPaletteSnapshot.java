package com.liy.blendlib.fabric.client.render.x7gpu;

import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.model.Matrix4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, CPU-validated GPU skin palette candidate for one exact {@link SkinPalette} instance.
 *
 * <p>The candidate intentionally owns bytes only.  A later D1 integration owner may upload those bytes into a
 * transient uniform buffer after it has proved the exact same policy-resource record and submitted child.  This type
 * neither allocates a Minecraft resource nor creates another lifecycle authority.</p>
 */
final class X7GpuSkinPaletteSnapshot {
    static final int MAX_BONES = 64;
    static final int MATRIX_FLOATS = 16;
    static final int MATRIX_BYTES = MATRIX_FLOATS * Float.BYTES;
    static final int POSITION_PALETTE_BYTES = MAX_BONES * MATRIX_BYTES;
    static final int NORMAL_PALETTE_BYTES = MAX_BONES * MATRIX_BYTES;
    static final int TOTAL_BYTES = POSITION_PALETTE_BYTES + NORMAL_PALETTE_BYTES;
    private static final double DETERMINANT_EPSILON = 1.0e-12;

    private final SkinPalette exactPalette;
    private final int jointCount;
    private final float[][] positionMatrices;
    private final float[][] normalMatrices;
    private ByteBuffer bytes;
    private boolean closed;

    private X7GpuSkinPaletteSnapshot(
            SkinPalette exactPalette,
            int jointCount,
            float[][] positionMatrices,
            float[][] normalMatrices,
            ByteBuffer bytes) {
        this.exactPalette = exactPalette;
        this.jointCount = jointCount;
        this.positionMatrices = positionMatrices;
        this.normalMatrices = normalMatrices;
        this.bytes = bytes;
    }

    /**
     * Captures exactly one bounded palette. Runtime eligibility failures are represented as a CPU fallback attempt;
     * fatal allocation failures deliberately retain their identity and propagate.
     */
    static Attempt tryCapture(SkinPalette palette) {
        try {
            return Attempt.accepted(capture(Objects.requireNonNull(palette, "palette")));
        } catch (RuntimeException failure) {
            return Attempt.cpuFallback(failure);
        }
    }

    private static X7GpuSkinPaletteSnapshot capture(SkinPalette palette) {
        int jointCount = palette.jointCount();
        if (jointCount <= 0 || jointCount > MAX_BONES) {
            throw new IllegalArgumentException("GPU skinning accepts one through sixty-four bones only");
        }

        float[][] positions = new float[jointCount][];
        float[][] normals = new float[jointCount][];
        for (int joint = 0; joint < jointCount; joint++) {
            Matrix4 matrix = palette.matrix(joint);
            float[] position = matrix.copy();
            requireFiniteMatrix(position, "position palette");
            positions[joint] = position;
            normals[joint] = inverseTransposeNormalMatrix(position);
        }

        ByteBuffer packed = ByteBuffer.allocateDirect(TOTAL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int joint = 0; joint < MAX_BONES; joint++) {
            putMatrix(packed, joint < jointCount ? positions[joint] : null);
        }
        for (int joint = 0; joint < MAX_BONES; joint++) {
            putMatrix(packed, joint < jointCount ? normals[joint] : null);
        }
        packed.flip();
        return new X7GpuSkinPaletteSnapshot(
                palette,
                jointCount,
                positions,
                normals,
                packed.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
    }

    private static void putMatrix(ByteBuffer destination, float[] matrix) {
        if (matrix == null) {
            for (int component = 0; component < MATRIX_FLOATS; component++) {
                destination.putFloat(0.0F);
            }
            return;
        }
        for (float component : matrix) {
            destination.putFloat(component);
        }
    }

    /** Matches {@code SkinPalette.transformNormalInto} exactly before its final float conversion. */
    private static float[] inverseTransposeNormalMatrix(float[] matrix) {
        double a00 = matrix[0];
        double a01 = matrix[4];
        double a02 = matrix[8];
        double a10 = matrix[1];
        double a11 = matrix[5];
        double a12 = matrix[9];
        double a20 = matrix[2];
        double a21 = matrix[6];
        double a22 = matrix[10];
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
        if (!Double.isFinite(determinant) || Math.abs(determinant) <= DETERMINANT_EPSILON) {
            throw new IllegalArgumentException("GPU skinning requires a finite normal determinant with abs(det) > 1e-12");
        }
        return new float[] {
            finite(c00 / determinant), finite(c10 / determinant), finite(c20 / determinant), 0.0F,
            finite(c01 / determinant), finite(c11 / determinant), finite(c21 / determinant), 0.0F,
            finite(c02 / determinant), finite(c12 / determinant), finite(c22 / determinant), 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F
        };
    }

    private static void requireFiniteMatrix(float[] matrix, String label) {
        if (matrix.length != MATRIX_FLOATS) {
            throw new IllegalArgumentException(label + " must contain sixteen components");
        }
        for (float component : matrix) {
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException(label + " must contain only finite components");
            }
        }
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("GPU skin normal matrix produced a non-finite component");
        }
        return result;
    }

    int jointCount() {
        return jointCount;
    }

    boolean matchesExactly(SkinPalette palette) {
        return exactPalette == palette;
    }

    Object exactPaletteIdentity() {
        return exactPalette;
    }

    float positionComponent(int joint, int component) {
        requireOpen();
        requireJoint(joint);
        return positionMatrices[joint][requireComponent(component)];
    }

    float normalComponent(int joint, int component) {
        requireOpen();
        requireJoint(joint);
        return normalMatrices[joint][requireComponent(component)];
    }

    float[] positionMatrixCopy(int joint) {
        requireOpen();
        requireJoint(joint);
        return Arrays.copyOf(positionMatrices[joint], MATRIX_FLOATS);
    }

    float[] normalMatrixCopy(int joint) {
        requireOpen();
        requireJoint(joint);
        return Arrays.copyOf(normalMatrices[joint], MATRIX_FLOATS);
    }

    synchronized ByteBuffer bytesForUpload() {
        requireOpen();
        ByteBuffer copy = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        copy.position(0);
        return copy;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        bytes = null;
    }

    private void requireJoint(int joint) {
        if (joint < 0 || joint >= jointCount) {
            throw new IndexOutOfBoundsException("GPU skin palette joint is outside the captured range");
        }
    }

    private static int requireComponent(int component) {
        if (component < 0 || component >= MATRIX_FLOATS) {
            throw new IndexOutOfBoundsException("GPU skin palette matrix component is outside 0..15");
        }
        return component;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("GPU skin palette staging has already been released");
        }
    }

    static final class Attempt {
        private final X7GpuSkinPaletteSnapshot snapshot;
        private final Throwable fallbackCause;

        private Attempt(X7GpuSkinPaletteSnapshot snapshot, Throwable fallbackCause) {
            this.snapshot = snapshot;
            this.fallbackCause = fallbackCause;
        }

        private static Attempt accepted(X7GpuSkinPaletteSnapshot snapshot) {
            return new Attempt(Objects.requireNonNull(snapshot, "snapshot"), null);
        }

        private static Attempt cpuFallback(Throwable failure) {
            return new Attempt(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean eligible() {
            return snapshot != null;
        }

        X7GpuSkinPaletteSnapshot snapshotOrNull() {
            return snapshot;
        }

        Throwable fallbackCauseOrNull() {
            return fallbackCause;
        }
    }
}
