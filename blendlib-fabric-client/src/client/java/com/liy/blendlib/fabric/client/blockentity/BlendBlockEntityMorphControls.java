package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Frame-local, presentation-only named weights, captured exclusively during extraction. */
@FunctionalInterface
public interface BlendBlockEntityMorphControls<T extends BlockEntity> {
    MorphFrameOverrides weights(T blockEntity, BlendBlockEntitySnapshotRequest request);
}
