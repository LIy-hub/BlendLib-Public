package com.liy.blendlib.fabric.client.entity;

import net.minecraft.world.entity.Entity;

/** Extraction-only callback after animation and pose modifiers, for client visual effects or socket inspection. */
@FunctionalInterface
public interface BlendEntitySocketHandler<E extends Entity> {
    /** Called once per successful extraction; missing models do not publish stale sockets. Not a gameplay hitbox API. */
    void onSockets(E entity, BlendEntitySnapshotRequest request, BlendEntitySockets sockets);
}
