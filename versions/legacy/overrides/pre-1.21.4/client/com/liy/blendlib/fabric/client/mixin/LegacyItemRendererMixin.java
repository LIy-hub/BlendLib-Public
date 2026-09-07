package com.liy.blendlib.fabric.client.mixin;

import com.liy.blendlib.fabric.client.item.BlendLibLegacyItemRenderBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only explicitly registered BlendLib marker items use this version-pinned older item hook. */
@Mixin(ItemRenderer.class)
abstract class LegacyItemRendererMixin {
    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void blendlib$renderMarker(ItemStack stack, ItemDisplayContext context, boolean leftHand,
            PoseStack poses, MultiBufferSource buffers, int light, int overlay, BakedModel incoming, CallbackInfo ci) {
        if (BlendLibLegacyItemRenderBridge.renderRegistered(stack, context, leftHand, poses, buffers, light, overlay)) ci.cancel();
    }
}
