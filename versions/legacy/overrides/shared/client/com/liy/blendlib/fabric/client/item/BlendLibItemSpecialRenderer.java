package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.compat.LegacySubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/** Version-native item hook preserving extraction before immutable snapshot submission. */
final class BlendLibItemSpecialRenderer implements SpecialModelRenderer<BlendLibItemRenderArgument> {
    private final BlendLibItemBinding binding;
    BlendLibItemSpecialRenderer(BlendLibItemBinding binding) { this.binding = Objects.requireNonNull(binding); }
    @Override public BlendLibItemRenderArgument extractArgument(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        ClientModelView model = BlendLibClientServices.models().resolve(binding.modelKey());
        return new BlendLibItemRenderArgument(binding, model.renderHandle());
    }
    @Override public void render(BlendLibItemRenderArgument argument, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay, boolean hasFoil) {
        Objects.requireNonNull(argument, "argument");
        BlendLibClientServices.renderer().submit(argument.snapshot(packedLight, packedOverlay),
                poseStack, new LegacySubmitNodeCollector(buffers));
    }
    public void getExtents(Set<Vector3f> output) {
        Objects.requireNonNull(output).add(new Vector3f(-0.5f, -0.5f, -0.5f));
        output.add(new Vector3f(0.5f, 0.5f, 0.5f));
    }
    static final class Unbaked implements SpecialModelRenderer.Unbaked {
        private final BlendLibItemBinding binding;
        Unbaked(BlendLibItemBinding binding) { this.binding = Objects.requireNonNull(binding); }
        @Override public SpecialModelRenderer<?> bake(EntityModelSet models) {
            Objects.requireNonNull(models, "models");
            return new BlendLibItemSpecialRenderer(binding);
        }
        @Override public MapCodec<? extends SpecialModelRenderer.Unbaked> type() { return MapCodec.unit(this); }
    }
}
