package com.liy.blendlib.fabric.client.reload;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable read-only diagnostics value retained by the one D1-owned X7 policy owner. */
record X7PolicyMetricsView(
        long generationId,
        long completedFrameId,
        X7PreparedFrameProjection.FrameProducerStatus frameProducerStatus,
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
        long projectionFailures,
        long currentGenerationModels,
        long currentGenerationPrimitives,
        long currentGenerationBones,
        long currentGenerationVertices,
        long completedFrameRequestedAnimatedInstances,
        long completedFrameRequestedBoneAnimationWork,
        long completedFrameRequestedVertexAnimationWork,
        long completedFrameAdmittedAnimatedInstances,
        long completedFrameAdmittedBoneAnimationWork,
        long completedFrameAdmittedVertexAnimationWork) {
    static final long NO_COMPLETED_FRAME_ID = -1L;

    X7PolicyMetricsView {
        requireNonNegative(generationId, "generationId");
        if (completedFrameId != NO_COMPLETED_FRAME_ID) {
            throw new IllegalArgumentException("this CPU/LOD0 candidate has no completed render frame");
        }
        frameProducerStatus = Objects.requireNonNull(frameProducerStatus, "frameProducerStatus");
        actualLodSelectionsByLevel = immutableLevelCounts(actualLodSelectionsByLevel);
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
        requireNonNegative(currentGenerationModels, "currentGenerationModels");
        requireNonNegative(currentGenerationPrimitives, "currentGenerationPrimitives");
        requireNonNegative(currentGenerationBones, "currentGenerationBones");
        requireNonNegative(currentGenerationVertices, "currentGenerationVertices");
        requireNonNegative(completedFrameRequestedAnimatedInstances, "completedFrameRequestedAnimatedInstances");
        requireNonNegative(completedFrameRequestedBoneAnimationWork, "completedFrameRequestedBoneAnimationWork");
        requireNonNegative(completedFrameRequestedVertexAnimationWork, "completedFrameRequestedVertexAnimationWork");
        requireNonNegative(completedFrameAdmittedAnimatedInstances, "completedFrameAdmittedAnimatedInstances");
        requireNonNegative(completedFrameAdmittedBoneAnimationWork, "completedFrameAdmittedBoneAnimationWork");
        requireNonNegative(completedFrameAdmittedVertexAnimationWork, "completedFrameAdmittedVertexAnimationWork");
    }

    static X7PolicyMetricsView unavailable(
            X7PublishedGenerationProjection projection, X7PolicyMetricsCollector.LiveSnapshot counters) {
        X7PublishedGenerationProjection checkedProjection = Objects.requireNonNull(projection, "projection");
        X7PolicyMetricsCollector.LiveSnapshot checkedCounters = Objects.requireNonNull(counters, "counters");
        X7PublishedGenerationProjection.GenerationUsage usage = checkedProjection.generationUsage();
        return new X7PolicyMetricsView(
                checkedProjection.generationId(),
                NO_COMPLETED_FRAME_ID,
                X7PreparedFrameProjection.FrameProducerStatus.UNAVAILABLE_NO_VERIFIED_FRAME_BOUNDARY,
                checkedCounters.gpuCandidateSelections(),
                checkedCounters.cpuSelections(),
                checkedCounters.capabilityUnavailableFallbacks(),
                checkedCounters.prepareFailureFallbacks(),
                checkedCounters.uploadFailureFallbacks(),
                checkedCounters.budgetAccepted(),
                checkedCounters.budgetDegraded(),
                checkedCounters.budgetCpuFallback(),
                checkedCounters.budgetRejected(),
                checkedCounters.actualLodSelectionsByLevel(),
                checkedCounters.actualLodHandleSwitches(),
                checkedCounters.lodRejections(),
                checkedCounters.instanceDraws(),
                checkedCounters.instanceCulls(),
                checkedCounters.primitiveDraws(),
                checkedCounters.primitiveCulls(),
                checkedCounters.boneDraws(),
                checkedCounters.boneCulls(),
                checkedCounters.animationFullExecuted(),
                checkedCounters.animationReducedExecuted(),
                checkedCounters.animationReusePoseExecuted(),
                checkedCounters.animationPausedExecuted(),
                checkedCounters.reducedCadenceAdvanceSkips(),
                checkedCounters.poseReuseHits(),
                checkedCounters.capturedSkinReuseHits(),
                checkedCounters.submittedPrimitiveCount(),
                checkedCounters.skippedPrimitiveCount(),
                checkedCounters.skippedBoneLayerCount(),
                checkedCounters.visibleMissingFallbacks(),
                checkedCounters.projectionFailures(),
                usage.models(),
                usage.primitives(),
                usage.bones(),
                usage.vertices(),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L);
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

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
