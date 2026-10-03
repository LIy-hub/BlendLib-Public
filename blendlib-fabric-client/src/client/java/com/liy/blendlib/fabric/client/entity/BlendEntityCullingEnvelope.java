package com.liy.blendlib.fabric.client.entity;

/**
 * Immutable, explicitly authored whole-assembly envelope in entity-local Minecraft block units.
 *
 * <p>Coordinates are relative to the entity origin, before the ordinary yaw or selected root
 * rotation, but after model units, attachment offsets/scales and all possible animation poses.
 * Include every possible child configuration for this renderer. Culling conservatively encloses
 * this box under every origin-centered rotation, then translates by the entity world position.
 * Custom snapshot translations/scales must already be covered; this is not a transform callback.
 * It neither changes collision bounds nor bypasses vanilla distance/visibility tests.</p>
 *
 * <p>Configuration survives resource reload unchanged. If a resource pack makes an assembly
 * larger, the consumer must configure a sufficient envelope or rebuild its renderer. Endpoints
 * must be finite and ordered, and the origin-centered radius must be representable.</p>
 */
public record BlendEntityCullingEnvelope(
        double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public BlendEntityCullingEnvelope {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Culling envelope endpoints must be finite and ordered");
        }
        if (!Double.isFinite(radius(minX, minY, minZ, maxX, maxY, maxZ))) {
            throw new IllegalArgumentException("Culling envelope radius must be finite");
        }
    }

    double radius() {
        return radius(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double radius(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        return Math.hypot(Math.hypot(Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(Math.abs(minY), Math.abs(maxY))), Math.max(Math.abs(minZ), Math.abs(maxZ)));
    }
}
