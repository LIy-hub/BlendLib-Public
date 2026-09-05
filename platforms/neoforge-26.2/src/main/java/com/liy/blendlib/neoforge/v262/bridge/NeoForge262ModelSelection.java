package com.liy.blendlib.neoforge.v262.bridge;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.neoforge.v262.NeoForge262Diagnostic;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable ready-or-fallback result for a NeoForge 26.2 bridge model lookup.
 *
 * <p><strong>WAITING boundary:</strong> the fallback explicitly means no render payload is exposed
 * to a future binder. The bridge does not invent a native rendering fallback before public
 * NeoForge 26.2 renderer APIs are verified.</p>
 *
 * @param modelKey requested strict model key
 * @param generation bridge generation
 * @param asset optional immutable decoded asset
 * @param fallback optional explicit missing/failed diagnostic
 */
public record NeoForge262ModelSelection(
        BlendModelKey modelKey,
        long generation,
        Optional<ModelAsset> asset,
        Optional<NeoForge262Diagnostic> fallback) {
    /**
     * Validates a ready-or-fallback selection with exactly one outcome.
     */
    public NeoForge262ModelSelection {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        asset = Objects.requireNonNull(asset, "asset");
        fallback = Objects.requireNonNull(fallback, "fallback");
        if (asset.isPresent() == fallback.isPresent()) {
            throw new IllegalArgumentException("selection must contain exactly one ready asset or fallback diagnostic");
        }
    }

    /**
     * Returns whether a strict immutable asset is available to a future verified binder.
     *
     * @return true only for a ready immutable asset
     */
    public boolean isReady() {
        return asset.isPresent();
    }
}
