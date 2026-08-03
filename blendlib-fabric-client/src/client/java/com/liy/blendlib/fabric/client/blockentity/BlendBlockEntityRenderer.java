package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Public 26.1.2 block-entity renderer adapter.
 *
 * <p>World and block-entity access is confined to extraction. Submit consumes only the prepared
 * {@link ModelRenderSnapshot} held by {@link BlendBlockEntityRenderState}; it does not query
 * synchronization, resources, model lookup, parsing, or controller state.</p>
 */
public final class BlendBlockEntityRenderer<T extends BlockEntity>
        implements BlockEntityRenderer<T, BlendBlockEntityRenderState> {
    private final BlendModelKey modelKey;
    private final BlendRenderer renderer;
    private final BlendBlockEntitySnapshotFactory<? super T> snapshotFactory;

    BlendBlockEntityRenderer(
            BlockEntityRendererProvider.Context context,
            BlendModelKey modelKey,
            BlendRenderer renderer,
            BlendBlockEntitySnapshotFactory<? super T> snapshotFactory) {
        Objects.requireNonNull(context, "context");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
    }

    /** Convenience builder using the renderer installed by the BlendLib client entrypoint. */
    public static <T extends BlockEntity> BlendBlockEntityRendererBuilder<T> builder(
            BlockEntityRendererProvider.Context context, BlendModelKey modelKey) {
        return builder(context, modelKey, BlendLibClientServices.renderer());
    }

    /** Builder overload with an explicitly supplied snapshot-only public renderer facade. */
    public static <T extends BlockEntity> BlendBlockEntityRendererBuilder<T> builder(
            BlockEntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer) {
        return new BlendBlockEntityRendererBuilder<>(context, modelKey, renderer);
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    @Override
    public BlendBlockEntityRenderState createRenderState() {
        return new BlendBlockEntityRenderState();
    }

    /**
     * Captures a dimension-plus-position key and immutable snapshot while the block entity and
     * client level are still valid.
     */
    @Override
    public void extractRenderState(
            T blockEntity,
            BlendBlockEntityRenderState state,
            float partialTick,
            Vec3 cameraPos,
            CrumblingOverlay crumblingOverlay) {
        T checkedBlockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
        BlendBlockEntityRenderState checkedState = Objects.requireNonNull(state, "state");
        Vec3 checkedCameraPos = Objects.requireNonNull(cameraPos, "cameraPos");
        BlockEntityRenderer.super.extractRenderState(
                checkedBlockEntity, checkedState, partialTick, checkedCameraPos, crumblingOverlay);

        Level level = checkedBlockEntity.getLevel();
        if (level == null) {
            checkedState.clearSnapshot();
            return;
        }
        BlockPos blockPos = checkedBlockEntity.getBlockPos();
        BlendResourceId dimension = BlendResourceId.parse(level.dimension().identifier().toString());
        BlendInstanceKey.BlockEntity instanceKey = new BlendInstanceKey.BlockEntity(dimension, blockPos.asLong());
        double distanceToCameraSq = checkedCameraPos.distanceToSqr(
                blockPos.getX() + 0.5D, blockPos.getY() + 0.5D, blockPos.getZ() + 0.5D);
        boolean animationVisible = Double.isFinite(distanceToCameraSq) && distanceToCameraSq <= 65_536.0D;
        long clientGameTick = level.getGameTime();
        checkedState.setSnapshot(snapshotFactory.create(checkedBlockEntity, new BlendBlockEntitySnapshotRequest(
                modelKey,
                instanceKey,
                partialTick,
                checkedState.lightCoords,
                clientGameTick,
                clientGameTick + partialTick,
                animationVisible,
                distanceToCameraSq)));
    }

    @Override
    public void submit(
            BlendBlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraRenderState) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        Objects.requireNonNull(cameraRenderState, "cameraRenderState");
        ModelRenderSnapshot snapshot = state.snapshotOrNull();
        if (snapshot != null) {
            renderer.submit(snapshot, poseStack, collector);
        }
    }
}
