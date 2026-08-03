package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable, version-bounded required or optional capability declaration. */
public record ExperimentalCapabilityRequirement(
        BlendResourceId id,
        ExperimentalSemVer minInclusive,
        ExperimentalSemVer maxExclusive,
        boolean required,
        OptionalCapabilityFallback fallback) {
    public ExperimentalCapabilityRequirement {
        id = Objects.requireNonNull(id, "id");
        minInclusive = Objects.requireNonNull(minInclusive, "minInclusive");
        maxExclusive = Objects.requireNonNull(maxExclusive, "maxExclusive");
        fallback = Objects.requireNonNull(fallback, "fallback");
        if (minInclusive.compareTo(maxExclusive) >= 0) {
            throw new IllegalArgumentException("Capability version range must be non-empty");
        }
        if (required && fallback != OptionalCapabilityFallback.FAIL_CLOSED) {
            throw new IllegalArgumentException("Required capabilities must fail closed");
        }
        if (!required && fallback == OptionalCapabilityFallback.FAIL_CLOSED) {
            throw new IllegalArgumentException("Optional capabilities must declare an explicit fallback");
        }
    }

    public boolean includes(ExperimentalSemVer version) {
        return minInclusive.compareTo(version) <= 0 && version.compareTo(maxExclusive) < 0;
    }
}
