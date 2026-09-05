package com.liy.blendlib.fabric.client.host;

import java.util.Objects;

/**
 * Immutable local ownership diagnostic for one X4 host adapter.
 *
 * <p>The counts describe caller-owned X4 leases and submit operations only. The injected
 * generation session remains integration-owned; its {@code ProviderLease} is held once for each
 * listed snapshot lease and is released exactly when that lease drains.</p>
 */
public record X4HostLeaseDiagnostics(
        X4HostLifecycleState state,
        long generation,
        int activeSnapshotLeases,
        int inFlightSubmissions,
        boolean retirementRequested,
        boolean closeRequested,
        String terminalFailureType) {
    public X4HostLeaseDiagnostics {
        state = Objects.requireNonNull(state, "state");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        if (activeSnapshotLeases < 0 || inFlightSubmissions < 0) {
            throw new IllegalArgumentException("X4 lease diagnostics cannot contain negative counts");
        }
        terminalFailureType = terminalFailureType == null ? "" : terminalFailureType;
    }
}
