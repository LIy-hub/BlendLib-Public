package com.liy.blendlib.fabric.common.animation.v2;

import java.util.Objects;

/** Immutable observer result; it exposes no mutable retention state. */
public record AnimationIntentReconciliationResult(
        AnimationIntentReconciliationOutcome outcome,
        AnimationIntent intent) {
    public AnimationIntentReconciliationResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        intent = Objects.requireNonNull(intent, "intent");
    }

    public boolean accepted() {
        return outcome == AnimationIntentReconciliationOutcome.ACCEPTED;
    }
}
