package com.liy.blendlib.fabric.v262;

import net.fabricmc.api.ClientModInitializer;

/**
 * Declares the isolated 26.2 spike as a client-only Fabric artifact.
 *
 * <p>The spike deliberately registers no production content. Its compatibility proof is the controlled static
 * showcase test in this standalone project, not a claim of a completed 26.2 runtime integration.</p>
 */
public final class BlendLib262SpikeClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Intentionally empty: the P8 spike only proves compile-time and controlled-submit compatibility.
    }
}
