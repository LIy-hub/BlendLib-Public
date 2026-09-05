package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    /**
     * A generation has one permanent lifecycle identity.  The reference never returns to {@code null}; otherwise a
     * detached cleanup path could reuse a retired generation through a second registry owner.
     */
    private final AtomicReference<ClientGenerationResourceOwner> resourceOwner = new AtomicReference<>();
    /** Coordinates an unowned direct retirement with the one-way owner binding. */
    private final Object resourceOwnershipLock = new Object();

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
        ClientGenerationResourceOwner owner = resourceOwner.get();
        return owner == null ? retireIfStillUnowned() : owner.retire(this);
    }

    GenerationRenderResourceLease acquireRenderResourceLease(ModelHandle handle) {
        ClientGenerationResourceOwner owner = resourceOwner.get();
        if (owner == null) {
            throw new IllegalStateException("Generation has no resource lifecycle owner");
        }
        return owner.acquire(this, handle);
    }

    GenerationRenderResourceLease acquireExactRenderResourceLease(
            BlendModelKey key,
            ModelHandle expectedHandle,
            ModelRenderHandle expectedRenderHandle) {
        ClientGenerationResourceOwner owner = resourceOwner.get();
        if (owner == null) {
            throw new IllegalStateException("Generation has no resource lifecycle owner");
        }
        return owner.acquireExact(this, key, expectedHandle, expectedRenderHandle);
    }

    /** Revalidates a source-owned absent or map-owned missing sample without acquiring a count. */
    void requireExactCurrentMissingBinding(
            BlendModelKey key, ModelHandle expectedHandle, ModelRenderHandle expectedRenderHandle) {
        ClientGenerationResourceOwner owner = resourceOwner.get();
        if (owner == null) {
            throw new IllegalStateException("Generation has no resource lifecycle owner");
        }
        owner.requireExactCurrentMissing(this, key, expectedHandle, expectedRenderHandle);
    }

    boolean retryFailedRenderResourceClose() {
        ClientGenerationResourceOwner owner = resourceOwner.get();
        return owner != null && owner.retryFailedClose(this);
    }

    /**
     * Claims this generation for exactly one owner.  A successful binding is permanent; same-owner calls are
     * idempotent and a foreign owner is rejected without changing either owner.
     */
    boolean tryAttachResourceOwner(ClientGenerationResourceOwner owner) {
        ClientGenerationResourceOwner checkedOwner = Objects.requireNonNull(owner, "owner");
        synchronized (resourceOwnershipLock) {
            ClientGenerationResourceOwner existing = resourceOwner.get();
            if (existing == checkedOwner) {
                return true;
            }
            return existing == null && resourceOwner.compareAndSet(null, checkedOwner);
        }
    }

    boolean isAttachedToResourceOwner(ClientGenerationResourceOwner owner) {
        return resourceOwner.get() == owner;
    }

    boolean retireFromResourceOwner(ClientGenerationResourceOwner owner) {
        return resourceOwner.get() == owner && retired.compareAndSet(false, true);
    }

    /**
     * Retires an aborted candidate only when no registry has won its permanent owner identity.  The lock prevents a
     * foreign owner from binding between the ownership check and the retirement transition.
     */
    private boolean retireIfStillUnowned() {
        synchronized (resourceOwnershipLock) {
            return resourceOwner.get() == null && retired.compareAndSet(false, true);
        }
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
