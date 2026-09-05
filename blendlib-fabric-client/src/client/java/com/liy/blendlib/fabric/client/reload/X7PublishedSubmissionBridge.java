package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import com.liy.blendlib.fabric.client.render.X6GeometryBinding;
import java.util.Objects;

/** Resolves one submit against the actual policy instance retained by the exact D1 lifecycle record. */
final class X7PublishedSubmissionBridge {
    private X7PublishedSubmissionBridge() {
    }

    static SubmissionProof requireExactSubmission(
            X7PolicyResourceRecord policyRecord,
            ModelRegistryGeneration exactGeneration,
            ModelHandle exactHandle,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw) {
        X7PolicyResourceRecord checkedRecord = Objects.requireNonNull(policyRecord, "policyRecord");
        ModelRegistryGeneration checkedGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
        ModelHandle checkedHandle = Objects.requireNonNull(exactHandle, "exactHandle");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(exactSnapshot, "exactSnapshot");
        X6DrawPrimitive checkedDraw = Objects.requireNonNull(exactDraw, "exactDraw");
        if (checkedRecord.isFutureGpuDisabled()) {
            throw new IllegalStateException("The exact D1 policy record has fused future work to the CPU route");
        }
        if (checkedSnapshot.generation() != checkedGeneration.generationId()
                || checkedSnapshot.handle() != checkedHandle.renderHandle()) {
            throw new IllegalArgumentException("Submitted work must retain the exact D1 generation and render-handle identity");
        }
        DrawIdentity identity = DrawIdentity.from(checkedDraw);
        X7PolicyResourceRecord.ExactLeafPolicy leafPolicy = checkedRecord.findExactLeafPolicy(
                checkedGeneration,
                checkedHandle,
                checkedSnapshot.handle(),
                identity.geometryIdentity,
                checkedDraw.material(),
                identity.lodIdentity);
        X7GenerationPerformancePlan.Published published = leafPolicy.published();
        if (published.backend() != X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE) {
            throw new IllegalStateException("Only an exact published GPU candidate may mint submitted work");
        }
        published.requireCurrentSubmitBinding(
                checkedSnapshot.generation(),
                checkedHandle,
                checkedSnapshot.handle(),
                identity.geometryIdentity,
                checkedDraw.material(),
                identity.lodIdentity);
        return new SubmissionProof(checkedRecord, leafPolicy.exactLeaf(), published);
    }

    /**
     * Exact-record pre-command fuse seam for the future T3c queue adapter.
     *
     * <p>Callers must retain the actual record reached through an exact submitted child; no lookup, value
     * reconstruction, global cache, or published-plan mutation is accepted here.</p>
     */
    static boolean disableFutureGpu(X7PolicyResourceRecord exactRecord) {
        return Objects.requireNonNull(exactRecord, "exactRecord").disableFutureGpu();
    }

    record SubmissionProof(
            X7PolicyResourceRecord policyRecord,
            CompletedGenerationResourceSet.ResourceLeaf exactLeaf,
            X7GenerationPerformancePlan.Published published) {
        SubmissionProof {
            policyRecord = Objects.requireNonNull(policyRecord, "policyRecord");
            exactLeaf = Objects.requireNonNull(exactLeaf, "exactLeaf");
            published = Objects.requireNonNull(published, "published");
        }
    }

    private record DrawIdentity(Object geometryIdentity, Object lodIdentity) {
        private static DrawIdentity from(X6DrawPrimitive draw) {
            X6GeometryBinding binding = draw.binding();
            if (binding instanceof X6GeometryBinding.StaticBinding staticBinding) {
                PreparedRenderPrimitive primitive = staticBinding.primitive();
                return new DrawIdentity(primitive.geometry(), primitive);
            }
            if (binding instanceof X6GeometryBinding.SkinnedBinding skinnedBinding) {
                PreparedSkinnedRenderPrimitive primitive = skinnedBinding.primitive();
                return new DrawIdentity(primitive.geometry(), primitive);
            }
            throw new IllegalArgumentException("Submitted X6 draw has no supported exact geometry binding");
        }
    }
}
