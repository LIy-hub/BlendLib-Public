package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically publishes complete client model generations and retires replaced generations. */
public final class ClientModelRegistry {
    private final AtomicReference<ModelRegistryGeneration> active;
    private final AtomicLong nextGenerationId;
    private final AtomicLong retiredGenerationCount = new AtomicLong();
    private final AtomicLong staleGenerationCount = new AtomicLong();
    private final AtomicInteger mostRecentlyRetiredBackendHandleCount = new AtomicInteger();
    private final AtomicInteger peakActiveBackendHandleCount;

    public ClientModelRegistry() {
        this(ModelRegistryGeneration.empty(0L));
    }

    ClientModelRegistry(ModelRegistryGeneration initialGeneration) {
        ModelRegistryGeneration checkedInitialGeneration = Objects.requireNonNull(initialGeneration, "initialGeneration");
        this.active = new AtomicReference<>(checkedInitialGeneration);
        this.nextGenerationId = new AtomicLong(checkedInitialGeneration.generationId());
        this.peakActiveBackendHandleCount = new AtomicInteger(checkedInitialGeneration.backendHandleCount());
    }

    /** Reserves a monotonic generation identifier before prepare begins. */
    public long reserveNextGenerationId() {
        return nextGenerationId.incrementAndGet();
    }

    public ModelRegistryGeneration current() {
        return active.get();
    }

    /**
     * Performs an allocation-free lookup in the active immutable generation.
     *
     * <p>Callers bind missing fallbacks before renderer submit; submit code must consume its already-built snapshot
     * and must not query this registry.</p>
     */
    public Optional<ModelHandle> find(BlendModelKey key) {
        return active.get().find(key);
    }

    /**
     * Atomically exposes a complete generation. A late stale prepare result is retired instead of replacing a newer
     * generation.
     */
    public ModelRegistryGeneration publish(ModelRegistryGeneration replacement) {
        ModelRegistryGeneration checkedReplacement = Objects.requireNonNull(replacement, "replacement");
        nextGenerationId.accumulateAndGet(checkedReplacement.generationId(), Math::max);
        while (true) {
            ModelRegistryGeneration previous = active.get();
            if (checkedReplacement.generationId() <= previous.generationId()) {
                if (checkedReplacement.retire()) {
                    recordRetirement(checkedReplacement, true);
                }
                return previous;
            }
            if (active.compareAndSet(previous, checkedReplacement)) {
                peakActiveBackendHandleCount.accumulateAndGet(
                        checkedReplacement.backendHandleCount(), Math::max);
                if (previous.retire()) {
                    recordRetirement(previous, false);
                }
                return checkedReplacement;
            }
        }
    }

    /**
     * Internal lifecycle observation for reload-retention regression tests.
     *
     * <p>The registry owns exactly one complete generation: {@link #current()}. Retired generations are never
     * retained in a registry collection, so their registry-owned backend-handle count is always zero. Immutable
     * snapshots can retain a previously captured generation independently; retiring one never clears its handles.
     * This method is package-private to avoid publishing an application metric/configuration API in v1.</p>
     */
    ReloadRetentionMetrics reloadRetentionMetrics() {
        ModelRegistryGeneration current = active.get();
        return new ReloadRetentionMetrics(
                current.generationId(),
                current.backendHandleCount(),
                current.loadedBackendHandleCount(),
                current.missingBackendHandleCount(),
                0,
                mostRecentlyRetiredBackendHandleCount.get(),
                retiredGenerationCount.get(),
                staleGenerationCount.get(),
                peakActiveBackendHandleCount.get());
    }

    private void recordRetirement(ModelRegistryGeneration retiredGeneration, boolean stale) {
        mostRecentlyRetiredBackendHandleCount.set(retiredGeneration.backendHandleCount());
        retiredGenerationCount.incrementAndGet();
        if (stale) {
            staleGenerationCount.incrementAndGet();
        }
    }
}
