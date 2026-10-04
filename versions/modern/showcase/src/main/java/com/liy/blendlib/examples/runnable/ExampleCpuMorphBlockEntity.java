package com.liy.blendlib.examples.runnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No ticker, animation state, controls, resource bytes, saved data, or network messages. */
public final class ExampleCpuMorphBlockEntity extends BlockEntity {
    public ExampleCpuMorphBlockEntity(BlockPos position, BlockState state) {
        super(ExampleCpuMorphContent.STATIC_BLOCK_ENTITY, position, state);
    }
}
