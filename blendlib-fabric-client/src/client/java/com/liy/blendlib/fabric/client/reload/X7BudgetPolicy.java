package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * Bounded deterministic policy for one caller-owned generation and frame admission ledger.
 *
 * <p>The caller passes immutable previously admitted usage and receives a new immutable usage only
 * after acceptance. This policy keeps no global accumulator or second rendering authority. Every
 * per-model and cumulative arithmetic operation is checked; overflow always rejects rather than
 * degrading into a publishable draw.</p>
 */
final class X7BudgetPolicy {
    private X7BudgetPolicy() {
    }

    /** Immutable limits for generation totals and derived animation work in one frame. */
    record Budget(
            long models,
            long primitives,
            long bones,
            long vertices,
            long animatedInstancesPerFrame,
            long boneAnimationWorkPerFrame,
            long vertexAnimationWorkPerFrame) {
        Budget {
            requireNonNegative(models, "models");
            requireNonNegative(primitives, "primitives");
            requireNonNegative(bones, "bones");
            requireNonNegative(vertices, "vertices");
            requireNonNegative(animatedInstancesPerFrame, "animatedInstancesPerFrame");
            requireNonNegative(boneAnimationWorkPerFrame, "boneAnimationWorkPerFrame");
            requireNonNegative(vertexAnimationWorkPerFrame, "vertexAnimationWorkPerFrame");
        }
    }

    /** Immutable added model cohort evaluated against a {@link Budget}. */
    record Request(long models, long primitives, long bones, long vertices, long animatedInstancesPerFrame) {
        Request {
            requireNonNegative(models, "models");
            requireNonNegative(primitives, "primitives");
            requireNonNegative(bones, "bones");
            requireNonNegative(vertices, "vertices");
            requireNonNegative(animatedInstancesPerFrame, "animatedInstancesPerFrame");
        }
    }

    /**
     * Immutable usage returned only by a successful admission and retained by the caller's real
     * generation/frame owner. It cannot be independently constructed by a package peer.
     */
    static final class Usage {
        private final long models;
        private final long primitives;
        private final long bones;
        private final long vertices;
        private final long animatedInstancesPerFrame;
        private final long boneAnimationWorkPerFrame;
        private final long vertexAnimationWorkPerFrame;

        private Usage(
                long models,
                long primitives,
                long bones,
                long vertices,
                long animatedInstancesPerFrame,
                long boneAnimationWorkPerFrame,
                long vertexAnimationWorkPerFrame) {
            this.models = models;
            this.primitives = primitives;
            this.bones = bones;
            this.vertices = vertices;
            this.animatedInstancesPerFrame = animatedInstancesPerFrame;
            this.boneAnimationWorkPerFrame = boneAnimationWorkPerFrame;
            this.vertexAnimationWorkPerFrame = vertexAnimationWorkPerFrame;
        }

        static Usage empty() {
            return new Usage(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        long models() {
            return models;
        }

        long primitives() {
            return primitives;
        }

        long bones() {
            return bones;
        }

        long vertices() {
            return vertices;
        }

        long animatedInstancesPerFrame() {
            return animatedInstancesPerFrame;
        }

        long boneAnimationWorkPerFrame() {
            return boneAnimationWorkPerFrame;
        }

        long vertexAnimationWorkPerFrame() {
            return vertexAnimationWorkPerFrame;
        }
    }

    /** Deterministic publication disposition; only rejection prohibits the requested work. */
    enum Disposition {
        ACCEPT,
        DEGRADE,
        CPU_FALLBACK,
        REJECT
    }

    /** Adapter-private diagnostic reason attached to each result. */
    enum Reason {
        NONE,
        BUDGET_PRESSURE,
        CPU_BACKEND,
        PER_MODEL_MODEL_LIMIT,
        PER_MODEL_PRIMITIVE_LIMIT,
        PER_MODEL_BONE_LIMIT,
        PER_MODEL_VERTEX_LIMIT,
        PER_MODEL_ANIMATED_INSTANCE_LIMIT,
        PER_MODEL_BONE_ANIMATION_WORK_LIMIT,
        PER_MODEL_VERTEX_ANIMATION_WORK_LIMIT,
        BONE_ANIMATION_WORK_MULTIPLICATION_OVERFLOW,
        VERTEX_ANIMATION_WORK_MULTIPLICATION_OVERFLOW,
        CUMULATIVE_MODEL_OVERFLOW,
        CUMULATIVE_PRIMITIVE_OVERFLOW,
        CUMULATIVE_BONE_OVERFLOW,
        CUMULATIVE_VERTEX_OVERFLOW,
        CUMULATIVE_ANIMATED_INSTANCE_OVERFLOW,
        CUMULATIVE_BONE_ANIMATION_WORK_OVERFLOW,
        CUMULATIVE_VERTEX_ANIMATION_WORK_OVERFLOW,
        CUMULATIVE_MODEL_LIMIT,
        CUMULATIVE_PRIMITIVE_LIMIT,
        CUMULATIVE_BONE_LIMIT,
        CUMULATIVE_VERTEX_LIMIT,
        CUMULATIVE_ANIMATED_INSTANCE_LIMIT,
        CUMULATIVE_BONE_ANIMATION_WORK_LIMIT,
        CUMULATIVE_VERTEX_ANIMATION_WORK_LIMIT
    }

    /** Immutable outcome; rejected work deliberately carries no usage token that could be reused. */
    static final class Result {
        private final Disposition disposition;
        private final Reason reason;
        private final Usage admittedUsage;

        private Result(Disposition disposition, Reason reason, Usage admittedUsage) {
            this.disposition = Objects.requireNonNull(disposition, "disposition");
            this.reason = Objects.requireNonNull(reason, "reason");
            this.admittedUsage = admittedUsage;
            if ((disposition == Disposition.ACCEPT) != (reason == Reason.NONE)) {
                throw new IllegalArgumentException("acceptance and budget reason must agree");
            }
            if (disposition != Disposition.ACCEPT && reason == Reason.NONE) {
                throw new IllegalArgumentException("non-acceptance requires a diagnostic reason");
            }
            if ((disposition == Disposition.REJECT) != (admittedUsage == null)) {
                throw new IllegalArgumentException("only rejected work may omit its admitted usage");
            }
        }

        Disposition disposition() {
            return disposition;
        }

        Reason reason() {
            return reason;
        }

        boolean allowsPublication() {
            return disposition != Disposition.REJECT;
        }

        Usage admittedUsage() {
            if (!allowsPublication()) {
                throw new IllegalStateException("rejected X7 budget work has no admitted usage");
            }
            return admittedUsage;
        }
    }

    /**
     * Evaluates one added model cohort against caller-owned existing usage and the frozen backend.
     * The published plan, rather than a freely supplied backend enum, fixes CPU/GPU-candidate
     * semantics before the budget disposition is selected.
     */
    static Result evaluate(
            Budget budget,
            Usage existingUsage,
            Request request,
            X7GenerationPerformancePlan.Published publishedPlan) {
        Budget checkedBudget = Objects.requireNonNull(budget, "budget");
        Usage checkedExistingUsage = Objects.requireNonNull(existingUsage, "existingUsage");
        Request checkedRequest = Objects.requireNonNull(request, "request");
        X7GenerationPerformancePlan.Published checkedPublishedPlan = Objects.requireNonNull(publishedPlan, "publishedPlan");

        final DerivedWork derivedWork;
        try {
            derivedWork = deriveWork(checkedRequest);
        } catch (CheckedArithmeticException exception) {
            return rejected(exception.reason());
        }

        Reason perModelExceeded = perModelExceededReason(checkedBudget, checkedRequest, derivedWork);
        if (perModelExceeded != Reason.NONE) {
            return rejected(perModelExceeded);
        }

        final Usage projectedUsage;
        try {
            projectedUsage = accumulate(checkedExistingUsage, checkedRequest, derivedWork);
        } catch (CheckedArithmeticException exception) {
            return rejected(exception.reason());
        }

        Reason cumulativeExceeded = cumulativeExceededReason(checkedBudget, projectedUsage);
        if (cumulativeExceeded != Reason.NONE) {
            return rejected(cumulativeExceeded);
        }
        if (checkedPublishedPlan.backend() == X7GenerationPerformancePlan.BackendChoice.CPU) {
            return admitted(Disposition.CPU_FALLBACK, Reason.CPU_BACKEND, projectedUsage);
        }
        if (underPressure(checkedBudget, projectedUsage)) {
            return admitted(Disposition.DEGRADE, Reason.BUDGET_PRESSURE, projectedUsage);
        }
        return admitted(Disposition.ACCEPT, Reason.NONE, projectedUsage);
    }

    private static Result admitted(Disposition disposition, Reason reason, Usage usage) {
        return new Result(disposition, reason, Objects.requireNonNull(usage, "usage"));
    }

    private static Result rejected(Reason reason) {
        return new Result(Disposition.REJECT, reason, null);
    }

    private static DerivedWork deriveWork(Request request) {
        return new DerivedWork(
                checkedMultiply(
                        request.bones(),
                        request.animatedInstancesPerFrame(),
                        Reason.BONE_ANIMATION_WORK_MULTIPLICATION_OVERFLOW),
                checkedMultiply(
                        request.vertices(),
                        request.animatedInstancesPerFrame(),
                        Reason.VERTEX_ANIMATION_WORK_MULTIPLICATION_OVERFLOW));
    }

    private static Reason perModelExceededReason(Budget budget, Request request, DerivedWork work) {
        if (request.models() > budget.models()) {
            return Reason.PER_MODEL_MODEL_LIMIT;
        }
        if (request.primitives() > budget.primitives()) {
            return Reason.PER_MODEL_PRIMITIVE_LIMIT;
        }
        if (request.bones() > budget.bones()) {
            return Reason.PER_MODEL_BONE_LIMIT;
        }
        if (request.vertices() > budget.vertices()) {
            return Reason.PER_MODEL_VERTEX_LIMIT;
        }
        if (request.animatedInstancesPerFrame() > budget.animatedInstancesPerFrame()) {
            return Reason.PER_MODEL_ANIMATED_INSTANCE_LIMIT;
        }
        if (work.boneAnimationWorkPerFrame() > budget.boneAnimationWorkPerFrame()) {
            return Reason.PER_MODEL_BONE_ANIMATION_WORK_LIMIT;
        }
        if (work.vertexAnimationWorkPerFrame() > budget.vertexAnimationWorkPerFrame()) {
            return Reason.PER_MODEL_VERTEX_ANIMATION_WORK_LIMIT;
        }
        return Reason.NONE;
    }

    private static Usage accumulate(Usage existingUsage, Request request, DerivedWork work) {
        return new Usage(
                checkedAdd(existingUsage.models(), request.models(), Reason.CUMULATIVE_MODEL_OVERFLOW),
                checkedAdd(existingUsage.primitives(), request.primitives(), Reason.CUMULATIVE_PRIMITIVE_OVERFLOW),
                checkedAdd(existingUsage.bones(), request.bones(), Reason.CUMULATIVE_BONE_OVERFLOW),
                checkedAdd(existingUsage.vertices(), request.vertices(), Reason.CUMULATIVE_VERTEX_OVERFLOW),
                checkedAdd(
                        existingUsage.animatedInstancesPerFrame(),
                        request.animatedInstancesPerFrame(),
                        Reason.CUMULATIVE_ANIMATED_INSTANCE_OVERFLOW),
                checkedAdd(
                        existingUsage.boneAnimationWorkPerFrame(),
                        work.boneAnimationWorkPerFrame(),
                        Reason.CUMULATIVE_BONE_ANIMATION_WORK_OVERFLOW),
                checkedAdd(
                        existingUsage.vertexAnimationWorkPerFrame(),
                        work.vertexAnimationWorkPerFrame(),
                        Reason.CUMULATIVE_VERTEX_ANIMATION_WORK_OVERFLOW));
    }

    private static Reason cumulativeExceededReason(Budget budget, Usage usage) {
        if (usage.models() > budget.models()) {
            return Reason.CUMULATIVE_MODEL_LIMIT;
        }
        if (usage.primitives() > budget.primitives()) {
            return Reason.CUMULATIVE_PRIMITIVE_LIMIT;
        }
        if (usage.bones() > budget.bones()) {
            return Reason.CUMULATIVE_BONE_LIMIT;
        }
        if (usage.vertices() > budget.vertices()) {
            return Reason.CUMULATIVE_VERTEX_LIMIT;
        }
        if (usage.animatedInstancesPerFrame() > budget.animatedInstancesPerFrame()) {
            return Reason.CUMULATIVE_ANIMATED_INSTANCE_LIMIT;
        }
        if (usage.boneAnimationWorkPerFrame() > budget.boneAnimationWorkPerFrame()) {
            return Reason.CUMULATIVE_BONE_ANIMATION_WORK_LIMIT;
        }
        if (usage.vertexAnimationWorkPerFrame() > budget.vertexAnimationWorkPerFrame()) {
            return Reason.CUMULATIVE_VERTEX_ANIMATION_WORK_LIMIT;
        }
        return Reason.NONE;
    }

    private static boolean underPressure(Budget budget, Usage usage) {
        return aboveHalf(usage.models(), budget.models())
                || aboveHalf(usage.primitives(), budget.primitives())
                || aboveHalf(usage.bones(), budget.bones())
                || aboveHalf(usage.vertices(), budget.vertices())
                || aboveHalf(usage.animatedInstancesPerFrame(), budget.animatedInstancesPerFrame())
                || aboveHalf(usage.boneAnimationWorkPerFrame(), budget.boneAnimationWorkPerFrame())
                || aboveHalf(usage.vertexAnimationWorkPerFrame(), budget.vertexAnimationWorkPerFrame());
    }

    private static boolean aboveHalf(long value, long limit) {
        return value > limit / 2L;
    }

    private static long checkedMultiply(long left, long right, Reason overflowReason) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CheckedArithmeticException(overflowReason, exception);
        }
    }

    private static long checkedAdd(long left, long right, Reason overflowReason) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CheckedArithmeticException(overflowReason, exception);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private record DerivedWork(long boneAnimationWorkPerFrame, long vertexAnimationWorkPerFrame) {
    }

    private static final class CheckedArithmeticException extends ArithmeticException {
        private static final long serialVersionUID = 1L;

        private final Reason reason;

        private CheckedArithmeticException(Reason reason, ArithmeticException cause) {
            super(reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
            initCause(cause);
        }

        private Reason reason() {
            return reason;
        }
    }
}
