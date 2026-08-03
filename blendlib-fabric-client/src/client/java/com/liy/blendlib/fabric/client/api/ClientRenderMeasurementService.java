package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.fabric.client.perf.ClientRenderMeasurementCollector;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Explicit opt-in adapter service for a client benchmark or diagnostics harness.
 *
 * <p>No normal renderer registration starts a capture. While inactive, submit and animation
 * extraction perform only one volatile-state check; while active, this service supplies immutable
 * CPU/cache observations at a caller-owned frame boundary. It exposes no Minecraft or raw GL
 * object.</p>
 */
public final class ClientRenderMeasurementService {
    private final Supplier<ClientAnimationRuntimeMetrics> animationMetrics;

    public ClientRenderMeasurementService(Supplier<ClientAnimationRuntimeMetrics> animationMetrics) {
        this.animationMetrics = Objects.requireNonNull(animationMetrics, "animationMetrics");
    }

    /** Starts a fresh capture on the current client render/extraction thread. */
    public void beginCapture() {
        ClientRenderMeasurementCollector.beginCapture();
    }

    /**
     * Returns CPU/cache observations accumulated since the preceding call on this render thread.
     * The caller must supply the actual frame cadence and any JFR/profiler allocation evidence.
     */
    public Optional<ClientRenderMeasurementSnapshot> completeFrame() {
        return ClientRenderMeasurementCollector.completeFrame(animationMetrics);
    }

    /** Stops the opt-in capture and clears thread-local counters for the current thread. */
    public void endCapture() {
        ClientRenderMeasurementCollector.endCapture();
    }

    /** Whether a benchmark or diagnostic owner has currently enabled capture. */
    public boolean capturing() {
        return ClientRenderMeasurementCollector.capturing();
    }
}
