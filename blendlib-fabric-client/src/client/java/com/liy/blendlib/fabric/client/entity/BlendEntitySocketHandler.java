package com.liy.blendlib.fabric.client.entity;

import net.minecraft.world.entity.Entity;

/** Extraction-only callback after animated pose modifiers or static-morph rest-pose capture. */
@FunctionalInterface
public interface BlendEntitySocketHandler<E extends Entity> {
    /** Called at most once per extraction; lifecycle changes during this callback may discard the frame. Not a gameplay hitbox API. */
    void onSockets(E entity, BlendEntitySnapshotRequest request, BlendEntitySockets sockets);
}
