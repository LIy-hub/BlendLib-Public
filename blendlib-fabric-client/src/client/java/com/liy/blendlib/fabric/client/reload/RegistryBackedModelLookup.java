package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reload-private adapter that is the sole production issuer of source-bound managed composites. */
final class RegistryBackedModelLookup implements ClientModelLookup {
    private final ClientModelRegistry registry;
    private final Runnable afterExactLeaseAdmission;

    RegistryBackedModelLookup(ClientModelRegistry registry) {
        this(registry, () -> { });
    }

    /** Package-private deterministic reload-race seam; production lookup construction uses no-op. */
    RegistryBackedModelLookup(ClientModelRegistry registry, Runnable afterExactLeaseAdmission) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.afterExactLeaseAdmission = Objects.requireNonNull(afterExactLeaseAdmission, "afterExactLeaseAdmission");
    }

    @Override
    public ClientRegistryView snapshot() {
        ModelRegistryGeneration generation = registry.current();
        Map<BlendModelKey, ClientModelView> models = new LinkedHashMap<>();
        generation.handles().forEach((key, handle) -> models.put(key, viewFor(generation, key, handle, true)));
        return new ClientRegistryView(
                generation.generationId(),
                models,
                generation.diagnostics().stream().map(RegistryBackedModelLookup::toClientDiagnostic).toList());
    }

    @Override
    public ClientModelView resolve(BlendModelKey modelKey) {
        BlendModelKey checkedKey = Objects.requireNonNull(modelKey, "modelKey");
        ModelRegistryGeneration generation = registry.current();
        Optional<ModelHandle> discovered = generation.find(checkedKey);
        ModelHandle handle = discovered.orElseGet(
                () -> MissingModelHandle.notDiscovered(checkedKey, generation.generationId()));
        return viewFor(generation, checkedKey, handle, discovered.isPresent());
    }

    @Override
    public ClientGenerationLease acquireGenerationLease(BlendModelKey modelKey) {
        return ClientGenerationLease.acquire(registry, this, Objects.requireNonNull(modelKey, "modelKey"));
    }

    @Override
    public ClientGenerationLeaseBinding acquireGenerationLeaseBinding(
            BlendModelKey modelKey, ModelRenderSnapshot snapshot) {
        BlendModelKey checkedKey = Objects.requireNonNull(modelKey, "modelKey");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        ClientGenerationLease lease = acquireGenerationLease(checkedKey);
        try {
            afterExactLeaseAdmission.run();
            ClientModelView sourceView = resolve(checkedKey);
            lease.requireCompatible(this, sourceView);
            if (!lease.managed()) {
                lease.requireCurrentMissingSnapshot(checkedSnapshot);
                return ClientGenerationLeaseBinding.unavailable(checkedSnapshot);
            }
            if (sourceView.renderHandle() != checkedSnapshot.handle()) {
                throw new IllegalArgumentException(
                        "Shared generation binding requires the exact handle sampled by its source-owned lookup");
            }
            X7ProductionPolicyOwner policyOwner = registry.policyOwnerForActiveSource(
                    checkedKey, sourceView.generationId(), sourceView.renderHandle());
            return ClientGenerationLeaseBinding.sourceBound(this, sourceView, checkedSnapshot, lease, policyOwner);
        } catch (Throwable failure) {
            try {
                lease.close();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private static ClientModelView viewFor(
            ModelRegistryGeneration generation, BlendModelKey key, ModelHandle handle, boolean discovered) {
        Optional<BlendDiagnostic> diagnostic = generation.primaryDiagnostic(key);
        if (diagnostic.isEmpty() && handle instanceof MissingModelHandle missing) {
            diagnostic = Optional.of(missing.diagnostic());
        }
        return new ClientModelView(
                key,
                generation.generationId(),
                discovered,
                handle.renderHandle(),
                diagnostic.map(RegistryBackedModelLookup::toClientDiagnostic));
    }

    private static ClientDiagnostic toClientDiagnostic(BlendDiagnostic diagnostic) {
        return new ClientDiagnostic(
                ClientDiagnosticSeverity.valueOf(diagnostic.severity().name()),
                diagnostic.code(),
                diagnostic.modelKey(),
                diagnostic.resourceId(),
                diagnostic.location(),
                diagnostic.message(),
                diagnostic.causeSummary());
    }
}
