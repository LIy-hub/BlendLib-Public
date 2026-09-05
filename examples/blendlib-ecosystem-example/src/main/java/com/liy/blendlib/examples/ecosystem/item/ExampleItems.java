package com.liy.blendlib.examples.ecosystem.item;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Server-safe marker item whose ordinary gameplay behavior is independent from its visual model. */
public final class ExampleItems {
    public static final Identifier MODEL_MARKER_ID =
            Identifier.fromNamespaceAndPath(ExampleKeys.MOD_ID, "model_marker");
    public static final Item MODEL_MARKER = Registry.register(
            BuiltInRegistries.ITEM,
            MODEL_MARKER_ID,
            new Item(new Item.Properties().setId(ResourceKey.create(BuiltInRegistries.ITEM.key(), MODEL_MARKER_ID))));

    private ExampleItems() {
    }

    /** Forces one-time item registration without loading a client class. */
    public static void initialize() {
        // Class initialization performs the immutable registry registration.
    }
}
