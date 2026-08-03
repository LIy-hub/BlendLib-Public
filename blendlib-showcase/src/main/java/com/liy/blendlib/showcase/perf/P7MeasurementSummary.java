package com.liy.blendlib.showcase.perf;

import java.util.List;
import java.util.Objects;

/**
 * A descriptive summary of a real-client capture. It intentionally has no PASS boolean because
 * the P7 gate additionally requires visual and environment evidence outside this pure math type.
 */
public record P7MeasurementSummary(
        int sampleCount,
        P7MetricPercentiles frameTimeNanos,
        P7MetricPercentiles animationPreparationNanos,
        P7MetricPercentiles submitCpuNanos,
        P7MetricPercentiles liveAllocationBytes,
        int peakPoseCacheEntries,
        int poseCacheCapacity,
        int peakTrackedAnimationInstances,
        int peakPreparedAnimationAssets,
        int peakModelHandleCount,
        int peakMissingModelHandleCount) {
    public P7MeasurementSummary {
        if (sampleCount <= 0 || peakPoseCacheEntries < 0 || poseCacheCapacity <= 0
                || peakPoseCacheEntries > poseCacheCapacity || peakTrackedAnimationInstances < 0
                || peakPreparedAnimationAssets < 0 || peakModelHandleCount < 0 || peakMissingModelHandleCount < 0
                || peakMissingModelHandleCount > peakModelHandleCount) {
            throw new IllegalArgumentException("P7 summary observations are invalid");
        }
        frameTimeNanos = Objects.requireNonNull(frameTimeNanos, "frameTimeNanos");
        animationPreparationNanos = Objects.requireNonNull(animationPreparationNanos, "animationPreparationNanos");
        submitCpuNanos = Objects.requireNonNull(submitCpuNanos, "submitCpuNanos");
        liveAllocationBytes = Objects.requireNonNull(liveAllocationBytes, "liveAllocationBytes");
    }

    /**
     * Uses nearest rank: rank {@code ceil(p * n)}, indexed from one. This makes the exact P50/P95
     * convention reproducible across JFR/Profiler evidence exports and test fixtures.
     */
    public static P7MeasurementSummary summarize(List<P7MeasurementRecord> records) {
        List<P7MeasurementRecord> checked = List.copyOf(Objects.requireNonNull(records, "records"));
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("At least one P7 measurement record is required");
        }
        long[] frame = new long[checked.size()];
        long[] animation = new long[checked.size()];
        long[] submit = new long[checked.size()];
        long[] allocation = new long[checked.size()];
        int peakCacheEntries = 0;
        int cacheCapacity = 0;
        int peakTrackedInstances = 0;
        int peakPreparedAssets = 0;
        int peakHandles = 0;
        int peakMissingHandles = 0;
        for (int index = 0; index < checked.size(); index++) {
            P7MeasurementRecord record = checked.get(index);
            frame[index] = record.timing().frameNanos();
            animation[index] = record.timing().animationPreparationNanos();
            submit[index] = record.timing().submitCpuNanos();
            allocation[index] = record.allocation().liveAllocationBytes();
            peakCacheEntries = Math.max(peakCacheEntries, record.cache().poseCacheEntries());
            cacheCapacity = Math.max(cacheCapacity, record.cache().poseCacheCapacity());
            peakTrackedInstances = Math.max(peakTrackedInstances, record.cache().trackedAnimationInstances());
            peakPreparedAssets = Math.max(peakPreparedAssets, record.cache().preparedAnimationAssets());
            peakHandles = Math.max(peakHandles, record.modelHandleCount());
            peakMissingHandles = Math.max(peakMissingHandles, record.missingModelHandleCount());
        }
        return new P7MeasurementSummary(
                checked.size(),
                percentiles(frame),
                percentiles(animation),
                percentiles(submit),
                percentiles(allocation),
                peakCacheEntries,
                cacheCapacity,
                peakTrackedInstances,
                peakPreparedAssets,
                peakHandles,
                peakMissingHandles);
    }

    private static P7MetricPercentiles percentiles(long[] values) {
        java.util.Arrays.sort(values);
        return new P7MetricPercentiles(nearestRank(values, 0.50d), nearestRank(values, 0.95d));
    }

    private static long nearestRank(long[] sortedValues, double percentile) {
        int rank = (int) Math.ceil(percentile * sortedValues.length);
        return sortedValues[Math.max(0, rank - 1)];
    }
}
