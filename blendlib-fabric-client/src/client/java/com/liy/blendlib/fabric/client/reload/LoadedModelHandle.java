package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import java.util.Objects;

/** Successfully decoded immutable model asset for one resource generation. */
public record LoadedModelHandle(BlendModelKey key, ModelAsset asset, ModelRenderHandle renderHandle) implements ModelHandle {
    public LoadedModelHandle {
        key = Objects.requireNonNull(key, "key");
        asset = Objects.requireNonNull(asset, "asset");
        renderHandle = Objects.requireNonNull(renderHandle, "renderHandle");
        if (!key.resourceId().equals(asset.modelKey())) {
            throw new IllegalArgumentException("Loaded asset model key must match its registry key");
        }
        if (!key.equals(renderHandle.modelKey()) || asset.generation() != renderHandle.generation() || renderHandle.missingModel()) {
            throw new IllegalArgumentException("Loaded render handle must match the loaded asset generation and model key");
        }
    }

    @Override
    public long generationId() {
        return asset.generation();
    }

    @Override
    public boolean missing() {
        return false;
    }
}
