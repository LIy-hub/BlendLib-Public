package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable sidecar variant declaration emitted outside the frozen v1 descriptor.
 *
 * <p><strong>Experimental boundary:</strong> v1 core does not consume this sidecar automatically.
 * It is deterministic authoring metadata for a later capability-negotiated platform integration;
 * it cannot silently change strict descriptor semantics.</p>
 *
 * @param variantId canonical variant identity
 * @param selectors immutable string selector map
 */
public record DatagenVariant(BlendResourceId variantId, Map<String, String> selectors) {
    /**
     * Validates a deterministic sidecar variant declaration.
     */
    public DatagenVariant {
        variantId = Objects.requireNonNull(variantId, "variantId");
        selectors = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(selectors, "selectors")));
        if (selectors.isEmpty() || selectors.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("selectors must contain non-blank keys and values");
        }
        DatagenLimits.requireAtMost("variant selectors", selectors.size(), DatagenLimits.MAX_VARIANT_SELECTORS);
    }
}
