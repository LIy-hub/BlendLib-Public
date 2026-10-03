package com.liy.blendlib.fabric.client.item;

import net.minecraft.world.item.ItemStack;

/**
 * Opt-in, extraction-thread item presentation callback. The stack is the exact extracted object.
 * Do not retain it unnecessarily, or treat markers as gameplay, damage, item consumption or
 * network instructions. No display context or world-space effect position is supplied.
 * Batches are consumed before callbacks; an exception propagates without replay. Nested
 * extraction consumes silently, and changing controls or retiring the stack stops the batch.
 */
@FunctionalInterface
public interface ItemAnimationVisualEventHandler {
    void onVisualEvent(ItemStack stack, ItemAnimationVisualEvent event);
}
