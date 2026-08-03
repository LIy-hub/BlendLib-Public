package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import java.util.Objects;

/** Stable logical fallback for an asset that could not enter the active generation. */
public record MissingModelHandle(
        BlendModelKey key,
        long generationId,
        BlendDiagnostic diagnostic,
        ModelRenderHandle renderHandle) implements ModelHandle {
    public MissingModelHandle {
        key = Objects.requireNonNull(key, "key");
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must be non-negative");
        }
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        renderHandle = Objects.requireNonNull(renderHandle, "renderHandle");
        if (!key.equals(renderHandle.modelKey()) || generationId != renderHandle.generation() || !renderHandle.missingModel()) {
            throw new IllegalArgumentException("Missing render handle must match the missing model generation and key");
        }
    }

    /**
     * Creates the deterministic logical fallback for a key absent from the active generation.
     *
     * <p>This factory belongs to snapshot extraction or diagnostics setup, never renderer submit code.</p>
     */
    public static MissingModelHandle notDiscovered(BlendModelKey key, long generationId) {
        Objects.requireNonNull(key, "key");
        BlendDiagnostic diagnostic = BlendDiagnostic.error(
                BlendDiagnosticCodes.DESC_002,
                key.resourceId(),
                key.descriptorResourceId(),
                "/",
                "Model key was not discovered in the active resource generation");
        return failed(key, generationId, diagnostic);
    }

    static MissingModelHandle failed(BlendModelKey key, long generationId, BlendDiagnostic diagnostic) {
        return new MissingModelHandle(key, generationId, diagnostic, new MissingModelRenderHandle(key, generationId));
    }

    @Override
    public boolean missing() {
        return true;
    }
}
