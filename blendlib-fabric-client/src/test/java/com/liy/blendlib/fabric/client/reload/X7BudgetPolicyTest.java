package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class X7BudgetPolicyTest {
    private static final X7BudgetPolicy.Budget BUDGET = new X7BudgetPolicy.Budget(
            10L, 10L, 10L, 10L, 10L, 100L, 100L);

    @Test
    void validatesRangesAndReturnsEveryExplicitDispositionFromTheFrozenPublishedPlan() {
        assertThrows(IllegalArgumentException.class,
                () -> new X7BudgetPolicy.Budget(-1L, 1L, 1L, 1L, 1L, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new X7BudgetPolicy.Request(1L, 1L, -1L, 1L, 1L));

        X7BudgetPolicy.Result accepted = evaluate(BUDGET, X7BudgetPolicy.Usage.empty(), request(5L, 1L, 1L, 1L, 1L), gpuPlan());
        X7BudgetPolicy.Result degraded = evaluate(BUDGET, X7BudgetPolicy.Usage.empty(), request(6L, 1L, 1L, 1L, 1L), gpuPlan());
        X7BudgetPolicy.Result fallback = evaluate(BUDGET, X7BudgetPolicy.Usage.empty(), request(1L, 1L, 1L, 1L, 1L), cpuPlan());

        assertOutcome(accepted, X7BudgetPolicy.Disposition.ACCEPT, X7BudgetPolicy.Reason.NONE, true);
        assertOutcome(degraded, X7BudgetPolicy.Disposition.DEGRADE, X7BudgetPolicy.Reason.BUDGET_PRESSURE, true);
        assertOutcome(fallback, X7BudgetPolicy.Disposition.CPU_FALLBACK, X7BudgetPolicy.Reason.CPU_BACKEND, true);
    }

    @Test
    void rejectsEveryPerModelHardLimitIncludingDerivedAnimationWork() {
        assertRejected(BUDGET, request(11L, 1L, 1L, 1L, 1L), X7BudgetPolicy.Reason.PER_MODEL_MODEL_LIMIT);
        assertRejected(BUDGET, request(1L, 11L, 1L, 1L, 1L), X7BudgetPolicy.Reason.PER_MODEL_PRIMITIVE_LIMIT);
        assertRejected(BUDGET, request(1L, 1L, 11L, 1L, 1L), X7BudgetPolicy.Reason.PER_MODEL_BONE_LIMIT);
        assertRejected(BUDGET, request(1L, 1L, 1L, 11L, 1L), X7BudgetPolicy.Reason.PER_MODEL_VERTEX_LIMIT);
        assertRejected(BUDGET, request(1L, 1L, 1L, 1L, 11L), X7BudgetPolicy.Reason.PER_MODEL_ANIMATED_INSTANCE_LIMIT);

        X7BudgetPolicy.Budget derivedLimits = new X7BudgetPolicy.Budget(
                10L, 10L, 10L, 10L, 10L, 11L, 11L);
        assertRejected(derivedLimits, request(1L, 1L, 4L, 0L, 3L),
                X7BudgetPolicy.Reason.PER_MODEL_BONE_ANIMATION_WORK_LIMIT);
        assertRejected(derivedLimits, request(1L, 1L, 0L, 4L, 3L),
                X7BudgetPolicy.Reason.PER_MODEL_VERTEX_ANIMATION_WORK_LIMIT);
    }

    @Test
    void derivesExactPerFrameWorkAndAccumulatesOnlyThroughReturnedUsage() {
        X7BudgetPolicy.Budget exactProductBudget = new X7BudgetPolicy.Budget(
                10L, 10L, 10L, 10L, 10L, 12L, 16L);
        X7BudgetPolicy.Result exactProduct = evaluate(
                exactProductBudget,
                X7BudgetPolicy.Usage.empty(),
                request(1L, 1L, 3L, 4L, 2L),
                gpuPlan());

        assertOutcome(exactProduct, X7BudgetPolicy.Disposition.ACCEPT, X7BudgetPolicy.Reason.NONE, true);
        assertUsage(exactProduct.admittedUsage(), 1L, 1L, 3L, 4L, 2L, 6L, 8L);

        X7BudgetPolicy.Budget cumulativeBudget = new X7BudgetPolicy.Budget(
                2L, 2L, 4L, 4L, 4L, 8L, 8L);
        X7BudgetPolicy.Result first = evaluate(
                cumulativeBudget,
                X7BudgetPolicy.Usage.empty(),
                request(1L, 1L, 2L, 2L, 2L),
                gpuPlan());
        X7BudgetPolicy.Result second = evaluate(
                cumulativeBudget,
                first.admittedUsage(),
                request(1L, 1L, 2L, 2L, 2L),
                gpuPlan());

        assertTrue(first.allowsPublication());
        assertTrue(second.allowsPublication());
        assertUsage(second.admittedUsage(), 2L, 2L, 4L, 4L, 4L, 8L, 8L);
        assertRejected(cumulativeBudget, second.admittedUsage(), request(1L, 0L, 0L, 0L, 0L),
                X7BudgetPolicy.Reason.CUMULATIVE_MODEL_LIMIT);
    }

    @Test
    void rejectsMultiplicationAndCumulativeLongMaxOverflowWithoutPublishing() {
        X7BudgetPolicy.Budget maximum = new X7BudgetPolicy.Budget(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE);

        assertRejected(maximum, request(0L, 0L, Long.MAX_VALUE, 0L, 2L),
                X7BudgetPolicy.Reason.BONE_ANIMATION_WORK_MULTIPLICATION_OVERFLOW);
        assertRejected(maximum, request(0L, 0L, 0L, Long.MAX_VALUE, 2L),
                X7BudgetPolicy.Reason.VERTEX_ANIMATION_WORK_MULTIPLICATION_OVERFLOW);

        X7BudgetPolicy.Result maximumUsage = evaluate(
                maximum,
                X7BudgetPolicy.Usage.empty(),
                request(Long.MAX_VALUE, 0L, 0L, 0L, 0L),
                gpuPlan());
        assertTrue(maximumUsage.allowsPublication());
        assertEquals(X7BudgetPolicy.Disposition.DEGRADE, maximumUsage.disposition());
        assertRejected(maximum, maximumUsage.admittedUsage(), request(1L, 0L, 0L, 0L, 0L),
                X7BudgetPolicy.Reason.CUMULATIVE_MODEL_OVERFLOW);
    }

    @Test
    void cumulativeAdmissionIsOrderIndependentAndZeroLimitsStayClosed() {
        X7BudgetPolicy.Budget budget = new X7BudgetPolicy.Budget(4L, 4L, 6L, 6L, 4L, 8L, 8L);
        X7BudgetPolicy.Request firstRequest = request(1L, 1L, 1L, 2L, 2L);
        X7BudgetPolicy.Request secondRequest = request(1L, 1L, 2L, 1L, 1L);

        X7BudgetPolicy.Result firstThenSecond = evaluate(
                budget,
                evaluate(budget, X7BudgetPolicy.Usage.empty(), firstRequest, gpuPlan()).admittedUsage(),
                secondRequest,
                gpuPlan());
        X7BudgetPolicy.Result secondThenFirst = evaluate(
                budget,
                evaluate(budget, X7BudgetPolicy.Usage.empty(), secondRequest, gpuPlan()).admittedUsage(),
                firstRequest,
                gpuPlan());

        assertUsage(firstThenSecond.admittedUsage(), 2L, 2L, 3L, 3L, 3L, 4L, 5L);
        assertUsage(secondThenFirst.admittedUsage(), 2L, 2L, 3L, 3L, 3L, 4L, 5L);

        X7BudgetPolicy.Budget zero = new X7BudgetPolicy.Budget(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        assertOutcome(evaluate(zero, X7BudgetPolicy.Usage.empty(), request(0L, 0L, 0L, 0L, 0L), gpuPlan()),
                X7BudgetPolicy.Disposition.ACCEPT,
                X7BudgetPolicy.Reason.NONE,
                true);
        assertRejected(zero, request(1L, 0L, 0L, 0L, 0L), X7BudgetPolicy.Reason.PER_MODEL_MODEL_LIMIT);
    }

    private static X7BudgetPolicy.Result evaluate(
            X7BudgetPolicy.Budget budget,
            X7BudgetPolicy.Usage usage,
            X7BudgetPolicy.Request request,
            X7GenerationPerformancePlan.Published plan) {
        return X7BudgetPolicy.evaluate(budget, usage, request, plan);
    }

    private static X7BudgetPolicy.Request request(
            long models, long primitives, long bones, long vertices, long animatedInstancesPerFrame) {
        return new X7BudgetPolicy.Request(models, primitives, bones, vertices, animatedInstancesPerFrame);
    }

    private static X7GenerationPerformancePlan.Published gpuPlan() {
        return plan(X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady());
    }

    private static X7GenerationPerformancePlan.Published cpuPlan() {
        return plan(X7GenerationPerformancePlan.CapabilitySnapshot.cpuOnly());
    }

    private static X7GenerationPerformancePlan.Published plan(X7GenerationPerformancePlan.CapabilitySnapshot capabilities) {
        return X7GenerationPerformancePlan.prepare(
                        new X7GenerationPerformancePlan.Binding(
                                1L, new Object(), new Object(), new Object(), new Object(), new Object()),
                        capabilities)
                .publish();
    }

    private static void assertRejected(
            X7BudgetPolicy.Budget budget, X7BudgetPolicy.Request request, X7BudgetPolicy.Reason reason) {
        assertRejected(budget, X7BudgetPolicy.Usage.empty(), request, reason);
    }

    private static void assertRejected(
            X7BudgetPolicy.Budget budget,
            X7BudgetPolicy.Usage usage,
            X7BudgetPolicy.Request request,
            X7BudgetPolicy.Reason reason) {
        X7BudgetPolicy.Result result = evaluate(budget, usage, request, gpuPlan());
        assertOutcome(result, X7BudgetPolicy.Disposition.REJECT, reason, false);
        assertThrows(IllegalStateException.class, result::admittedUsage);
    }

    private static void assertOutcome(
            X7BudgetPolicy.Result result,
            X7BudgetPolicy.Disposition disposition,
            X7BudgetPolicy.Reason reason,
            boolean allowsPublication) {
        assertEquals(disposition, result.disposition());
        assertEquals(reason, result.reason());
        assertEquals(allowsPublication, result.allowsPublication());
    }

    private static void assertUsage(
            X7BudgetPolicy.Usage usage,
            long models,
            long primitives,
            long bones,
            long vertices,
            long animatedInstances,
            long boneWork,
            long vertexWork) {
        assertEquals(models, usage.models());
        assertEquals(primitives, usage.primitives());
        assertEquals(bones, usage.bones());
        assertEquals(vertices, usage.vertices());
        assertEquals(animatedInstances, usage.animatedInstancesPerFrame());
        assertEquals(boneWork, usage.boneAnimationWorkPerFrame());
        assertEquals(vertexWork, usage.vertexAnimationWorkPerFrame());
    }
}
