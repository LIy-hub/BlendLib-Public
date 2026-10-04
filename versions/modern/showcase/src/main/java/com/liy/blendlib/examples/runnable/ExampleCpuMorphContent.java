package com.liy.blendlib.examples.runnable;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Optional dedicated CPU morph consumer; the other example entities are unchanged. */
public final class ExampleCpuMorphContent implements ModInitializer {
    public static final EntityType<CpuMorphActor> ACTOR = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            ExampleContent.id("cpu_morph_actor"), EntityType.Builder.of(CpuMorphActor::new, MobCategory.MISC)
                    .sized(.8F, 1.8F).clientTrackingRange(8).updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ExampleContent.id("cpu_morph_actor"))));
    public static final EntityType<StaticCpuMorphActor> STATIC_ACTOR = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            ExampleContent.id("static_cpu_morph_actor"), EntityType.Builder.of(StaticCpuMorphActor::new, MobCategory.MISC)
                    .sized(.8F, 1.8F).clientTrackingRange(8).updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ExampleContent.id("static_cpu_morph_actor"))));
    public static final Identifier STATIC_BLOCK_ID = ExampleContent.id("static_cpu_morph_block");
    public static final ExampleCpuMorphBlock STATIC_BLOCK = Registry.register(BuiltInRegistries.BLOCK,
            STATIC_BLOCK_ID, new ExampleCpuMorphBlock(BlockBehaviour.Properties.of().strength(1.0F)
                    .noOcclusion().setId(ResourceKey.create(Registries.BLOCK, STATIC_BLOCK_ID))));
    public static final BlockEntityType<ExampleCpuMorphBlockEntity> STATIC_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, STATIC_BLOCK_ID,
            FabricBlockEntityTypeBuilder.create(ExampleCpuMorphBlockEntity::new, STATIC_BLOCK).build());
    public static final Identifier STATIC_ITEM_ID = ExampleContent.id("static_cpu_morph_item");
    public static final Item STATIC_ITEM = Registry.register(BuiltInRegistries.ITEM, STATIC_ITEM_ID,
            new Item(new Item.Properties().stacksTo(ExampleCpuMorphItemControls.MAX_STACK_SIZE)
                    .setId(ResourceKey.create(Registries.ITEM, STATIC_ITEM_ID))));
    @Override public void onInitialize() { }
}
