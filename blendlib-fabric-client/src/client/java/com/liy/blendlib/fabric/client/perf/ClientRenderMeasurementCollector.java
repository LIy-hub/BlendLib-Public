package com.liy.blendlib.fabric.client.perf;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientAnimationRuntimeMetrics;
import com.liy.blendlib.fabric.client.api.ClientRenderMeasurementSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter-private, thread-local CPU-duration collector behind the public immutable measurement
 * service.
 *
 * <p>It is deliberately disabled by default. The render and extraction hot paths make no timing
 * call unless an explicit benchmark enables capture; this collector never reads resources, parses
 * assets, emits a report, or calls raw OpenGL.</p>
 */
public final class ClientRenderMeasurementCollector {
    private static final long NOT_CAPTURING = -1L;
    private static final ThreadLocal<FrameCounters> COUNTERS = ThreadLocal.withInitial(FrameCounters::new);
    private static volatile boolean capturing;

    private ClientRenderMeasurementCollector() {
    }

    /** Enables a fresh capture for the current rendering/extraction thread. */
    public static void beginCapture() {
        COUNTERS.get().clear();
        capturing = true;
    }

    /** Stops capture globally and clears current-thread counters. */
    public static void endCapture() {
        capturing = false;
        COUNTERS.get().clear();
    }

    /** Whether an explicit capture is currently enabled. */
    public static boolean capturing() {
        return capturing;
    }

    /** Starts one extraction-side animation-preparation duration only while capture is active. */
    public static long startAnimationPreparation() {
        return capturing ? System.nanoTime() : NOT_CAPTURING;
    }

    /** Completes an extraction-side animation-preparation duration. */
    public static void finishAnimationPreparation(long startedNanos) {
        recordDuration(startedNanos, true);
    }

    /** Starts one adapter submit duration only while capture is active. */
    public static long startSubmit() {
        return capturing ? System.nanoTime() : NOT_CAPTURING;
    }

    /** Completes one adapter submit duration. */
    public static void finishSubmit(long startedNanos, BlendModelKey modelKey) {
        Objects.requireNonNull(modelKey, "modelKey");
        recordSubmitDuration(startedNanos, modelKey);
    }

    /**
     * Drains observations accumulated on this thread since the preceding frame boundary.
     * Different-thread records are intentionally not merged, because a Fabric level-render frame
     * is owned by one client render/extraction thread.
     */
    public static Optional<ClientRenderMeasurementSnapshot> completeFrame(
            Supplier<ClientAnimationRuntimeMetrics> animationMetrics) {
        Objects.requireNonNull(animationMetrics, "animationMetrics");
        if (!capturing) {
            return Optional.empty();
        }
        FrameCounters counters = COUNTERS.get();
        ClientRenderMeasurementSnapshot snapshot = new ClientRenderMeasurementSnapshot(
                counters.animationPreparationNanos,
                counters.submitCpuNanos,
                Objects.requireNonNull(animationMetrics.get(), "animationMetrics result"),
                counters.submittedModelCounts);
        counters.clear();
        return Optional.of(snapshot);
    }

    static void resetForTests() {
        capturing = false;
        COUNTERS.remove();
    }

    private static void recordDuration(long startedNanos, boolean animation) {
        if (startedNanos == NOT_CAPTURING || !capturing) {
            return;
        }
        long duration = Math.max(0L, System.nanoTime() - startedNanos);
        FrameCounters counters = COUNTERS.get();
        if (animation) {
            counters.animationPreparationNanos = Math.addExact(counters.animationPreparationNanos, duration);
        } else {
            counters.submitCpuNanos = Math.addExact(counters.submitCpuNanos, duration);
        }
    }

    private static void recordSubmitDuration(long startedNanos, BlendModelKey modelKey) {
        if (startedNanos == NOT_CAPTURING || !capturing) {
            return;
        }
        long duration = Math.max(0L, System.nanoTime() - startedNanos);
        FrameCounters counters = COUNTERS.get();
        counters.submitCpuNanos = Math.addExact(counters.submitCpuNanos, duration);
        counters.submittedModelCounts.merge(modelKey, 1, Math::addExact);
    }

    private static final class FrameCounters {
        private long animationPreparationNanos;
        private long submitCpuNanos;
        private final Map<BlendModelKey, Integer> submittedModelCounts = new HashMap<>();

        private void clear() {
            animationPreparationNanos = 0L;
            submitCpuNanos = 0L;
            submittedModelCounts.clear();
        }
    }
}
