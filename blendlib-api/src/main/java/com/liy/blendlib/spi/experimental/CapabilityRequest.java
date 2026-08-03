package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable request for one canonical capability and a bounded protocol-version range.
 *
 * @param capabilityId canonical capability identity represented by the existing pure key grammar
 * @param supportedVersions non-empty supported range
 * @param requirement required or optional behavior
 * @param fallback explicit semantic-equivalent fallback only for optional requests
 */
@ExperimentalBlendLibSpi
public record CapabilityRequest(
        BlendResourceId capabilityId,
        CapabilityVersionRange supportedVersions,
        CapabilityRequirement requirement,
        Optional<CapabilityFallback> fallback) {

    /** Validates requirement/fallback consistency without accepting a descriptor payload. */
    public CapabilityRequest {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        ExperimentalControlBoundary.requireId(capabilityId, "capabilityId");
        supportedVersions = Objects.requireNonNull(supportedVersions, "supportedVersions");
        requirement = Objects.requireNonNull(requirement, "requirement");
        fallback = Objects.requireNonNull(fallback, "fallback");
        if (requirement == CapabilityRequirement.REQUIRED && fallback.isPresent()) {
            throw new IllegalArgumentException("Required capability requests cannot declare a fallback");
        }
        if (requirement == CapabilityRequirement.OPTIONAL && fallback.isEmpty()) {
            throw new IllegalArgumentException("Optional capability requests require an explicit safe fallback");
        }
    }

    /**
     * Creates a required capability request.
     *
     * @param capabilityId canonical requested capability id
     * @param supportedVersions bounded compatible protocol range
     * @return immutable required request
     */
    public static CapabilityRequest required(BlendResourceId capabilityId, CapabilityVersionRange supportedVersions) {
        return new CapabilityRequest(capabilityId, supportedVersions, CapabilityRequirement.REQUIRED, Optional.empty());
    }

    /**
     * Creates an optional capability request with an explicit semantic-equivalent fallback.
     *
     * @param capabilityId canonical requested capability id
     * @param supportedVersions bounded compatible protocol range
     * @param fallback explicit semantic-equivalent fallback
     * @return immutable optional request
     */
    public static CapabilityRequest optional(
            BlendResourceId capabilityId,
            CapabilityVersionRange supportedVersions,
            CapabilityFallback fallback) {
        return new CapabilityRequest(capabilityId, supportedVersions, CapabilityRequirement.OPTIONAL,
                Optional.of(Objects.requireNonNull(fallback, "fallback")));
    }
}
