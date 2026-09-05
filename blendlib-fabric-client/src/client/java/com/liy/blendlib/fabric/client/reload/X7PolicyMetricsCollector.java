package com.liy.blendlib.fabric.client.reload;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Mutable single-owner collector with immutable count-only snapshots. */
final class X7PolicyMetricsCollector {
    private long gpuCandidateSelections;
    private long cpuSelections;
    private long capabilityUnavailableFallbacks;
    private long prepareFailureFallbacks;
    private long uploadFailureFallbacks;
    private long acceptedBudgetDecisions;
    private long degradedBudgetDecisions;
    private long cpuFallbackBudgetDecisions;
    private long rejectedBudgetDecisions;
    private long lodSelections;
    private long lodRejections;
    private long drawDecisions;
    private long cullDecisions;
    private long fullWorkRecommendations;
    private long reducedWorkRecommendations;
    private long pausedWorkRecommendations;
    private long reusedPoseRecommendations;
    private long budgetAccepted;
    private long budgetDegraded;
    private long budgetCpuFallback;
    private long budgetRejected;
    private final Map<Integer, Long> actualLodSelectionsByLevel = new TreeMap<>();
    private long actualLodHandleSwitches;
    private long actualLodRejections;
    private long instanceDraws;
    private long instanceCulls;
    private long primitiveDraws;
    private long primitiveCulls;
    private long boneDraws;
    private long boneCulls;
    private long animationFullExecuted;
    private long animationReducedExecuted;
    private long animationReusePoseExecuted;
    private long animationPausedExecuted;
    private long reducedCadenceAdvanceSkips;
    private long poseReuseHits;
    private long capturedSkinReuseHits;
    private long submittedPrimitiveCount;
    private long skippedPrimitiveCount;
    private long skippedBoneLayerCount;
    private long visibleMissingFallbacks;
    private long projectionFailures;

    /** Immutable count-only diagnostics snapshot for the private X7 foundation. */
    record Snapshot(
            long gpuCandidateSelections,
            long cpuSelections,
            long capabilityUnavailableFallbacks,
            long prepareFailureFallbacks,
            long uploadFailureFallbacks,
            long acceptedBudgetDecisions,
            long degradedBudgetDecisions,
            long cpuFallbackBudgetDecisions,
            long rejectedBudgetDecisions,
            long lodSelections,
            long lodRejections,
            long drawDecisions,
            long cullDecisions,
            long fullWorkRecommendations,
            long reducedWorkRecommendations,
            long pausedWorkRecommendations,
            long reusedPoseRecommendations) {
        Snapshot {
            requireNonNegative(gpuCandidateSelections, "gpuCandidateSelections");
            requireNonNegative(cpuSelections, "cpuSelections");
            requireNonNegative(capabilityUnavailableFallbacks, "capabilityUnavailableFallbacks");
            requireNonNegative(prepareFailureFallbacks, "prepareFailureFallbacks");
            requireNonNegative(uploadFailureFallbacks, "uploadFailureFallbacks");
            requireNonNegative(acceptedBudgetDecisions, "acceptedBudgetDecisions");
            requireNonNegative(degradedBudgetDecisions, "degradedBudgetDecisions");
            requireNonNegative(cpuFallbackBudgetDecisions, "cpuFallbackBudgetDecisions");
            requireNonNegative(rejectedBudgetDecisions, "rejectedBudgetDecisions");
            requireNonNegative(lodSelections, "lodSelections");
            requireNonNegative(lodRejections, "lodRejections");
            requireNonNegative(drawDecisions, "drawDecisions");
            requireNonNegative(cullDecisions, "cullDecisions");
            requireNonNegative(fullWorkRecommendations, "fullWorkRecommendations");
            requireNonNegative(reducedWorkRecommendations, "reducedWorkRecommendations");
            requireNonNegative(pausedWorkRecommendations, "pausedWorkRecommendations");
            requireNonNegative(reusedPoseRecommendations, "reusedPoseRecommendations");
        }
    }

    /**
     * Exact live-policy counters.  Unlike the historical foundation snapshot, these fields retain
     * their culling scope and distinguish an installed action from a recommendation.
     */
    record LiveSnapshot(
            long gpuCandidateSelections,
            long cpuSelections,
            long capabilityUnavailableFallbacks,
            long prepareFailureFallbacks,
            long uploadFailureFallbacks,
            long budgetAccepted,
            long budgetDegraded,
            long budgetCpuFallback,
            long budgetRejected,
            Map<Integer, Long> actualLodSelectionsByLevel,
            long actualLodHandleSwitches,
            long lodRejections,
            long instanceDraws,
            long instanceCulls,
            long primitiveDraws,
            long primitiveCulls,
            long boneDraws,
            long boneCulls,
            long animationFullExecuted,
            long animationReducedExecuted,
            long animationReusePoseExecuted,
            long animationPausedExecuted,
            long reducedCadenceAdvanceSkips,
            long poseReuseHits,
            long capturedSkinReuseHits,
            long submittedPrimitiveCount,
            long skippedPrimitiveCount,
            long skippedBoneLayerCount,
            long visibleMissingFallbacks,
            long projectionFailures) {
        LiveSnapshot {
            requireNonNegative(gpuCandidateSelections, "gpuCandidateSelections");
            requireNonNegative(cpuSelections, "cpuSelections");
            requireNonNegative(capabilityUnavailableFallbacks, "capabilityUnavailableFallbacks");
            requireNonNegative(prepareFailureFallbacks, "prepareFailureFallbacks");
            requireNonNegative(uploadFailureFallbacks, "uploadFailureFallbacks");
            requireNonNegative(budgetAccepted, "budgetAccepted");
            requireNonNegative(budgetDegraded, "budgetDegraded");
            requireNonNegative(budgetCpuFallback, "budgetCpuFallback");
            requireNonNegative(budgetRejected, "budgetRejected");
            requireNonNegative(actualLodHandleSwitches, "actualLodHandleSwitches");
            requireNonNegative(lodRejections, "lodRejections");
            requireNonNegative(instanceDraws, "instanceDraws");
            requireNonNegative(instanceCulls, "instanceCulls");
            requireNonNegative(primitiveDraws, "primitiveDraws");
            requireNonNegative(primitiveCulls, "primitiveCulls");
            requireNonNegative(boneDraws, "boneDraws");
            requireNonNegative(boneCulls, "boneCulls");
            requireNonNegative(animationFullExecuted, "animationFullExecuted");
            requireNonNegative(animationReducedExecuted, "animationReducedExecuted");
            requireNonNegative(animationReusePoseExecuted, "animationReusePoseExecuted");
            requireNonNegative(animationPausedExecuted, "animationPausedExecuted");
            requireNonNegative(reducedCadenceAdvanceSkips, "reducedCadenceAdvanceSkips");
            requireNonNegative(poseReuseHits, "poseReuseHits");
            requireNonNegative(capturedSkinReuseHits, "capturedSkinReuseHits");
            requireNonNegative(submittedPrimitiveCount, "submittedPrimitiveCount");
            requireNonNegative(skippedPrimitiveCount, "skippedPrimitiveCount");
            requireNonNegative(skippedBoneLayerCount, "skippedBoneLayerCount");
            requireNonNegative(visibleMissingFallbacks, "visibleMissingFallbacks");
            requireNonNegative(projectionFailures, "projectionFailures");
            actualLodSelectionsByLevel = immutableLevelCounts(actualLodSelectionsByLevel);
        }
    }

    void recordBackend(X7GenerationPerformancePlan.Published plan) {
        X7GenerationPerformancePlan.Published checkedPlan = Objects.requireNonNull(plan, "plan");
        recordBackend(checkedPlan.backend(), checkedPlan.fallbackReason());
    }

    /** Records an already-frozen backend action without exposing a construction path for a plan. */
    void recordBackendChoice(
            X7GenerationPerformancePlan.BackendChoice backend,
            X7GenerationPerformancePlan.FallbackReason fallbackReason) {
        recordBackend(
                Objects.requireNonNull(backend, "backend"),
                Objects.requireNonNull(fallbackReason, "fallbackReason"));
    }

    /** Records the D2b CPU-subset route without coercing it into the full X6 plan identity. */
    void recordBackend(X7GenerationPerformancePlan.CpuSubsetPublished plan) {
        X7GenerationPerformancePlan.CpuSubsetPublished checkedPlan = Objects.requireNonNull(plan, "plan");
        recordBackend(checkedPlan.backend(), checkedPlan.fallbackReason());
    }

    private void recordBackend(
            X7GenerationPerformancePlan.BackendChoice backend,
            X7GenerationPerformancePlan.FallbackReason fallbackReason) {
        if (backend == X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE) {
            gpuCandidateSelections = increment(gpuCandidateSelections);
            return;
        }
        cpuSelections = increment(cpuSelections);
        switch (fallbackReason) {
            case CAPABILITY_UNAVAILABLE -> capabilityUnavailableFallbacks = increment(capabilityUnavailableFallbacks);
            case PREPARE_FAILED -> prepareFailureFallbacks = increment(prepareFailureFallbacks);
            case UPLOAD_FAILED -> uploadFailureFallbacks = increment(uploadFailureFallbacks);
            case NONE -> throw new IllegalArgumentException("CPU selection requires a fallback reason");
        }
    }

    void recordBudget(X7BudgetPolicy.Result result) {
        switch (Objects.requireNonNull(result, "result").disposition()) {
            case ACCEPT -> acceptedBudgetDecisions = increment(acceptedBudgetDecisions);
            case DEGRADE -> degradedBudgetDecisions = increment(degradedBudgetDecisions);
            case CPU_FALLBACK -> cpuFallbackBudgetDecisions = increment(cpuFallbackBudgetDecisions);
            case REJECT -> rejectedBudgetDecisions = increment(rejectedBudgetDecisions);
        }
    }

    void recordLod(X7LodPolicy.Selection selection) {
        if (Objects.requireNonNull(selection, "selection").disposition() == X7LodPolicy.Disposition.SELECTED) {
            lodSelections = increment(lodSelections);
        } else {
            lodRejections = increment(lodRejections);
        }
    }

    void recordCulling(
            X7CullingPolicy.InstanceDecision result, long activeGeneration, Object activeHandleIdentity) {
        X7CullingPolicy.CurrentInstanceDecision current = Objects.requireNonNull(result, "result")
                .readCurrent(activeGeneration, activeHandleIdentity);
        recordCullingDecision(current.decision());
    }

    void recordCulling(X7CullingPolicy.PrimitiveDecision result) {
        recordCullingDecision(Objects.requireNonNull(result, "result").decision());
    }

    void recordCulling(X7CullingPolicy.BoneDecision result) {
        recordCullingDecision(Objects.requireNonNull(result, "result").decision());
    }

    void recordAnimationWork(X7AnimationWorkPolicy.Mode mode) {
        switch (Objects.requireNonNull(mode, "mode")) {
            case FULL -> fullWorkRecommendations = increment(fullWorkRecommendations);
            case REDUCED -> reducedWorkRecommendations = increment(reducedWorkRecommendations);
            case PAUSED -> pausedWorkRecommendations = increment(pausedWorkRecommendations);
            case REUSE_POSE -> reusedPoseRecommendations = increment(reusedPoseRecommendations);
        }
    }

    /** Records a real selected LOD after its exact handle has been frozen into a projection. */
    void recordActualLodSelection(int level, boolean handleChanged) {
        if (level < 0) {
            throw new IllegalArgumentException("LOD level must be non-negative");
        }
        actualLodSelectionsByLevel.merge(level, 1L, Math::addExact);
        if (handleChanged) {
            actualLodHandleSwitches = increment(actualLodHandleSwitches);
        }
    }

    /** Records a real rejected LOD admission after the owner has selected the LOD0 fallback. */
    void recordActualLodRejection() {
        actualLodRejections = increment(actualLodRejections);
    }

    /** Records a budget disposition only after it has installed the corresponding generation/frame action. */
    void recordInstalledBudget(X7BudgetPolicy.Result result) {
        switch (Objects.requireNonNull(result, "result").disposition()) {
            case ACCEPT -> budgetAccepted = increment(budgetAccepted);
            case DEGRADE -> budgetDegraded = increment(budgetDegraded);
            case CPU_FALLBACK -> budgetCpuFallback = increment(budgetCpuFallback);
            case REJECT -> budgetRejected = increment(budgetRejected);
        }
    }

    void recordInstalledInstanceDecision(X7CullingPolicy.CurrentInstanceDecision decision) {
        switch (Objects.requireNonNull(decision, "decision").decision()) {
            case DRAW -> instanceDraws = increment(instanceDraws);
            case CULL -> instanceCulls = increment(instanceCulls);
        }
    }

    void recordInstalledPrimitiveDecision(X7CullingPolicy.Decision decision) {
        switch (Objects.requireNonNull(decision, "decision")) {
            case DRAW -> primitiveDraws = increment(primitiveDraws);
            case CULL -> primitiveCulls = increment(primitiveCulls);
        }
    }

    void recordInstalledBoneDecision(X7CullingPolicy.Decision decision) {
        switch (Objects.requireNonNull(decision, "decision")) {
            case DRAW -> boneDraws = increment(boneDraws);
            case CULL -> boneCulls = increment(boneCulls);
        }
    }

    /** Records an executed animation mode, deliberately separate from the foundation recommendation counter. */
    void recordExecutedAnimationWork(X7AnimationWorkPolicy.Mode mode) {
        switch (Objects.requireNonNull(mode, "mode")) {
            case FULL -> animationFullExecuted = increment(animationFullExecuted);
            case REDUCED -> animationReducedExecuted = increment(animationReducedExecuted);
            case PAUSED -> animationPausedExecuted = increment(animationPausedExecuted);
            case REUSE_POSE -> animationReusePoseExecuted = increment(animationReusePoseExecuted);
        }
    }

    void recordReducedCadenceAdvanceSkip() {
        reducedCadenceAdvanceSkips = increment(reducedCadenceAdvanceSkips);
    }

    void recordPoseReuseHit() {
        poseReuseHits = increment(poseReuseHits);
    }

    void recordCapturedSkinReuseHit() {
        capturedSkinReuseHits = increment(capturedSkinReuseHits);
    }

    void recordSubmittedPrimitive() {
        submittedPrimitiveCount = increment(submittedPrimitiveCount);
    }

    void recordSkippedPrimitive() {
        skippedPrimitiveCount = increment(skippedPrimitiveCount);
    }

    void recordSkippedBoneLayer() {
        skippedBoneLayerCount = increment(skippedBoneLayerCount);
    }

    void recordVisibleMissingFallback() {
        visibleMissingFallbacks = increment(visibleMissingFallbacks);
    }

    void recordProjectionFailure() {
        projectionFailures = increment(projectionFailures);
    }

    Snapshot snapshot() {
        return new Snapshot(
                gpuCandidateSelections,
                cpuSelections,
                capabilityUnavailableFallbacks,
                prepareFailureFallbacks,
                uploadFailureFallbacks,
                acceptedBudgetDecisions,
                degradedBudgetDecisions,
                cpuFallbackBudgetDecisions,
                rejectedBudgetDecisions,
                lodSelections,
                lodRejections,
                drawDecisions,
                cullDecisions,
                fullWorkRecommendations,
                reducedWorkRecommendations,
                pausedWorkRecommendations,
                reusedPoseRecommendations);
    }

    LiveSnapshot liveSnapshot() {
        return new LiveSnapshot(
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
                actualLodRejections,
                instanceDraws,
                instanceCulls,
                primitiveDraws,
                primitiveCulls,
                boneDraws,
                boneCulls,
                animationFullExecuted,
                animationReducedExecuted,
                animationReusePoseExecuted,
                animationPausedExecuted,
                reducedCadenceAdvanceSkips,
                poseReuseHits,
                capturedSkinReuseHits,
                submittedPrimitiveCount,
                skippedPrimitiveCount,
                skippedBoneLayerCount,
                visibleMissingFallbacks,
                projectionFailures);
    }

    void reset() {
        gpuCandidateSelections = 0L;
        cpuSelections = 0L;
        capabilityUnavailableFallbacks = 0L;
        prepareFailureFallbacks = 0L;
        uploadFailureFallbacks = 0L;
        acceptedBudgetDecisions = 0L;
        degradedBudgetDecisions = 0L;
        cpuFallbackBudgetDecisions = 0L;
        rejectedBudgetDecisions = 0L;
        lodSelections = 0L;
        lodRejections = 0L;
        drawDecisions = 0L;
        cullDecisions = 0L;
        fullWorkRecommendations = 0L;
        reducedWorkRecommendations = 0L;
        pausedWorkRecommendations = 0L;
        reusedPoseRecommendations = 0L;
        budgetAccepted = 0L;
        budgetDegraded = 0L;
        budgetCpuFallback = 0L;
        budgetRejected = 0L;
        actualLodSelectionsByLevel.clear();
        actualLodHandleSwitches = 0L;
        actualLodRejections = 0L;
        instanceDraws = 0L;
        instanceCulls = 0L;
        primitiveDraws = 0L;
        primitiveCulls = 0L;
        boneDraws = 0L;
        boneCulls = 0L;
        animationFullExecuted = 0L;
        animationReducedExecuted = 0L;
        animationReusePoseExecuted = 0L;
        animationPausedExecuted = 0L;
        reducedCadenceAdvanceSkips = 0L;
        poseReuseHits = 0L;
        capturedSkinReuseHits = 0L;
        submittedPrimitiveCount = 0L;
        skippedPrimitiveCount = 0L;
        skippedBoneLayerCount = 0L;
        visibleMissingFallbacks = 0L;
        projectionFailures = 0L;
    }

    private static long increment(long value) {
        return Math.incrementExact(value);
    }

    private void recordCullingDecision(X7CullingPolicy.Decision decision) {
        if (decision == X7CullingPolicy.Decision.DRAW) {
            drawDecisions = increment(drawDecisions);
        } else {
            cullDecisions = increment(cullDecisions);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static Map<Integer, Long> immutableLevelCounts(Map<Integer, Long> source) {
        Objects.requireNonNull(source, "actualLodSelectionsByLevel");
        Map<Integer, Long> ordered = new LinkedHashMap<>();
        new TreeMap<>(source).forEach((level, count) -> {
            if (level == null || level < 0) {
                throw new IllegalArgumentException("LOD level must be non-negative");
            }
            requireNonNegative(Objects.requireNonNull(count, "LOD selection count"), "LOD selection count");
            ordered.put(level, count);
        });
        return Collections.unmodifiableMap(ordered);
    }
}
