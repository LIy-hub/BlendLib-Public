package com.liy.blendlib.fabric.client.entity;

import net.minecraft.world.entity.Entity;

/**
 * Selects one complete glTF-space entity root rotation during client extraction.
 *
 * <p>The returned normalized quaternion maps the model's canonical axes into Minecraft world
 * axes. It replaces the adapter's ordinary interpolated-yaw root for this extraction only; no
 * entity or selector reaches render submit.</p>
 */
@FunctionalInterface
public interface BlendEntityRootRotationSelector<E extends Entity> {
    BlendEntityRotation select(E entity, BlendEntitySnapshotRequest request);
}
