package com.liy.blendlib.showcase.block;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Server-safe Showcase block registrations. */
public final class ShowcaseBlocks {
    public static final Identifier ANIMATED_ALTAR_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "animated_altar");

    /**
     * Fixed vanilla block properties are gameplay-only and intentionally independent from the
     * BlendLib presentation asset.
     */
    public static final Block ANIMATED_ALTAR = Registry.register(
            BuiltInRegistries.BLOCK,
            ANIMATED_ALTAR_ID,
            new ShowcaseAnimatedAltarBlock(properties(ANIMATED_ALTAR_ID)));

    private ShowcaseBlocks() {
    }

    /** Forces common registration before block-entity type initialization. */
    public static void initialize() {
        // Class initialization above performs registry registration exactly once.
    }

    private static BlockBehaviour.Properties properties(Identifier id) {
        return BlockBehaviour.Properties.of()
                .strength(2.0F)
                .setId(ResourceKey.create(BuiltInRegistries.BLOCK.key(), id));
    }
}
