package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.Optional;
import net.minecraft.world.entity.Entity;

/**
 * Reads the latest optional semantic state for one entity during snapshot extraction.
 *
 * <p>The selector does not expose a transport, renderer, controller, model, or resource object.
 * Returning an empty value makes the configured local {@link SkinnedAnimationStateSelector}
 * the active state source for that extraction.</p>
 */
@FunctionalInterface
public interface SyncedSkinnedAnimationStateSelector<E extends Entity> {
    Optional<SyncedAnimationState> select(E entity, BlendEntitySnapshotRequest request);
}
