package com.liy.blendlib.showcase.entity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Server-safe registrations for summonable Showcase render hosts. */
public final class ShowcaseEntities {
    public static final Identifier STATIC_RIGID_ENTITY_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "static_rigid");
    /** P5-only client-rendered rigid animation host; gameplay dimensions remain explicit. */
    public static final Identifier RIGID_PULSE_ENTITY_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "rigid_pulse");
    public static final Identifier ANIMATED_ACTOR_ENTITY_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "animated_actor");
    /** P5-only local-schedule fixture; it is distinct from the P6-synchronized animated actor. */
    public static final Identifier P5_FALLBACK_ACTOR_ENTITY_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "p5_fallback_actor");
    /** Opt-in P7-only host; it has no renderer binding until the benchmark client path registers one. */
    public static final Identifier P7_BENCHMARK_RIGID_ENTITY_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "p7_benchmark_rigid");
    /** Opt-in P7-only host; it has no renderer binding until the benchmark client path registers one. */
    public static final Identifier P7_BENCHMARK_SKINNED_ENTITY_ID =
            Identifier.fromNamespaceAndPath("blendlib_showcase", "p7_benchmark_skinned");

    /** Fixed server gameplay dimensions; visual assets never determine these values. */
    public static final float ANIMATED_ACTOR_GAMEPLAY_WIDTH = 0.60F;
    /** Fixed server gameplay dimensions; visual assets never determine these values. */
    public static final float ANIMATED_ACTOR_GAMEPLAY_HEIGHT = 1.80F;
    /** Fixed server gameplay dimensions for the P5-only local-schedule fixture. */
    public static final float P5_FALLBACK_ACTOR_GAMEPLAY_WIDTH = 0.60F;
    /** Fixed server gameplay dimensions for the P5-only local-schedule fixture. */
    public static final float P5_FALLBACK_ACTOR_GAMEPLAY_HEIGHT = 1.80F;

    /** Fixed entity dimensions are independent of the BlendLib visual asset and never use mesh collision. */
    public static final EntityType<ShowcaseRigidEntity> STATIC_RIGID = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            STATIC_RIGID_ENTITY_ID,
            EntityType.Builder.of(ShowcaseRigidEntity::new, MobCategory.MISC)
                    .sized(0.75F, 0.75F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, STATIC_RIGID_ENTITY_ID)));

    /**
     * Server-safe host for the client-only P5 rigid-palette vertical slice. Its dimensions do not
     * derive from the visual mesh or animation.
     */
    public static final EntityType<ShowcaseRigidEntity> RIGID_PULSE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            RIGID_PULSE_ENTITY_ID,
            EntityType.Builder.of(ShowcaseRigidEntity::new, MobCategory.MISC)
                    .sized(0.75F, 0.75F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, RIGID_PULSE_ENTITY_ID)));

    /**
     * Server-safe host for the later P5 client-only skinned actor binding.
     *
     * <p>Its width and height are explicit gameplay values, independent of any visual mesh,
     * animation, or resource. No P6 synchronization behavior is registered here.</p>
     */
    public static final EntityType<ShowcaseAnimatedActorEntity> ANIMATED_ACTOR = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            ANIMATED_ACTOR_ENTITY_ID,
            EntityType.Builder.of(ShowcaseAnimatedActorEntity::new, MobCategory.MISC)
                    .sized(ANIMATED_ACTOR_GAMEPLAY_WIDTH, ANIMATED_ACTOR_GAMEPLAY_HEIGHT)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ANIMATED_ACTOR_ENTITY_ID)));

    /**
     * P5-only fixture whose client binding deliberately uses the local fallback schedule. Its
     * gameplay dimensions are explicit and independent from visual assets and animation.
     */
    public static final EntityType<ShowcaseP5FallbackActorEntity> P5_FALLBACK_ACTOR = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            P5_FALLBACK_ACTOR_ENTITY_ID,
            EntityType.Builder.of(ShowcaseP5FallbackActorEntity::new, MobCategory.MISC)
                    .sized(P5_FALLBACK_ACTOR_GAMEPLAY_WIDTH, P5_FALLBACK_ACTOR_GAMEPLAY_HEIGHT)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, P5_FALLBACK_ACTOR_ENTITY_ID)));

    /**
     * P7-only rigid benchmark host. Its fixed gameplay dimensions are intentionally unrelated to
     * the generated 10,000-triangle client mesh.
     */
    public static final EntityType<P7BenchmarkRigidEntity> P7_BENCHMARK_RIGID = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            P7_BENCHMARK_RIGID_ENTITY_ID,
            EntityType.Builder.of(P7BenchmarkRigidEntity::new, MobCategory.MISC)
                    .sized(0.75F, 0.75F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, P7_BENCHMARK_RIGID_ENTITY_ID)));

    /**
     * P7-only skinned benchmark host. Its fixed gameplay dimensions are intentionally unrelated
     * to the generated 20,000-triangle/64-joint client mesh.
     */
    public static final EntityType<P7BenchmarkSkinnedEntity> P7_BENCHMARK_SKINNED = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            P7_BENCHMARK_SKINNED_ENTITY_ID,
            EntityType.Builder.of(P7BenchmarkSkinnedEntity::new, MobCategory.MISC)
                    .sized(0.60F, 1.80F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, P7_BENCHMARK_SKINNED_ENTITY_ID)));

    private ShowcaseEntities() {
    }

    /** Forces type registration during the common Showcase entrypoint before client renderer registration. */
    public static void initialize() {
        // Class initialization above performs the immutable registry registration exactly once.
    }
}
