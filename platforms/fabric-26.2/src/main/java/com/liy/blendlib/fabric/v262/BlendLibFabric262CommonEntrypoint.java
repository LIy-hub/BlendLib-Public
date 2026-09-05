package com.liy.blendlib.fabric.v262;

import net.fabricmc.api.ModInitializer;

/**
 * Common Minecraft 26.2 Fabric entrypoint for the independent BlendLib artifact.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. This entrypoint deliberately
 * installs no renderer, resource listener, or client class so dedicated-server class loading
 * cannot reach client-only code. BlendLib model submission remains client-only.</p>
 */
public final class BlendLibFabric262CommonEntrypoint implements ModInitializer {
    /**
     * Establishes the common-side no-op boundary for the client-only artifact.
     *
     * <p>The method is intentionally free of model I/O, platform registration, and renderer
     * construction. The client entrypoint owns those operations.</p>
     */
    @Override
    public void onInitialize() {
        // The adapter exposes no authoritative gameplay behavior on the common/server side.
    }
}
