package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.Optional;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;

/** Compile fixture representing a consumer that needs only the adapter's public entity surface. */
final class PublicEntityConsumerCompileFixture {
    private static final BlendAnimationKey LOCAL_IDLE = BlendAnimationKey.parse("consumer_fixture:idle");

    private PublicEntityConsumerCompileFixture() {
    }

    static <E extends Entity> BlendEntityRendererBuilder<E> configure(
            EntityRendererProvider.Context context, BlendModelKey modelKey) {
        return BlendEntityRenderer.<E>builder(context, modelKey).staticRestPose();
    }

    static <E extends Entity> BlendEntityRendererBuilder<E> configureSynchronized(
            EntityRendererProvider.Context context, BlendModelKey modelKey) {
        return BlendEntityRenderer.<E>builder(context, modelKey)
                .synchronizedSkinnedAnimation((entity, request) -> LOCAL_IDLE);
    }

    static <E extends Entity> BlendEntityRendererBuilder<E> configureCustomSynchronized(
            EntityRendererProvider.Context context, BlendModelKey modelKey) {
        return BlendEntityRenderer.<E>builder(context, modelKey)
                .synchronizedSkinnedAnimation(
                        (entity, request) -> Optional.empty(),
                        (entity, request) -> LOCAL_IDLE);
    }

    static <E extends Entity> BlendEntityRendererBuilder<E> configurePoseModified(
            EntityRendererProvider.Context context, BlendModelKey modelKey) {
        return BlendEntityRenderer.<E>builder(context, modelKey)
                .skinnedAnimation((entity, request) -> LOCAL_IDLE)
                .rootRotation((entity, request) -> BlendEntityRotation.IDENTITY)
                .poseModifier((entity, poseContext, basePose) -> {
                    if (poseContext.rig().nodeIndices().isEmpty()) {
                        return basePose;
                    }
                    int nodeIndex = poseContext.rig().nodeIndices().iterator().next();
                    BlendEntityRotation baseRotation = basePose.rotation(nodeIndex);
                    return basePose.withRotation(nodeIndex, BlendEntityRotation.normalized(
                            baseRotation.x(), baseRotation.y(), baseRotation.z(), baseRotation.w()));
                });
    }
}
