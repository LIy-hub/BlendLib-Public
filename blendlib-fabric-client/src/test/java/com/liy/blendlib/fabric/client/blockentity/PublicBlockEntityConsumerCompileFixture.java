package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Compile fixture representing a consumer that needs only the public block-entity adapter surface. */
final class PublicBlockEntityConsumerCompileFixture {
    private PublicBlockEntityConsumerCompileFixture() {
    }

    static <T extends BlockEntity> BlendBlockEntityRendererBuilder<T> configure(
            BlockEntityRendererProvider.Context context, BlendModelKey modelKey) {
        return BlendBlockEntityRenderer.<T>builder(context, modelKey).staticRestPose();
    }

    static <T extends BlockEntity> BlendBlockEntityRendererBuilder<T> configureSynchronizedSkinned(
            BlockEntityRendererProvider.Context context,
            BlendModelKey modelKey,
            BlendAnimationKey fallbackAnimation) {
        return BlendBlockEntityRenderer.<T>builder(context, modelKey).syncedSkinnedAnimation(fallbackAnimation);
    }
}
