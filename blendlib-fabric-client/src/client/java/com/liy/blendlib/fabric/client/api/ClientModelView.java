package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import java.util.Objects;
import java.util.Optional;

/** Immutable diagnostic and prepared-handle view for one semantic model key. */
public record ClientModelView(
        BlendModelKey key,
        long generationId,
        boolean discovered,
        ModelRenderHandle renderHandle,
        Optional<ClientDiagnostic> primaryDiagnostic) {
    public ClientModelView {
        key = Objects.requireNonNull(key, "key");
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must be non-negative");
        }
        renderHandle = Objects.requireNonNull(renderHandle, "renderHandle");
        primaryDiagnostic = Objects.requireNonNull(primaryDiagnostic, "primaryDiagnostic");
        if (!key.equals(renderHandle.modelKey()) || generationId != renderHandle.generation()) {
            throw new IllegalArgumentException("Render handle must match the model key and generation");
        }
    }

    /** Whether this view resolves to the generation's stable missing-model fallback. */
    public boolean missing() {
        return renderHandle.missingModel();
    }
}
