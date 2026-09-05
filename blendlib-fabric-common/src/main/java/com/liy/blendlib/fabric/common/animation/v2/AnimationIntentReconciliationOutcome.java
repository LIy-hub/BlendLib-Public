package com.liy.blendlib.fabric.common.animation.v2;

/** Deterministic result of reconciling one incoming semantic intent. */
public enum AnimationIntentReconciliationOutcome {
    ACCEPTED,
    DUPLICATE_DROPPED,
    STALE_DROPPED,
    SEQUENCE_CONFLICT_REJECTED,
    SCOPE_REJECTED,
    PLAN_REJECTED,
    CONTROLLER_REJECTED,
    INGRESS_QUEUE_OVERFLOW,
    DRAIN_BUDGET_EXHAUSTED
}
