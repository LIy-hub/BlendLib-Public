package com.liy.blendlib.core.model;

/** Immutable finite three-dimensional vector in canonical asset space. */
public record Vec3(float x, float y, float z) {
    public static final Vec3 ZERO = new Vec3(0.0f, 0.0f, 0.0f);
    public static final Vec3 ONE = new Vec3(1.0f, 1.0f, 1.0f);

    public Vec3 {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 multiply(float scalar) {
        return new Vec3(x * scalar, y * scalar, z * scalar);
    }

    public float dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
    }

    public float length() {
        return (float) Math.sqrt(dot(this));
    }

    public Vec3 normalized() {
        float length = length();
        if (!Float.isFinite(length) || length <= 1.0e-8f) {
            throw new IllegalArgumentException("Cannot normalize a zero or non-finite vector");
        }
        return multiply(1.0f / length);
    }
}
