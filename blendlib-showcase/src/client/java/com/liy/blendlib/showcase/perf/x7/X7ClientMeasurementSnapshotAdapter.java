package com.liy.blendlib.showcase.perf.x7;

import com.liy.blendlib.fabric.client.api.ClientAnimationRuntimeMetrics;
import com.liy.blendlib.fabric.client.api.ClientRenderMeasurementSnapshot;
import java.util.Objects;

/**
 * Client-only adapter from the existing public measurement snapshot to X7's CPU observation.
 *
 * <p>This class does not start capture, access Minecraft, inspect GPU state, infer frame timing,
 * write files, or issue any result. Its sole job is to make the already collected public
 * snapshot explicit at the X7 evidence boundary.</p>
 */
public final class X7ClientMeasurementSnapshotAdapter {
    private X7ClientMeasurementSnapshotAdapter() {
    }

    /** Converts one existing adapter-public snapshot without exposing mutable render state. */
    public static CpuRenderObservation from(ClientRenderMeasurementSnapshot snapshot) {
        ClientRenderMeasurementSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        ClientAnimationRuntimeMetrics runtime = checked.animationRuntime();
        if (!runtime.available()) {
            throw new IllegalArgumentException("X7 requires available client animation runtime metrics");
        }
        return new CpuRenderObservation(
                checked.animationPreparationNanos(),
                checked.submitCpuNanos(),
                runtime.poseCacheEntries(),
                runtime.poseCacheCapacity(),
                runtime.poseCacheHits(),
                runtime.poseCacheMisses(),
                runtime.poseCacheEvictions(),
                runtime.trackedAnimationInstances(),
                runtime.preparedAnimationAssets());
    }

    /** Immutable CPU/cache observation only; GPU timing and identity must come from genuine external capture evidence. */
    public record CpuRenderObservation(
            long animationPreparationNanos,
            long submitCpuNanos,
            int poseCacheEntries,
            int poseCacheCapacity,
            long poseCacheHits,
            long poseCacheMisses,
            long poseCacheEvictions,
            int trackedAnimationInstances,
            int preparedAnimationAssets) {
        public CpuRenderObservation {
            if (animationPreparationNanos < 0L || submitCpuNanos < 0L
                    || poseCacheCapacity <= 0 || poseCacheEntries < 0 || poseCacheEntries > poseCacheCapacity
                    || poseCacheHits < 0L || poseCacheMisses < 0L || poseCacheEvictions < 0L
                    || trackedAnimationInstances < 0 || preparedAnimationAssets < 0) {
                throw new IllegalArgumentException("X7 CPU observation is invalid");
            }
        }
    }
}
