package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights;
import net.minecraft.world.entity.Entity;

/**
 * Extraction-only source of immutable frame-local clip-layer multipliers.
 * The callback runs once per extraction, never during rendering. For descriptor-backed entity
 * layers, controller and layer IDs both equal {@code ModelAnimationLayers.Layer.id()}.
 * Omitted entries mean one; a zero contribution does not pause playback or change cue sequences.
 */
@FunctionalInterface
public interface BlendEntityLayerWeights<E extends Entity> {
    AnimationV2LayerWeights weights(E entity, BlendEntitySnapshotRequest request);
}
