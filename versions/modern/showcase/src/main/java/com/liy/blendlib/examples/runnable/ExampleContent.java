package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

/** Optional consumer content. Nothing here is loaded by the normal BlendLib JAR. */
public final class ExampleContent implements ModInitializer {
    public static final String MOD_ID = "blendlib_runnable_examples";
    public static final BlendModelKey ACTOR_MODEL = BlendModelKey.parse(MOD_ID + ":actor");
    public static final BlendModelKey WAND_MODEL = BlendModelKey.parse(MOD_ID + ":wand");
    public static final BlendModelKey MARKER_MODEL = BlendModelKey.parse(MOD_ID + ":marker");
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse(MOD_ID + ":idle");
    public static final BlendAnimationKey WALK = BlendAnimationKey.parse(MOD_ID + ":walk");
    public static final BlendAnimationKey ATTACK = BlendAnimationKey.parse(MOD_ID + ":attack");
    public static final BlendResourceId TIP = BlendResourceId.parse(MOD_ID + ":tip");
    public static final Identifier ACTOR_ID = id("layered_actor");
    public static final Identifier WAND_ID = id("animated_wand");
    public static final EntityType<LayeredActor> ACTOR = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, ACTOR_ID,
            EntityType.Builder.of(LayeredActor::new, MobCategory.MISC)
                    .sized(0.8F, 1.5F).clientTrackingRange(8).updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ACTOR_ID)));
    public static final Item WAND = Registry.register(BuiltInRegistries.ITEM, WAND_ID,
            new Item(new Item.Properties().stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, WAND_ID))));

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        // Initialization of this explicitly installed mod registers only its own entity and item.
    }
}
