package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/** Entity-local render roots derived only during extraction, never during submit. */
final class EntityRootTransforms {
    private EntityRootTransforms() {
    }

    static Transform interpolatedYaw(Entity entity, float partialTick) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("partialTick must be finite");
        }
        return fromMinecraftYawDegrees(Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot()));
    }

    static <E extends Entity> Transform selected(
            E entity,
            BlendEntitySnapshotRequest request,
            BlendEntityRootRotationSelector<? super E> selector) {
        E checkedEntity = Objects.requireNonNull(entity, "entity");
        BlendEntitySnapshotRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (selector == null) {
            return interpolatedYaw(checkedEntity, checkedRequest.partialTick());
        }
        BlendEntityRotation selected = Objects.requireNonNull(
                selector.select(checkedEntity, checkedRequest), "selected root rotation");
        return fromEntityRotation(selected);
    }

    static Transform fromEntityRotation(BlendEntityRotation rotation) {
        BlendEntityRotation checked = Objects.requireNonNull(rotation, "rotation");
        return new Transform(
                Vec3.ZERO,
                new Quaternion(checked.x(), checked.y(), checked.z(), checked.w()),
                Vec3.ONE);
    }

    /**
     * Converts Minecraft yaw to a glTF Y-axis rotation.
     *
     * <p>Minecraft positive yaw turns the forward +Z vector toward -X, while a positive
     * right-handed glTF Y rotation turns +Z toward +X. The sign is therefore intentionally
     * inverted.</p>
     */
    static Transform fromMinecraftYawDegrees(float yawDegrees) {
        if (!Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("yawDegrees must be finite");
        }
        double halfRadians = Math.toRadians(-yawDegrees) * 0.5D;
        Quaternion rotation = new Quaternion(
                0.0F,
                (float) Math.sin(halfRadians),
                0.0F,
                (float) Math.cos(halfRadians));
        return new Transform(Vec3.ZERO, rotation, Vec3.ONE);
    }
}
