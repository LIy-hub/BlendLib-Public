package com.liy.blendlib.fabric.client.mixin;

import com.liy.blendlib.fabric.client.reload.Minecraft2612FinalPresentBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets vanilla's one pinned final present without replacing or cancelling it. */
@Mixin(Minecraft.class)
abstract class Minecraft2612FinalPresentMixin {
    private static final String FLIP_FRAME_TARGET =
            "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V";

    @Inject(
            method = "renderFrame(Z)V",
            at = @At(value = "INVOKE", target = FLIP_FRAME_TARGET, ordinal = 0, shift = At.Shift.BEFORE),
            require = 1)
    private void blendlib$beforeOriginalFinalPresent(boolean renderLevel, CallbackInfo callbackInfo) {
        Minecraft self = (Minecraft) (Object) this;
        Minecraft2612FinalPresentBridge.beforeOriginalPresent(self.isRunning());
    }

    @Inject(
            method = "renderFrame(Z)V",
            at = @At(value = "INVOKE", target = FLIP_FRAME_TARGET, ordinal = 0, shift = At.Shift.AFTER),
            require = 1)
    private void blendlib$afterOriginalFinalPresent(boolean renderLevel, CallbackInfo callbackInfo) {
        Minecraft2612FinalPresentBridge.afterOriginalPresent();
    }
}
