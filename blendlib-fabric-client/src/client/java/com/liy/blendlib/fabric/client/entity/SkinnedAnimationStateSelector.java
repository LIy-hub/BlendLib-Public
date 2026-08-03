package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendAnimationKey;
import net.minecraft.world.entity.Entity;

/**
 * Chooses one declared visual animation state while an entity render snapshot is extracted.
 *
 * <p>The selector receives only the client entity and immutable extraction values. It must not
 * create gameplay effects, mutate server-owned state, or perform resource access.</p>
 */
@FunctionalInterface
public interface SkinnedAnimationStateSelector<E extends Entity> {
    BlendAnimationKey select(E entity, BlendEntitySnapshotRequest request);
}
