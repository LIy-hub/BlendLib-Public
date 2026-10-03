package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent;
import net.minecraft.world.entity.Entity;

/**
 * Extraction-thread presentation callback. Never use it for damage, drops or other server gameplay.
 * The event is immutable; the entity must not be retained by a renderer. Consumer failures propagate
 * just like the legacy visual callback, but consumed events are not retried.
 */
@FunctionalInterface
public interface BlendEntityLayerVisualEventHandler<E extends Entity> {
    void onVisualEvent(E entity, LayerAnimationVisualEvent event);
}
