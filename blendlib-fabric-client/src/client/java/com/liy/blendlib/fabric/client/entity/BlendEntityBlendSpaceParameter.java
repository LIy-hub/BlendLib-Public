package com.liy.blendlib.fabric.client.entity;

import net.minecraft.world.entity.Entity;

/** Extraction-only finite scalar captured exactly once for the configured fixed-cadence blendspace. */
@FunctionalInterface
public interface BlendEntityBlendSpaceParameter<E extends Entity> {
    double parameter(E entity, BlendEntitySnapshotRequest request);
}
