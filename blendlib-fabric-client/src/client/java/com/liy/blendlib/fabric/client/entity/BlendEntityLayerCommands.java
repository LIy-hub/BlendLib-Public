package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import java.util.List;
import net.minecraft.world.entity.Entity;

/** Extraction-only command source. Empty means continue; repeated sequence values are deduplicated. */
@FunctionalInterface
public interface BlendEntityLayerCommands<E extends Entity> {
    List<AnimationV2Command> commands(E entity, BlendEntitySnapshotRequest request);
}
