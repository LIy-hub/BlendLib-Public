package com.liy.blendlib.fabric.client.api;

/**
 * Opaque, exclusive owner lease for one client-render measurement capture.
 *
 * <p>Only {@link ClientRenderMeasurementService} can issue an implementation. A lease is bound
 * to its issuing service, one render thread, and a monotonically increasing in-process epoch.
 * It is generic telemetry ownership, not benchmark or hardware authority.</p>
 */
public interface ClientRenderMeasurementSession extends AutoCloseable {
    /** Monotonically increasing identity of this in-process exclusive lease. */
    long epoch();

    /** Drains exactly one completed render-frame observation for the lease owner. */
    ClientRenderMeasurementSnapshot completeFrame();

    /** Releases this lease. Only its exact issuing render thread may close it. */
    @Override
    void close();
}
