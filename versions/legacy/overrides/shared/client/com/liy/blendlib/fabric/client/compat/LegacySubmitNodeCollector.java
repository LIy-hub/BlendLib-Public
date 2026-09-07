package com.liy.blendlib.fabric.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** Bridges immutable BlendLib draws to the public pre-1.21.9 buffer-source API. */
public final class LegacySubmitNodeCollector {
    private final MultiBufferSource buffers;

    public LegacySubmitNodeCollector(MultiBufferSource buffers) {
        this.buffers = Objects.requireNonNull(buffers, "buffers");
    }

    public void submitCustomGeometry(PoseStack poseStack, RenderType type, CustomGeometryRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer").render(poseStack.last(), buffers.getBuffer(type));
    }

    public LegacySubmitNodeCollector order(int order) { return this; }

    @FunctionalInterface
    public interface CustomGeometryRenderer {
        void render(PoseStack.Pose pose, VertexConsumer consumer);
    }
}
