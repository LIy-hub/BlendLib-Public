package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Submit-only X6 bridge over Minecraft 26.1.2's verified public collector and RenderType APIs. */
public final class X6PlanSubmitter {
    private static final float OUTLINE_NORMAL_OFFSET = 0.015f;
    private static final ThreadLocal<Quaternionf> QUATERNION_SCRATCH = ThreadLocal.withInitial(Quaternionf::new);
    private static final X6PreparedRenderPlanFactory.SuppressionAppender SUPPRESSION_APPENDER = Throwable::addSuppressed;
    private static volatile X7DeferredSubmissionEndpoint deferredSubmissionEndpoint = X7DeferredSubmissionEndpoint.current();

    private X6PlanSubmitter() {
    }

    /** Client-bootstrap seam; the endpoint itself remains the only cross-package X7 adapter. */
    static void installDeferredSubmissionEndpoint(X7DeferredSubmissionEndpoint endpoint) {
        deferredSubmissionEndpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    /** Emits prepared base draws, final layers, and frozen child snapshots through only public APIs. */
    public static void submit(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            RenderSubmissionContext context,
            ModelRenderBackend attachmentBackend) {
        plan = Objects.requireNonNull(plan, "plan");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        context = Objects.requireNonNull(context, "context");
        attachmentBackend = Objects.requireNonNull(attachmentBackend, "attachmentBackend");
        plan.acquireSubmissionHold(snapshot);
        Throwable primaryFailure = null;
        Throwable releaseFailure = null;
        try {
            try {
                submitAdmitted(plan, snapshot, context, attachmentBackend);
            } catch (Throwable failure) {
                primaryFailure = failure;
            }
        } finally {
            try {
                plan.releaseSubmissionHold();
            } catch (Throwable failure) {
                releaseFailure = failure;
            }
        }
        rethrow(selectFailure(primaryFailure, releaseFailure));
    }

    /** Runs every renderer/backend callback outside the plan monitor after its primitive hold admits it. */
    private static void submitAdmitted(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            RenderSubmissionContext context,
            ModelRenderBackend attachmentBackend) {
        if (!plan.permitsFrozenCpuRoute()) {
            return;
        }
        if (snapshot.visibility() == RenderVisibility.CULLED) {
            return;
        }
        // Stage A is intentionally narrower than the ordinary CPU route: it can replace a whole
        // submit call only when this plan has exactly one final base draw and no independently
        // rendered layer/equipment work. It runs while the plan submission hold and caller context
        // are live, and may suppress CPU only after the gateway has frozen every value and moved
        // the exact child into its bounded queue.
        X6PreparedDraw deferredCandidate = isSingleDeferredCandidate(plan) ? plan.baseDraws().getFirst() : null;
        if (deferredCandidate != null
                && deferredSubmissionEndpoint.tryPreAdmit(
                                plan,
                                snapshot,
                                deferredCandidate.draw(),
                                freezeFinalDrawInputs(deferredCandidate, snapshot, context))
                        == X7DeferredSubmissionEndpoint.StageAResult.SUPPRESS_CPU_AFTER_FULL_ADMISSION) {
            return;
        }
        for (X6PreparedDraw draw : plan.baseDraws()) {
            submitDraw(
                    draw,
                    draw.draw().material(),
                    draw.draw().argbTint(),
                    X6LayerPresentation.NONE,
                    0xFFFFFFFF,
                    null,
                    snapshot,
                    context,
                    draw.renderType());
        }
        for (X6PreparedLayerSubmission submission : plan.layerSubmissions()) {
            X6ResolvedRenderLayer layer = submission.layer();
            if (layer.attachmentSnapshot().isPresent()) {
                attachmentBackend.submit(layer.attachmentSnapshot().orElseThrow(), context);
                continue;
            }
            submitDraw(
                    submission.target(),
                    submission.material(),
                    submission.target().draw().argbTint(),
                    layer.presentation(),
                    layer.presentationArgb(),
                    submission.boneSubset().orElse(null),
                    snapshot,
                    context,
                    submission.renderType());
        }
        for (ModelRenderSnapshot equipment : plan.variants().equipmentSnapshots()) {
            attachmentBackend.submit(equipment, context);
        }
    }

    private static boolean isSingleDeferredCandidate(X6PreparedRenderPlan plan) {
        return plan.baseDraws().size() == 1
                && plan.layerSubmissions().isEmpty()
                && plan.variants().equipmentSnapshots().isEmpty();
    }

    private static Throwable selectFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) {
            return cleanup;
        }
        if (cleanup == null || cleanup == primary) {
            return primary;
        }
        Throwable selected = primary instanceof Error || !(cleanup instanceof Error) ? primary : cleanup;
        Throwable suppressed = selected == primary ? cleanup : primary;
        X6PreparedRenderPlanFactory.addSuppressedSafely(selected, suppressed, SUPPRESSION_APPENDER);
        return selected;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("X6 plan submit failed", failure);
    }

    private static void submitDraw(
            X6PreparedDraw prepared,
            RenderMaterial material,
            int drawTint,
            X6LayerPresentation presentation,
            int presentationArgb,
            X6BoneInfluenceSubset boneSubset,
            ModelRenderSnapshot snapshot,
            RenderSubmissionContext context,
            RenderType renderType) {
        X6DrawPrimitive draw = prepared.draw();
        withFinalDrawInputs(prepared, material, drawTint, presentation, presentationArgb, snapshot, context,
                (poseStack, finalInputs) -> {
                    switch (draw.binding()) {
                        case X6GeometryBinding.StaticBinding staticBinding -> {
                            if (boneSubset != null) {
                                throw new IllegalArgumentException(X6DiagnosticCode.GEOMETRY_MISMATCH.code()
                                        + ": a prepared bone subset cannot target static geometry");
                            }
                            submitStatic(
                                    staticBinding.primitive(),
                                    poseStack,
                                    context.collector(),
                                    renderType,
                                    finalInputs,
                                    presentation,
                                    snapshot);
                        }
                        case X6GeometryBinding.SkinnedBinding skinnedBinding -> submitSkinned(
                                skinnedBinding,
                                poseStack,
                                context.collector(),
                                renderType,
                                finalInputs,
                                presentation,
                                boneSubset,
                                snapshot);
                    }
                    return null;
                });
    }

    private static void submitStatic(
            PreparedRenderPrimitive primitive,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            RenderType renderType,
            FinalDrawInputs finalInputs,
            X6LayerPresentation presentation,
            ModelRenderSnapshot snapshot) {
        collector.submitCustomGeometry(
                poseStack,
                renderType,
                FrozenGeometryRenderer.staticGeometry(
                        primitive.geometry(),
                        finalInputs.composedArgb(),
                        finalInputs.packedLight(),
                        snapshot.packedOverlay(),
                        presentation));
    }

    private static void submitSkinned(
            X6GeometryBinding.SkinnedBinding binding,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            RenderType renderType,
            FinalDrawInputs finalInputs,
            X6LayerPresentation presentation,
            X6BoneInfluenceSubset boneSubset,
            ModelRenderSnapshot snapshot) {
        SkinnedRenderSnapshot skinned = snapshot.skinnedRenderSnapshot();
        if (skinned == null || binding.snapshotMeshIndex() >= skinned.meshCount()) {
            throw new IllegalArgumentException(X6DiagnosticCode.GEOMETRY_MISMATCH.code()
                    + ": prepared X6 skinned draw lacks its exact captured mesh");
        }
        if (boneSubset != null && boneSubset.primitive() != binding.primitive()) {
            throw new IllegalArgumentException(X6DiagnosticCode.GEOMETRY_MISMATCH.code()
                    + ": prepared X6 bone subset belongs to another skinned primitive");
        }
        SkinnedMeshSnapshot mesh = skinned.meshes().get(binding.snapshotMeshIndex());
        collector.submitCustomGeometry(
                poseStack,
                renderType,
                FrozenGeometryRenderer.skinnedGeometry(
                        mesh,
                        boneSubset,
                        finalInputs.composedArgb(),
                        finalInputs.packedLight(),
                        snapshot.packedOverlay(),
                        presentation));
    }

    /**
     * Captures the exact final single-base-draw state while the caller's context and submission hold remain live.
     * It uses the same transform/light path as ordinary CPU emission but retains only immutable copies.
     */
    static FinalDrawInputs freezeFinalDrawInputs(
            X6PreparedDraw prepared, ModelRenderSnapshot snapshot, RenderSubmissionContext context) {
        X6PreparedDraw checkedPrepared = Objects.requireNonNull(prepared, "prepared");
        X6DrawPrimitive draw = checkedPrepared.draw();
        return withFinalDrawInputs(
                checkedPrepared,
                draw.material(),
                draw.argbTint(),
                X6LayerPresentation.NONE,
                0xFFFFFFFF,
                snapshot,
                context,
                (ignoredPoseStack, finalInputs) -> finalInputs);
    }

    /** One non-retaining final-draw transform/light helper shared by CPU emission and deferred Stage A. */
    private static <T> T withFinalDrawInputs(
            X6PreparedDraw prepared,
            RenderMaterial material,
            int drawTint,
            X6LayerPresentation presentation,
            int presentationArgb,
            ModelRenderSnapshot snapshot,
            RenderSubmissionContext context,
            FinalDrawConsumer<T> consumer) {
        X6PreparedDraw checkedPrepared = Objects.requireNonNull(prepared, "prepared");
        RenderMaterial checkedMaterial = Objects.requireNonNull(material, "material");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        RenderSubmissionContext checkedContext = Objects.requireNonNull(context, "context");
        PoseStack poseStack = checkedContext.poseStack();
        poseStack.pushPose();
        try {
            applyRootAndPresentation(poseStack, checkedSnapshot, presentation);
            if (checkedPrepared.draw().binding() instanceof X6GeometryBinding.StaticBinding staticBinding) {
                applyTransform(
                        poseStack,
                        Minecraft2612StaticRigidRenderBackend.nodeTransformFor(
                                checkedSnapshot, staticBinding.primitive().nodeIndex()));
            }
            int color = multiplyArgb(multiplyArgb(checkedSnapshot.tintArgb(), checkedMaterial.argbTint()), drawTint);
            if (presentation != X6LayerPresentation.NONE) {
                color = multiplyArgb(color, presentationArgb);
            }
            PoseStack.Pose finalPose = Objects.requireNonNull(poseStack.last(), "final pose");
            FinalDrawInputs inputs = new FinalDrawInputs(
                    new Matrix4f(finalPose.pose()),
                    new Matrix3f(finalPose.normal()),
                    color,
                    Minecraft2612StaticRigidRenderBackend.packedLightFor(checkedMaterial, checkedSnapshot.packedLight()));
            return Objects.requireNonNull(consumer, "consumer").accept(poseStack, inputs);
        } finally {
            poseStack.popPose();
        }
    }

    @FunctionalInterface
    private interface FinalDrawConsumer<T> {
        T accept(PoseStack poseStack, FinalDrawInputs inputs);
    }

    /** Immutable final pose/color/light copies; it deliberately retains no live context, stack, or collector. */
    static final class FinalDrawInputs {
        private final Matrix4f modelView;
        private final Matrix3f normalTransform;
        private final int composedArgb;
        private final int packedLight;

        private FinalDrawInputs(Matrix4f modelView, Matrix3f normalTransform, int composedArgb, int packedLight) {
            this.modelView = new Matrix4f(Objects.requireNonNull(modelView, "modelView"));
            this.normalTransform = new Matrix3f(Objects.requireNonNull(normalTransform, "normalTransform"));
            this.composedArgb = composedArgb;
            this.packedLight = packedLight;
            if (!this.modelView.isFinite() || !this.normalTransform.isFinite()) {
                throw new IllegalArgumentException("X6 final draw matrices must be finite");
            }
        }

        Matrix4f modelViewCopy() {
            return new Matrix4f(modelView);
        }

        Matrix3f normalCopy() {
            return new Matrix3f(normalTransform);
        }

        int composedArgb() {
            return composedArgb;
        }

        int packedLight() {
            return packedLight;
        }
    }

    private static void applyRootAndPresentation(
            PoseStack poseStack, ModelRenderSnapshot snapshot, X6LayerPresentation presentation) {
        applyTransform(poseStack, snapshot.rootTransform());
        float scale = snapshot.handle().unitsToBlocksScale();
        poseStack.scale(scale, scale, scale);
        if (presentation == X6LayerPresentation.SHADOW_FLATTEN) {
            // Bounded CPU transform under the existing translucent public route; no shader state.
            poseStack.translate(0.0f, -0.015f, 0.0f);
            poseStack.scale(1.05f, 0.0125f, 1.05f);
        }
    }

    private static void applyTransform(PoseStack poseStack, Transform transform) {
        poseStack.translate(transform.translation().x(), transform.translation().y(), transform.translation().z());
        Quaternion rotation = transform.rotation();
        Quaternionf scratch = QUATERNION_SCRATCH.get();
        poseStack.mulPose(scratch.set(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
        poseStack.scale(transform.scale().x(), transform.scale().y(), transform.scale().z());
    }

    /**
     * One immutable deferred collector callback per submitted draw.
     *
     * <p>The callback owns only frozen geometry references and scalar frame values. Its mutable
     * pose/consumer fields exist solely during its own {@link #render} invocation, never retain a
     * plan, lease, thread-local scratch, or frame-state object, and avoid the former nested
     * capturing {@code VertexSink} lambda.</p>
     */
    private static final class FrozenGeometryRenderer implements
            SubmitNodeCollector.CustomGeometryRenderer,
            StaticGeometry.VertexSink,
            SkinnedMeshSnapshot.VertexSink {
        private final StaticGeometry staticGeometry;
        private final SkinnedMeshSnapshot skinnedGeometry;
        private final X6BoneInfluenceSubset boneSubset;
        private final int argb;
        private final int packedLight;
        private final int packedOverlay;
        private final X6LayerPresentation presentation;
        private PoseStack.Pose activePose;
        private VertexConsumer activeConsumer;

        private FrozenGeometryRenderer(
                StaticGeometry staticGeometry,
                SkinnedMeshSnapshot skinnedGeometry,
                X6BoneInfluenceSubset boneSubset,
                int argb,
                int packedLight,
                int packedOverlay,
                X6LayerPresentation presentation) {
            this.staticGeometry = staticGeometry;
            this.skinnedGeometry = skinnedGeometry;
            this.boneSubset = boneSubset;
            this.argb = argb;
            this.packedLight = packedLight;
            this.packedOverlay = packedOverlay;
            this.presentation = presentation;
        }

        static FrozenGeometryRenderer staticGeometry(
                StaticGeometry geometry,
                int argb,
                int packedLight,
                int packedOverlay,
                X6LayerPresentation presentation) {
            return new FrozenGeometryRenderer(
                    Objects.requireNonNull(geometry, "geometry"), null, null, argb, packedLight, packedOverlay, presentation);
        }

        static FrozenGeometryRenderer skinnedGeometry(
                SkinnedMeshSnapshot geometry,
                X6BoneInfluenceSubset boneSubset,
                int argb,
                int packedLight,
                int packedOverlay,
                X6LayerPresentation presentation) {
            return new FrozenGeometryRenderer(
                    null, Objects.requireNonNull(geometry, "geometry"), boneSubset, argb, packedLight, packedOverlay, presentation);
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            if (activePose != null || activeConsumer != null) {
                throw new IllegalStateException("X6 collector callback must not re-enter one frozen renderer instance");
            }
            activePose = Objects.requireNonNull(pose, "pose");
            activeConsumer = Objects.requireNonNull(consumer, "consumer");
            try {
                if (staticGeometry != null) {
                    staticGeometry.emit(this);
                } else if (boneSubset != null) {
                    boneSubset.emit(skinnedGeometry, this);
                } else {
                    skinnedGeometry.emit(this);
                }
            } finally {
                activeConsumer = null;
                activePose = null;
            }
        }

        @Override
        public void vertex(float x, float y, float z, float normalX, float normalY, float normalZ, float u, float v) {
            float offset = presentation == X6LayerPresentation.OUTLINE_SHELL ? OUTLINE_NORMAL_OFFSET : 0.0f;
            activeConsumer.addVertex(
                            activePose,
                            x + normalX * offset,
                            y + normalY * offset,
                            z + normalZ * offset)
                    .setColor(argb)
                    .setUv(u, v)
                    .setOverlay(packedOverlay)
                    .setLight(packedLight)
                    .setNormal(activePose, normalX, normalY, normalZ);
        }
    }

    private static int multiplyArgb(int left, int right) {
        return multiplyChannel(left >>> 24, right >>> 24) << 24
                | multiplyChannel(left >>> 16 & 0xFF, right >>> 16 & 0xFF) << 16
                | multiplyChannel(left >>> 8 & 0xFF, right >>> 8 & 0xFF) << 8
                | multiplyChannel(left & 0xFF, right & 0xFF);
    }

    private static int multiplyChannel(int left, int right) {
        return left * right / 255;
    }
}
