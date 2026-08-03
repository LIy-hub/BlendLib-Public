package com.liy.blendlib.showcase.blockentity;

import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-owned host that publishes one persistent semantic idle loop while it is loaded.
 *
 * <p>The object stores no model, asset bytes, transform, render snapshot, or client class. A fresh
 * loaded instance republishes the same public semantic state once so the P6-A tracking observer
 * can replay it to newly tracking clients.</p>
 */
public final class ShowcaseAnimatedAltarBlockEntity extends BlockEntity {
    private boolean persistentLoopPublished;

    public ShowcaseAnimatedAltarBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ShowcaseBlockEntities.ANIMATED_ALTAR, blockPos, blockState);
    }

    /** Public ticker bridge used by the fixed-shape Showcase block registration. */
    public static void serverTick(
            Level level,
            BlockPos blockPos,
            BlockState blockState,
            ShowcaseAnimatedAltarBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel) || blockEntity.isRemoved() || blockEntity.persistentLoopPublished) {
            return;
        }
        BlendAnimations.blockEntity(blockEntity).setPersistent(ShowcaseBlockEntityAnimations.IDLE_LOOP);
        blockEntity.persistentLoopPublished = true;
    }
}
