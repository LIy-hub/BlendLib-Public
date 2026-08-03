package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBuckets;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.animation.sync.BlendLibClientAnimationSync;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Extraction-only bridge from one typed block-entity identity to a captured skinned snapshot.
 *
 * <p>The only synchronization input is the P6 semantic state already accepted by the client
 * store. This factory never accesses a payload, packet, model loader, resource manager, or
 * renderer submit path.</p>
 */
final class SyncedSkinnedBlockEntitySnapshotFactory<T extends BlockEntity>
        implements BlendBlockEntitySnapshotFactory<T> {
    private final BlendModelKey modelKey;
    private final BlendAnimationKey fallbackAnimation;

    SyncedSkinnedBlockEntitySnapshotFactory(BlendModelKey modelKey, BlendAnimationKey fallbackAnimation) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.fallbackAnimation = Objects.requireNonNull(fallbackAnimation, "fallbackAnimation");
    }

    @Override
    public ModelRenderSnapshot create(T blockEntity, BlendBlockEntitySnapshotRequest request) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        BlendBlockEntitySnapshotRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (!modelKey.equals(checkedRequest.modelKey())) {
            throw new IllegalArgumentException("Skinned block-entity snapshot request does not match the renderer model key");
        }

        ClientModelView model = BlendLibClientServices.models().resolve(modelKey);
        if (model.missing() || !model.renderHandle().skinned()) {
            return missingSnapshot(model, checkedRequest);
        }

        return BlendLibClientServices.skinnedAnimationRuntime()
                .extract(new SkinnedAnimationRuntimeInput(
                        modelKey,
                        checkedRequest.instanceKey(),
                        checkedRequest.clientGameTick(),
                        checkedRequest.partialTick(),
                        fallbackAnimation,
                        BlendLibClientAnimationSync.runtime().blockEntityState(
                                checkedRequest.instanceKey().dimension(),
                                BlockPos.of(checkedRequest.instanceKey().packedBlockPos())),
                        AnimationUpdateBuckets.select(
                                checkedRequest.animationVisible(), checkedRequest.distanceToCameraSq()),
                        new SkinnedExtractionRequest(
                                Transform.IDENTITY,
                                checkedRequest.packedLight(),
                                OverlayTexture.NO_OVERLAY,
                                0xFFFFFFFF,
                                visibilityFor(checkedRequest),
                                new CullingMetadata(model.renderHandle().bounds(), true))))
                .map(result -> result.frame().renderSnapshot())
                .orElseGet(() -> missingSnapshot(model, checkedRequest));
    }

    private static ModelRenderSnapshot missingSnapshot(ClientModelView model, BlendBlockEntitySnapshotRequest request) {
        ModelRenderHandle handle = model.missing()
                ? model.renderHandle()
                : new MissingModelRenderHandle(request.modelKey(), model.generationId());
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                request.packedLight(),
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF,
                visibilityFor(request),
                new CullingMetadata(handle.bounds(), true));
    }

    private static RenderVisibility visibilityFor(BlendBlockEntitySnapshotRequest request) {
        return request.animationVisible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED;
    }
}
