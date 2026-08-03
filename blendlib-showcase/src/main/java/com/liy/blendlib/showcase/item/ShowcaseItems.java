package com.liy.blendlib.showcase.item;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Server-safe Showcase item registration; visual model binding remains in the client source set. */
public final class ShowcaseItems {
    public static final Identifier STATIC_RIGID_ITEM_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "static_rigid_item");

    /** Gameplay item state is deliberately independent of the BlendLib visual model. */
    public static final Item STATIC_RIGID_ITEM = Registry.register(
            BuiltInRegistries.ITEM,
            STATIC_RIGID_ITEM_ID,
            new Item(new Item.Properties().setId(ResourceKey.create(BuiltInRegistries.ITEM.key(), STATIC_RIGID_ITEM_ID))));

    private ShowcaseItems() {
    }

    /** Forces item registration during the common entrypoint without loading any client adapter class. */
    public static void initialize() {
        // Class initialization above performs the immutable registry registration exactly once.
    }
}
