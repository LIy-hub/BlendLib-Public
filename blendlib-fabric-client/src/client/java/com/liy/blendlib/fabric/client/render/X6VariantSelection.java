package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/** One frozen selection; fallback deliberately carries no mutable or inferred variant. */
public record X6VariantSelection(
        BlendResourceId selectorId,
        X6VariantRuleOutcome outcome,
        Optional<BlendResourceId> variantId) {
    public X6VariantSelection {
        selectorId = X6Ids.requireId(selectorId, "selectorId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        variantId = Objects.requireNonNull(variantId, "variantId");
        if ((outcome == X6VariantRuleOutcome.BASELINE_FALLBACK) != variantId.isEmpty()) {
            throw new IllegalArgumentException("Only baseline fallback may omit a selected variant");
        }
    }
}
