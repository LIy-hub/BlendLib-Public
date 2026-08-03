package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendModelKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, generation-scoped registry data intended for client diagnostics and consumer inspection. */
public record ClientRegistryView(
        long generationId,
        Map<BlendModelKey, ClientModelView> models,
        List<ClientDiagnostic> diagnostics) {
    public ClientRegistryView {
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must be non-negative");
        }
        models = immutableModels(models, generationId);
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    private static Map<BlendModelKey, ClientModelView> immutableModels(
            Map<BlendModelKey, ClientModelView> input, long generationId) {
        Objects.requireNonNull(input, "models");
        Map<BlendModelKey, ClientModelView> copy = new LinkedHashMap<>();
        input.forEach((key, view) -> {
            BlendModelKey checkedKey = Objects.requireNonNull(key, "model key");
            ClientModelView checkedView = Objects.requireNonNull(view, "model view");
            if (!checkedKey.equals(checkedView.key()) || checkedView.generationId() != generationId) {
                throw new IllegalArgumentException("Every model view must match the registry generation and map key");
            }
            copy.put(checkedKey, checkedView);
        });
        return Map.copyOf(copy);
    }
}
