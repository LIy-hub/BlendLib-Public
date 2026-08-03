package com.liy.blendlib.fabric.client.command;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Read-only service behind the client {@code /blendlib} diagnostics commands. */
public final class ClientDiagnosticsService {
    private final ClientModelLookup models;

    public ClientDiagnosticsService(ClientModelLookup models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    /** Returns immutable generation, handle, and aggregate diagnostic data for {@code assets}. */
    public ClientRegistryView assets() {
        return models.snapshot();
    }

    /** Returns the current handle and primary diagnostic for {@code inspect <model-id>}. */
    public ClientModelView inspect(BlendModelKey modelKey) {
        return models.resolve(Objects.requireNonNull(modelKey, "modelKey"));
    }

    /** Returns all generation diagnostics for {@code diagnostics}, without parsing or loading assets. */
    public List<ClientDiagnostic> diagnostics() {
        return assets().diagnostics();
    }

    /**
     * Returns model-specific diagnostics for {@code diagnostics <model-id>}. An unknown key still
     * reports its deterministic missing-model diagnostic even though it is absent from the map.
     */
    public List<ClientDiagnostic> diagnostics(BlendModelKey modelKey) {
        BlendModelKey checkedKey = Objects.requireNonNull(modelKey, "modelKey");
        ClientRegistryView registry = assets();
        ClientModelView model = inspect(checkedKey);
        LinkedHashSet<ClientDiagnostic> matching = new LinkedHashSet<>();
        registry.diagnostics().stream()
                .filter(diagnostic -> belongsTo(checkedKey, diagnostic))
                .forEach(matching::add);
        model.primaryDiagnostic().ifPresent(matching::add);
        return List.copyOf(matching);
    }

    private static boolean belongsTo(BlendModelKey key, ClientDiagnostic diagnostic) {
        if (key.resourceId().equals(diagnostic.modelKey())) {
            return true;
        }
        return key.descriptorResourceId().equals(diagnostic.resourceId());
    }
}
