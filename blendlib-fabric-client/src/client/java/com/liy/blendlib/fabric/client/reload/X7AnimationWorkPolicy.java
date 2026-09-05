package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * Client-side animation-work recommendation aligned with the existing near/mid/far cadence split.
 *
 * <p>The result is only a frozen scheduling recommendation. It neither changes synchronized state
 * nor asserts that hidden content is safe to stop skinning.</p>
 */
final class X7AnimationWorkPolicy {
    static final double NEAR_DISTANCE_SQUARED = 32.0D * 32.0D;

    private X7AnimationWorkPolicy() {
    }

    /** Immutable client extraction facts used to recommend animation work. */
    record Input(
            X7CullingPolicy.InstanceDecision instanceCulling,
            double distanceSquared,
            boolean reusablePoseAvailable,
            boolean clientMayPause,
            boolean clientStateRequiresAdvance) {
        Input {
            instanceCulling = Objects.requireNonNull(instanceCulling, "instanceCulling");
        }
    }

    /** Scheduling semantics only; a future owner maps them to existing animation/cache work. */
    enum Mode {
        FULL,
        REDUCED,
        PAUSED,
        REUSE_POSE
    }

    /**
     * Consumes one instance decision only after it proves it still belongs to the active generation
     * and exact render handle.
     */
    static Mode select(Input input, long activeGeneration, Object activeHandleIdentity) {
        Input checkedInput = Objects.requireNonNull(input, "input");
        Object checkedActiveHandleIdentity = Objects.requireNonNull(activeHandleIdentity, "activeHandleIdentity");
        X7CullingPolicy.CurrentInstanceDecision currentCulling = checkedInput.instanceCulling()
                .readCurrent(activeGeneration, checkedActiveHandleIdentity);
        X7CullingPolicy.Reason cullingReason = currentCulling.reason();
        X7CullingPolicy.Decision cullingDecision = currentCulling.decision();
        if (!Double.isFinite(checkedInput.distanceSquared()) || checkedInput.distanceSquared() < 0.0D
                || cullingReason == X7CullingPolicy.Reason.UNKNOWN_INPUT
                || cullingReason == X7CullingPolicy.Reason.DYNAMIC_BOUNDS_INSUFFICIENT
                || checkedInput.clientStateRequiresAdvance()) {
            return Mode.FULL;
        }
        if (cullingDecision == X7CullingPolicy.Decision.DRAW) {
            return checkedInput.distanceSquared() <= NEAR_DISTANCE_SQUARED
                    ? Mode.FULL
                    : Mode.REDUCED;
        }
        if (checkedInput.reusablePoseAvailable()) {
            return Mode.REUSE_POSE;
        }
        return checkedInput.clientMayPause() ? Mode.PAUSED : Mode.REDUCED;
    }
}
