package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class X7AnimationWorkAndMetricsTest {
    private static final X7BudgetPolicy.Budget METRICS_BUDGET = new X7BudgetPolicy.Budget(
            10L, 10L, 10L, 10L, 10L, 100L, 100L);

    @Test
    void animationWorkUsesOnlyTheTypedInstanceDecisionAndStaysConservative() {
        assertEquals(X7AnimationWorkPolicy.Mode.FULL, select(
                X7CullingPolicy.Visibility.VISIBLE,
                true,
                X7AnimationWorkPolicy.NEAR_DISTANCE_SQUARED,
                false,
                false,
                false));
        assertEquals(X7AnimationWorkPolicy.Mode.REDUCED, select(
                X7CullingPolicy.Visibility.VISIBLE,
                true,
                X7AnimationWorkPolicy.NEAR_DISTANCE_SQUARED + 1.0D,
                false,
                false,
                false));
        assertEquals(X7AnimationWorkPolicy.Mode.REUSE_POSE, select(
                X7CullingPolicy.Visibility.HIDDEN, true, 1.0D, true, true, false));
        assertEquals(X7AnimationWorkPolicy.Mode.PAUSED, select(
                X7CullingPolicy.Visibility.HIDDEN, true, 1.0D, false, true, false));
        assertEquals(X7AnimationWorkPolicy.Mode.REDUCED, select(
                X7CullingPolicy.Visibility.HIDDEN, true, 1.0D, false, false, false));
        assertEquals(X7AnimationWorkPolicy.Mode.FULL, select(
                X7CullingPolicy.Visibility.UNKNOWN, true, 1.0D, true, true, false));
        assertEquals(X7AnimationWorkPolicy.Mode.FULL, select(
                X7CullingPolicy.Visibility.HIDDEN, false, 1.0D, true, true, false));
        assertEquals(X7AnimationWorkPolicy.Mode.FULL, select(
                X7CullingPolicy.Visibility.HIDDEN, true, Double.NaN, true, true, false));
        assertEquals(X7AnimationWorkPolicy.Mode.FULL, select(
                X7CullingPolicy.Visibility.HIDDEN, true, 1.0D, true, true, true));
    }

    @Test
    void animationWorkRejectsStaleGenerationBeforeItCanThrottlePauseOrReuseAPose() {
        Object handle = new Object();
        X7AnimationWorkPolicy.Input staleInput = input(
                X7CullingPolicy.decideInstance(
                        new X7CullingPolicy.InstanceTarget(1L, handle),
                        new X7CullingPolicy.Evidence(X7CullingPolicy.Visibility.HIDDEN, true)),
                1.0D,
                true,
                true,
                false);

        assertThrows(IllegalStateException.class, () -> X7AnimationWorkPolicy.select(staleInput, 2L, handle));
    }

    @Test
    void animationWorkRejectsCrossHandleBeforeItCanThrottlePauseOrReuseAPose() {
        Object handle = new Object();
        X7AnimationWorkPolicy.Input crossHandleInput = input(
                X7CullingPolicy.decideInstance(
                        new X7CullingPolicy.InstanceTarget(1L, handle),
                        new X7CullingPolicy.Evidence(X7CullingPolicy.Visibility.HIDDEN, true)),
                1.0D,
                true,
                true,
                false);

        assertThrows(IllegalStateException.class,
                () -> X7AnimationWorkPolicy.select(crossHandleInput, 1L, new Object()));
    }

    @Test
    void mutableCollectorExportsIndependentSnapshotsAndResetsEveryCount() {
        X7PolicyMetricsCollector collector = new X7PolicyMetricsCollector();
        collector.recordBackend(published(X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()));
        collector.recordBackend(published(new X7GenerationPerformancePlan.CapabilitySnapshot(false, true, true)));
        collector.recordBackend(published(new X7GenerationPerformancePlan.CapabilitySnapshot(true, false, true)));
        collector.recordBackend(published(new X7GenerationPerformancePlan.CapabilitySnapshot(true, true, false)));
        collector.recordBudget(budgetResult(request(1L), X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()));
        collector.recordBudget(budgetResult(request(6L), X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()));
        collector.recordBudget(budgetResult(request(1L), X7GenerationPerformancePlan.CapabilitySnapshot.cpuOnly()));
        collector.recordBudget(budgetResult(request(11L), X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()));
        X7LodPolicy.Binding binding = new X7LodPolicy.Binding(1L, new Object(), new Object());
        collector.recordLod(X7LodPolicy.Selection.selected(
                0, new X7LodPolicy.History(binding, 0), X7LodPolicy.Reason.FRESH_DISTANCE));
        collector.recordLod(X7LodPolicy.Selection.rejected(X7LodPolicy.Reason.INVALID_DISTANCE));
        Object instanceHandle = new Object();
        collector.recordCulling(
                X7CullingPolicy.decideInstance(
                        new X7CullingPolicy.InstanceTarget(1L, instanceHandle),
                        new X7CullingPolicy.Evidence(X7CullingPolicy.Visibility.VISIBLE, true)),
                1L,
                instanceHandle);
        X7CullingPolicy.InstanceTarget primitiveInstance = new X7CullingPolicy.InstanceTarget(1L, new Object());
        collector.recordCulling(X7CullingPolicy.decidePrimitive(
                new X7CullingPolicy.PrimitiveTarget(primitiveInstance, new Object()),
                new X7CullingPolicy.Evidence(X7CullingPolicy.Visibility.HIDDEN, true)));
        for (X7AnimationWorkPolicy.Mode mode : X7AnimationWorkPolicy.Mode.values()) {
            collector.recordAnimationWork(mode);
        }

        X7PolicyMetricsCollector.Snapshot first = collector.snapshot();
        assertEquals(1L, first.gpuCandidateSelections());
        assertEquals(3L, first.cpuSelections());
        assertEquals(1L, first.capabilityUnavailableFallbacks());
        assertEquals(1L, first.prepareFailureFallbacks());
        assertEquals(1L, first.uploadFailureFallbacks());
        assertEquals(1L, first.acceptedBudgetDecisions());
        assertEquals(1L, first.degradedBudgetDecisions());
        assertEquals(1L, first.cpuFallbackBudgetDecisions());
        assertEquals(1L, first.rejectedBudgetDecisions());
        assertEquals(1L, first.lodSelections());
        assertEquals(1L, first.lodRejections());
        assertEquals(1L, first.drawDecisions());
        assertEquals(1L, first.cullDecisions());
        assertEquals(1L, first.fullWorkRecommendations());
        assertEquals(1L, first.reducedWorkRecommendations());
        assertEquals(1L, first.pausedWorkRecommendations());
        assertEquals(1L, first.reusedPoseRecommendations());

        collector.reset();
        X7PolicyMetricsCollector.Snapshot afterReset = collector.snapshot();
        assertEquals(1L, first.gpuCandidateSelections());
        assertEquals(0L, afterReset.gpuCandidateSelections());
        assertEquals(0L, afterReset.cpuSelections());
        assertEquals(0L, afterReset.rejectedBudgetDecisions());
        assertEquals(0L, afterReset.reusedPoseRecommendations());
    }

    private static X7AnimationWorkPolicy.Mode select(
            X7CullingPolicy.Visibility visibility,
            boolean complete,
            double distanceSquared,
            boolean reusablePoseAvailable,
            boolean clientMayPause,
            boolean clientStateRequiresAdvance) {
        Object handle = new Object();
        return X7AnimationWorkPolicy.select(input(
                X7CullingPolicy.decideInstance(
                        new X7CullingPolicy.InstanceTarget(1L, handle),
                        new X7CullingPolicy.Evidence(visibility, complete)),
                distanceSquared,
                reusablePoseAvailable,
                clientMayPause,
                clientStateRequiresAdvance), 1L, handle);
    }

    private static X7AnimationWorkPolicy.Input input(
            X7CullingPolicy.InstanceDecision instanceDecision,
            double distanceSquared,
            boolean reusablePoseAvailable,
            boolean clientMayPause,
            boolean clientStateRequiresAdvance) {
        return new X7AnimationWorkPolicy.Input(
                instanceDecision, distanceSquared, reusablePoseAvailable, clientMayPause, clientStateRequiresAdvance);
    }

    private static X7BudgetPolicy.Result budgetResult(
            X7BudgetPolicy.Request request, X7GenerationPerformancePlan.CapabilitySnapshot capabilities) {
        return X7BudgetPolicy.evaluate(METRICS_BUDGET, X7BudgetPolicy.Usage.empty(), request, published(capabilities));
    }

    private static X7BudgetPolicy.Request request(long models) {
        return new X7BudgetPolicy.Request(models, 1L, 1L, 1L, 1L);
    }

    private static X7GenerationPerformancePlan.Published published(
            X7GenerationPerformancePlan.CapabilitySnapshot capabilities) {
        return X7GenerationPerformancePlan.prepare(
                        new X7GenerationPerformancePlan.Binding(
                                1L, new Object(), new Object(), new Object(), new Object(), new Object()),
                        capabilities)
                .publish();
    }
}
