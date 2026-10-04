package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.ProceduralPoseComponent;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.ProceduralPosePipeline;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.TwoBoneIkPoseComponent;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.TwoBoneIkTarget;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.WeightedPoseComponent;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachment;
import com.liy.blendlib.fabric.client.entity.BlendEntityCullingEnvelope;
import com.liy.blendlib.fabric.client.entity.BlendEntityRotation;
import com.liy.blendlib.fabric.client.entity.BlendEntitySnapshotRequest;
import com.liy.blendlib.fabric.client.entity.BlendEntitySocketPose;
import com.liy.blendlib.fabric.client.entity.BlendEntitySockets;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** The live renderer and headless check share this ordinary, Minecraft-free consumer scene. */
public final class ExampleTwoBoneIkScene {
    public static final String PROPERTY = "blendlib.examples.twoBoneIk";
    public static final BlendModelKey MODEL = BlendModelKey.parse(ExampleContent.MOD_ID + ":mechanical_arm");
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":idle");
    public static final BlendResourceId END = BlendResourceId.parse(ExampleContent.MOD_ID + ":ik_end");
    public static final BlendResourceId ORIGIN = BlendResourceId.parse(ExampleContent.MOD_ID + ":ik_origin");
    public static final BlendResourceId MOUNT = BlendResourceId.parse(ExampleContent.MOD_ID + ":ik_mount");
    public static final BlendModelKey TARGET_MARKER = BlendModelKey.parse(ExampleContent.MOD_ID + ":ik_target_marker");
    public static final BlendModelKey END_MARKER = BlendModelKey.parse(ExampleContent.MOD_ID + ":ik_end_marker");
    public static final String ROOT_BONE = "ArmShoulder", MIDDLE_BONE = "ArmElbow", END_BONE = "ArmEnd";
    // All links, target cage and end fork remain within this pre-entity-rotation block envelope.
    public static final BlendEntityCullingEnvelope ENVELOPE =
            new BlendEntityCullingEnvelope(-3, -2.5, -3, 3, 3.6, 3);

    private ExampleTwoBoneIkScene() { }

    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }

    /** One deterministic extraction-time trajectory; no wall-clock or submit-time sampling. */
    public static TwoBoneIkTarget target(double ticks) {
        if (!Double.isFinite(ticks)) throw new IllegalArgumentException("ticks must be finite");
        double phase = (ticks % 240.0) * Math.PI / 120.0;
        return new TwoBoneIkTarget(new Vec3((float) (.9 * Math.cos(phase)),
                (float) (1.45 + .25 * Math.sin(phase * 2)), (float) (.9 + .35 * Math.sin(phase))),
                new Vec3(0, 2.8F, -.9F));
    }

    public static ProceduralPosePipeline procedural() {
        return procedural(message -> System.getLogger(ExampleTwoBoneIkScene.class.getName())
                .log(System.Logger.Level.WARNING, message));
    }

    /** Diagnostic injection also lets verification exercise the exact resource-pack fallback. */
    public static ProceduralPosePipeline procedural(Consumer<String> diagnostic) {
        var ik = new TwoBoneIkPoseComponent(ROOT_BONE, MIDDLE_BONE, END_BONE,
                context -> target(context.clientGameTimeInTicks()));
        return ProceduralPosePipeline.of(new CompatibleRigGuard(
                new WeightedPoseComponent(ik, context -> 1.0), diagnostic));
    }

    /** Both are real prepared models: a cyan target cage and a gold final extracted socket marker. */
    public static List<BlendEntityAttachment> attachments(ClientModelLookup models,
            BlendEntitySnapshotRequest request, BlendEntitySockets sockets, int packedOverlay) {
        var origin = sockets.socket(ORIGIN);
        var end = sockets.socket(END);
        var targetModel = models.resolve(TARGET_MARKER);
        var endModel = models.resolve(END_MARKER);
        if (origin.isEmpty() || end.isEmpty() || targetModel.missing() || endModel.missing()
                || targetModel.generationId() != sockets.generation() || endModel.generationId() != sockets.generation()) {
            return List.of();
        }
        var target = target(request.clientGameTick() + (double) request.partialTick()).targetModelSpace();
        // An authored origin is not necessarily the model origin: resource packs may translate,
        // rotate or uniformly scale ArmScene. Attachment offsets are socket-local blocks, so
        // invert the captured model-space socket first, then apply descriptor unit conversion.
        var originSocket = origin.orElseThrow();
        var modelOrigin = originSocket.modelSpace();
        var rotation = modelOrigin.rotation();
        var inverseRotation = new Quaternion(-rotation.x(), -rotation.y(), -rotation.z(), rotation.w());
        var relativeTarget = inverseRotation.rotate(new Vec3(
                (float) (target.x() - modelOrigin.x()), (float) (target.y() - modelOrigin.y()),
                (float) (target.z() - modelOrigin.z())))
                .multiply(originSocket.unitsToBlocksScale() / modelOrigin.scale());
        var targetOffset = new BlendEntitySocketPose(relativeTarget.x(), relativeTarget.y(), relativeTarget.z(),
                BlendEntityRotation.IDENTITY, 1);
        var targetHandle = targetModel.renderHandle();
        var endHandle = endModel.renderHandle();
        var cage = new ModelRenderSnapshot(targetHandle, Transform.IDENTITY, request.packedLight(), packedOverlay,
                0xFF30E8F0, RenderVisibility.VISIBLE, new CullingMetadata(targetHandle.bounds(), true));
        var endpoint = new ModelRenderSnapshot(endHandle, Transform.IDENTITY, request.packedLight(), packedOverlay,
                0xFFFFD040, RenderVisibility.VISIBLE, new CullingMetadata(endHandle.bounds(), true));
        return List.of(BlendEntityAttachment.at(origin.orElseThrow(), targetOffset, cage),
                BlendEntityAttachment.at(end.orElseThrow(), endpoint));
    }

    /** Resource packs may replace the authored chain. Only that configuration failure is softened. */
    private static final class CompatibleRigGuard implements ProceduralPoseComponent {
        private final ProceduralPoseComponent delegate;
        private final Consumer<String> diagnostic;
        private long rejectedGeneration = -1;
        private CompatibleRigGuard(ProceduralPoseComponent delegate, Consumer<String> diagnostic) {
            this.delegate = delegate;
            this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        }
        @Override public LocalPose modify(ClientAnimationPoseContext context, LocalPose pose) {
            var rig = context.rig();
            boolean compatible = rig.uniqueNodeNames().containsAll(List.of(ROOT_BONE, MIDDLE_BONE, END_BONE));
            if (compatible) {
                int root = rig.requireNodeIndex(ROOT_BONE), middle = rig.requireNodeIndex(MIDDLE_BONE), end = rig.requireNodeIndex(END_BONE);
                compatible = rig.parentIndex(middle).orElse(-1) == root && rig.parentIndex(end).orElse(-1) == middle;
            }
            if (!compatible) {
                if (rejectedGeneration != context.generation()) {
                    rejectedGeneration = context.generation();
                    diagnostic.accept("Mechanical-arm IK disabled for model " + context.modelKey()
                            + " generation " + context.generation() + ": resource pack must provide unique direct chain "
                            + ROOT_BONE + " -> " + MIDDLE_BONE + " -> " + END_BONE + "; preserving sampled pose");
                }
                return pose;
            }
            return delegate.modify(context, pose);
        }
        @Override public void reset() { rejectedGeneration = -1; delegate.reset(); }
        @Override public void reset(com.liy.blendlib.api.BlendInstanceKey key) { delegate.reset(key); }
    }
}
