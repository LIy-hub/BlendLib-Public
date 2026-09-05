package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLease;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Objects;

/** Read-only view of the atomically published client model generation. */
public interface ClientModelLookup {
    /** Returns an immutable point-in-time view of all discovered model handles. */
    ClientRegistryView snapshot();

    /** Resolves one semantic key against the current generation, returning a stable missing view when absent. */
    ClientModelView resolve(BlendModelKey modelKey);

    /**
     * Returns source-owned ownership for this key when this lookup manages the D1 registry.
     *
     * <p>External implementations retain source and binary compatibility: their default result is
     * the explicit unmanaged fallback, never a fabricated resource-owner lease. Callers that need
     * registry-backed ownership must treat a non-managed result as unavailable. Registry-backed
     * implementations sample and admit their own current raw handle; callers cannot use a view as
     * provenance for this operation.</p>
     */
    default ClientGenerationLease acquireGenerationLease(BlendModelKey modelKey) {
        Objects.requireNonNull(modelKey, "modelKey");
        return ClientGenerationLease.unavailable();
    }

    /**
     * Returns the source-bound composite required by managed X4/X6 ownership paths.
     *
     * <p>The default preserves compatibility for external lookups and deliberately carries only
     * the explicit no-resource fallback. A registry-backed implementation samples its own current
     * view and accepts the supplied prepared snapshot only after exact handle-identity validation;
     * callers never nominate a registry or a view as provenance. The snapshot itself is not a
     * registry-provenance proof when two registries share a raw handle; that proof is the trusted
     * lookup which issued the composite.</p>
     */
    default ClientGenerationLeaseBinding acquireGenerationLeaseBinding(
            BlendModelKey modelKey, ModelRenderSnapshot snapshot) {
        Objects.requireNonNull(modelKey, "modelKey");
        return ClientGenerationLeaseBinding.unavailable(snapshot);
    }
}
