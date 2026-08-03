package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable set of adapter-side CPU observations collected during an explicit render capture.
 *
 * <p>Frame cadence and JFR allocation are intentionally not inferred here: the client benchmark
 * owner records those externally at the Fabric render-event boundary. This separation keeps the
 * renderer submit path free from file I/O, parsing, and benchmark-report formatting.</p>
 */
public record ClientRenderMeasurementSnapshot(
        long animationPreparationNanos,
        long submitCpuNanos,
        ClientAnimationRuntimeMetrics animationRuntime,
        Map<BlendModelKey, Integer> submittedModelCounts) {
    public ClientRenderMeasurementSnapshot {
        if (animationPreparationNanos < 0L || submitCpuNanos < 0L) {
            throw new IllegalArgumentException("client render measurement durations must be non-negative");
        }
        animationRuntime = Objects.requireNonNull(animationRuntime, "animationRuntime");
        submittedModelCounts = Map.copyOf(Objects.requireNonNull(submittedModelCounts, "submittedModelCounts"));
        submittedModelCounts.forEach((modelKey, count) -> {
            Objects.requireNonNull(modelKey, "submitted model key");
            if (count == null || count <= 0) {
                throw new IllegalArgumentException("submitted model counts must be positive");
            }
        });
    }
}
