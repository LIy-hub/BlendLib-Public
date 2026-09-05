package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/**
 * Immutable ordinary-host binding accepted by the Minecraft 26.2 Fabric adapter.
 *
 * <p>This value is committed only after {@link Fabric262HostRenderDispatcher} installs the matching
 * public Fabric entity, block-entity, or item hook. It intentionally exposes neither a renderer
 * nor a Minecraft token across the experimental stable facade.</p>
 *
 * <p>The production dispatcher retains its complete typed registration in a separate
 * package-private installation object so this public metadata carrier remains source- and
 * binary-compatible.</p>
 *
 * @param target translated native host identity
 * @param modelKey strict model selected by the stable facade
 */
public record Fabric262HostBinding(Fabric262HostTarget target, BlendModelKey modelKey) {
    /**
     * Validates one immutable X4-capable host binding.
     */
    public Fabric262HostBinding {
        target = Objects.requireNonNull(target, "target");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
    }
}
