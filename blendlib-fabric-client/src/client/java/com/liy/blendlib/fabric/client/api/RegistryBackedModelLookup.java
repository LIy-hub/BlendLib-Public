package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.MissingModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Internal adapter from the reload registry to the narrow public inspection surface. */
final class RegistryBackedModelLookup implements ClientModelLookup {
    private final ClientModelRegistry registry;

    RegistryBackedModelLookup(ClientModelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
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
