package com.liy.blendlib.fabric.client.entity;

import net.minecraft.world.entity.Entity;

/** Extraction-only positive cadence multiplier for the configured 1D or 2D blendspace.
 * Captured once before external weights/commands; applies to future intervals, not elapsed time. */
@FunctionalInterface
public interface BlendEntityBlendSpaceCadence<E extends Entity> {
    double multiplier(E entity, BlendEntitySnapshotRequest request);
}
