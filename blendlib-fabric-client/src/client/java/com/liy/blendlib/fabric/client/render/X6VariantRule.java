package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One bounded AND-rule evaluated during prepare, never during submit. */
public record X6VariantRule(
        BlendResourceId ruleId,
        BlendResourceId selectorId,
        int priority,
        Map<BlendResourceId, String> requiredContext,
        BlendResourceId variantId) {
    public X6VariantRule {
        ruleId = X6Ids.requireId(ruleId, "ruleId");
        selectorId = X6Ids.requireId(selectorId, "selectorId");
        variantId = X6Ids.requireId(variantId, "variantId");
        Objects.requireNonNull(requiredContext, "requiredContext");
        if (requiredContext.size() > X6Ids.MAX_RULE_DEPTH) {
            throw new IllegalArgumentException("A variant rule may contain at most " + X6Ids.MAX_RULE_DEPTH + " predicates");
        }
        Map<BlendResourceId, String> sorted = new LinkedHashMap<>();
        requiredContext.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> {
                    BlendResourceId key = X6Ids.requireId(entry.getKey(), "requiredContext key");
                    if (sorted.put(key, X6Ids.requireContextValue(entry.getValue(), "requiredContext value")) != null) {
                        throw new IllegalArgumentException("A variant rule may not repeat a context key");
                    }
                });
        requiredContext = X6Ids.immutableOrderedMap(sorted);
    }

    boolean matches(X6VariantContext context) {
        for (Map.Entry<BlendResourceId, String> entry : requiredContext.entrySet()) {
            if (!entry.getValue().equals(context.values().get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
