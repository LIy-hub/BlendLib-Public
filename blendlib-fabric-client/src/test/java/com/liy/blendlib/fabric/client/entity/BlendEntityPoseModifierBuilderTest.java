package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import java.util.List;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityPoseModifierBuilderTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("builder_test:animated");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("builder_test:idle");
    private static final BlendEntityPoseModifier<Entity> IDENTITY_MODIFIER =
            (entity, context, basePose) -> basePose;
    private static final BlendEntityRootRotationSelector<Entity> IDENTITY_ROOT =
            (entity, request) -> BlendEntityRotation.IDENTITY;

    @Test
    void requiresAnimatedPathFirstAndRejectsStaticOrCustomSnapshotConfigurations() {
        BlendEntityRendererBuilder<Entity> unconfigured = builder();
        assertThrows(IllegalStateException.class, () -> unconfigured.poseModifier(IDENTITY_MODIFIER));

        BlendEntityRendererBuilder<Entity> staticBuilder = builder().staticRestPose();
        assertThrows(IllegalStateException.class, () -> staticBuilder.poseModifier(IDENTITY_MODIFIER));

        BlendEntityRendererBuilder<Entity> customBuilder = builder().snapshotFactory((entity, request) -> null);
        assertThrows(IllegalStateException.class, () -> customBuilder.poseModifier(IDENTITY_MODIFIER));
    }

    @Test
    void acceptsOneModifierAfterEitherAnimatedSelectorAndRejectsDuplicatesOrNull() {
        BlendEntityRendererBuilder<Entity> local = builder().skinnedAnimation((entity, request) -> IDLE);
        assertSame(local, local.poseModifier(IDENTITY_MODIFIER));
        assertThrows(IllegalStateException.class, () -> local.poseModifier(IDENTITY_MODIFIER));
        assertThrows(IllegalStateException.class, local::staticRestPose);

        BlendEntityRendererBuilder<Entity> synchronizedBuilder = builder()
                .synchronizedSkinnedAnimation((entity, request) -> IDLE);
        assertDoesNotThrow(() -> synchronizedBuilder.poseModifier(IDENTITY_MODIFIER));

        BlendEntityRendererBuilder<Entity> nullModifier = builder().skinnedAnimation((entity, request) -> IDLE);
        assertThrows(NullPointerException.class, () -> nullModifier.poseModifier(null));
    }

    @Test
    void completeRootRotationRequiresAnimatedPathAndRejectsDuplicatesOrNull() {
        assertThrows(IllegalStateException.class, () -> builder().rootRotation(IDENTITY_ROOT));
        assertThrows(
                IllegalStateException.class,
                () -> builder().staticRestPose().rootRotation(IDENTITY_ROOT));
        assertThrows(
                IllegalStateException.class,
                () -> builder().snapshotFactory((entity, request) -> null).rootRotation(IDENTITY_ROOT));

        BlendEntityRendererBuilder<Entity> animated = builder()
                .skinnedAnimation((entity, request) -> IDLE);
        assertSame(animated, animated.rootRotation(IDENTITY_ROOT));
        assertThrows(IllegalStateException.class, () -> animated.rootRotation(IDENTITY_ROOT));

        BlendEntityRendererBuilder<Entity> nullSelector = builder()
                .skinnedAnimation((entity, request) -> IDLE);
        assertThrows(NullPointerException.class, () -> nullSelector.rootRotation(null));
    }

    @Test
    void layerEventsRequireLayersAndPreserveTheirCallbackDescriptor() throws ReflectiveOperationException {
        BlendEntityLayerVisualEventHandler<Entity> handler = (entity, event) -> { };
        assertThrows(IllegalStateException.class, () -> builder().onAnimationLayerVisualEvent(handler));
        assertThrows(IllegalStateException.class,
                () -> builder().skinnedAnimation((entity, request) -> IDLE).onAnimationLayerVisualEvent(handler));
        var layers = List.of(new ModelAnimationLayers.Layer(BlendResourceId.parse("builder_test:base"),
                0, AnimationV2LayerMode.OVERRIDE, 1F, List.of(), IDLE));
        var animated = builder().skinnedAnimation((entity, request) -> IDLE)
                .animationLayers(layers, (entity, request) -> List.of());
        assertThrows(NullPointerException.class, () -> animated.onAnimationLayerVisualEvent(null));
        assertSame(animated, animated.onAnimationLayerVisualEvent(handler));
        var method = BlendEntityLayerVisualEventHandler.class.getDeclaredMethod("onVisualEvent", Entity.class,
                com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent.class);
        assertSame(void.class, method.getReturnType());
    }

    @Test
    void layerWeightsRequireAnimatedLayersOrCuesAndRejectNull() {
        BlendEntityLayerWeights<Entity> weights = (entity, request) -> AnimationV2LayerWeights.empty();
        assertThrows(IllegalStateException.class, () -> builder().animationLayerWeights(weights));
        assertThrows(IllegalStateException.class, () -> builder().staticRestPose().animationLayerWeights(weights));
        assertThrows(IllegalStateException.class,
                () -> builder().snapshotFactory((entity, request) -> null).animationLayerWeights(weights));
        assertThrows(IllegalStateException.class,
                () -> builder().skinnedAnimation((entity, request) -> IDLE).animationLayerWeights(weights));
        var layers = List.of(new ModelAnimationLayers.Layer(BlendResourceId.parse("builder_test:base"),
                0, AnimationV2LayerMode.OVERRIDE, 1F, List.of(), IDLE));
        var animated = builder().skinnedAnimation((entity, request) -> IDLE)
                .animationLayers(layers, (entity, request) -> List.of());
        assertThrows(NullPointerException.class, () -> animated.animationLayerWeights(null));
        assertSame(animated, animated.animationLayerWeights(weights));
        var cues = builder().synchronizedSkinnedAnimation((entity, request) -> IDLE)
                .animationLayerCues(layers, (entity, request) -> List.of());
        assertSame(cues, cues.animationLayerWeights(weights));
    }

    @Test
    void layerWeightCallbackPinsEntityAndSnapshotRequestAbi() throws ReflectiveOperationException {
        Method method = BlendEntityLayerWeights.class.getDeclaredMethod(
                "weights", Entity.class, BlendEntitySnapshotRequest.class);
        assertSame(AnimationV2LayerWeights.class, method.getReturnType());
        org.junit.jupiter.api.Assertions.assertEquals(1, BlendEntityLayerWeights.class.getDeclaredMethods().length);
        org.junit.jupiter.api.Assertions.assertTrue(BlendEntityLayerWeights.class.isAnnotationPresent(FunctionalInterface.class));
    }

    @Test
    void staticMorphIsAnExclusiveSnapshotPathAndAcceptsFrameControls() {
        var stat = builder().staticMorph();
        assertSame(stat, stat.morphControls((entity, request) ->
                com.liy.blendlib.core.animation.runtime.MorphFrameOverrides.empty()));
        assertThrows(NullPointerException.class, () -> stat.morphControls(null));
        assertThrows(IllegalStateException.class, stat::staticRestPose);
        assertThrows(IllegalStateException.class, stat::staticMorph);
        assertThrows(IllegalStateException.class, () -> stat.skinnedAnimation((entity, request) -> IDLE));
        assertThrows(IllegalStateException.class, () -> stat.snapshotFactory((entity, request) -> null));
        assertThrows(IllegalStateException.class, () -> stat.poseModifier(IDENTITY_MODIFIER));
        assertThrows(IllegalStateException.class, () -> builder().staticRestPose().staticMorph());
        assertThrows(IllegalStateException.class, () -> builder().skinnedAnimation((entity, request) -> IDLE).staticMorph());
        assertThrows(IllegalStateException.class, () -> builder().snapshotFactory((entity, request) -> null).staticMorph());
    }

    private static BlendEntityRendererBuilder<Entity> builder() {
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> {
        });
        return new BlendEntityRendererBuilder<>(uninitializedContext(), MODEL, renderer);
    }

    /** Allocates a non-null context without bootstrapping Minecraft; configuration methods never read it. */
    private static EntityRendererProvider.Context uninitializedContext() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return (EntityRendererProvider.Context) allocateInstance.invoke(
                    unsafe, EntityRendererProvider.Context.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not allocate isolated renderer context", exception);
        }
    }
}
