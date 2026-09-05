package com.liy.blendlib.examples.ecosystem.block;

import com.liy.blendlib.examples.ecosystem.blockentity.ExampleAnimatedAltarBlockEntity;
import com.liy.blendlib.examples.ecosystem.blockentity.ExampleBlockEntities;
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
 * Ordinary fixed-shape gameplay block with a server-owned semantic idle animation publication.
 *
 * <p>BlendLib assets do not determine collision, drops, interaction, or any server rule.</p>
 */
public final class ExampleAnimatedAltarBlock extends Block implements EntityBlock {
    public ExampleAnimatedAltarBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new ExampleAnimatedAltarBlockEntity(position, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type) {
        if (type != ExampleBlockEntities.ANIMATED_ALTAR) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<ExampleAnimatedAltarBlockEntity>)
                ExampleAnimatedAltarBlockEntity::serverTick;
    }
}
