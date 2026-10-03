package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Quaternionf;

/** Snapshot-only attachment rendering; all lookup and animation work belongs to extraction. */
public final class BlendEntityAttachmentSubmitter {
    private BlendEntityAttachmentSubmitter() {}

    /** The input pose stack is entity-relative, just as for the parent snapshot. */
    public static void submit(List<BlendEntityAttachment> attachments, BlendRenderer renderer,
            PoseStack poseStack, SubmitNodeCollector collector) {
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        for (BlendEntityAttachment attachment : attachments) {
            poseStack.pushPose();
            try {
                apply(poseStack, attachment.placement());
                apply(poseStack, attachment.offset());
                renderer.submit(attachment.snapshot(), poseStack, collector);
            } finally {
                poseStack.popPose();
            }
        }
    }

    static void apply(PoseStack poseStack, BlendEntitySocketPose pose) {
        poseStack.translate(pose.x(), pose.y(), pose.z());
        var rotation = pose.rotation();
        poseStack.mulPose(new Quaternionf(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
        poseStack.scale(pose.scale(), pose.scale(), pose.scale());
    }
}
