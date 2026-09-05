package com.liy.blendlib.fabric.v262.resource;

import java.util.Objects;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Public Minecraft resource-reload listener that publishes one prepared Fabric 26.2 generation.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. The listener is registered only
 * with Fabric's public {@code ResourceLoader}; it performs descriptor/GLB I/O only during a
 * resource reload and never at model submit time. Its synchronous listener shape is deliberate:
 * it avoids guessing private 26.2 shared-state keys while retaining an explicit prepare-then-apply
 * coordinator boundary.</p>
 */
public final class Fabric262ResourceReloadListener implements ResourceManagerReloadListener {
    private final Fabric262ResourceReloadCoordinator coordinator;

    /**
     * Creates a listener bound to one non-null coordinator.
     *
     * @param coordinator owner of prepared generation publication
     */
    public Fabric262ResourceReloadListener(Fabric262ResourceReloadCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * Prepares and atomically publishes the next strict resource generation.
     *
     * @param resourceManager current Minecraft client resource manager
     */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        coordinator.reload(new Fabric262MinecraftResourceAccess(resourceManager));
    }
}
