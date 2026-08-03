package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import java.util.Objects;
import net.minecraft.world.phys.AABB;

/** Internal finite AABB construction for the current generation's conservative model envelope. */
final class EntityCullingBounds {
    private EntityCullingBounds() {
    }

    /**
     * Resolves a prepared handle only while vanilla performs entity culling, then unions its
     * translated render bounds with the entity's ordinary collision/display bounds.
     */
    static AABB unionWithCurrentModelBounds(
            ClientModelLookup models,
            BlendModelKey modelKey,
            AABB entityBounds,
            double entityX,
            double entityY,
            double entityZ) {
        return unionWithCurrentModelBounds(
                models, modelKey, entityBounds, entityX, entityY, entityZ, false);
    }

    static AABB unionWithCurrentModelBounds(
            ClientModelLookup models,
            BlendModelKey modelKey,
            AABB entityBounds,
            double entityX,
            double entityY,
            double entityZ,
            boolean rotationInvariant) {
        ClientModelView model = Objects.requireNonNull(models, "models")
                .resolve(Objects.requireNonNull(modelKey, "modelKey"));
        return unionWithPreparedBounds(
                entityBounds,
                entityX,
                entityY,
                entityZ,
                model.renderHandle().bounds(),
                rotationInvariant);
    }

    /**
     * Translates finite, precomputed model bounds into entity-world space and preserves the
     * vanilla entity bounds by union. Invalid coordinates never create a non-finite culling box.
     */
    static AABB unionWithPreparedBounds(
            AABB entityBounds, double entityX, double entityY, double entityZ, Bounds preparedBounds) {
        return unionWithPreparedBounds(
                entityBounds, entityX, entityY, entityZ, preparedBounds, false);
    }

    static AABB unionWithPreparedBounds(
            AABB entityBounds,
            double entityX,
            double entityY,
            double entityZ,
            Bounds preparedBounds,
            boolean rotationInvariant) {
        AABB checkedEntityBounds = Objects.requireNonNull(entityBounds, "entityBounds");
        Bounds checkedPreparedBounds = Objects.requireNonNull(preparedBounds, "preparedBounds");
        if (!Double.isFinite(entityX) || !Double.isFinite(entityY) || !Double.isFinite(entityZ)) {
            return finiteOrOrigin(checkedEntityBounds);
        }

        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
        if (rotationInvariant) {
            double radiusX = Math.max(
                    Math.abs(checkedPreparedBounds.min().x()),
                    Math.abs(checkedPreparedBounds.max().x()));
            double radiusY = Math.max(
                    Math.abs(checkedPreparedBounds.min().y()),
                    Math.abs(checkedPreparedBounds.max().y()));
            double radiusZ = Math.max(
                    Math.abs(checkedPreparedBounds.min().z()),
                    Math.abs(checkedPreparedBounds.max().z()));
            double radius = Math.sqrt(radiusX * radiusX + radiusY * radiusY + radiusZ * radiusZ);
            minX = entityX - radius;
            minY = entityY - radius;
            minZ = entityZ - radius;
            maxX = entityX + radius;
            maxY = entityY + radius;
            maxZ = entityZ + radius;
        } else {
            minX = entityX + checkedPreparedBounds.min().x();
            minY = entityY + checkedPreparedBounds.min().y();
            minZ = entityZ + checkedPreparedBounds.min().z();
            maxX = entityX + checkedPreparedBounds.max().x();
            maxY = entityY + checkedPreparedBounds.max().y();
            maxZ = entityZ + checkedPreparedBounds.max().z();
        }
        if (!allFinite(minX, minY, minZ, maxX, maxY, maxZ)) {
            return finiteOrOrigin(checkedEntityBounds);
        }

        AABB modelBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        return isFinite(checkedEntityBounds) ? checkedEntityBounds.minmax(modelBounds) : modelBounds;
    }

    private static boolean isFinite(AABB bounds) {
        return allFinite(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    private static AABB finiteOrOrigin(AABB bounds) {
        return isFinite(bounds) ? bounds : new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private static boolean allFinite(
            double first, double second, double third, double fourth, double fifth, double sixth) {
        return Double.isFinite(first)
                && Double.isFinite(second)
                && Double.isFinite(third)
                && Double.isFinite(fourth)
                && Double.isFinite(fifth)
                && Double.isFinite(sixth);
    }
}
