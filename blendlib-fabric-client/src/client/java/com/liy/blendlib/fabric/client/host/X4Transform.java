package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;

/**
 * Bounded client-adapter transform used by X4 host builders and extraction frames.
 *
 * <p>This type keeps the public target-specific surface independent from core transform objects;
 * conversion happens once while an immutable snapshot is prepared.</p>
 */
public record X4Transform(
        float translationX,
        float translationY,
        float translationZ,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW,
        float uniformScale) {
    /** Smallest allowed host scale. */
    public static final float MIN_SCALE = 1.0F / 64.0F;

    /** Largest allowed host scale. */
    public static final float MAX_SCALE = 16.0F;

    /** Identity transform in canonical asset space. */
    public static final X4Transform IDENTITY = new X4Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F);

    public X4Transform {
        if (!Float.isFinite(translationX) || !Float.isFinite(translationY) || !Float.isFinite(translationZ)
                || !Float.isFinite(rotationX) || !Float.isFinite(rotationY) || !Float.isFinite(rotationZ)
                || !Float.isFinite(rotationW)) {
            throw new IllegalArgumentException("X4 host transform components must be finite");
        }
        if (!Float.isFinite(uniformScale) || uniformScale < MIN_SCALE || uniformScale > MAX_SCALE) {
            throw new IllegalArgumentException("uniformScale must be finite and in [" + MIN_SCALE + ", " + MAX_SCALE + "]");
        }
        double quaternionLengthSquared = (double) rotationX * rotationX
                + (double) rotationY * rotationY
                + (double) rotationZ * rotationZ
                + (double) rotationW * rotationW;
        if (!Double.isFinite(quaternionLengthSquared) || quaternionLengthSquared <= 1.0e-12D) {
            throw new IllegalArgumentException("X4 host transform rotation must be non-zero and finite");
        }
        float inverseLength = (float) (1.0D / Math.sqrt(quaternionLengthSquared));
        rotationX *= inverseLength;
        rotationY *= inverseLength;
        rotationZ *= inverseLength;
        rotationW *= inverseLength;
    }

    /** Converts this bounded adapter value to the immutable core transform used by a snapshot. */
    Transform toCoreTransform() {
        return new Transform(
                new Vec3(translationX, translationY, translationZ),
                new Quaternion(rotationX, rotationY, rotationZ, rotationW),
                new Vec3(uniformScale, uniformScale, uniformScale));
    }
}
