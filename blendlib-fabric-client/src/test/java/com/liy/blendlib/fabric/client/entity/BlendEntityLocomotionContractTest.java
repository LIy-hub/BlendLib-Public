package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.rules.LocomotionInputs;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.entity.consumer.LocomotionRulesConsumerSample;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityLocomotionContractTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("locomotion:actor");
    private static final BlendResourceId BASE = BlendResourceId.parse("locomotion:base");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("locomotion:idle");
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(new ModelAnimationLayers.Layer(
            BASE, 0, AnimationV2LayerMode.OVERRIDE, 1, List.of(), IDLE));
    private static final BlendEntityLocomotionInputs<Entity> INPUTS =
            (entity, request) -> new LocomotionInputs(Map.of(), Map.of("speed", 0.0));

    @Test
    void requiresAnimatedLayersAndDeclaredControllerAndRejectsDuplicateOrReorderedConfiguration() {
        assertThrows(IllegalStateException.class, () -> builder().animationLocomotionRules(BASE, INPUTS));
        assertThrows(IllegalStateException.class, () -> builder().staticRestPose().animationLocomotionRules(BASE, INPUTS));
        assertThrows(IllegalStateException.class, () -> builder().snapshotFactory((e, r) -> null).animationLocomotionRules(BASE, INPUTS));
        assertThrows(IllegalStateException.class, () -> builder().skinnedAnimation((e, r) -> IDLE).animationLocomotionRules(BASE, INPUTS));
        var builder = animated();
        assertThrows(IllegalArgumentException.class,
                () -> builder.animationLocomotionRules(BlendResourceId.parse("locomotion:missing"), INPUTS));
        assertThrows(NullPointerException.class, () -> builder.animationLocomotionRules(null, INPUTS));
        assertThrows(NullPointerException.class, () -> builder.animationLocomotionRules(BASE, null));
        assertSame(builder, builder.animationLocomotionRules(BASE, INPUTS));
        assertThrows(IllegalStateException.class, () -> builder.animationLocomotionRules(BASE, INPUTS));
        assertThrows(IllegalStateException.class, () -> builder.animationLayers(LAYERS, (e, r) -> List.of()));
        assertThrows(IllegalStateException.class, () -> builder.animationLayerCues(LAYERS, (e, r) -> List.of()));
        var synced = builder().synchronizedSkinnedAnimation((e, r) -> IDLE)
                .animationLayerCues(LAYERS, (e, r) -> List.of());
        assertSame(synced, synced.animationLocomotionRules(BASE, INPUTS));
    }

    @Test
    void additiveLocomotionDescriptorsAndPriorLayerRuntimeDescriptorsRemainCallable() throws Exception {
        assertEquals(1, BlendEntityLocomotionInputs.class.getDeclaredMethods().length);
        assertTrue(BlendEntityLocomotionInputs.class.isAnnotationPresent(FunctionalInterface.class));
        assertReturn(BlendEntityLocomotionInputs.class, "capture", LocomotionInputs.class,
                Entity.class, BlendEntitySnapshotRequest.class);
        assertReturn(BlendEntityRendererBuilder.class, "animationLocomotionRules", BlendEntityRendererBuilder.class,
                BlendResourceId.class, BlendEntityLocomotionInputs.class);
        assertReturn(SkinnedAnimationRuntime.class, "captureEntityLocomotionRules", List.class,
                Object.class, Object.class, int.class, BlendModelKey.class, long.class, double.class,
                BlendResourceId.class, Supplier.class, Supplier.class);
        assertReturn(SkinnedAnimationRuntime.class, "captureExtractionLifecycleRevision", long.class);
        assertReturn(SkinnedAnimationRuntime.class, "captureEntityLayerCues", List.class,
                Object.class, Object.class, int.class, BlendModelKey.class, long.class, double.class, List.class);
        assertReturn(SkinnedAnimationRuntime.class, "extract", Optional.class, SkinnedAnimationRuntimeInput.class);
        assertReturn(SkinnedAnimationRuntime.class, "extract", Optional.class,
                SkinnedAnimationRuntimeInput.class, ClientAnimationPoseModifier.class);
        assertReturn(SkinnedAnimationRuntime.class, "extractLayered", Optional.class,
                SkinnedAnimationRuntimeInput.class, List.class, List.class, ClientAnimationPoseModifier.class);
        assertReturn(SkinnedAnimationRuntime.class, "extractLayered", Optional.class,
                SkinnedAnimationRuntimeInput.class, List.class, List.class, AnimationV2LayerWeights.class, ClientAnimationPoseModifier.class);
        assertReturn(BlendEntityRendererBuilder.class, "animationLayers", BlendEntityRendererBuilder.class,
                List.class, BlendEntityLayerCommands.class);
        assertReturn(BlendEntityRendererBuilder.class, "animationLayerCues", BlendEntityRendererBuilder.class,
                List.class, BlendEntityLayerCues.class);
        assertReturn(BlendEntityRendererBuilder.class, "animationLayerWeights", BlendEntityRendererBuilder.class,
                BlendEntityLayerWeights.class);
        assertReturn(BlendEntityRendererBuilder.class, "onAnimationLayerVisualEvent", BlendEntityRendererBuilder.class,
                BlendEntityLayerVisualEventHandler.class);
        assertNotNull(ModelRegistryGeneration.class.getConstructor(long.class, Map.class, Map.class, List.class));
        assertNotNull(ModelRegistryGeneration.class.getConstructor(long.class, Map.class, Map.class, List.class, Map.class));
        assertNotNull(SkinnedAnimationRuntime.class.getConstructor(ClientModelRegistry.class, ClientAnimationLifecycleBridge.class));
    }

    @Test
    void externalConsumerCompilesAndConfiguresThroughOnlyPublicApis() {
        var builder = builder();
        assertSame(builder, LocomotionRulesConsumerSample.configure(builder, (entity, request) -> List.of()));
    }

    private static void assertReturn(Class<?> type, String name, Class<?> result, Class<?>... arguments) throws Exception {
        assertSame(result, type.getMethod(name, arguments).getReturnType(), name);
    }

    private static BlendEntityRendererBuilder<Entity> animated() {
        return builder().skinnedAnimation((entity, request) -> IDLE).animationLayers(LAYERS, (entity, request) -> List.of());
    }

    private static BlendEntityRendererBuilder<Entity> builder() {
        return new BlendEntityRendererBuilder<>(allocate(EntityRendererProvider.Context.class), MODEL,
                new BlendRenderer((snapshot, context) -> {}));
    }

    static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field field = unsafe.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
