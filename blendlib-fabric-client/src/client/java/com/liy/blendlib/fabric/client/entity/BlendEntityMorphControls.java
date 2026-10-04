package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import net.minecraft.world.entity.Entity;

/** Presentation-only frame input. The runtime validates the whole immutable batch before advancement. */
@FunctionalInterface
public interface BlendEntityMorphControls<E extends Entity> {
    MorphFrameOverrides weights(E entity, BlendEntitySnapshotRequest request);
}
