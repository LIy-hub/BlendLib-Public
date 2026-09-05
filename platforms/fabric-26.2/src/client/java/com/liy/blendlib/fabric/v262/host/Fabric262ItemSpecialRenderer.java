package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.fabric.v262.model.Fabric262FrameState;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSubmitter;
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
 * Programmatic public special renderer for a dispatcher-owned Fabric 26.2 item binding.
 *
 * <p>Extraction evaluates the formal registration source, advances a transient stateless LOOP
 * controller, and retains only an immutable generation-pinned snapshot/palette handoff. Submit
 * consumes that handoff and releases it in {@code finally}; it never evaluates a source, advances
 * a controller, reads model data, parses JSON/GLB, or discovers a provider.</p>
 */
final class Fabric262ItemSpecialRenderer implements SpecialModelRenderer<Fabric262ItemRenderArgument> {
    private static final Fabric262RenderSubmitter SUBMITTER = new Fabric262RenderSubmitter();
    private final Fabric262ItemModelBindings.ItemBinding binding;

    Fabric262ItemSpecialRenderer(Fabric262ItemModelBindings.ItemBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public Fabric262ItemRenderArgument extractArgument(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return new Fabric262ItemRenderArgument(
                binding, binding.owner().acquireItemOrNull(binding.registered()));
    }

    @Override
    public void submit(
            Fabric262ItemRenderArgument argument,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            int packedOverlay,
            boolean hasFoil,
            int outlineColor) {
        Fabric262ItemRenderArgument checkedArgument = Objects.requireNonNull(argument, "argument");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        Fabric262DispatchSnapshot snapshot = checkedArgument.snapshotForSubmit();
        if (snapshot == null) {
            return;
        }
        Throwable submitFailure = null;
        try {
            var rawSnapshot = snapshot.snapshot();
            SUBMITTER.submit(
                    rawSnapshot,
                    poseStack,
                    collector,
                    Fabric262FrameState.white(packedLight, packedOverlay),
                    snapshot.pose());
        } catch (RuntimeException | Error failure) {
            submitFailure = failure;
            throw failure;
        } finally {
            try {
                checkedArgument.releaseSubmittedSnapshot(snapshot);
            } catch (RuntimeException closeFailure) {
                if (submitFailure != null) {
                    submitFailure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        Consumer<Vector3fc> checkedOutput = Objects.requireNonNull(output, "output");
        checkedOutput.accept(new Vector3f(-0.5F, -0.5F, -0.5F));
        checkedOutput.accept(new Vector3f(0.5F, 0.5F, 0.5F));
    }

    /** Programmatic-only unbaked wrapper; no private special-renderer codec registry is used. */
    static final class Unbaked implements SpecialModelRenderer.Unbaked<Fabric262ItemRenderArgument> {
        private final Fabric262ItemModelBindings.ItemBinding binding;

        Unbaked(Fabric262ItemModelBindings.ItemBinding binding) {
            this.binding = Objects.requireNonNull(binding, "binding");
        }

        @Override
        public SpecialModelRenderer<Fabric262ItemRenderArgument> bake(BakingContext context) {
            Objects.requireNonNull(context, "context");
            return new Fabric262ItemSpecialRenderer(binding);
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<Fabric262ItemRenderArgument>> type() {
            return MapCodec.unit(this);
        }
    }
}
