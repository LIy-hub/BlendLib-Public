package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.fabric.client.render.MaterialSlotAppearance;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Map;
import net.minecraft.world.item.ItemStack;

/**
 * Optional client/extraction-thread appearance for one exact stack object.
 * Select exact authored slot names; values are RGB multipliers and visibility only.
 * Unspecified slots retain authored appearance. A map containing unknown slots falls back
 * atomically to authored appearance and exposes sorted names through the captured snapshot.
 * Do not mutate or retain the supplied stack, change playback, or perform resource I/O here.
 * The selector is retained for the binding lifetime; selected maps are defensively copied.
 */
@FunctionalInterface
public interface BlendLibItemMaterialAppearance {
    Map<String, MaterialSlotAppearance> select(ItemStack stack);

    /**
     * Optional read-only observer of this completed extraction, including unknownMaterialSlots().
     * Called once after capture, never during submit. Missing-model handles bypass both callbacks
     * and preserve diagnostic appearance. Exceptions propagate just like entity selectors.
     * The immutable snapshot can be inspected without touching playback/status/LRU; consumers
     * should retain only needed diagnostics, not stacks or snapshots across reloads.
     */
    default void captured(ItemStack stack, ModelRenderSnapshot snapshot) { }
}
