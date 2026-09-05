package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/** Conservative, scope-typed culling preparation; this package does not perform a backend cull. */
final class X7CullingPolicy {
    private X7CullingPolicy() {
    }

    /** Input state may be visible, hidden, or not safely known at extraction time. */
    enum Visibility {
        VISIBLE,
        HIDDEN,
        UNKNOWN
    }

    /** Whether evidence for one exact scope is complete enough to cull it. */
    record Evidence(Visibility visibility, boolean dynamicBoundsComplete) {
        Evidence {
            visibility = Objects.requireNonNull(visibility, "visibility");
        }
    }

    /** Exact identity for an instance scope. */
    record InstanceTarget(long generation, Object handleIdentity) {
        InstanceTarget {
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            handleIdentity = Objects.requireNonNull(handleIdentity, "handleIdentity");
        }

        private boolean matches(long activeGeneration, Object activeHandleIdentity) {
            return generation == activeGeneration && handleIdentity == activeHandleIdentity;
        }
    }

    /** Exact identity for one primitive belonging to an instance scope. */
    record PrimitiveTarget(InstanceTarget instanceTarget, Object primitiveIdentity) {
        PrimitiveTarget {
            instanceTarget = Objects.requireNonNull(instanceTarget, "instanceTarget");
            primitiveIdentity = Objects.requireNonNull(primitiveIdentity, "primitiveIdentity");
        }

        private boolean matches(long activeGeneration, Object activeHandleIdentity, Object activePrimitiveIdentity) {
            return instanceTarget.matches(activeGeneration, activeHandleIdentity)
                    && primitiveIdentity == activePrimitiveIdentity;
        }
    }

    /** Exact identity for one bone-influence subset belonging to a primitive scope. */
    record BoneTarget(PrimitiveTarget primitiveTarget, Object boneIdentity) {
        BoneTarget {
            primitiveTarget = Objects.requireNonNull(primitiveTarget, "primitiveTarget");
            boneIdentity = Objects.requireNonNull(boneIdentity, "boneIdentity");
        }

        private boolean matches(
                long activeGeneration,
                Object activeHandleIdentity,
                Object activePrimitiveIdentity,
                Object activeBoneIdentity) {
            return primitiveTarget.matches(activeGeneration, activeHandleIdentity, activePrimitiveIdentity)
                    && boneIdentity == activeBoneIdentity;
        }
    }

    /** Prepared decision that a later backend may honor only at its matching scope. */
    enum Decision {
        DRAW,
        CULL
    }

    /** Why one scope was kept drawable or was conclusively culled. */
    enum Reason {
        VISIBLE_OR_UNCULLED,
        KNOWN_HIDDEN,
        UNKNOWN_INPUT,
        DYNAMIC_BOUNDS_INSUFFICIENT
    }

    /**
     * Outcome exposed only after an instance decision has been checked against the active identity.
     *
     * <p>The private constructor keeps ordinary package-peer source from manufacturing an unchecked
     * outcome. This does not make adversarial reflection a policy trust boundary.</p>
     */
    static final class CurrentInstanceDecision {
        private final Decision decision;
        private final Reason reason;

        private CurrentInstanceDecision(Decision decision, Reason reason) {
            this.decision = Objects.requireNonNull(decision, "decision");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        Decision decision() {
            return decision;
        }

        Reason reason() {
            return reason;
        }
    }

    /**
     * Instance-only decision. Its result is readable only through {@link #readCurrent(long, Object)},
     * which validates the active generation and exact handle first.
     */
    static final class InstanceDecision {
        private final InstanceTarget target;
        private final Decision decision;
        private final Reason reason;

        private InstanceDecision(InstanceTarget target, Decision decision, Reason reason) {
            this.target = Objects.requireNonNull(target, "target");
            this.decision = Objects.requireNonNull(decision, "decision");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        InstanceTarget target() {
            return target;
        }

        void requireCurrent(long activeGeneration, Object activeHandleIdentity) {
            Objects.requireNonNull(activeHandleIdentity, "activeHandleIdentity");
            if (!target.matches(activeGeneration, activeHandleIdentity)) {
                throw new IllegalStateException("instance culling decision does not match the current generation or handle");
            }
        }

        CurrentInstanceDecision readCurrent(long activeGeneration, Object activeHandleIdentity) {
            requireCurrent(activeGeneration, activeHandleIdentity);
            return new CurrentInstanceDecision(decision, reason);
        }
    }

    /** Primitive-only decision; it cannot be supplied where an instance decision is required. */
    static final class PrimitiveDecision {
        private final PrimitiveTarget target;
        private final Decision decision;
        private final Reason reason;

        private PrimitiveDecision(PrimitiveTarget target, Decision decision, Reason reason) {
            this.target = Objects.requireNonNull(target, "target");
            this.decision = Objects.requireNonNull(decision, "decision");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        PrimitiveTarget target() {
            return target;
        }

        Decision decision() {
            return decision;
        }

        Reason reason() {
            return reason;
        }

        void requireCurrent(long activeGeneration, Object activeHandleIdentity, Object activePrimitiveIdentity) {
            Objects.requireNonNull(activeHandleIdentity, "activeHandleIdentity");
            Objects.requireNonNull(activePrimitiveIdentity, "activePrimitiveIdentity");
            if (!target.matches(activeGeneration, activeHandleIdentity, activePrimitiveIdentity)) {
                throw new IllegalStateException(
                        "primitive culling decision does not match the current generation, handle, or primitive");
            }
        }
    }

    /** Bone-only decision; a hidden subset cannot cull a containing primitive or instance. */
    static final class BoneDecision {
        private final BoneTarget target;
        private final Decision decision;
        private final Reason reason;

        private BoneDecision(BoneTarget target, Decision decision, Reason reason) {
            this.target = Objects.requireNonNull(target, "target");
            this.decision = Objects.requireNonNull(decision, "decision");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        BoneTarget target() {
            return target;
        }

        Decision decision() {
            return decision;
        }

        Reason reason() {
            return reason;
        }

        void requireCurrent(
                long activeGeneration,
                Object activeHandleIdentity,
                Object activePrimitiveIdentity,
                Object activeBoneIdentity) {
            Objects.requireNonNull(activeHandleIdentity, "activeHandleIdentity");
            Objects.requireNonNull(activePrimitiveIdentity, "activePrimitiveIdentity");
            Objects.requireNonNull(activeBoneIdentity, "activeBoneIdentity");
            if (!target.matches(activeGeneration, activeHandleIdentity, activePrimitiveIdentity, activeBoneIdentity)) {
                throw new IllegalStateException(
                        "bone culling decision does not match the current generation, handle, primitive, or bone");
            }
        }
    }

    static InstanceDecision decideInstance(InstanceTarget target, Evidence evidence) {
        InstanceTarget checkedTarget = Objects.requireNonNull(target, "target");
        Evaluated evaluated = evaluate(Objects.requireNonNull(evidence, "evidence"));
        return new InstanceDecision(checkedTarget, evaluated.decision(), evaluated.reason());
    }

    static PrimitiveDecision decidePrimitive(PrimitiveTarget target, Evidence evidence) {
        PrimitiveTarget checkedTarget = Objects.requireNonNull(target, "target");
        Evaluated evaluated = evaluate(Objects.requireNonNull(evidence, "evidence"));
        return new PrimitiveDecision(checkedTarget, evaluated.decision(), evaluated.reason());
    }

    static BoneDecision decideBone(BoneTarget target, Evidence evidence) {
        BoneTarget checkedTarget = Objects.requireNonNull(target, "target");
        Evaluated evaluated = evaluate(Objects.requireNonNull(evidence, "evidence"));
        return new BoneDecision(checkedTarget, evaluated.decision(), evaluated.reason());
    }

    private static Evaluated evaluate(Evidence evidence) {
        if (!evidence.dynamicBoundsComplete()) {
            return new Evaluated(Decision.DRAW, Reason.DYNAMIC_BOUNDS_INSUFFICIENT);
        }
        if (evidence.visibility() == Visibility.UNKNOWN) {
            return new Evaluated(Decision.DRAW, Reason.UNKNOWN_INPUT);
        }
        if (evidence.visibility() == Visibility.HIDDEN) {
            return new Evaluated(Decision.CULL, Reason.KNOWN_HIDDEN);
        }
        return new Evaluated(Decision.DRAW, Reason.VISIBLE_OR_UNCULLED);
    }

    private record Evaluated(Decision decision, Reason reason) {
    }
}
