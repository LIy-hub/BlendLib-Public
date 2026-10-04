package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.v2.AnimationBlendSpace2D;
import net.minecraft.world.entity.Entity;

/** Captures one validated vector per extraction before layer weight/command callbacks. */
@FunctionalInterface
public interface BlendEntityBlendSpace2DParameter<E extends Entity> {
    AnimationBlendSpace2D.Input parameter(E entity, BlendEntitySnapshotRequest request);
}
