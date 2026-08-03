package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Immutable metadata offer made by one controlled provider for one capability.
 *
 * @param providerId canonical provider identity
 * @param capabilityId canonical capability identity
 * @param protocolVersion exactly offered protocol version
 * @param priority bounded deterministic ordering priority
 */
@ExperimentalBlendLibSpi
public record CapabilityOffer(
        BlendResourceId providerId,
        BlendResourceId capabilityId,
        CapabilityVersion protocolVersion,
        int priority) {

    /** Largest absolute provider claim priority. */
    public static final int MAX_ABSOLUTE_PRIORITY = 100_000;

    /** Validates canonical identities, protocol version, and bounded priority. */
    public CapabilityOffer {
        providerId = Objects.requireNonNull(providerId, "providerId");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        ExperimentalControlBoundary.requireId(providerId, "providerId");
        ExperimentalControlBoundary.requireId(capabilityId, "capabilityId");
        protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (priority < -MAX_ABSOLUTE_PRIORITY || priority > MAX_ABSOLUTE_PRIORITY) {
            throw new IllegalArgumentException("priority must be in [-" + MAX_ABSOLUTE_PRIORITY + ", "
                    + MAX_ABSOLUTE_PRIORITY + "]");
        }
    }
}
