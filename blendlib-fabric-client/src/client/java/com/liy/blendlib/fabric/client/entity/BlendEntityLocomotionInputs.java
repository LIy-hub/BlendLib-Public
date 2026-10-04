package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.rules.LocomotionInputs;
import net.minecraft.world.entity.Entity;

/** Extraction-only immutable boolean/numeric inputs for optional resource-pack locomotion rules. */
@FunctionalInterface
public interface BlendEntityLocomotionInputs<E extends Entity> {
    LocomotionInputs capture(E entity, BlendEntitySnapshotRequest request);
}
