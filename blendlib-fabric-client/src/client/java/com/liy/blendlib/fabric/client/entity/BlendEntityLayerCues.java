package com.liy.blendlib.fabric.client.entity;

import java.util.List;
import net.minecraft.world.entity.Entity;

/** Extraction-only cue source; BlendLib owns command capture and lifecycle cleanup. */
@FunctionalInterface
public interface BlendEntityLayerCues<E extends Entity> {
    List<BlendEntityLayerCue> cues(E entity, BlendEntitySnapshotRequest request);
}
