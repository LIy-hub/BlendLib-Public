package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
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
