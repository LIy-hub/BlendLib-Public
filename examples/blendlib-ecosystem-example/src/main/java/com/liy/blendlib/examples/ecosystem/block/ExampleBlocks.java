package com.liy.blendlib.examples.ecosystem.block;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Fixed gameplay block registration for a block-entity presentation host. */
public final class ExampleBlocks {
    public static final Identifier ANIMATED_ALTAR_ID =
            Identifier.fromNamespaceAndPath(ExampleKeys.MOD_ID, "animated_altar");
    public static final Block ANIMATED_ALTAR = Registry.register(
            BuiltInRegistries.BLOCK,
            ANIMATED_ALTAR_ID,
            new ExampleAnimatedAltarBlock(properties(ANIMATED_ALTAR_ID)));

    private ExampleBlocks() {
    }

    /** Forces one-time block registration before the dependent block-entity type. */
    public static void initialize() {
        // Class initialization performs the immutable registry registration.
    }

    private static BlockBehaviour.Properties properties(Identifier id) {
        return BlockBehaviour.Properties.of()
                .strength(2.0F)
                .setId(ResourceKey.create(BuiltInRegistries.BLOCK.key(), id));
    }
}
