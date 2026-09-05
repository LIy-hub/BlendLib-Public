package com.liy.blendlib.fabric.client.internal.x7;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, completed-frame-only X7 policy observation for read-only diagnostics consumers.
 *
 * <p>Construction remains inside this internal package.  The value deliberately contains no
 * resource, owner, collector, capability, handle, or mutable ledger reference.</p>
 */
public final class X7PolicyMetricsView {
    /** Actual backend route observed by the completed frame. */
    public enum BackendRoute {
        CPU,
        GPU
    }

    /** Actual fallback reason observed by the completed frame. */
    public enum FallbackReason {
        NONE,
        CAPABILITY_UNAVAILABLE,
        PREPARE_FAILED,
        UPLOAD_FAILED
    }

    /** Immutable generation-wide resource usage observed with a completed frame. */
    public record GenerationUsage(
            long models,
            long primitives,
            long bones,
            long vertices,
            long gpuResourceCount,
            long gpuVertexBytes,
            long gpuIndexBytes) {
        public GenerationUsage {
            requireNonNegative(models, "models");
            requireNonNegative(primitives, "primitives");
            requireNonNegative(bones, "bones");
            requireNonNegative(vertices, "vertices");
            requireNonNegative(gpuResourceCount, "gpuResourceCount");
            requireNonNegative(gpuVertexBytes, "gpuVertexBytes");
            requireNonNegative(gpuIndexBytes, "gpuIndexBytes");
        }
    }

    /** Immutable work totals belonging exactly to {@link #completedFrameId()}. */
    public record CompletedFrameWork(
            long requestedAnimatedInstances,
            long admittedAnimatedInstances,
            long requestedBoneWork,
            long admittedBoneWork,
            long requestedVertexWork,
            long admittedVertexWork) {
        public CompletedFrameWork {
            requireNonNegative(requestedAnimatedInstances, "requestedAnimatedInstances");
            requireNonNegative(admittedAnimatedInstances, "admittedAnimatedInstances");
            requireNonNegative(requestedBoneWork, "requestedBoneWork");
            requireNonNegative(admittedBoneWork, "admittedBoneWork");
            requireNonNegative(requestedVertexWork, "requestedVertexWork");
            requireNonNegative(admittedVertexWork, "admittedVertexWork");
        }
    }

    private final long generationId;
    private final long completedFrameId;
    private final BackendRoute selectedBackend;
    private final FallbackReason activeFallbackReason;
    private final boolean backendSelectionVerified;
    private final long gpuCandidateSelections;
    private final long cpuSelections;
    private final long capabilityUnavailableFallbacks;
    private final long prepareFailureFallbacks;
    private final long uploadFailureFallbacks;
    private final long budgetAccepted;
    private final long budgetDegraded;
    private final long budgetCpuFallback;
    private final long budgetRejected;
    private final List<Long> actualLodSelectionsByLevel;
    private final long actualLodHandleSwitches;
    private final long lodRejections;
    private final long instanceDraws;
    private final long instanceCulls;
    private final long primitiveDraws;
    private final long primitiveCulls;
    private final long boneDraws;
    private final long boneCulls;
    private final long submittedBatchCount;
    private final long submittedBatchInstanceCount;
    private final long submittedPrimitiveCount;
    private final long skippedPrimitiveCount;
    private final long skippedBoneLayerCount;
    private final long animationFullExecuted;
    private final long animationReducedExecuted;
    private final long animationReusePoseExecuted;
    private final long animationPausedExecuted;
    private final long reducedCadenceAdvanceSkips;
    private final long poseReuseHits;
    private final long capturedSkinReuseHits;
    private final long visibleMissingFallbacks;
    private final long projectionFailures;
    private final GenerationUsage currentGenerationUsage;
    private final CompletedFrameWork completedFrameWork;

    private X7PolicyMetricsView(
            long generationId,
            long completedFrameId,
            BackendRoute selectedBackend,
            FallbackReason activeFallbackReason,
            boolean backendSelectionVerified,
            long gpuCandidateSelections,
            long cpuSelections,
            long capabilityUnavailableFallbacks,
            long prepareFailureFallbacks,
            long uploadFailureFallbacks,
            long budgetAccepted,
            long budgetDegraded,
            long budgetCpuFallback,
            long budgetRejected,
            List<Long> actualLodSelectionsByLevel,
            long actualLodHandleSwitches,
            long lodRejections,
            long instanceDraws,
            long instanceCulls,
            long primitiveDraws,
            long primitiveCulls,
            long boneDraws,
            long boneCulls,
            long submittedBatchCount,
            long submittedBatchInstanceCount,
            long submittedPrimitiveCount,
            long skippedPrimitiveCount,
            long skippedBoneLayerCount,
            long animationFullExecuted,
            long animationReducedExecuted,
            long animationReusePoseExecuted,
            long animationPausedExecuted,
            long reducedCadenceAdvanceSkips,
            long poseReuseHits,
            long capturedSkinReuseHits,
            long visibleMissingFallbacks,
            long projectionFailures,
            GenerationUsage currentGenerationUsage,
            CompletedFrameWork completedFrameWork) {
        this.generationId = requireNonNegative(generationId, "generationId");
        this.completedFrameId = requireNonNegative(completedFrameId, "completedFrameId");
        this.selectedBackend = Objects.requireNonNull(selectedBackend, "selectedBackend");
        this.activeFallbackReason = Objects.requireNonNull(activeFallbackReason, "activeFallbackReason");
        this.backendSelectionVerified = backendSelectionVerified;
        this.gpuCandidateSelections = requireNonNegative(gpuCandidateSelections, "gpuCandidateSelections");
        this.cpuSelections = requireNonNegative(cpuSelections, "cpuSelections");
        this.capabilityUnavailableFallbacks = requireNonNegative(
                capabilityUnavailableFallbacks, "capabilityUnavailableFallbacks");
        this.prepareFailureFallbacks = requireNonNegative(prepareFailureFallbacks, "prepareFailureFallbacks");
        this.uploadFailureFallbacks = requireNonNegative(uploadFailureFallbacks, "uploadFailureFallbacks");
        this.budgetAccepted = requireNonNegative(budgetAccepted, "budgetAccepted");
        this.budgetDegraded = requireNonNegative(budgetDegraded, "budgetDegraded");
        this.budgetCpuFallback = requireNonNegative(budgetCpuFallback, "budgetCpuFallback");
        this.budgetRejected = requireNonNegative(budgetRejected, "budgetRejected");
        this.actualLodSelectionsByLevel = immutableLodSelections(actualLodSelectionsByLevel);
        this.actualLodHandleSwitches = requireNonNegative(actualLodHandleSwitches, "actualLodHandleSwitches");
        this.lodRejections = requireNonNegative(lodRejections, "lodRejections");
        this.instanceDraws = requireNonNegative(instanceDraws, "instanceDraws");
        this.instanceCulls = requireNonNegative(instanceCulls, "instanceCulls");
        this.primitiveDraws = requireNonNegative(primitiveDraws, "primitiveDraws");
        this.primitiveCulls = requireNonNegative(primitiveCulls, "primitiveCulls");
        this.boneDraws = requireNonNegative(boneDraws, "boneDraws");
        this.boneCulls = requireNonNegative(boneCulls, "boneCulls");
        this.submittedBatchCount = requireNonNegative(submittedBatchCount, "submittedBatchCount");
        this.submittedBatchInstanceCount = requireNonNegative(
                submittedBatchInstanceCount, "submittedBatchInstanceCount");
        this.submittedPrimitiveCount = requireNonNegative(submittedPrimitiveCount, "submittedPrimitiveCount");
        this.skippedPrimitiveCount = requireNonNegative(skippedPrimitiveCount, "skippedPrimitiveCount");
        this.skippedBoneLayerCount = requireNonNegative(skippedBoneLayerCount, "skippedBoneLayerCount");
        this.animationFullExecuted = requireNonNegative(animationFullExecuted, "animationFullExecuted");
        this.animationReducedExecuted = requireNonNegative(animationReducedExecuted, "animationReducedExecuted");
        this.animationReusePoseExecuted = requireNonNegative(
                animationReusePoseExecuted, "animationReusePoseExecuted");
        this.animationPausedExecuted = requireNonNegative(animationPausedExecuted, "animationPausedExecuted");
        this.reducedCadenceAdvanceSkips = requireNonNegative(
                reducedCadenceAdvanceSkips, "reducedCadenceAdvanceSkips");
        this.poseReuseHits = requireNonNegative(poseReuseHits, "poseReuseHits");
        this.capturedSkinReuseHits = requireNonNegative(capturedSkinReuseHits, "capturedSkinReuseHits");
        this.visibleMissingFallbacks = requireNonNegative(visibleMissingFallbacks, "visibleMissingFallbacks");
        this.projectionFailures = requireNonNegative(projectionFailures, "projectionFailures");
        this.currentGenerationUsage = Objects.requireNonNull(currentGenerationUsage, "currentGenerationUsage");
        this.completedFrameWork = Objects.requireNonNull(completedFrameWork, "completedFrameWork");
    }

    static X7PolicyMetricsView completed(
            long generationId,
            long completedFrameId,
            BackendRoute selectedBackend,
            FallbackReason activeFallbackReason,
            boolean backendSelectionVerified,
            long gpuCandidateSelections,
            long cpuSelections,
            long capabilityUnavailableFallbacks,
            long prepareFailureFallbacks,
            long uploadFailureFallbacks,
            long budgetAccepted,
            long budgetDegraded,
            long budgetCpuFallback,
            long budgetRejected,
            List<Long> actualLodSelectionsByLevel,
            long actualLodHandleSwitches,
            long lodRejections,
            long instanceDraws,
            long instanceCulls,
            long primitiveDraws,
            long primitiveCulls,
            long boneDraws,
            long boneCulls,
            long submittedBatchCount,
            long submittedBatchInstanceCount,
            long submittedPrimitiveCount,
            long skippedPrimitiveCount,
            long skippedBoneLayerCount,
            long animationFullExecuted,
            long animationReducedExecuted,
            long animationReusePoseExecuted,
            long animationPausedExecuted,
            long reducedCadenceAdvanceSkips,
            long poseReuseHits,
            long capturedSkinReuseHits,
            long visibleMissingFallbacks,
            long projectionFailures,
            GenerationUsage currentGenerationUsage,
            CompletedFrameWork completedFrameWork) {
        return new X7PolicyMetricsView(
                generationId,
                completedFrameId,
                selectedBackend,
                activeFallbackReason,
                backendSelectionVerified,
                gpuCandidateSelections,
                cpuSelections,
                capabilityUnavailableFallbacks,
                prepareFailureFallbacks,
                uploadFailureFallbacks,
                budgetAccepted,
                budgetDegraded,
                budgetCpuFallback,
                budgetRejected,
                actualLodSelectionsByLevel,
                actualLodHandleSwitches,
                lodRejections,
                instanceDraws,
                instanceCulls,
                primitiveDraws,
                primitiveCulls,
                boneDraws,
                boneCulls,
                submittedBatchCount,
                submittedBatchInstanceCount,
                submittedPrimitiveCount,
                skippedPrimitiveCount,
                skippedBoneLayerCount,
                animationFullExecuted,
                animationReducedExecuted,
                animationReusePoseExecuted,
                animationPausedExecuted,
                reducedCadenceAdvanceSkips,
                poseReuseHits,
                capturedSkinReuseHits,
                visibleMissingFallbacks,
                projectionFailures,
                currentGenerationUsage,
                completedFrameWork);
    }

    public long generationId() {
        return generationId;
    }

    public long completedFrameId() {
        return completedFrameId;
    }

    public BackendRoute selectedBackend() {
        return selectedBackend;
    }

    public FallbackReason activeFallbackReason() {
        return activeFallbackReason;
    }

    public boolean backendSelectionVerified() {
        return backendSelectionVerified;
    }

    public long gpuCandidateSelections() {
        return gpuCandidateSelections;
    }

    public long cpuSelections() {
        return cpuSelections;
    }

    public long capabilityUnavailableFallbacks() {
        return capabilityUnavailableFallbacks;
    }

    public long prepareFailureFallbacks() {
        return prepareFailureFallbacks;
    }

    public long uploadFailureFallbacks() {
        return uploadFailureFallbacks;
    }

    public long budgetAccepted() {
        return budgetAccepted;
    }

    public long budgetDegraded() {
        return budgetDegraded;
    }

    public long budgetCpuFallback() {
        return budgetCpuFallback;
    }

    public long budgetRejected() {
        return budgetRejected;
    }

    public List<Long> actualLodSelectionsByLevel() {
        return actualLodSelectionsByLevel;
    }

    public long actualLodHandleSwitches() {
        return actualLodHandleSwitches;
    }

    public long lodRejections() {
        return lodRejections;
    }

    public long instanceDraws() {
        return instanceDraws;
    }

    public long instanceCulls() {
        return instanceCulls;
    }

    public long primitiveDraws() {
        return primitiveDraws;
    }

    public long primitiveCulls() {
        return primitiveCulls;
    }

    public long boneDraws() {
        return boneDraws;
    }

    public long boneCulls() {
        return boneCulls;
    }

    public long submittedBatchCount() {
        return submittedBatchCount;
    }

    public long submittedBatchInstanceCount() {
        return submittedBatchInstanceCount;
    }

    public long submittedPrimitiveCount() {
        return submittedPrimitiveCount;
    }

    public long skippedPrimitiveCount() {
        return skippedPrimitiveCount;
    }

    public long skippedBoneLayerCount() {
        return skippedBoneLayerCount;
    }

    public long animationFullExecuted() {
        return animationFullExecuted;
    }

    public long animationReducedExecuted() {
        return animationReducedExecuted;
    }

    public long animationReusePoseExecuted() {
        return animationReusePoseExecuted;
    }

    public long animationPausedExecuted() {
        return animationPausedExecuted;
    }

    public long reducedCadenceAdvanceSkips() {
        return reducedCadenceAdvanceSkips;
    }

    public long poseReuseHits() {
        return poseReuseHits;
    }

    public long capturedSkinReuseHits() {
        return capturedSkinReuseHits;
    }

    public long visibleMissingFallbacks() {
        return visibleMissingFallbacks;
    }

    public long projectionFailures() {
        return projectionFailures;
    }

    public GenerationUsage currentGenerationUsage() {
        return currentGenerationUsage;
    }

    public CompletedFrameWork completedFrameWork() {
        return completedFrameWork;
    }

    private static List<Long> immutableLodSelections(List<Long> source) {
        List<Long> copied = List.copyOf(Objects.requireNonNull(source, "actualLodSelectionsByLevel"));
        if (copied.size() != 8) {
            throw new IllegalArgumentException("actualLodSelectionsByLevel must contain exactly eight levels");
        }
        for (Long count : copied) {
            requireNonNegative(Objects.requireNonNull(count, "LOD selection count"), "LOD selection count");
        }
        return copied;
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }
}
