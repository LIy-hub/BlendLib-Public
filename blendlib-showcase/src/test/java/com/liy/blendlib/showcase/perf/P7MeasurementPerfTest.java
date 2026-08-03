package com.liy.blendlib.showcase.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class P7MeasurementPerfTest {
    @Test
    void nearestRankPercentilesAndPeakObservationsAreReproducible() {
        P7MeasurementSession session = new P7MeasurementSession(100);
        for (int sample = 0; sample < 100; sample++) {
            long value = sample + 1L;
            session.record(new P7MeasurementRecord(
                    sample,
                    new P7FrameTiming(value, value, value),
                    new P7AllocationObservation(value * 10L, P7AllocationObservation.Source.JFR),
                    new P7CacheObservation(sample, 128, sample, sample, 0L, sample + 1, 2),
                    7L,
                    6,
                    0));
        }

        P7MeasurementSummary summary = session.summarize();
        assertEquals(100, summary.sampleCount());
        assertEquals(new P7MetricPercentiles(50L, 95L), summary.frameTimeNanos());
        assertEquals(new P7MetricPercentiles(50L, 95L), summary.animationPreparationNanos());
        assertEquals(new P7MetricPercentiles(50L, 95L), summary.submitCpuNanos());
        assertEquals(new P7MetricPercentiles(500L, 950L), summary.liveAllocationBytes());
        assertEquals(99, summary.peakPoseCacheEntries());
        assertEquals(128, summary.poseCacheCapacity());
        assertEquals(100, summary.peakTrackedAnimationInstances());
        assertEquals(2, summary.peakPreparedAnimationAssets());
    }

    @Test
    void sessionIsBoundedAndRequiresContiguousSampleIndexes() {
        P7MeasurementSession session = new P7MeasurementSession(1);
        P7MeasurementRecord first = record(0);
        session.record(first);
        assertEquals(true, session.complete());
        assertThrows(IllegalStateException.class, () -> session.record(record(1)));

        P7MeasurementSession ordering = new P7MeasurementSession(2);
        assertThrows(IllegalArgumentException.class, () -> ordering.record(record(1)));
    }

    @Test
    void timingStampsCannotReverseInstrumentationOrder() {
        assertEquals(new P7FrameTiming(60L, 10L, 20L), P7FrameTiming.fromStamps(0L, 10L, 20L, 30L, 50L, 60L));
        assertThrows(IllegalArgumentException.class, () -> P7FrameTiming.fromStamps(0L, 20L, 10L, 30L, 40L, 50L));
    }

    private static P7MeasurementRecord record(int index) {
        return new P7MeasurementRecord(
                index,
                new P7FrameTiming(1L, 1L, 1L),
                new P7AllocationObservation(1L, P7AllocationObservation.Source.PROFILER),
                new P7CacheObservation(0, 1, 0L, 0L, 0L, 0, 0),
                0L,
                0,
                0);
    }
}
