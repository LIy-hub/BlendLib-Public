package com.liy.blendlib.showcase.perf;

import com.liy.blendlib.showcase.client.P7BenchmarkCaptureController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Writes local-only P7 benchmark evidence without assigning a Gate outcome. */
public final class P7BenchmarkReportWriter {
    private static final String FORMAT = "blendlib-showcase-p7-runtime-capture-v1";

    private P7BenchmarkReportWriter() {
    }

    public static Path writeCompleted(P7BenchmarkCaptureController.Completion completion) throws IOException {
        Objects.requireNonNull(completion, "completion");
        Path report = reportPath(completion.outputDirectory(), "complete");
        P7MeasurementSummary summary = completion.summary();
        StringBuilder json = new StringBuilder(2_048);
        begin(json, "RUNTIME_CAPTURE_COMPLETE");
        field(json, "status", "experimental", true);
        field(json, "gate_reason", "Runtime data is captured, but visual, Iris/Sodium, reload, and audit evidence remain required.", true);
        field(json, "jfr_file", completion.jfrFile().toAbsolutePath().normalize().toString(), true);
        numeric(json, "warmup_frames", P7ReferenceScenario.WARMUP_FRAME_COUNT, true);
        numeric(json, "sample_frames", summary.sampleCount(), true);
        numeric(json, "rigid_submits_per_sample", completion.rigidSubmitsPerSample(), true);
        numeric(json, "skinned_submits_per_sample", completion.skinnedSubmitsPerSample(), true);
        clientConditions(json, "client_conditions_at_capture_start", completion.clientConditionsAtStart(), true);
        objectStart(json, "frame_time_nanos");
        numeric(json, "p50", summary.frameTimeNanos().p50(), true);
        numeric(json, "p95", summary.frameTimeNanos().p95(), false);
        objectEnd(json, true);
        objectStart(json, "animation_preparation_nanos");
        numeric(json, "p50", summary.animationPreparationNanos().p50(), true);
        numeric(json, "p95", summary.animationPreparationNanos().p95(), false);
        objectEnd(json, true);
        objectStart(json, "submit_cpu_nanos");
        numeric(json, "p50", summary.submitCpuNanos().p50(), true);
        numeric(json, "p95", summary.submitCpuNanos().p95(), false);
        objectEnd(json, true);
        objectStart(json, "jfr_allocation_bytes");
        numeric(json, "p50", summary.liveAllocationBytes().p50(), true);
        numeric(json, "p95", summary.liveAllocationBytes().p95(), false);
        objectEnd(json, true);
        objectStart(json, "cache_peaks");
        numeric(json, "pose_entries", summary.peakPoseCacheEntries(), true);
        numeric(json, "pose_capacity", summary.poseCacheCapacity(), true);
        numeric(json, "tracked_animation_instances", summary.peakTrackedAnimationInstances(), true);
        numeric(json, "prepared_animation_assets", summary.peakPreparedAnimationAssets(), false);
        objectEnd(json, true);
        objectStart(json, "model_handles");
        numeric(json, "peak_total", summary.peakModelHandleCount(), true);
        numeric(json, "peak_missing", summary.peakMissingModelHandleCount(), false);
        objectEnd(json, false);
        end(json);
        write(report, json);
        return report;
    }

    public static Path writeInvalid(
            Path outputDirectory,
            String reason,
            String observation,
            P7ReferenceScenario.ClientConditions clientConditions) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Path report = reportPath(outputDirectory, "invalid");
        StringBuilder json = new StringBuilder(1_024);
        begin(json, "INVALID_OR_INCOMPLETE");
        field(json, "status", "experimental", true);
        field(json, "reason", Objects.requireNonNull(reason, "reason"), true);
        field(json, "last_scene_observation", Objects.requireNonNull(observation, "observation"), clientConditions != null);
        if (clientConditions == null) {
            booleanField(json, "client_conditions_observed", false, false);
        } else {
            clientConditions(json, "client_conditions_at_invalidation", clientConditions, false);
        }
        end(json);
        write(report, json);
        return report;
    }

    private static Path reportPath(Path outputDirectory, String state) throws IOException {
        Path checked = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        Files.createDirectories(checked);
        return checked.resolve("p7-runtime-" + state + "-" + System.currentTimeMillis() + ".json");
    }

    private static void write(Path report, StringBuilder json) throws IOException {
        Files.writeString(report, json, StandardCharsets.UTF_8);
    }

    private static void begin(StringBuilder json, String status) {
        json.append('{').append(System.lineSeparator());
        field(json, "format", FORMAT, true);
        field(json, "status", status, true);
    }

    private static void end(StringBuilder json) {
        json.append(System.lineSeparator()).append('}').append(System.lineSeparator());
    }

    private static void objectStart(StringBuilder json, String name) {
        indent(json).append(quote(name)).append(": {").append(System.lineSeparator());
    }

    private static void objectEnd(StringBuilder json, boolean trailingComma) {
        indent(json).append('}');
        if (trailingComma) {
            json.append(',');
        }
        json.append(System.lineSeparator());
    }

    private static void field(StringBuilder json, String name, String value, boolean trailingComma) {
        indent(json).append(quote(name)).append(": ").append(quote(value));
        if (trailingComma) {
            json.append(',');
        }
        json.append(System.lineSeparator());
    }

    private static void numeric(StringBuilder json, String name, long value, boolean trailingComma) {
        indent(json).append(quote(name)).append(": ").append(value);
        if (trailingComma) {
            json.append(',');
        }
        json.append(System.lineSeparator());
    }

    private static void decimal(StringBuilder json, String name, double value, boolean trailingComma) {
        indent(json).append(quote(name)).append(": ").append(value);
        if (trailingComma) {
            json.append(',');
        }
        json.append(System.lineSeparator());
    }

    private static void booleanField(StringBuilder json, String name, boolean value, boolean trailingComma) {
        indent(json).append(quote(name)).append(": ").append(value);
        if (trailingComma) {
            json.append(',');
        }
        json.append(System.lineSeparator());
    }

    private static void clientConditions(
            StringBuilder json,
            String name,
            P7ReferenceScenario.ClientConditions conditions,
            boolean trailingComma) {
        P7ReferenceScenario.ClientConditions checked = Objects.requireNonNull(conditions, "conditions");
        objectStart(json, name);
        numeric(json, "framebuffer_width", checked.framebufferWidth(), true);
        numeric(json, "framebuffer_height", checked.framebufferHeight(), true);
        numeric(json, "render_target_width", checked.renderTargetWidth(), true);
        numeric(json, "render_target_height", checked.renderTargetHeight(), true);
        field(json, "required_aspect_ratio", P7ReferenceScenario.CAPTURE_ASPECT_RATIO_WIDTH + ":"
                + P7ReferenceScenario.CAPTURE_ASPECT_RATIO_HEIGHT, true);
        numeric(json, "fov_degrees", checked.fovDegrees(), true);
        decimal(json, "fov_effect_scale", checked.fovEffectScale(), true);
        booleanField(json, "dynamic_fov_disabled", checked.dynamicFovDisabled(), true);
        numeric(json, "configured_render_distance_chunks", checked.configuredRenderDistanceChunks(), true);
        numeric(json, "effective_render_distance_chunks", checked.effectiveRenderDistanceChunks(), true);
        numeric(json, "required_minimum_render_distance_chunks",
                P7ReferenceScenario.MIN_CAPTURE_RENDER_DISTANCE_CHUNKS, true);
        booleanField(json, "contract_satisfied", checked.meetsCaptureContract(), false);
        objectEnd(json, trailingComma);
    }

    private static StringBuilder indent(StringBuilder json) {
        return json.append("  ");
    }

    private static String quote(String value) {
        String checked = Objects.requireNonNull(value, "value");
        StringBuilder quoted = new StringBuilder(checked.length() + 8).append('"');
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            switch (character) {
                case '"' -> quoted.append((char) 92).append('"');
                case (char) 92 -> quoted.append((char) 92).append((char) 92);
                case (char) 10 -> quoted.append((char) 92).append('n');
                case (char) 13 -> quoted.append((char) 92).append('r');
                case (char) 9 -> quoted.append((char) 92).append('t');
                default -> quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }
}
