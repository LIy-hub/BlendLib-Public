package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import net.minecraft.world.item.ItemStack;

/**
 * Extraction-only named CPU morph weights for one ordinary marker stack.
 * Return an immutable frame-local batch; omitted controls use authored defaults. Called once per
 * successful extraction attempt, never during submission. Do not retain or mutate the stack, invoke
 * nested item extraction, or capture stacks in this registration-lifetime callback. Invalid names,
 * out-of-range weights, null results and callback failures propagate without publishing a frame.
 */
@FunctionalInterface
public interface BlendLibItemMorphControls {
    MorphFrameOverrides weights(ItemStack stack);
}
