package com.liy.blendlib.examples.runnable;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Distinct preview host; the existing layered actor and its selectable scenes are unchanged. */
public final class ExampleNativeCubicContent implements ModInitializer {
    public static final EntityType<LayeredActor> ACTOR = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            ExampleContent.id("native_cubic_actor"), EntityType.Builder.of(LayeredActor::new, MobCategory.MISC)
                    .sized(.8F, 1.5F).clientTrackingRange(8).updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ExampleContent.id("native_cubic_actor"))));

    @Override public void onInitialize() { }
}
