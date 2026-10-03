package com.liy.blendlib.fabric.client.entity;

import java.util.Objects;

/** Immutable socket position, orientation and positive uniform scale; positions retain world precision. */
public record BlendEntitySocketPose(double x, double y, double z, BlendEntityRotation rotation, float scale) {
    public static final BlendEntitySocketPose IDENTITY = new BlendEntitySocketPose(0, 0, 0, BlendEntityRotation.IDENTITY, 1);

    public BlendEntitySocketPose {
        rotation = Objects.requireNonNull(rotation, "rotation");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("Socket position must be finite and scale must be finite and positive");
        }
    }
}
