package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Creates one immutable snapshot while the block entity is available on the client extraction
 * path.
 *
 * <p>Implementations may read client-visible block-entity state only here. The later submit path
 * receives the returned snapshot, not this factory or the source block entity.</p>
 */
@FunctionalInterface
public interface BlendBlockEntitySnapshotFactory<T extends BlockEntity> {
    ModelRenderSnapshot create(T blockEntity, BlendBlockEntitySnapshotRequest request);
}
