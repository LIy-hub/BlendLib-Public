package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendResourceId;
import net.minecraft.world.entity.Entity;

/**
 * Observes a declared P5 animation event for client presentation only.
 *
 * <p>Implementations may drive local particles, sounds, trails, or socket-attached visuals. They
 * must never treat an event as collision, damage, item consumption, drop, hit detection, server
 * authority, or a network trigger.</p>
 */
@FunctionalInterface
public interface SkinnedAnimationVisualEventHandler<E extends Entity> {
    void onVisualEvent(E entity, BlendResourceId eventKey);
}
