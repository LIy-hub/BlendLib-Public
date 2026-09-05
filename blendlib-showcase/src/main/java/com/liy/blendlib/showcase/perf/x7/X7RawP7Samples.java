package com.liy.blendlib.showcase.perf.x7;

import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Canonical, bounded raw P7 frame samples retained independently from the summary envelope.
 *
 * <p>The format is intentionally simple line-oriented UTF-8 rather than another permissive JSON
 * dialect. It admits exactly the inherited 600 warm-up / 1,800 measured-frame contract, exactly
 * 100 rigid and 25 skinned submissions on every measured frame, and nearest-rank percentile
 * recomputation. The byte representation is canonical and is itself hashed by the artifact
 * manifest before any hardware status is considered.</p>
 */
public final class X7RawP7Samples {
    static final String FORMAT = "x7-p7-raw-samples-v1";
    private static final String COLUMNS = "fps,frame_time_ms,cpu_render_ms,allocation_bytes,rigid_submits,skinned_submits";
    private static final int HEADER_LINES = 7;

    private X7RawP7Samples() {
    }

    public static byte[] canonicalUtf8(List<Sample> samples) {
        RawSamples checked = new RawSamples(samples);
        StringBuilder output = new StringBuilder(checked.samples().size() * 48 + 256);
        appendLine(output, "format=" + FORMAT);
        appendLine(output, "warmup_frames=" + P7ReferenceScenario.WARMUP_FRAME_COUNT);
        appendLine(output, "sample_frames=" + P7ReferenceScenario.SAMPLE_FRAME_COUNT);
        appendLine(output, "rigid_submits_per_sample=" + P7ReferenceScenario.RIGID_INSTANCE_COUNT);
        appendLine(output, "skinned_submits_per_sample=" + P7ReferenceScenario.SKINNED_INSTANCE_COUNT);
        appendLine(output, "columns=" + COLUMNS);
        appendLine(output, "data");
        for (Sample sample : checked.samples()) {
            appendLine(output, X7BenchmarkEvidenceCodec.canonicalNumber(sample.fps()) + ","
                    + X7BenchmarkEvidenceCodec.canonicalNumber(sample.frameTimeMillis()) + ","
                    + X7BenchmarkEvidenceCodec.canonicalNumber(sample.cpuRenderMillis()) + ","
                    + X7BenchmarkEvidenceCodec.canonicalNumber(sample.allocationBytes()) + ","
                    + sample.rigidSubmissions() + "," + sample.skinnedSubmissions());
            if (output.length() > X7BenchmarkEvidenceCodec.MAX_INPUT_CHARS) {
                throw X7BenchmarkEvidenceCodec.error("raw P7 samples exceed the character limit");
            }
        }
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples exceed the byte limit");
        }
        return bytes;
    }

    public static RawSamples parseCanonical(byte[] bytes) {
        byte[] checked = Objects.requireNonNull(bytes, "bytes");
        if (checked.length > X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples exceed the byte limit");
        }
        String text = decodeUtf8(checked);
        if (text.length() > X7BenchmarkEvidenceCodec.MAX_INPUT_CHARS) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples exceed the character limit");
        }
        if (text.indexOf('\r') >= 0 || !text.endsWith("\n")) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples must use canonical LF line endings");
        }
        String[] lines = text.split("\n", -1);
        int expectedLines = HEADER_LINES + P7ReferenceScenario.SAMPLE_FRAME_COUNT + 1;
        if (lines.length != expectedLines || !lines[lines.length - 1].isEmpty()) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples have an unexpected line count");
        }
        requireLine(lines, 0, "format=" + FORMAT);
        requireLine(lines, 1, "warmup_frames=" + P7ReferenceScenario.WARMUP_FRAME_COUNT);
        requireLine(lines, 2, "sample_frames=" + P7ReferenceScenario.SAMPLE_FRAME_COUNT);
        requireLine(lines, 3, "rigid_submits_per_sample=" + P7ReferenceScenario.RIGID_INSTANCE_COUNT);
        requireLine(lines, 4, "skinned_submits_per_sample=" + P7ReferenceScenario.SKINNED_INSTANCE_COUNT);
        requireLine(lines, 5, "columns=" + COLUMNS);
        requireLine(lines, 6, "data");

        List<Sample> samples = new ArrayList<>(P7ReferenceScenario.SAMPLE_FRAME_COUNT);
        for (int index = 0; index < P7ReferenceScenario.SAMPLE_FRAME_COUNT; index++) {
            samples.add(parseSample(lines[HEADER_LINES + index], index));
        }
        RawSamples result = new RawSamples(samples);
        if (!Arrays.equals(checked, canonicalUtf8(result.samples()))) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples are not in canonical form");
        }
        return result;
    }

    private static void appendLine(StringBuilder output, String value) {
        output.append(value).append('\n');
    }

    private static void requireLine(String[] lines, int index, String expected) {
        if (!expected.equals(lines[index])) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples have an invalid header line " + index);
        }
    }

    private static Sample parseSample(String line, int index) {
        String[] fields = line.split(",", -1);
        if (fields.length != 6) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 sample " + index + " has an invalid field count");
        }
        return new Sample(
                canonicalFiniteNonNegative(fields[0], index, "fps"),
                canonicalFiniteNonNegative(fields[1], index, "frame_time_ms"),
                canonicalFiniteNonNegative(fields[2], index, "cpu_render_ms"),
                canonicalFiniteNonNegative(fields[3], index, "allocation_bytes"),
                canonicalExactInt(fields[4], index, "rigid_submits", P7ReferenceScenario.RIGID_INSTANCE_COUNT),
                canonicalExactInt(fields[5], index, "skinned_submits", P7ReferenceScenario.SKINNED_INSTANCE_COUNT));
    }

    private static double canonicalFiniteNonNegative(String token, int index, String field) {
        if (token.isEmpty() || token.length() > X7BenchmarkEvidenceCodec.MAX_NUMBER_CHARS) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 sample " + index + " has an invalid " + field + " token");
        }
        try {
            double value = new BigDecimal(token).doubleValue();
            if (!Double.isFinite(value) || value < 0.0d || Double.doubleToRawLongBits(value)
                    == Double.doubleToRawLongBits(-0.0d)
                    || !X7BenchmarkEvidenceCodec.canonicalNumber(value).equals(token)) {
                throw X7BenchmarkEvidenceCodec.error("raw P7 sample " + index + " has a non-canonical " + field);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 sample " + index + " has an invalid " + field + " number");
        }
    }

    private static int canonicalExactInt(String token, int index, String field, int expected) {
        try {
            int value = Integer.parseInt(token);
            if (!Integer.toString(value).equals(token) || value != expected) {
                throw X7BenchmarkEvidenceCodec.error("raw P7 sample " + index + " has an invalid " + field);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 sample " + index + " has an invalid " + field);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw X7BenchmarkEvidenceCodec.error("raw P7 samples are not well-formed UTF-8");
        }
    }

    public static final class RawSamples {
        private final List<Sample> samples;

        private RawSamples(List<Sample> samples) {
            this.samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
            if (this.samples.size() != P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
                throw X7BenchmarkEvidenceCodec.error("raw P7 samples must contain exactly 1,800 frames");
            }
        }

        public List<Sample> samples() {
            return samples;
        }

        public X7BenchmarkEvidence.Metrics metrics() {
            return new X7BenchmarkEvidence.Metrics(
                    percentiles(samples, Metric.FPS),
                    percentiles(samples, Metric.FRAME_TIME),
                    percentiles(samples, Metric.CPU_RENDER),
                    percentiles(samples, Metric.ALLOCATION));
        }
    }

    public record Sample(
            double fps,
            double frameTimeMillis,
            double cpuRenderMillis,
            double allocationBytes,
            int rigidSubmissions,
            int skinnedSubmissions) {
        public Sample {
            for (double value : List.of(fps, frameTimeMillis, cpuRenderMillis, allocationBytes)) {
                if (!Double.isFinite(value) || value < 0.0d || Double.doubleToRawLongBits(value)
                        == Double.doubleToRawLongBits(-0.0d)) {
                    throw X7BenchmarkEvidenceCodec.error("raw P7 sample metrics must be finite, non-negative canonical doubles");
                }
            }
            if (!P7ReferenceScenario.hasExactTargetSubmissions(rigidSubmissions, skinnedSubmissions)) {
                throw X7BenchmarkEvidenceCodec.error("raw P7 sample must retain exact 100/25 submissions");
            }
        }
    }

    private enum Metric {
        FPS,
        FRAME_TIME,
        CPU_RENDER,
        ALLOCATION
    }

    private static X7BenchmarkEvidence.Percentiles percentiles(List<Sample> samples, Metric metric) {
        double[] values = new double[samples.size()];
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            values[index] = switch (metric) {
                case FPS -> sample.fps();
                case FRAME_TIME -> sample.frameTimeMillis();
                case CPU_RENDER -> sample.cpuRenderMillis();
                case ALLOCATION -> sample.allocationBytes();
            };
        }
        Arrays.sort(values);
        return new X7BenchmarkEvidence.Percentiles(
                nearestRank(values, 0.50d), nearestRank(values, 0.95d), nearestRank(values, 0.99d));
    }

    private static double nearestRank(double[] values, double percentile) {
        int rank = (int) Math.ceil(percentile * values.length);
        return values[Math.max(0, rank - 1)];
    }
}
