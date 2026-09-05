package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable identity and target of one code- or data-selected X6 variant. */
public record X6VariantDefinition(BlendResourceId variantId, X6VariantKind kind, BlendResourceId partId) {
    public X6VariantDefinition {
        variantId = X6Ids.requireId(variantId, "variantId");
        kind = Objects.requireNonNull(kind, "kind");
        partId = X6Ids.requireId(partId, "partId");
    }
}
