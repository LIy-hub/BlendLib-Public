package com.liy.blendlib.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** Public 26.1.2 submission objects supplied by an entity/block/world adapter at render time. */
public record RenderSubmissionContext(PoseStack poseStack, SubmitNodeCollector collector) {
    public RenderSubmissionContext {
        poseStack = Objects.requireNonNull(poseStack, "poseStack");
        collector = Objects.requireNonNull(collector, "collector");
    }
}
