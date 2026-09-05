package com.liy.blendlib.examples.ecosystem.entity;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Server-safe content registration for the animated presentation host. */
public final class ExampleEntities {
    public static final Identifier ANIMATED_ACTOR_ID =
            Identifier.fromNamespaceAndPath(ExampleKeys.MOD_ID, "animated_actor");
    public static final EntityType<ExampleAnimatedEntity> ANIMATED_ACTOR = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            ANIMATED_ACTOR_ID,
            EntityType.Builder.of(ExampleAnimatedEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ANIMATED_ACTOR_ID)));

    private ExampleEntities() {
    }

    /** Forces the one-time content registration before a client renderer is registered. */
    public static void initialize() {
        // Class initialization performs the immutable registry registration.
    }
}
