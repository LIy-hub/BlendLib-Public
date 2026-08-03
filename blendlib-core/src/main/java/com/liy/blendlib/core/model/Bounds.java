package com.liy.blendlib.core.model;

import java.util.Objects;

/** Immutable axis-aligned bounds with finite endpoints. */
public record Bounds(Vec3 min, Vec3 max) {
    public Bounds {
        min = Objects.requireNonNull(min, "min");
        max = Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Bounds min must not exceed max");
        }
    }

    public Bounds include(Vec3 point) {
        return new Bounds(
                new Vec3(Math.min(min.x(), point.x()), Math.min(min.y(), point.y()), Math.min(min.z(), point.z())),
                new Vec3(Math.max(max.x(), point.x()), Math.max(max.y(), point.y()), Math.max(max.z(), point.z())));
    }

    public Bounds union(Bounds other) {
        return include(other.min).include(other.max);
    }

    public Bounds transformed(Transform transform) {
        Objects.requireNonNull(transform, "transform");
        Bounds result = null;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    Vec3 corner = new Vec3(x == 0 ? min.x() : max.x(), y == 0 ? min.y() : max.y(), z == 0 ? min.z() : max.z());
                    Vec3 transformed = transform.transformPoint(corner);
                    result = result == null ? new Bounds(transformed, transformed) : result.include(transformed);
                }
            }
        }
        return result;
    }

    public static Bounds fromPositions(float[] positions) {
        Objects.requireNonNull(positions, "positions");
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("Position data must contain complete non-empty vec3 values");
        }
        Bounds result = null;
        for (int index = 0; index < positions.length; index += 3) {
            Vec3 point = new Vec3(positions[index], positions[index + 1], positions[index + 2]);
            result = result == null ? new Bounds(point, point) : result.include(point);
        }
        return result;
    }
}
