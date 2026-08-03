package com.liy.blendlib.fabric.client.blockentity;

import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Public 26.1.2 registration helper for {@link BlendBlockEntityRenderer} providers. */
public final class BlendBlockEntityRenderers {
    private BlendBlockEntityRenderers() {
    }

    /** Registers a client-only BlendLib block-entity renderer through Fabric's public registry. */
    public static <T extends BlockEntity> void register(
            BlockEntityType<T> blockEntityType,
            BlockEntityRendererProvider<T, BlendBlockEntityRenderState> provider) {
        BlockEntityRendererRegistry.register(
                Objects.requireNonNull(blockEntityType, "blockEntityType"),
                Objects.requireNonNull(provider, "provider"));
    }
}
