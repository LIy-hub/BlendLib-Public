package com.liy.blendlib.examples.ecosystem.blockentity;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Publishes one persistent semantic idle loop when a server owns the block entity.
 *
 * <p>No resource bytes, client renderer, matrix, socket transform, or visual state is stored in
 * the block entity.</p>
 */
public final class ExampleAnimatedAltarBlockEntity extends BlockEntity {
    private boolean idlePublished;

    public ExampleAnimatedAltarBlockEntity(BlockPos position, BlockState state) {
        super(ExampleBlockEntities.ANIMATED_ALTAR, position, state);
    }

    /** Public ticker bridge used only by the server-side fixed block behavior. */
    public static void serverTick(
            Level level,
            BlockPos position,
            BlockState state,
            ExampleAnimatedAltarBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel) || blockEntity.isRemoved() || blockEntity.idlePublished) {
            return;
        }
        BlendAnimations.blockEntity(blockEntity).setPersistent(ExampleKeys.IDLE);
        blockEntity.idlePublished = true;
    }
}
