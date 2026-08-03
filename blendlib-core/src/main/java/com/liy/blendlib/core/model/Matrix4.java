package com.liy.blendlib.core.model;

import java.util.Arrays;
import java.util.Objects;

/** Immutable column-major 4x4 matrix used for inverse-bind data and TRS decomposition. */
public final class Matrix4 {
    private final float[] values;

    public Matrix4(float[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length != 16) {
            throw new IllegalArgumentException("Matrix4 requires exactly 16 values");
        }
        this.values = Arrays.copyOf(values, values.length);
        for (float value : this.values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Matrix4 values must be finite");
            }
        }
    }

    public static Matrix4 identity() {
        return new Matrix4(new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    public float get(int column, int row) {
        if (column < 0 || column > 3 || row < 0 || row > 3) {
            throw new IndexOutOfBoundsException("Matrix indices must be 0..3");
        }
        return values[column * 4 + row];
    }

    public float[] copy() {
        return Arrays.copyOf(values, values.length);
    }

    /** Decomposes an affine, non-sheared glTF matrix into a normalized TRS transform. */
    public Transform decomposeTrs() {
        if (Math.abs(values[3]) > 1.0e-5f || Math.abs(values[7]) > 1.0e-5f || Math.abs(values[11]) > 1.0e-5f
                || Math.abs(values[15] - 1.0f) > 1.0e-5f) {
            throw new IllegalArgumentException("Node matrix must be affine");
        }
        Vec3 xAxis = new Vec3(values[0], values[1], values[2]);
        Vec3 yAxis = new Vec3(values[4], values[5], values[6]);
        Vec3 zAxis = new Vec3(values[8], values[9], values[10]);
        float sx = xAxis.length();
        float sy = yAxis.length();
        float sz = zAxis.length();
        if (sx <= 1.0e-8f || sy <= 1.0e-8f || sz <= 1.0e-8f) {
            throw new IllegalArgumentException("Node matrix contains a zero scale axis");
        }
        xAxis = xAxis.multiply(1.0f / sx);
        yAxis = yAxis.multiply(1.0f / sy);
        zAxis = zAxis.multiply(1.0f / sz);
        if (Math.abs(xAxis.dot(yAxis)) > 1.0e-4f || Math.abs(xAxis.dot(zAxis)) > 1.0e-4f || Math.abs(yAxis.dot(zAxis)) > 1.0e-4f) {
            throw new IllegalArgumentException("Node matrix contains unsupported shear");
        }
        float determinant = xAxis.dot(yAxis.cross(zAxis));
        if (determinant < 0.0f) {
            sx = -sx;
            xAxis = xAxis.multiply(-1.0f);
        }
        return new Transform(new Vec3(values[12], values[13], values[14]), Quaternion.fromRotationColumns(xAxis, yAxis, zAxis),
                new Vec3(sx, sy, sz));
    }
}
