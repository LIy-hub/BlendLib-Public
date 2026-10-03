package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Optional;
import net.minecraft.world.entity.Entity;

/**
 * Selects one model-scoped, startup-registered skin once during extraction, before appearance.
 * Return empty to retain authored textures. Unknown or invalid skins fall back atomically and
 * expose a diagnostic on the captured snapshot. Never perform resource I/O in this callback.
 * Missing-model snapshots bypass selection. The selector is never consulted during submit.
 */
@FunctionalInterface
public interface BlendEntitySkinSelector<E extends Entity> {
    Optional<BlendResourceId> select(E entity, BlendEntitySnapshotRequest request);
}
