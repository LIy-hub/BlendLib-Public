package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import net.minecraft.world.entity.Entity;

/**
 * Extracts one immutable model snapshot while an entity is still available on the client/game
 * extraction path.
 *
 * <p>The returned snapshot is the only BlendLib object consumed later by renderer submit. P4
 * intentionally leaves animation/controller construction to P5.</p>
 */
@FunctionalInterface
public interface BlendEntitySnapshotFactory<E extends Entity> {
    ModelRenderSnapshot create(E entity, BlendEntitySnapshotRequest request);
}
