package com.liy.blendlib.core.model;

import java.util.Objects;

/** Immutable translation-rotation-scale transform in canonical asset space. */
public record Transform(Vec3 translation, Quaternion rotation, Vec3 scale) {
    public static final Transform IDENTITY = new Transform(Vec3.ZERO, Quaternion.IDENTITY, Vec3.ONE);

    public Transform {
        translation = Objects.requireNonNull(translation, "translation");
        rotation = Objects.requireNonNull(rotation, "rotation").normalized();
        scale = Objects.requireNonNull(scale, "scale");
        validateStrictV1Scale(scale);
    }

    public Vec3 transformPoint(Vec3 point) {
        Objects.requireNonNull(point, "point");
        Vec3 scaled = new Vec3(
                finiteFloat((double) point.x() * scale.x(), "Scaled x component"),
                finiteFloat((double) point.y() * scale.y(), "Scaled y component"),
                finiteFloat((double) point.z() * scale.z(), "Scaled z component"));
        Vec3 rotated = rotation.rotate(scaled);
        return new Vec3(
                finiteFloat((double) rotated.x() + translation.x(), "Translated x component"),
                finiteFloat((double) rotated.y() + translation.y(), "Translated y component"),
                finiteFloat((double) rotated.z() + translation.z(), "Translated z component"));
    }

    /** Applies this transform after the supplied parent transform. */
    public Transform compose(Transform child) {
        Objects.requireNonNull(child, "child");
        Vec3 composedScale = new Vec3(
                finiteFloat((double) scale.x() * child.scale.x(), "Composed x scale"),
                finiteFloat((double) scale.y() * child.scale.y(), "Composed y scale"),
                finiteFloat((double) scale.z() * child.scale.z(), "Composed z scale"));
        Vec3 composedTranslation = transformPoint(child.translation);
        return new Transform(composedTranslation, rotation.multiply(child.rotation), composedScale);
    }

    /** Enforces the frozen v1 positive, uniform scale rule for static and animated TRS data. */
    public static void validateStrictV1Scale(Vec3 scale) {
        Objects.requireNonNull(scale, "scale");
        if (scale.x() <= 0.0f || scale.y() <= 0.0f || scale.z() <= 0.0f) {
            throw new IllegalArgumentException("Strict v1 node scale must be positive; negative or zero scale must be baked");
        }
        float minimum = Math.min(scale.x(), Math.min(scale.y(), scale.z()));
        float maximum = Math.max(scale.x(), Math.max(scale.y(), scale.z()));
        if (maximum - minimum > Math.max(1.0e-6f, maximum * 1.0e-5f)) {
            throw new IllegalArgumentException("Strict v1 node scale must be uniform; non-uniform scale must be baked");
        }
    }

    private static float finiteFloat(double value, String message) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException(message + " is non-finite");
        }
        return result;
    }
}
