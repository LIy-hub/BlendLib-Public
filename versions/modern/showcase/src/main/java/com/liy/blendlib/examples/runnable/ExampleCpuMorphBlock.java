package com.liy.blendlib.examples.runnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Fixed vanilla collision and behavior; only the client presentation deforms. */
public final class ExampleCpuMorphBlock extends Block implements EntityBlock {
    public ExampleCpuMorphBlock(BlockBehaviour.Properties properties) { super(properties); }

    @Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new ExampleCpuMorphBlockEntity(position, state);
    }
}
