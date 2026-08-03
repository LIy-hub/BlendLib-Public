package com.liy.blendlib.core.model;

/** Immutable normalized quaternion using glTF's x, y, z, w component order. */
public record Quaternion(float x, float y, float z, float w) {
    public static final Quaternion IDENTITY = new Quaternion(0.0f, 0.0f, 0.0f, 1.0f);

    public Quaternion {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w)) {
            throw new IllegalArgumentException("Quaternion components must be finite");
        }
    }

    public Quaternion normalized() {
        double lengthSquared = (double) x * x + (double) y * y + (double) z * z + (double) w * w;
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0e-12) {
            throw new IllegalArgumentException("Cannot normalize a zero quaternion");
        }
        float inverse = (float) (1.0 / Math.sqrt(lengthSquared));
        return new Quaternion(x * inverse, y * inverse, z * inverse, w * inverse);
    }

    public Quaternion multiply(Quaternion other) {
        return new Quaternion(
                w * other.x + x * other.w + y * other.z - z * other.y,
                w * other.y - x * other.z + y * other.w + z * other.x,
                w * other.z + x * other.y - y * other.x + z * other.w,
                w * other.w - x * other.x - y * other.y - z * other.z).normalized();
    }

    public Vec3 rotate(Vec3 value) {
        Quaternion rotation = normalized();
        double tx = 2.0 * (rotation.y * value.z() - rotation.z * value.y());
        double ty = 2.0 * (rotation.z * value.x() - rotation.x * value.z());
        double tz = 2.0 * (rotation.x * value.y() - rotation.y * value.x());
        double rx = value.x() + rotation.w * tx + rotation.y * tz - rotation.z * ty;
        double ry = value.y() + rotation.w * ty + rotation.z * tx - rotation.x * tz;
        double rz = value.z() + rotation.w * tz + rotation.x * ty - rotation.y * tx;
        return new Vec3(finiteFloat(rx, "Rotated x component"), finiteFloat(ry, "Rotated y component"),
                finiteFloat(rz, "Rotated z component"));
    }

    /** Shortest-path spherical interpolation with normalized output. */
    public static Quaternion slerp(Quaternion start, Quaternion end, float amount) {
        if (!Float.isFinite(amount)) {
            throw new IllegalArgumentException("Slerp amount must be finite");
        }
        Quaternion left = start.normalized();
        Quaternion right = end.normalized();
        double cosine = left.x * right.x + left.y * right.y + left.z * right.z + left.w * right.w;
        if (cosine < 0.0) {
            right = new Quaternion(-right.x, -right.y, -right.z, -right.w);
            cosine = -cosine;
        }
        if (cosine > 0.9995) {
            return new Quaternion(
                    left.x + amount * (right.x - left.x),
                    left.y + amount * (right.y - left.y),
                    left.z + amount * (right.z - left.z),
                    left.w + amount * (right.w - left.w)).normalized();
        }
        double theta = Math.acos(Math.min(1.0, Math.max(-1.0, cosine)));
        double sine = Math.sin(theta);
        double startWeight = Math.sin((1.0 - amount) * theta) / sine;
        double endWeight = Math.sin(amount * theta) / sine;
        return new Quaternion(
                (float) (startWeight * left.x + endWeight * right.x),
                (float) (startWeight * left.y + endWeight * right.y),
                (float) (startWeight * left.z + endWeight * right.z),
                (float) (startWeight * left.w + endWeight * right.w)).normalized();
    }

    /** Creates a normalized quaternion from a normalized 3x3 rotation matrix in column-major order. */
    public static Quaternion fromRotationColumns(Vec3 xAxis, Vec3 yAxis, Vec3 zAxis) {
        float m00 = xAxis.x();
        float m01 = yAxis.x();
        float m02 = zAxis.x();
        float m10 = xAxis.y();
        float m11 = yAxis.y();
        float m12 = zAxis.y();
        float m20 = xAxis.z();
        float m21 = yAxis.z();
        float m22 = zAxis.z();
        float trace = m00 + m11 + m22;
        Quaternion result;
        if (trace > 0.0f) {
            float scale = (float) Math.sqrt(trace + 1.0f) * 2.0f;
            result = new Quaternion((m21 - m12) / scale, (m02 - m20) / scale, (m10 - m01) / scale, scale * 0.25f);
        } else if (m00 > m11 && m00 > m22) {
            float scale = (float) Math.sqrt(1.0f + m00 - m11 - m22) * 2.0f;
            result = new Quaternion(scale * 0.25f, (m01 + m10) / scale, (m02 + m20) / scale, (m21 - m12) / scale);
        } else if (m11 > m22) {
            float scale = (float) Math.sqrt(1.0f + m11 - m00 - m22) * 2.0f;
            result = new Quaternion((m01 + m10) / scale, scale * 0.25f, (m12 + m21) / scale, (m02 - m20) / scale);
        } else {
            float scale = (float) Math.sqrt(1.0f + m22 - m00 - m11) * 2.0f;
            result = new Quaternion((m02 + m20) / scale, (m12 + m21) / scale, scale * 0.25f, (m10 - m01) / scale);
        }
        return result.normalized();
    }

    private Quaternion multiplyRaw(Quaternion other) {
        return new Quaternion(
                w * other.x + x * other.w + y * other.z - z * other.y,
                w * other.y - x * other.z + y * other.w + z * other.x,
                w * other.z + x * other.y - y * other.x + z * other.w,
                w * other.w - x * other.x - y * other.y - z * other.z);
    }

    private static float finiteFloat(double value, String message) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException(message + " is non-finite");
        }
        return result;
    }
}
