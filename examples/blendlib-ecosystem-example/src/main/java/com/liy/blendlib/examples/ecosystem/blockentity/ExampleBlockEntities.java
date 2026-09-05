package com.liy.blendlib.examples.ecosystem.blockentity;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import com.liy.blendlib.examples.ecosystem.block.ExampleBlocks;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Server-safe registration of the example block-entity host type. */
public final class ExampleBlockEntities {
    public static final Identifier ANIMATED_ALTAR_ID =
            Identifier.fromNamespaceAndPath(ExampleKeys.MOD_ID, "animated_altar");
    public static final BlockEntityType<ExampleAnimatedAltarBlockEntity> ANIMATED_ALTAR = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            ANIMATED_ALTAR_ID,
            FabricBlockEntityTypeBuilder.create(
                    ExampleAnimatedAltarBlockEntity::new,
                    ExampleBlocks.ANIMATED_ALTAR).build());

    private ExampleBlockEntities() {
    }

    /** Forces registration after the owning block is registered. */
    public static void initialize() {
        // Class initialization performs the immutable registry registration.
    }
}
