package com.liy.blendlib.examples.runnable;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Optional dedicated CPU morph consumer; the other example entities are unchanged. */
public final class ExampleCpuMorphContent implements ModInitializer {
    public static final EntityType<CpuMorphActor> ACTOR = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            ExampleContent.id("cpu_morph_actor"), EntityType.Builder.of(CpuMorphActor::new, MobCategory.MISC)
                    .sized(.8F, 1.8F).clientTrackingRange(8).updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ExampleContent.id("cpu_morph_actor"))));
    @Override public void onInitialize() { }
}
