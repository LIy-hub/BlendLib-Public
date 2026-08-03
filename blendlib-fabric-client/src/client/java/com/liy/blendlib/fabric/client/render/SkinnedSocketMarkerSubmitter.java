package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Quaternionf;

/**
 * Client-only submission of one extraction-captured P5 skinned socket marker.
 *
 * <p>The entity dispatcher has already positioned the supplied pose stack. This helper applies
 * only the immutable snapshot's root transform, prepared model-unit conversion, and socket TRS
 * before submitting ordinary line geometry. It never receives an entity/world, resolves a socket,
 * samples animation, accesses a registry, or performs resource I/O.</p>
 */
public final class SkinnedSocketMarkerSubmitter {
    private static final float MARKER_HALF_EXTENT = 0.075F;
    private static final float MARKER_LINE_WIDTH = 1.0F;
    private static final int X_AXIS_ARGB = 0xFFFF4A4A;
    private static final int Y_AXIS_ARGB = 0xFF4AFF4A;
    private static final int Z_AXIS_ARGB = 0xFF4AA8FF;

    private SkinnedSocketMarkerSubmitter() {
    }

    /** Submits a small RGB axis marker only when extraction captured a visible skinned socket. */
    public static void submit(
            ModelRenderSnapshot snapshot,
            PoseStack poseStack,
            SubmitNodeCollector collector) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        PoseStack checkedPoseStack = Objects.requireNonNull(poseStack, "poseStack");
        SubmitNodeCollector checkedCollector = Objects.requireNonNull(collector, "collector");
        if (!hasPreparedMarker(checkedSnapshot)) {
            return;
        }

        Transform socketTransform = checkedSnapshot.presentationSocketTransformOrNull();
        checkedPoseStack.pushPose();
        try {
            applyMarkerTransform(checkedPoseStack, checkedSnapshot, socketTransform);
            checkedCollector.submitCustomGeometry(
                    checkedPoseStack,
                    RenderTypes.lines(),
                    SkinnedSocketMarkerSubmitter::emitAxes);
        } finally {
            checkedPoseStack.popPose();
        }
    }

    /** Package-private predicate for render-contract tests and the submit fast path. */
    static boolean hasPreparedMarker(ModelRenderSnapshot snapshot) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        return checkedSnapshot.handle().skinned()
                && checkedSnapshot.visibility() == RenderVisibility.VISIBLE
                && checkedSnapshot.presentationSocketTransformOrNull() != null;
    }

    /** Applies exactly {@code root -> unitsToBlocksScale -> socket} in the entity-relative stack. */
    static void applyMarkerTransform(
            PoseStack poseStack,
            ModelRenderSnapshot snapshot,
            Transform socketTransform) {
        PoseStack checkedPoseStack = Objects.requireNonNull(poseStack, "poseStack");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        Transform checkedSocketTransform = Objects.requireNonNull(socketTransform, "socketTransform");
        applyTransform(checkedPoseStack, checkedSnapshot.rootTransform());
        float unitsToBlocksScale = checkedSnapshot.handle().unitsToBlocksScale();
        checkedPoseStack.scale(unitsToBlocksScale, unitsToBlocksScale, unitsToBlocksScale);
        applyTransform(checkedPoseStack, checkedSocketTransform);
    }

    private static void emitAxes(PoseStack.Pose pose, VertexConsumer consumer) {
        emitAxis(pose, consumer, -MARKER_HALF_EXTENT, 0.0F, 0.0F, MARKER_HALF_EXTENT, 0.0F, 0.0F, X_AXIS_ARGB, 1.0F, 0.0F, 0.0F);
        emitAxis(pose, consumer, 0.0F, -MARKER_HALF_EXTENT, 0.0F, 0.0F, MARKER_HALF_EXTENT, 0.0F, Y_AXIS_ARGB, 0.0F, 1.0F, 0.0F);
        emitAxis(pose, consumer, 0.0F, 0.0F, -MARKER_HALF_EXTENT, 0.0F, 0.0F, MARKER_HALF_EXTENT, Z_AXIS_ARGB, 0.0F, 0.0F, 1.0F);
    }

    private static void emitAxis(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float startX,
            float startY,
            float startZ,
            float endX,
            float endY,
            float endZ,
            int argb,
            float normalX,
            float normalY,
            float normalZ) {
        consumer.addVertex(pose, startX, startY, startZ)
                .setColor(argb)
                .setNormal(pose, normalX, normalY, normalZ)
                .setLineWidth(MARKER_LINE_WIDTH);
        consumer.addVertex(pose, endX, endY, endZ)
                .setColor(argb)
                .setNormal(pose, normalX, normalY, normalZ)
                .setLineWidth(MARKER_LINE_WIDTH);
    }

    private static void applyTransform(PoseStack poseStack, Transform transform) {
        poseStack.translate(transform.translation().x(), transform.translation().y(), transform.translation().z());
        Quaternion rotation = transform.rotation();
        poseStack.mulPose(new Quaternionf(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
        poseStack.scale(transform.scale().x(), transform.scale().y(), transform.scale().z());
    }
}
