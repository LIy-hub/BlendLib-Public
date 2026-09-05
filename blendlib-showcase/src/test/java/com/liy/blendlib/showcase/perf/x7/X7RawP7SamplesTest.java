package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class X7RawP7SamplesTest {
    @Test
    void exact1800FrameCanonicalRawArtifactRoundTripsAndRecomputesEveryMetric() {
        List<X7RawP7Samples.Sample> samples = new ArrayList<>(1_800);
        for (int index = 0; index < 1_800; index++) {
            samples.add(new X7RawP7Samples.Sample(60.0d, 16.0d, 4.0d, 1_024.0d, 100, 25));
        }

        byte[] canonical = X7RawP7Samples.canonicalUtf8(samples);
        X7RawP7Samples.RawSamples decoded = X7RawP7Samples.parseCanonical(canonical);

        assertEquals(samples, decoded.samples());
        assertEquals(new X7BenchmarkEvidence.Metrics(
                new X7BenchmarkEvidence.Percentiles(60.0d, 60.0d, 60.0d),
                new X7BenchmarkEvidence.Percentiles(16.0d, 16.0d, 16.0d),
                new X7BenchmarkEvidence.Percentiles(4.0d, 4.0d, 4.0d),
                new X7BenchmarkEvidence.Percentiles(1_024.0d, 1_024.0d, 1_024.0d)), decoded.metrics());
    }

    @Test
    void rawArtifactRejectsOneFrameShortOrAnyPerFrameSubmissionDrift() {
        List<X7RawP7Samples.Sample> tooShort = new ArrayList<>(1_799);
        for (int index = 0; index < 1_799; index++) {
            tooShort.add(new X7RawP7Samples.Sample(60.0d, 16.0d, 4.0d, 1_024.0d, 100, 25));
        }
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7RawP7Samples.canonicalUtf8(tooShort));

        List<X7RawP7Samples.Sample> exact = new ArrayList<>(1_800);
        for (int index = 0; index < 1_800; index++) {
            exact.add(new X7RawP7Samples.Sample(60.0d, 16.0d, 4.0d, 1_024.0d, 100, 25));
        }
        String mutated = new String(X7RawP7Samples.canonicalUtf8(exact), StandardCharsets.UTF_8)
                .replace(",100,25\n", ",99,25\n");
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7RawP7Samples.parseCanonical(mutated.getBytes(StandardCharsets.UTF_8)));
    }
}
