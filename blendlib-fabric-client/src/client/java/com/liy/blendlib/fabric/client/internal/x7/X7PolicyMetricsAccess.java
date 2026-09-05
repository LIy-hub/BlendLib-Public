package com.liy.blendlib.fabric.client.internal.x7;

import java.util.Optional;

/**
 * Read-only completed-frame endpoint for the D1-owned X7 policy producer.
 *
 * <p>The current CPU/LOD0 candidate has no verified frame boundary: its only D1-local observation
 * uses the explicit {@code completedFrameId=-1} unavailable state.  That is not a completed frame,
 * so this endpoint remains empty rather than manufacturing a metrics value.  A later verified
 * frame-completion owner may publish only a fully immutable {@link X7PolicyMetricsView} here.</p>
 */
public final class X7PolicyMetricsAccess {
    private X7PolicyMetricsAccess() {
    }

    /** Returns the newest completed immutable frame observation, or empty when none exists. */
    public static Optional<X7PolicyMetricsView> latestCompleted() {
        return Optional.empty();
    }
}
