package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Selects a model-scoped, startup-registered skin once per extraction, before appearance.
 * Return empty for authored textures. Unknown or invalid skins fall back atomically with
 * a snapshot diagnostic. Do not mutate or retain stacks or perform resource I/O here.
 * Missing-model handles bypass both callbacks; neither callback runs during submit.
 */
@FunctionalInterface
public interface BlendLibItemSkinSelector {
    Optional<BlendResourceId> select(ItemStack stack);

    /** Observes the completed immutable extraction, including skin and appearance diagnostics. */
    default void captured(ItemStack stack, ModelRenderSnapshot snapshot) { }
}
