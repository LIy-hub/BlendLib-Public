package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.compat.LegacySubmitNodeCollector;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Captures the same immutable animation snapshot before submitting legacy BER buffers. */
public final class BlendBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
    private final BlendModelKey modelKey;
    private final BlendRenderer renderer;
    private final BlendBlockEntitySnapshotFactory<? super T> snapshotFactory;

    BlendBlockEntityRenderer(BlockEntityRendererProvider.Context context, BlendModelKey modelKey,
            BlendRenderer renderer, BlendBlockEntitySnapshotFactory<? super T> snapshotFactory) {
        Objects.requireNonNull(context, "context");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
    }
    public static <T extends BlockEntity> BlendBlockEntityRendererBuilder<T> builder(
            BlockEntityRendererProvider.Context context, BlendModelKey modelKey) {
        return builder(context, modelKey, BlendLibClientServices.renderer());
    }
    public static <T extends BlockEntity> BlendBlockEntityRendererBuilder<T> builder(
            BlockEntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer) {
        return new BlendBlockEntityRendererBuilder<>(context, modelKey, renderer);
    }
    public BlendModelKey modelKey() { return modelKey; }
    public BlendBlockEntityRenderState createRenderState() { return new BlendBlockEntityRenderState(); }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
            int packedLight, int packedOverlay, Vec3 cameraPos) {
        Level level = blockEntity.getLevel();
        if (level == null) return;
        BlockPos position = blockEntity.getBlockPos();
        BlendResourceId dimension = BlendResourceId.parse(level.dimension().location().toString());
        double distance = cameraPos.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
        long tick = level.getGameTime();
        ModelRenderSnapshot snapshot = snapshotFactory.create(blockEntity, new BlendBlockEntitySnapshotRequest(
                modelKey, new BlendInstanceKey.BlockEntity(dimension, position.asLong()), partialTick, packedLight,
                tick, tick + partialTick, Double.isFinite(distance) && distance <= 65_536.0, distance));
        if (snapshot != null) renderer.submit(snapshot, poseStack, new LegacySubmitNodeCollector(buffers));
    }
}
