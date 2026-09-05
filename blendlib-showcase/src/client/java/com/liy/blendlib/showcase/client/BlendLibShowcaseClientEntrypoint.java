package com.liy.blendlib.showcase.client;

import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderers;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import com.liy.blendlib.showcase.BlendLibShowcaseEntrypoint;
import com.liy.blendlib.showcase.blockentity.ShowcaseAnimatedAltarBlockEntity;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntities;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntityAnimations;
import com.liy.blendlib.showcase.client.blockentity.ShowcaseAnimatedAltarClientBinding;
import com.liy.blendlib.showcase.item.ShowcaseItems;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.resources.Identifier;

/** Client-only registration of the Showcase item and block-entity consumers. */
public final class BlendLibShowcaseClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ShowcaseAnimatedAltarClientBinding.validateCanonicalContract();
        BlendLibItemModelBindings.register(new BlendLibItemBinding(
                ShowcaseItems.STATIC_RIGID_ITEM_ID,
                BlendLibShowcaseEntrypoint.STATIC_RIGID_MODEL,
                Identifier.withDefaultNamespace("item/stick")));
        BlendBlockEntityRenderers.register(
                ShowcaseBlockEntities.ANIMATED_ALTAR,
                context -> BlendBlockEntityRenderer.<ShowcaseAnimatedAltarBlockEntity>builder(
                                context, ShowcaseAnimatedAltarClientBinding.MODEL_KEY)
                        .syncedSkinnedAnimation(ShowcaseBlockEntityAnimations.IDLE_LOOP)
                        .build());
    }
}
