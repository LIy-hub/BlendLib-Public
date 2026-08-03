package com.liy.blendlib.showcase.perf;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import java.util.Objects;

/**
 * Client-side collector for a real P7 reference-scene capture.
 *
 * <p>This class deliberately consumes only the public 26.1.2 client facade. Instrumentation
 * supplies timing, JFR/profiler allocation, and cache observations; the harness adds a
 * generation-scoped public model-handle observation. It is dormant until an explicit benchmark
 * integration creates it, so it cannot alter normal Showcase rendering or fabricate visual or
 * performance evidence.</p>
 */
public final class P7ClientMeasurementHarness {
    private final P7MeasurementSession session;

    public P7ClientMeasurementHarness(P7MeasurementSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** Creates the bounded 1,800-sample collector prescribed by the P7 reference scenario. */
    public static P7ClientMeasurementHarness standard() {
        return new P7ClientMeasurementHarness(P7MeasurementSession.standard());
    }

    /**
     * Captures one already-instrumented frame. The handle counts are read through the immutable
     * public registry snapshot, never through a reload, parser, renderer implementation, or
     * Minecraft singleton.
     */
    public P7MeasurementRecord capture(
            P7FrameTiming timing,
            P7AllocationObservation allocation,
            P7CacheObservation cache) {
        ClientRegistryView registry = BlendLibClientServices.models().snapshot();
        long missingHandles = registry.models().values().stream().filter(model -> model.missing()).count();
        int missingHandleCount = Math.toIntExact(missingHandles);
        P7MeasurementRecord record = new P7MeasurementRecord(
                session.size(),
                Objects.requireNonNull(timing, "timing"),
                Objects.requireNonNull(allocation, "allocation"),
                Objects.requireNonNull(cache, "cache"),
                registry.generationId(),
                registry.models().size(),
                missingHandleCount);
        session.record(record);
        return record;
    }

    public int sampleCount() {
        return session.size();
    }

    public boolean complete() {
        return session.complete();
    }

    public P7MeasurementSummary summarize() {
        return session.summarize();
    }
}
