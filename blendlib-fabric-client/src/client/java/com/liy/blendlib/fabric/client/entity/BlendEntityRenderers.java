package com.liy.blendlib.fabric.client.entity;

import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Public 26.1.2 registration helper for {@link BlendEntityRenderer} providers. */
public final class BlendEntityRenderers {
    private BlendEntityRenderers() {
    }

    /**
     * Registers a BlendLib entity renderer through Fabric's public client-only registry.
     * Registration neither loads model resources nor creates render snapshots.
     */
    public static <E extends Entity> void register(
            EntityType<? extends E> entityType, EntityRendererProvider<E> provider) {
        EntityRendererRegistry.register(
                Objects.requireNonNull(entityType, "entityType"),
                Objects.requireNonNull(provider, "provider"));
    }
}
