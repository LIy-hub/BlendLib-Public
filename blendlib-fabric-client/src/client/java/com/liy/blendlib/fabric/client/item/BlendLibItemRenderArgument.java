package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;

/** Immutable extraction-to-submit item argument bound to one already prepared model generation. */
record BlendLibItemRenderArgument(BlendLibItemBinding binding, ModelRenderHandle handle, ModelRenderSnapshot animatedSnapshot) {
    BlendLibItemRenderArgument(BlendLibItemBinding binding, ModelRenderHandle handle) {
        this(binding, handle, null);
    }
    BlendLibItemRenderArgument {
        binding = Objects.requireNonNull(binding, "binding");
        handle = Objects.requireNonNull(handle, "handle");
        if (!binding.modelKey().equals(handle.modelKey())) {
            throw new IllegalArgumentException("Item binding and prepared handle must use the same model key");
        }
        if (animatedSnapshot != null && animatedSnapshot.handle() != handle) {
            throw new IllegalArgumentException("Animated snapshot must use the exact extracted handle");
        }
    }

    static BlendLibItemRenderArgument capture(BlendLibItemBinding binding, ModelRenderHandle handle,
            ModelRenderSnapshot animatedSnapshot, net.minecraft.world.item.ItemStack stack,
            BlendLibItemMaterialAppearance appearance) {
        var argument = new BlendLibItemRenderArgument(binding, handle, animatedSnapshot);
        if (appearance == null || handle.missingModel()) return argument;
        var captured = argument.snapshot(0, 0).withMaterialAppearance(appearance.select(stack));
        appearance.captured(stack, captured);
        return new BlendLibItemRenderArgument(binding, handle, captured);
    }

    ModelRenderSnapshot snapshot(int packedLight, int packedOverlay) {
        if (animatedSnapshot != null) return animatedSnapshot.withLighting(packedLight, packedOverlay);
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                packedLight,
                packedOverlay,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }
}
