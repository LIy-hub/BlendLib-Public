package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Programmatic special renderer used only by a registered 26.1.2 marker-item binding.
 *
 * <p>Extraction resolves the current immutable handle before submit. Submit then consumes only
 * that argument plus the vanilla pose/collector/light/overlay values; it never looks up a model,
 * opens a resource, parses JSON/GLB, samples animation, or accesses a Minecraft global.</p>
 */
final class BlendLibItemSpecialRenderer implements SpecialModelRenderer<BlendLibItemRenderArgument> {
    private final BlendLibItemBinding binding;

    BlendLibItemSpecialRenderer(BlendLibItemBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public BlendLibItemRenderArgument extractArgument(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        ClientModelView model = BlendLibClientServices.models().resolve(binding.modelKey());
        return new BlendLibItemRenderArgument(binding, model.renderHandle());
    }

    @Override
    public void submit(
            BlendLibItemRenderArgument argument,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            int packedOverlay,
            boolean hasFoil,
            int outlineColor) {
        Objects.requireNonNull(argument, "argument");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        // P4 intentionally does not reinterpret vanilla foil or outline flags as material/culling
        // intent. The base geometry is submitted exactly once through the standard BlendLib path.
        BlendLibClientServices.renderer().submit(argument.snapshot(packedLight, packedOverlay), poseStack, collector);
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        Objects.requireNonNull(output, "output");
        // Conservative finite fallback for item-model bounds. It deliberately avoids a registry
        // lookup at bake time, when the current reload generation may not yet be published.
        output.accept(new Vector3f(-0.5F, -0.5F, -0.5F));
        output.accept(new Vector3f(0.5F, 0.5F, 0.5F));
    }

    /** Programmatic-only unbaked form; it is never inserted into vanilla's private type mapper. */
    static final class Unbaked implements SpecialModelRenderer.Unbaked<BlendLibItemRenderArgument> {
        private final BlendLibItemBinding binding;

        Unbaked(BlendLibItemBinding binding) {
            this.binding = Objects.requireNonNull(binding, "binding");
        }

        @Override
        public SpecialModelRenderer<BlendLibItemRenderArgument> bake(BakingContext context) {
            Objects.requireNonNull(context, "context");
            return new BlendLibItemSpecialRenderer(binding);
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<BlendLibItemRenderArgument>> type() {
            // The wrapper is created by the public model-loading hook, never decoded from a custom
            // JSON type. A unit codec satisfies the public interface without registering a codec.
            return MapCodec.unit(this);
        }
    }
}
