package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.Objects;

/** Immutable queue-owned handoff; it retains identities and its opaque child but no live callback/pass objects. */
final class X7PreAdmittedFrameRequest {
    private final long requestId;
    private final X7AfterSolidFrameIdentity targetFrame;
    private final Object exactPlanIdentity;
    private final ModelRenderSnapshot exactSnapshot;
    private final X6DrawPrimitive exactDraw;
    private final X7PolicyResourceRecord exactPolicyRecord;
    private final CompletedGenerationResourceSet.ResourceLeaf exactLeaf;
    private final X7GenerationPerformancePlan.Published exactPublished;
    private final X7DeferredSubmissionBridge.QueueChild child;

    private X7PreAdmittedFrameRequest(
            long requestId,
            X7AfterSolidFrameIdentity targetFrame,
            Object exactPlanIdentity,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw,
            X7DeferredSubmissionBridge.QueueChild child) {
        if (requestId <= 0L) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        this.requestId = requestId;
        this.targetFrame = Objects.requireNonNull(targetFrame, "targetFrame");
        this.exactPlanIdentity = Objects.requireNonNull(exactPlanIdentity, "exactPlanIdentity");
        this.exactSnapshot = Objects.requireNonNull(exactSnapshot, "exactSnapshot");
        this.exactDraw = Objects.requireNonNull(exactDraw, "exactDraw");
        this.child = Objects.requireNonNull(child, "child");
        this.exactPolicyRecord = child.policyResourceRecord();
        X7PublishedSubmissionBridge.SubmissionProof proof = child.policyProof();
        this.exactLeaf = proof.exactLeaf();
        this.exactPublished = proof.published();
        if (proof.policyRecord() != exactPolicyRecord) {
            throw new IllegalStateException("Queue request lost its exact D1 policy record identity");
        }
    }

    static X7PreAdmittedFrameRequest create(
            long requestId,
            X7AfterSolidFrameIdentity targetFrame,
            Object exactPlanIdentity,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw,
            X7DeferredSubmissionBridge.QueueChild child) {
        return new X7PreAdmittedFrameRequest(
                requestId, targetFrame, exactPlanIdentity, exactSnapshot, exactDraw, child);
    }

    long requestId() {
        return requestId;
    }

    X7AfterSolidFrameIdentity targetFrame() {
        return targetFrame;
    }

    Object exactPlanIdentity() {
        return exactPlanIdentity;
    }

    ModelRenderSnapshot exactSnapshot() {
        return exactSnapshot;
    }

    X6DrawPrimitive exactDraw() {
        return exactDraw;
    }

    X7PolicyResourceRecord exactPolicyRecord() {
        return exactPolicyRecord;
    }

    CompletedGenerationResourceSet.ResourceLeaf exactLeaf() {
        return exactLeaf;
    }

    X7GenerationPerformancePlan.Published exactPublished() {
        return exactPublished;
    }

    X7DeferredSubmissionBridge.QueueChild child() {
        return child;
    }
}
