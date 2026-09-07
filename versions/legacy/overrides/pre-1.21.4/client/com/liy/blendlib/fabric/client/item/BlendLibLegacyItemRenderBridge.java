package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.compat.LegacySubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Legacy imperative item hook: captures first, then emits the same immutable snapshot. */
public final class BlendLibLegacyItemRenderBridge {
    private BlendLibLegacyItemRenderBridge() { }
    public static boolean renderRegistered(ItemStack stack, ItemDisplayContext context, boolean leftHand,
            PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (stack.isEmpty()) return false;
        BlendLibItemBinding binding = BlendLibItemModelBindings.find(BuiltInRegistries.ITEM.getKey(stack.getItem())).orElse(null);
        if (binding == null) return false;
        ClientModelView model = BlendLibClientServices.models().resolve(binding.modelKey());
        BlendLibItemRenderArgument argument = new BlendLibItemRenderArgument(binding, model.renderHandle());
        BakedModel base = Minecraft.getInstance().getModelManager().getModel(binding.baseModelId());
        poses.pushPose();
        try {
            base.getTransforms().getTransform(context).apply(leftHand, poses);
            poses.translate(-0.5f, -0.5f, -0.5f);
            BlendLibClientServices.renderer().submit(argument.snapshot(light, overlay), poses,
                    new LegacySubmitNodeCollector(buffers));
        } finally { poses.popPose(); }
        return true;
    }
}
