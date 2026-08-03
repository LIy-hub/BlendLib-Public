package com.liy.blendlib.fabric.client.entity;

/**
 * Immutable normalized quaternion exposed by the client entity adapter.
 *
 * <p>Components use glTF's {@code x, y, z, w} order. This value intentionally has no dependency
 * on BlendLib's nested core implementation, so a separately compiled Fabric consumer can use a
 * procedural entity pose modifier through the outer adapter artifact alone.</p>
 */
public record BlendEntityRotation(float x, float y, float z, float w) {
    public static final BlendEntityRotation IDENTITY = new BlendEntityRotation(0.0F, 0.0F, 0.0F, 1.0F);

    private static final double NORMALIZED_LENGTH_SQUARED_TOLERANCE = 1.0E-4D;

    public BlendEntityRotation {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(w, "w");
        double lengthSquared = lengthSquared(x, y, z, w);
        if (!Double.isFinite(lengthSquared)
                || Math.abs(lengthSquared - 1.0D) > NORMALIZED_LENGTH_SQUARED_TOLERANCE) {
            throw new IllegalArgumentException("Entity pose rotation must be normalized");
        }
    }

    /** Normalizes one finite, non-zero quaternion for use as a rotation override. */
    public static BlendEntityRotation normalized(float x, float y, float z, float w) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(w, "w");
        double lengthSquared = lengthSquared(x, y, z, w);
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0E-12D) {
            throw new IllegalArgumentException("Cannot normalize a zero entity pose rotation");
        }
        float inverseLength = (float) (1.0D / Math.sqrt(lengthSquared));
        return new BlendEntityRotation(
                x * inverseLength,
                y * inverseLength,
                z * inverseLength,
                w * inverseLength);
    }

    private static double lengthSquared(float x, float y, float z, float w) {
        return (double) x * x + (double) y * y + (double) z * z + (double) w * w;
    }

    private static void requireFinite(float component, String name) {
        if (!Float.isFinite(component)) {
            throw new IllegalArgumentException("Entity pose rotation " + name + " component must be finite");
        }
    }
}
