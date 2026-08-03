package com.liy.blendlib.showcase.blockentity;

import com.liy.blendlib.showcase.block.ShowcaseBlocks;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Server-safe Showcase block-entity type registrations. */
public final class ShowcaseBlockEntities {
    public static final Identifier ANIMATED_ALTAR_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "animated_altar");

    public static final BlockEntityType<ShowcaseAnimatedAltarBlockEntity> ANIMATED_ALTAR = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            ANIMATED_ALTAR_ID,
            FabricBlockEntityTypeBuilder.create(
                    ShowcaseAnimatedAltarBlockEntity::new,
                    ShowcaseBlocks.ANIMATED_ALTAR).build());

    private ShowcaseBlockEntities() {
    }

    /** Forces type registration after the owning block is registered. */
    public static void initialize() {
        // Class initialization above performs registry registration exactly once.
    }
}
