package com.liy.blendlib.showcase.block;

import com.liy.blendlib.showcase.blockentity.ShowcaseAnimatedAltarBlockEntity;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fixed-shape server-safe host for the Showcase persistent animation loop.
 *
 * <p>This block deliberately uses ordinary vanilla block behavior for gameplay. Its BlendLib
 * visual model does not affect collision, damage, interaction, drops, or any other server rule.</p>
 */
public final class ShowcaseAnimatedAltarBlock extends Block implements EntityBlock {
    public ShowcaseAnimatedAltarBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new ShowcaseAnimatedAltarBlockEntity(blockPos, blockState);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState blockState, BlockEntityType<T> blockEntityType) {
        if (blockEntityType != ShowcaseBlockEntities.ANIMATED_ALTAR) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<ShowcaseAnimatedAltarBlockEntity>)
                ShowcaseAnimatedAltarBlockEntity::serverTick;
    }
}
