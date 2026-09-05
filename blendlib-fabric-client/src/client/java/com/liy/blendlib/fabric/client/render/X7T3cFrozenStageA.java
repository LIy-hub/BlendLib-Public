package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding.DeferredSubmissionReceipt;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedFrameProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * One immutable Stage-A handoff from the live X6 submit call to the client-private X7 queue gateway.
 *
 * <p>This is deliberately a value boundary: X6 copies the final caller matrices while the pose stack is live and
 * retains no {@link RenderSubmissionContext}, pose stack, collector, target, encoder, pass, D1 record, or native
 * handle. The only ownership value crossing the package boundary is the pre-existing close-only submitted child.
 * A caller must close this object unless the queue atomically takes that child.</p>
 */
public final class X7T3cFrozenStageA implements AutoCloseable {
    /** The only two routes that may be represented by a frozen Stage-A handoff. */
    public enum Route {
        STATIC_RIGID,
        SKINNED_T4P
    }

    private final X6PreparedRenderPlan exactPlan;
    private final ModelRenderSnapshot exactSnapshot;
    private final X6DrawPrimitive exactDraw;
    private final Route route;
    private final Matrix4f modelView;
    private final Matrix3f normalTransform;
    private final int composedArgb;
    private final int packedLight;
    private final int packedOverlay;
    private final boolean cadenceAlreadyConsumed;
    private final X7SkinnedFrameProvenance skinnedFrameProof;
    private final X7SkinnedTexelProvenance.Attempt skinnedAttempt;
    private DeferredSubmissionReceipt callerOwnedChild;
    private boolean skinnedProofSealedForQueue;
    private boolean closed;

    private X7T3cFrozenStageA(
            X6PreparedRenderPlan exactPlan,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw,
            Route route,
            Matrix4f modelView,
            Matrix3f normalTransform,
            int composedArgb,
            int packedLight,
            boolean cadenceAlreadyConsumed,
            X7SkinnedFrameProvenance skinnedFrameProof,
            X7SkinnedTexelProvenance.Attempt skinnedAttempt,
            DeferredSubmissionReceipt callerOwnedChild) {
        this.exactPlan = Objects.requireNonNull(exactPlan, "exactPlan");
        this.exactSnapshot = Objects.requireNonNull(exactSnapshot, "exactSnapshot");
        this.exactDraw = Objects.requireNonNull(exactDraw, "exactDraw");
        this.route = Objects.requireNonNull(route, "route");
        this.modelView = new Matrix4f(Objects.requireNonNull(modelView, "modelView"));
        this.normalTransform = new Matrix3f(Objects.requireNonNull(normalTransform, "normalTransform"));
        this.composedArgb = composedArgb;
        this.packedLight = packedLight;
        this.packedOverlay = exactSnapshot.packedOverlay();
        this.cadenceAlreadyConsumed = cadenceAlreadyConsumed;
        this.skinnedFrameProof = skinnedFrameProof;
        this.skinnedAttempt = skinnedAttempt;
        this.callerOwnedChild = Objects.requireNonNull(callerOwnedChild, "callerOwnedChild");
        if (!this.modelView.isFinite() || !this.normalTransform.isFinite()) {
            throw new IllegalArgumentException("X7 Stage A requires finite final model-view and normal matrices");
        }
        if (packedOverlay != OverlayTexture.NO_OVERLAY) {
            throw new IllegalArgumentException("X7 Stage A requires OverlayTexture.NO_OVERLAY exactly");
        }
        if (route == Route.SKINNED_T4P
                && (skinnedFrameProof == null || skinnedAttempt == null || !skinnedAttempt.eligible())) {
            throw new IllegalArgumentException("X7 skinned Stage A requires one exact materialized T4p proof");
        }
        if (route == Route.STATIC_RIGID && (skinnedFrameProof != null || skinnedAttempt != null)) {
            throw new IllegalArgumentException("X7 static Stage A cannot retain a skinned proof");
        }
    }

    /**
     * Freezes the exact draw while the caller's context and plan submit hold remain live, then mints its one child.
     *
     * <p>Reaching this method means X6 has already consumed the selection/cadence decision for this immutable
     * submitted snapshot; Phase B cannot revisit or recompute it. Unsupported real inputs fail before child mint and
     * retain the ordinary same-call CPU route.</p>
     */
    static X7T3cFrozenStageA freezeAndMint(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            X6DrawPrimitive draw,
            X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
        return freezeAndMint(plan, snapshot, draw, finalDrawInputs, true);
    }

    /** Package-private focused-test seam for an explicit missing cadence/provenance proof. */
    static X7T3cFrozenStageA freezeAndMint(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            X6DrawPrimitive draw,
            X6PlanSubmitter.FinalDrawInputs finalDrawInputs,
            boolean cadenceAlreadyConsumed) {
        X6PreparedRenderPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        X6DrawPrimitive checkedDraw = Objects.requireNonNull(draw, "draw");
        X6PlanSubmitter.FinalDrawInputs checkedFinalDrawInputs =
                Objects.requireNonNull(finalDrawInputs, "finalDrawInputs");
        if (checkedSnapshot.visibility() != RenderVisibility.VISIBLE) {
            throw new IllegalArgumentException("Only a visible X6 snapshot may enter X7 Stage A");
        }
        if (checkedSnapshot.packedOverlay() != OverlayTexture.NO_OVERLAY) {
            throw new IllegalArgumentException("X7 Stage A requires OverlayTexture.NO_OVERLAY exactly");
        }
        if (checkedDraw.material().layer() != RenderLayer.SOLID
                || checkedDraw.material().doubleSided()
                || checkedDraw.material().missingModelMaterial()
                || !opaque(checkedSnapshot.tintArgb())
                || !opaque(checkedDraw.material().argbTint())
                || !opaque(checkedDraw.argbTint())) {
            throw new IllegalArgumentException("X7 Stage A accepts only one opaque single-sided SOLID draw");
        }
        checkedPlan.requireCompatible(checkedSnapshot);
        X7SkinnedFrameProvenance frameProof = null;
        X7SkinnedTexelProvenance.Attempt attempt = null;
        Route route;
        if (checkedDraw.binding() instanceof X6GeometryBinding.StaticBinding) {
            if (checkedSnapshot.handle().skinned()) {
                throw new IllegalArgumentException("A static X7 draw cannot retain a skinned render handle");
            }
            route = Route.STATIC_RIGID;
        } else if (checkedDraw.binding() instanceof X6GeometryBinding.SkinnedBinding skinnedBinding) {
            if (!checkedSnapshot.handle().skinned()) {
                throw new IllegalArgumentException("A skinned X7 draw requires its exact skinned render handle");
            }
            SkinnedRenderSnapshot skinnedSnapshot = checkedSnapshot.skinnedRenderSnapshot();
            if (skinnedSnapshot == null) {
                throw new IllegalArgumentException("A skinned X7 draw requires an exact captured skinned snapshot");
            }
            frameProof = skinnedSnapshot.x7FrameProvenanceAt(
                    skinnedBinding.snapshotMeshIndex(), skinnedBinding.primitive());
            attempt = frameProof.tryMaterialize();
            if (!attempt.eligible()) {
                throw new IllegalArgumentException("The exact T4p frame proof is GPU-ineligible", attempt.fallbackCauseOrNull());
            }
            route = Route.SKINNED_T4P;
        } else {
            throw new IllegalArgumentException("X7 Stage A received an unsupported prepared geometry binding");
        }

        DeferredSubmissionReceipt child = null;
        try {
            child = checkedPlan.beginDeferredSubmission(checkedSnapshot, checkedDraw);
            return new X7T3cFrozenStageA(
                    checkedPlan,
                    checkedSnapshot,
                    checkedDraw,
                    route,
                    checkedFinalDrawInputs.modelViewCopy(),
                    checkedFinalDrawInputs.normalCopy(),
                    checkedFinalDrawInputs.composedArgb(),
                    checkedFinalDrawInputs.packedLight(),
                    cadenceAlreadyConsumed,
                    frameProof,
                    attempt,
                    child);
        } catch (Throwable failure) {
            closeChildAndProof(child, attempt, failure);
            throw failure;
        }
    }

    public ModelRenderSnapshot exactSnapshot() {
        return exactSnapshot;
    }

    public X6DrawPrimitive exactDraw() {
        return exactDraw;
    }

    public Route route() {
        return route;
    }

    public Matrix4f modelViewCopy() {
        return new Matrix4f(modelView);
    }

    public Matrix3f normalCopy() {
        return new Matrix3f(normalTransform);
    }

    public int composedArgb() {
        return composedArgb;
    }

    public int packedLight() {
        return packedLight;
    }

    public int packedOverlay() {
        return packedOverlay;
    }

    /** True only for the exact selection/cadence decision already consumed by this X6 submit call. */
    public boolean cadenceAlreadyConsumed() {
        return cadenceAlreadyConsumed;
    }

    /** Exact T4p frame proof for the skinned route, otherwise {@code null}. */
    public X7SkinnedFrameProvenance skinnedFrameProofOrNull() {
        return skinnedFrameProof;
    }

    /** Exact typed T4p materialization attempt for the skinned route, otherwise {@code null}. */
    public X7SkinnedTexelProvenance.Attempt skinnedAttemptOrNull() {
        return skinnedAttempt;
    }

    /**
     * Records that reload has identity-matched the existing exact D1 static source. It moves no D1 value: this object
     * keeps the current palette/parity proof until the queue's no-command or verified-completion terminal owner closes
     * it, while D1 independently owns the immutable geometry/influence source.
     */
    public synchronized boolean transferSkinnedProofToExactD1Leaf(X7SkinnedTexelProvenance exactProvenance) {
        if (closed || route != Route.SKINNED_T4P || skinnedProofSealedForQueue
                || skinnedAttempt == null || skinnedAttempt.provenanceOrNull() != exactProvenance) {
            return false;
        }
        skinnedProofSealedForQueue = true;
        return true;
    }

    /** The existing close-only child; it exposes no D1 owner, leaf, record, policy, or native handle. */
    public synchronized DeferredSubmissionReceipt childReceipt() {
        if (closed || callerOwnedChild == null) {
            throw new IllegalStateException("X7 Stage-A child is no longer caller-owned");
        }
        return callerOwnedChild;
    }

    /** Internal identity predicate used by the inline queue payload without exposing the retained plan. */
    boolean retainsExactPlan(X6PreparedRenderPlan candidate) {
        return exactPlan == Objects.requireNonNull(candidate, "candidate");
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        DeferredSubmissionReceipt child = callerOwnedChild;
        callerOwnedChild = null;
        Throwable primary = null;
        try {
            if (child != null) {
                child.close();
            }
        } catch (Throwable failure) {
            primary = failure;
        }
        X7SkinnedTexelProvenance provenance = skinnedAttempt == null ? null : skinnedAttempt.provenanceOrNull();
        if (provenance != null) {
            try {
                provenance.close();
            } catch (Throwable failure) {
                if (primary == null) {
                    primary = failure;
                } else if (primary != failure) {
                    primary.addSuppressed(failure);
                }
            }
        }
        if (primary != null) {
            rethrow(primary);
        }
    }

    private static void closeChildAndProof(
            DeferredSubmissionReceipt child, X7SkinnedTexelProvenance.Attempt attempt, Throwable primary) {
        if (child != null) {
            try {
                child.close();
            } catch (Throwable cleanup) {
                if (cleanup != primary) {
                    primary.addSuppressed(cleanup);
                }
            }
        }
        X7SkinnedTexelProvenance provenance = attempt == null ? null : attempt.provenanceOrNull();
        if (provenance != null) {
            try {
                provenance.close();
            } catch (Throwable cleanup) {
                if (cleanup != primary) {
                    primary.addSuppressed(cleanup);
                }
            }
        }
    }

    private static boolean opaque(int argb) {
        return (argb >>> 24) == 0xFF;
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

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("X7 Stage-A cleanup failed", failure);
    }
}
