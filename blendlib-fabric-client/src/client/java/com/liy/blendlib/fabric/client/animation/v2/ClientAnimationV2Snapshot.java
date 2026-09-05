package com.liy.blendlib.fabric.client.animation.v2;

import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentReconciliationResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable client-observation boundary for the experimental v2 runtime. */
public final class ClientAnimationV2Snapshot {
    private final Optional<AnimationV2EvaluationSnapshot> evaluation;
    private final Optional<com.liy.blendlib.fabric.common.animation.v2.AnimationIntentScope> scope;
    private final List<AnimationIntentReconciliationResult> reconciliationResults;

    public ClientAnimationV2Snapshot(
            Optional<AnimationV2EvaluationSnapshot> evaluation,
            Optional<com.liy.blendlib.fabric.common.animation.v2.AnimationIntentScope> scope,
            List<AnimationIntentReconciliationResult> reconciliationResults) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.reconciliationResults = List.copyOf(Objects.requireNonNull(reconciliationResults, "reconciliationResults"));
    }

    public static ClientAnimationV2Snapshot empty() {
        return new ClientAnimationV2Snapshot(Optional.empty(), Optional.empty(), List.of());
    }

    public Optional<AnimationV2EvaluationSnapshot> evaluation() {
        return evaluation;
    }

    public Optional<com.liy.blendlib.fabric.common.animation.v2.AnimationIntentScope> scope() {
        return scope;
    }

    public List<AnimationIntentReconciliationResult> reconciliationResults() {
        return reconciliationResults;
    }
}
