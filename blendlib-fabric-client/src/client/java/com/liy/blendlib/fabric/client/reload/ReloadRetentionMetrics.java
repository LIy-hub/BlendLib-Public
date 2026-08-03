package com.liy.blendlib.fabric.client.reload;

/**
 * Internal immutable observation of client model-registry generation retention.
 *
 * <p>Counts describe ownership by {@link ClientModelRegistry}, not references owned by immutable extraction or
 * render snapshots. The registry keeps only its active generation; a replaced or stale candidate is marked retired
 * but is never added to a registry retention collection. This lets regression tests distinguish legitimate snapshot
 * lifetime from a registry-side backend-handle leak without exposing a public metrics API.</p>
 */
record ReloadRetentionMetrics(
        long activeGenerationId,
        int activeBackendHandleCount,
        int activeLoadedBackendHandleCount,
        int activeMissingBackendHandleCount,
        int registryRetainedRetiredBackendHandleCount,
        int mostRecentlyRetiredBackendHandleCount,
        long retiredGenerationCount,
        long staleGenerationCount,
        int peakActiveBackendHandleCount) {
    ReloadRetentionMetrics {
        if (activeGenerationId < 0L) {
            throw new IllegalArgumentException("activeGenerationId must be non-negative");
        }
        if (activeBackendHandleCount < 0
                || activeLoadedBackendHandleCount < 0
                || activeMissingBackendHandleCount < 0
                || registryRetainedRetiredBackendHandleCount < 0
                || mostRecentlyRetiredBackendHandleCount < 0
                || retiredGenerationCount < 0L
                || staleGenerationCount < 0L
                || peakActiveBackendHandleCount < 0) {
            throw new IllegalArgumentException("reload retention metrics must be non-negative");
        }
        if (activeLoadedBackendHandleCount + activeMissingBackendHandleCount != activeBackendHandleCount) {
            throw new IllegalArgumentException("active loaded and missing handle counts must equal the active total");
        }
        if (staleGenerationCount > retiredGenerationCount) {
            throw new IllegalArgumentException("stale generations must be a subset of retired generations");
        }
        if (peakActiveBackendHandleCount < activeBackendHandleCount) {
            throw new IllegalArgumentException("peak active backend handles cannot be below the active count");
        }
    }
}
