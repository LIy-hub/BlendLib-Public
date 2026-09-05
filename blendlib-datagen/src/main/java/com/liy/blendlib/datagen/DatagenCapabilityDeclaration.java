package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable experimental capability declaration emitted into a deterministic sidecar.
 *
 * <p><strong>Experimental boundary:</strong> required declarations remain fail-closed, while
 * optional declarations require a named semantic fallback. The data generator never discovers
 * providers or invokes platform behavior.</p>
 *
 * @param capabilityId canonical requested capability
 * @param requirement required or optional strength
 * @param fallbackId required only for optional declarations
 */
public record DatagenCapabilityDeclaration(
        BlendResourceId capabilityId,
        DatagenCapabilityRequirement requirement,
        Optional<BlendResourceId> fallbackId) {
    /**
     * Validates a strict experimental capability declaration.
     */
    public DatagenCapabilityDeclaration {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        requirement = Objects.requireNonNull(requirement, "requirement");
        fallbackId = Objects.requireNonNull(fallbackId, "fallbackId");
        if ((requirement == DatagenCapabilityRequirement.REQUIRED && fallbackId.isPresent())
                || (requirement == DatagenCapabilityRequirement.OPTIONAL && fallbackId.isEmpty())) {
            throw new IllegalArgumentException("required capabilities cannot have fallback and optional capabilities need one");
        }
    }
}
