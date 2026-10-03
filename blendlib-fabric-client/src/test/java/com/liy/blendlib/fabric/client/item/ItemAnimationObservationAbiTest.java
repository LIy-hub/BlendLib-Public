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
    @Test void existingItemFacadeAndPlaybackAbiHaveOnlyTheApprovedObserveAddition() throws Exception {
        assertEquals(Set.of(
                "public static final MAX_RETAINED_INSTANCES:I",
                "public static synchronized register(Lcom/liy/blendlib/fabric/client/item/BlendLibItemBinding;Lcom/liy/blendlib/api/BlendAnimationKey;)V",
                "public static playback(Lnet/minecraft/world/item/ItemStack;)Lcom/liy/blendlib/fabric/client/item/ItemAnimationPlayback;",
                "public static observe(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
                "public static release(Lnet/minecraft/world/item/ItemStack;)V",
                "public static clear()V"), exportedDescriptors(BlendLibItemAnimations.class));
        assertEquals(256, BlendLibItemAnimations.MAX_RETAINED_INSTANCES);
        assertEquals("java.util.Optional<com.liy.blendlib.fabric.client.item.ItemAnimationObservation>",
                BlendLibItemAnimations.class.getDeclaredMethod("observe", net.minecraft.world.item.ItemStack.class)
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
