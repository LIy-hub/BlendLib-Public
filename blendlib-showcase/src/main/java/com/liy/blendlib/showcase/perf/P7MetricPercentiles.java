package com.liy.blendlib.showcase.perf;

/** P50 and P95 values using the documented nearest-rank convention. */
public record P7MetricPercentiles(long p50, long p95) {
    public P7MetricPercentiles {
        if (p50 < 0L || p95 < 0L || p95 < p50) {
            throw new IllegalArgumentException("P7 percentiles must be ordered non-negative values");
        }
    }
}
