package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Exact compiled public/protected JVM descriptors, following the retained X7 ABI boundary pins. */
class ItemAnimationObservationAbiTest {
    @Test void existingItemFacadeAndPlaybackAbiHaveOnlyTheApprovedAdditions() throws Exception {
        assertEquals(Set.of(
                "public static final MAX_RETAINED_INSTANCES:I",
                "public static synchronized register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/api/BlendAnimationKey;)V",
                "public static synchronized register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/api/BlendAnimationKey;Lcom/liy/blendlib/fabric/client/item/ItemAnimationVisualEventHandler;)V",
                "public static playback(Lnet/minecraft/world/item/ItemStack;)Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public static observe(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
                "public static extractionStatus(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
                "public static release(Lnet/minecraft/world/item/ItemStack;)V",
                "public static clear()V"), exportedDescriptors(BlendLibItemAnimations.class));
        assertEquals(256, BlendLibItemAnimations.MAX_RETAINED_INSTANCES);
        assertEquals("java.util.Optional<com.liy.blendlib.fabric.client.item.ItemAnimationObservation>",
                BlendLibItemAnimations.class.getDeclaredMethod("observe", net.minecraft.world.item.ItemStack.class)
                        .getGenericReturnType().getTypeName());
        assertEquals("java.util.Optional<com.liy.blendlib.fabric.client.item.ItemAnimationExtractionStatus>",
                BlendLibItemAnimations.class.getDeclaredMethod("extractionStatus", net.minecraft.world.item.ItemStack.class)
                        .getGenericReturnType().getTypeName());
        assertEquals(Set.of(
                "public play(Lcom/liy/blendlib/api/BlendAnimationKey;Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;)Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public pause()Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public resume()Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public stop()Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public speed(D)Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public seek(D)Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public animation()Lcom/liy/blendlib/api/BlendAnimationKey;",
                "public mode()Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;",
                "public speed()D",
                "public playing()Z"), exportedDescriptors(ItemAnimationPlayback.class));
        assertEquals(Set.of(
                "public static final LOOP:Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;",
                "public static final ONCE:Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;",
                "public static final HOLD:Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;",
                "public static values()[Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;",
                "public static valueOf(Ljava/lang/String;)Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;"),
                exportedDescriptors(ItemAnimationPlayback.Mode.class));
    }

    @Test void observationAndNestedSampleExposeExactlyTheApprovedImmutableRecordAbi() throws Exception {
        assertEquals(Set.of(
                "public <init>(Lcom/liy/blendlib/api/BlendAnimationKey;Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;DZDLjava/util/Optional;Z)V",
                "public animation()Lcom/liy/blendlib/api/BlendAnimationKey;",
                "public mode()Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback$Mode;",
                "public speed()D",
                "public playing()Z",
                "public storedSeconds()D",
                "public lastSample()Ljava/util/Optional;",
                "public sampleCurrentGeneration()Z",
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"), exportedDescriptors(ItemAnimationObservation.class));
        assertEquals(Set.of(
                "public <init>(Lcom/liy/blendlib/api/BlendModelKey;Lcom/liy/blendlib/api/BlendAnimationKey;JDD)V",
                "public model()Lcom/liy/blendlib/api/BlendModelKey;",
                "public animation()Lcom/liy/blendlib/api/BlendAnimationKey;",
                "public generation()J",
                "public seconds()D",
                "public durationSeconds()D",
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"), exportedDescriptors(ItemAnimationObservation.Sample.class));
        assertEquals("java.util.Optional<com.liy.blendlib.fabric.client.item.ItemAnimationObservation$Sample>",
                ItemAnimationObservation.class.getDeclaredMethod("lastSample").getGenericReturnType().getTypeName());
        assertEquals(java.util.List.of("animation", "mode", "speed", "playing", "storedSeconds", "lastSample", "sampleCurrentGeneration"),
                Arrays.stream(ItemAnimationObservation.class.getRecordComponents()).map(component -> component.getName()).toList());
        assertEquals(java.util.List.of("model", "animation", "generation", "seconds", "durationSeconds"),
                Arrays.stream(ItemAnimationObservation.Sample.class.getRecordComponents()).map(component -> component.getName()).toList());
        assertTrue(Modifier.isPublic(ItemAnimationObservation.class.getModifiers()));
        assertTrue(Modifier.isPublic(ItemAnimationObservation.Sample.class.getModifiers()));
        assertTrue(Modifier.isStatic(ItemAnimationObservation.Sample.class.getModifiers()));
        assertFalse(Modifier.isPublic(ItemAnimationInstances.class.getModifiers()));
        assertTrue(exportedDescriptors(ItemAnimationInstances.class).isEmpty());
    }

    @Test void extractionStatusAddsExactlyItsImmutableRecordAndExplicitOutcomeEnums() {
        assertEquals(Set.of(
                "public <init>(Lcom/liy/blendlib/api/BlendModelKey;Lcom/liy/blendlib/api/BlendAnimationKey;JLcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;Z)V",
                "public model()Lcom/liy/blendlib/api/BlendModelKey;",
                "public requestedAnimation()Lcom/liy/blendlib/api/BlendAnimationKey;",
                "public generation()J",
                "public outcome()Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;",
                "public fallback()Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;",
                "public currentGeneration()Z",
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"), exportedDescriptors(ItemAnimationExtractionStatus.class));
        assertEquals(java.util.List.of("model", "requestedAnimation", "generation", "outcome", "fallback", "currentGeneration"),
                Arrays.stream(ItemAnimationExtractionStatus.class.getRecordComponents()).map(component -> component.getName()).toList());
        assertEquals(Set.of(
                "public static final ANIMATED:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;",
                "public static final ANIMATION_UNAVAILABLE:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;",
                "public static final MODEL_UNAVAILABLE:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;",
                "public static final EXTRACTION_UNAVAILABLE:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;",
                "public static values()[Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;",
                "public static valueOf(Ljava/lang/String;)Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Outcome;"),
                exportedDescriptors(ItemAnimationExtractionStatus.Outcome.class));
        assertEquals(Set.of(
                "public static final NONE:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;",
                "public static final STATIC_MODEL:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;",
                "public static final MISSING_MODEL:Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;",
                "public static values()[Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;",
                "public static valueOf(Ljava/lang/String;)Lcom/liy/blendlib/fabric/client/item/ItemAnimationExtractionStatus$Fallback;"),
                exportedDescriptors(ItemAnimationExtractionStatus.Fallback.class));
        assertTrue(Modifier.isPublic(ItemAnimationExtractionStatus.class.getModifiers()));
        for (var type : new Class<?>[] {ItemAnimationExtractionStatus.Outcome.class, ItemAnimationExtractionStatus.Fallback.class}) {
            assertTrue(type.isEnum());
            assertTrue(Modifier.isPublic(type.getModifiers()));
            assertTrue(Modifier.isStatic(type.getModifiers()));
        }
    }

    @Test void materialAppearanceIsAnAdditiveRegistrationOverloadAndSmallFunctionalCallback() throws Exception {
        assertEquals(Set.of(
                "public static register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;)V",
                "public static register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/fabric/client/item/BlendLibItemMaterialAppearance;)V",
                "public static registerWithSkin(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/fabric/client/item/BlendLibItemSkinSelector;)V",
                "public static registerWithSkin(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/fabric/client/item/BlendLibItemMaterialAppearance;Lcom/liy/blendlib/fabric/client/item/BlendLibItemSkinSelector;)V",
                "public static find(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;",
                "public static bindings()Ljava/util/Map;",
                "public static installModelLoadingPlugin()V"), exportedDescriptors(BlendLibItemModelBindings.class));
        assertEquals(Set.of(
                "public abstract select(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Map;",
                "public captured(Lnet/minecraft/world/item/ItemStack;Lcom/liy/blendlib/fabric/client/render/ModelRenderSnapshot;)V"),
                exportedDescriptors(BlendLibItemMaterialAppearance.class));
        assertTrue(BlendLibItemMaterialAppearance.class.isAnnotationPresent(FunctionalInterface.class));
        assertTrue(BlendLibItemMaterialAppearance.class.getMethod("captured", net.minecraft.world.item.ItemStack.class,
                com.liy.blendlib.fabric.client.render.ModelRenderSnapshot.class).isDefault());
        assertEquals("java.util.Map<java.lang.String, com.liy.blendlib.fabric.client.render.MaterialSlotAppearance>",
                BlendLibItemMaterialAppearance.class.getMethod("select", net.minecraft.world.item.ItemStack.class)
                        .getGenericReturnType().getTypeName());
        assertNotNull(BlendLibItemRenderArgument.class.getDeclaredConstructor(BlendLibItemBinding.class,
                com.liy.blendlib.fabric.client.render.ModelRenderHandle.class));
        assertNotNull(BlendLibItemRenderArgument.class.getDeclaredConstructor(BlendLibItemBinding.class,
                com.liy.blendlib.fabric.client.render.ModelRenderHandle.class,
                com.liy.blendlib.fabric.client.render.ModelRenderSnapshot.class));
        assertNotNull(BlendLibItemSpecialRenderer.class.getDeclaredConstructor(BlendLibItemBinding.class));
        assertNotNull(BlendLibItemSpecialRenderer.Unbaked.class.getDeclaredConstructor(BlendLibItemBinding.class));
    }

    @Test void namedSkinSelectionIsAnAdditiveSmallFunctionalCallback() throws Exception {
        assertEquals(Set.of(
                "public abstract select(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
                "public captured(Lnet/minecraft/world/item/ItemStack;Lcom/liy/blendlib/fabric/client/render/ModelRenderSnapshot;)V"),
                exportedDescriptors(BlendLibItemSkinSelector.class));
        assertTrue(BlendLibItemSkinSelector.class.isAnnotationPresent(FunctionalInterface.class));
        assertTrue(BlendLibItemSkinSelector.class.getMethod("captured", net.minecraft.world.item.ItemStack.class,
                com.liy.blendlib.fabric.client.render.ModelRenderSnapshot.class).isDefault());
        assertEquals("java.util.Optional<com.liy.blendlib.api.BlendResourceId>",
                BlendLibItemSkinSelector.class.getMethod("select", net.minecraft.world.item.ItemStack.class)
                        .getGenericReturnType().getTypeName());
        assertNotNull(BlendLibItemSpecialRenderer.class.getDeclaredConstructor(BlendLibItemBinding.class,
                BlendLibItemMaterialAppearance.class));
        assertNotNull(BlendLibItemSpecialRenderer.Unbaked.class.getDeclaredConstructor(BlendLibItemBinding.class,
                BlendLibItemMaterialAppearance.class));
    }

    @Test void itemVisualEventsExposeOnlyTheAdditiveImmutablePresentationContract() {
        assertEquals(Set.of(
                "public abstract onVisualEvent(Lnet/minecraft/world/item/ItemStack;Lcom/liy/blendlib/fabric/client/item/ItemAnimationVisualEvent;)V"),
                exportedDescriptors(ItemAnimationVisualEventHandler.class));
        assertTrue(ItemAnimationVisualEventHandler.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(Set.of(
                "public <init>(Lcom/liy/blendlib/api/BlendModelKey;Lcom/liy/blendlib/api/BlendAnimationKey;JLcom/liy/blendlib/core/animation/runtime/AnimationVisualEvent;)V",
                "public model()Lcom/liy/blendlib/api/BlendModelKey;",
                "public animation()Lcom/liy/blendlib/api/BlendAnimationKey;",
                "public generation()J",
                "public event()Lcom/liy/blendlib/core/animation/runtime/AnimationVisualEvent;",
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"), exportedDescriptors(ItemAnimationVisualEvent.class));
        assertTrue(ItemAnimationVisualEvent.class.isRecord());
    }

    private static Set<String> exportedDescriptors(Class<?> type) {
        var descriptors = new TreeSet<String>();
        Arrays.stream(type.getDeclaredMethods()).filter(method -> exported(method.getModifiers())).forEach(method ->
                descriptors.add(Modifier.toString(method.getModifiers()) + " " + method.getName()
                        + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString()));
        Arrays.stream(type.getDeclaredConstructors()).filter(constructor -> exported(constructor.getModifiers())).forEach(constructor ->
                descriptors.add(Modifier.toString(constructor.getModifiers()) + " <init>"
                        + MethodType.methodType(void.class, constructor.getParameterTypes()).toMethodDescriptorString()));
        Arrays.stream(type.getDeclaredFields()).filter(field -> exported(field.getModifiers())).forEach(field ->
                descriptors.add(Modifier.toString(field.getModifiers() & ~0x4000) + " " + field.getName() + ":" + field.getType().descriptorString()));
        return descriptors;
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
