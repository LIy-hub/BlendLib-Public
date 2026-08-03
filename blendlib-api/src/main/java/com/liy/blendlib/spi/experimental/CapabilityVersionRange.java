package com.liy.blendlib.spi.experimental;

import java.util.Objects;

/**
 * Strictly bounded half-open range of supported experimental capability protocol versions.
 *
 * @param minInclusive lowest compatible version
 * @param maxExclusive first incompatible version
 */
@ExperimentalBlendLibSpi
public record CapabilityVersionRange(CapabilityVersion minInclusive, CapabilityVersion maxExclusive) {
    /** Validates a non-empty bounded half-open version interval. */
    public CapabilityVersionRange {
        minInclusive = Objects.requireNonNull(minInclusive, "minInclusive");
        maxExclusive = Objects.requireNonNull(maxExclusive, "maxExclusive");
        if (minInclusive.compareTo(maxExclusive) >= 0) {
            throw new IllegalArgumentException("minInclusive must be lower than maxExclusive");
        }
    }

    /**
     * Tests whether a version belongs to this half-open range.
     *
     * @param version candidate protocol version
     * @return whether {@code minInclusive <= version < maxExclusive}
     */
    public boolean contains(CapabilityVersion version) {
        Objects.requireNonNull(version, "version");
        return version.compareTo(minInclusive) >= 0 && version.compareTo(maxExclusive) < 0;
    }
}
