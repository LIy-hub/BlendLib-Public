package com.liy.blendlib.showcase.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, append-only capture session for the prescribed P7 sample window. */
public final class P7MeasurementSession {
    private final int maximumSamples;
    private final List<P7MeasurementRecord> records = new ArrayList<>();

    public P7MeasurementSession(int maximumSamples) {
        if (maximumSamples <= 0) {
            throw new IllegalArgumentException("maximumSamples must be positive");
        }
        this.maximumSamples = maximumSamples;
    }

    public static P7MeasurementSession standard() {
        return new P7MeasurementSession(P7ReferenceScenario.SAMPLE_FRAME_COUNT);
    }

    public void record(P7MeasurementRecord record) {
        P7MeasurementRecord checked = Objects.requireNonNull(record, "record");
        if (records.size() >= maximumSamples) {
            throw new IllegalStateException("P7 measurement session reached its bounded sample count");
        }
        if (checked.sampleIndex() != records.size()) {
            throw new IllegalArgumentException("P7 measurement samples must be recorded in contiguous order");
        }
        records.add(checked);
    }

    public int size() {
        return records.size();
    }

    public boolean complete() {
        return records.size() == maximumSamples;
    }

    public List<P7MeasurementRecord> records() {
        return List.copyOf(records);
    }

    public P7MeasurementSummary summarize() {
        return P7MeasurementSummary.summarize(records);
    }
}
