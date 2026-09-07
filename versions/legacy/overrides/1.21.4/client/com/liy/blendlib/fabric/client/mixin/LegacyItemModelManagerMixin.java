package com.liy.blendlib.fabric.client.mixin;

import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The exact 1.21.4 item-model lookup, restricted to explicitly registered marker bindings. */
@Mixin(ModelManager.class)
abstract class LegacyItemModelManagerMixin {
    @Inject(method = "getItemModel(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/item/ItemModel;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void blendlib$registeredMarker(ResourceLocation id, CallbackInfoReturnable<ItemModel> callback) {
        ItemModel replacement = BlendLibItemModelBindings.registeredMarkerModel(id, (ModelManager) (Object) this);
        if (replacement != null) callback.setReturnValue(replacement);
    }
}
