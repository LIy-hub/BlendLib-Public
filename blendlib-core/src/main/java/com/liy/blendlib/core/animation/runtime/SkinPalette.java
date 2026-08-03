package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.Matrix4;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable glTF skin palette in joint-slot order: posed joint world matrix times inverse bind. */
public final class SkinPalette {
    private static final double EPSILON = 1.0e-12;

    private final float[][] matrices;

    private SkinPalette(List<float[]> matrices) {
        this.matrices = new float[matrices.size()][];
        for (int index = 0; index < matrices.size(); index++) {
            float[] matrix = matrices.get(index);
            if (matrix.length != 16) {
                throw new IllegalArgumentException("Skin palette matrices must contain sixteen components");
            }
            this.matrices[index] = Arrays.copyOf(matrix, matrix.length);
        }
    }

    public static SkinPalette from(Skin skin, NodePalette nodePalette) {
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(nodePalette, "nodePalette");
        List<float[]> matrices = new ArrayList<>(skin.joints().size());
        for (int jointSlot = 0; jointSlot < skin.joints().size(); jointSlot++) {
            Transform jointWorld = nodePalette.worldTransform(skin.joints().get(jointSlot));
            matrices.add(multiply(toMatrix(jointWorld), skin.inverseBindMatrix(jointSlot).copy()));
        }
        return new SkinPalette(matrices);
    }

    public int jointCount() {
        return matrices.length;
    }

    public Matrix4 matrix(int jointSlot) {
        return new Matrix4(matrices[jointSlot]);
    }

    /**
     * Transforms a point into caller-owned primitive storage.
     *
     * <p>This is package-private because it is an animation hot-path implementation detail, not a
     * model API. Callers reuse the same scratch array across vertex influences.</p>
     */
    void transformPointInto(int jointSlot, float pointX, float pointY, float pointZ, float[] output, int outputOffset) {
        float[] matrix = matrices[jointSlot];
        double x = matrix[0] * pointX + matrix[4] * pointY + matrix[8] * pointZ + matrix[12];
        double y = matrix[1] * pointX + matrix[5] * pointY + matrix[9] * pointZ + matrix[13];
        double z = matrix[2] * pointX + matrix[6] * pointY + matrix[10] * pointZ + matrix[14];
        double w = matrix[3] * pointX + matrix[7] * pointY + matrix[11] * pointZ + matrix[15];
        if (!Double.isFinite(w) || Math.abs(w) <= EPSILON) {
            throw new IllegalArgumentException("Skin point transform produced a non-finite or zero homogeneous coordinate");
        }
        output[outputOffset] = finite(x / w);
        output[outputOffset + 1] = finite(y / w);
        output[outputOffset + 2] = finite(z / w);
    }

    /** Transforms a normal into caller-owned primitive storage using the inverse-transpose matrix. */
    void transformNormalInto(int jointSlot, float normalX, float normalY, float normalZ, float[] output, int outputOffset) {
        float[] matrix = matrices[jointSlot];
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
        if (!Double.isFinite(determinant) || Math.abs(determinant) <= EPSILON) {
            throw new IllegalArgumentException("Skin normal transform requires an invertible joint matrix");
        }
        output[outputOffset] = finite((c00 * normalX + c01 * normalY + c02 * normalZ) / determinant);
        output[outputOffset + 1] = finite((c10 * normalX + c11 * normalY + c12 * normalZ) / determinant);
        output[outputOffset + 2] = finite((c20 * normalX + c21 * normalY + c22 * normalZ) / determinant);
    }

    private static float[] toMatrix(Transform transform) {
        Quaternion rotation = transform.rotation().normalized();
        double x = rotation.x();
        double y = rotation.y();
        double z = rotation.z();
        double w = rotation.w();
        double xx = x * x;
        double yy = y * y;
        double zz = z * z;
        double xy = x * y;
        double xz = x * z;
        double yz = y * z;
        double wx = w * x;
        double wy = w * y;
        double wz = w * z;
        double sx = transform.scale().x();
        double sy = transform.scale().y();
        double sz = transform.scale().z();
        return new float[] {
            finite((1.0 - 2.0 * (yy + zz)) * sx), finite(2.0 * (xy + wz) * sx), finite(2.0 * (xz - wy) * sx), 0.0f,
            finite(2.0 * (xy - wz) * sy), finite((1.0 - 2.0 * (xx + zz)) * sy), finite(2.0 * (yz + wx) * sy), 0.0f,
            finite(2.0 * (xz + wy) * sz), finite(2.0 * (yz - wx) * sz), finite((1.0 - 2.0 * (xx + yy)) * sz), 0.0f,
            transform.translation().x(), transform.translation().y(), transform.translation().z(), 1.0f
        };
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0.0;
                for (int index = 0; index < 4; index++) {
                    value += left[index * 4 + row] * right[column * 4 + index];
                }
                result[column * 4 + row] = finite(value);
            }
        }
        return result;
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("Skin matrix calculation produced a non-finite component");
        }
        return result;
    }
}
