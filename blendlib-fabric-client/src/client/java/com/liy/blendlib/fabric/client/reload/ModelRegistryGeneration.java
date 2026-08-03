package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One whole immutable model-registry generation.
 *
 * <p>The maps and diagnostics are immutable after construction. Retirement is a separate lifecycle bit so
 * existing snapshots can observe that no new bindings may use this generation.</p>
 */
public final class ModelRegistryGeneration {
    private final long generationId;
    private final Map<BlendModelKey, ModelHandle> handles;
    private final Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics;
    private final List<BlendDiagnostic> diagnostics;
    private final int loadedBackendHandleCount;
    private final int missingBackendHandleCount;
    private final AtomicBoolean retired = new AtomicBoolean(false);

    public ModelRegistryGeneration(
            long generationId,
            Map<BlendModelKey, ? extends ModelHandle> handles,
            Map<BlendModelKey, BlendDiagnostic> primaryDiagnostics,
            List<BlendDiagnostic> globalDiagnostics) {
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must be non-negative");
        }
        this.generationId = generationId;
        this.handles = immutableHandles(handles, generationId);
        this.loadedBackendHandleCount = countLoadedHandles(this.handles);
        this.missingBackendHandleCount = this.handles.size() - loadedBackendHandleCount;
        this.primaryDiagnostics = immutableDiagnostics(primaryDiagnostics);

        List<BlendDiagnostic> allDiagnostics = new ArrayList<>(this.primaryDiagnostics.values());
        allDiagnostics.addAll(List.copyOf(Objects.requireNonNull(globalDiagnostics, "globalDiagnostics")));
        this.diagnostics = List.copyOf(allDiagnostics);
    }

    public static ModelRegistryGeneration empty(long generationId) {
        return new ModelRegistryGeneration(generationId, Map.of(), Map.of(), List.of());
    }

    public long generationId() {
        return generationId;
    }

    public Map<BlendModelKey, ModelHandle> handles() {
        return handles;
    }

    /**
     * Hot-path-safe immutable lookup.
     *
     * <p>A caller that receives an empty result must bind an explicit {@link MissingModelHandle} outside renderer
     * submit code; the registry deliberately does not allocate a fallback during lookup.</p>
     */
    public Optional<ModelHandle> find(BlendModelKey key) {
        return Optional.ofNullable(handles.get(Objects.requireNonNull(key, "key")));
    }

    public Optional<BlendDiagnostic> primaryDiagnostic(BlendModelKey key) {
        return Optional.ofNullable(primaryDiagnostics.get(Objects.requireNonNull(key, "key")));
    }

    public List<BlendDiagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean isRetired() {
        return retired.get();
    }

    /**
     * Marks this immutable generation as unavailable for new bindings.
     *
     * <p>Retirement deliberately keeps the immutable handle map intact. A render snapshot that already captured a
     * handle remains self-consistent until its own owner releases it; only registry ownership changes.</p>
     *
     * @return {@code true} only for the transition from active to retired
     */
    boolean retire() {
        return retired.compareAndSet(false, true);
    }

    int backendHandleCount() {
        return handles.size();
    }

    int loadedBackendHandleCount() {
        return loadedBackendHandleCount;
    }

    int missingBackendHandleCount() {
        return missingBackendHandleCount;
    }

    private static Map<BlendModelKey, ModelHandle> immutableHandles(
            Map<BlendModelKey, ? extends ModelHandle> input,
            long generationId) {
        Objects.requireNonNull(input, "handles");
        Map<BlendModelKey, ModelHandle> copy = new LinkedHashMap<>();
        input.forEach((key, handle) -> {
            BlendModelKey checkedKey = Objects.requireNonNull(key, "handle key");
            ModelHandle checkedHandle = Objects.requireNonNull(handle, "handle");
            if (!checkedKey.equals(checkedHandle.key()) || checkedHandle.generationId() != generationId) {
                throw new IllegalArgumentException("Every handle must belong to this generation and map key");
            }
            copy.put(checkedKey, checkedHandle);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<BlendModelKey, BlendDiagnostic> immutableDiagnostics(Map<BlendModelKey, BlendDiagnostic> input) {
        Objects.requireNonNull(input, "primaryDiagnostics");
        Map<BlendModelKey, BlendDiagnostic> copy = new LinkedHashMap<>();
        input.forEach((key, diagnostic) -> copy.put(
                Objects.requireNonNull(key, "diagnostic key"),
                Objects.requireNonNull(diagnostic, "diagnostic")));
        return Collections.unmodifiableMap(copy);
    }

    private static int countLoadedHandles(Map<BlendModelKey, ModelHandle> handles) {
        int loaded = 0;
        for (ModelHandle handle : handles.values()) {
            if (!handle.missing()) {
                loaded++;
            }
        }
        return loaded;
    }
}
