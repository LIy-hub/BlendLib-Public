package com.liy.blendlib.showcase.perf;

import java.util.Objects;

/**
 * Allocation observation supplied by a real JFR or profiler capture.
 *
 * <p>The harness intentionally does not manufacture an allocation value from a unit test or a
 * forced GC. A measurement is valid only when its external source is recorded.</p>
 */
public record P7AllocationObservation(long liveAllocationBytes, Source source) {
    public P7AllocationObservation {
        if (liveAllocationBytes < 0L) {
            throw new IllegalArgumentException("liveAllocationBytes must be non-negative");
        }
        source = Objects.requireNonNull(source, "source");
    }

    public enum Source {
        JFR,
        PROFILER
    }
}
