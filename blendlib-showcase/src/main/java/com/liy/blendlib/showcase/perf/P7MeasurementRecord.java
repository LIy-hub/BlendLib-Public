package com.liy.blendlib.showcase.perf;

import java.util.Objects;

/** One durable measurement row; it does not imply that a performance gate has passed. */
public record P7MeasurementRecord(
        int sampleIndex,
        P7FrameTiming timing,
        P7AllocationObservation allocation,
        P7CacheObservation cache,
        long generationId,
        int modelHandleCount,
        int missingModelHandleCount) {
    public P7MeasurementRecord {
        if (sampleIndex < 0 || generationId < 0L || modelHandleCount < 0 || missingModelHandleCount < 0
                || missingModelHandleCount > modelHandleCount) {
            throw new IllegalArgumentException("P7 measurement record observations are invalid");
        }
        timing = Objects.requireNonNull(timing, "timing");
        allocation = Objects.requireNonNull(allocation, "allocation");
        cache = Objects.requireNonNull(cache, "cache");
    }
}
