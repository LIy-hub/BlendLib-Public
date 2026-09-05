package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.CapabilitySelectionOutcome;
import java.util.Objects;
import java.util.Optional;

/** Immutable, metadata-only material capability decision retained by one X6 prepared plan. */
public record X6MaterialProviderBinding(
        BlendResourceId capabilityId,
        CapabilitySelectionOutcome outcome,
        Optional<BlendResourceId> selectedProviderId,
        Optional<BlendResourceId> fallbackId) {
    public X6MaterialProviderBinding {
        capabilityId = X6Ids.requireId(capabilityId, "capabilityId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        selectedProviderId = Objects.requireNonNull(selectedProviderId, "selectedProviderId");
        fallbackId = Objects.requireNonNull(fallbackId, "fallbackId");
        if ((outcome == CapabilitySelectionOutcome.SELECTED) != selectedProviderId.isPresent()) {
            throw new IllegalArgumentException("A selected X6 material capability must retain exactly one provider id");
        }
        if ((outcome == CapabilitySelectionOutcome.FALLBACK) != fallbackId.isPresent()) {
            throw new IllegalArgumentException("A fallback X6 material capability must retain exactly one fallback id");
        }
    }
}
