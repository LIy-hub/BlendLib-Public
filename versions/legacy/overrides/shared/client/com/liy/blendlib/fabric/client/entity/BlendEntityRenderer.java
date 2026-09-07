package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.compat.LegacySubmitNodeCollector;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.SkinnedSocketMarkerSubmitter;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Public legacy entity adapter; animation extraction stays separate from buffer submission. */
public final class BlendEntityRenderer<E extends Entity> extends EntityRenderer<E, BlendEntityRenderState> {
    private final BlendModelKey modelKey;
    private final BlendRenderer renderer;
    private final BlendEntitySnapshotFactory<? super E> snapshotFactory;
    private final boolean rotationInvariantCulling;

    BlendEntityRenderer(EntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer,
            BlendEntitySnapshotFactory<? super E> snapshotFactory, boolean rotationInvariantCulling,
            float shadowRadius, float shadowStrength) {
        super(Objects.requireNonNull(context, "context"));
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        this.rotationInvariantCulling = rotationInvariantCulling;
        this.shadowRadius = shadowRadius;
        this.shadowStrength = shadowStrength;
    }
    public static <E extends Entity> BlendEntityRendererBuilder<E> builder(
            EntityRendererProvider.Context context, BlendModelKey modelKey) {
        return builder(context, modelKey, BlendLibClientServices.renderer());
    }
    public static <E extends Entity> BlendEntityRendererBuilder<E> builder(
            EntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer) {
        return new BlendEntityRendererBuilder<>(context, modelKey, renderer);
    }
    public BlendModelKey modelKey() { return modelKey; }
    @Override public BlendEntityRenderState createRenderState() { return new BlendEntityRenderState(); }
    @Override protected AABB getBoundingBoxForCulling(E entity) {
        return EntityCullingBounds.unionWithCurrentModelBounds(BlendLibClientServices.models(), modelKey,
                super.getBoundingBoxForCulling(entity), entity.getX(), entity.getY(), entity.getZ(), rotationInvariantCulling);
    }
    @Override public void extractRenderState(E entity, BlendEntityRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.setSnapshot(snapshotFactory.create(entity, new BlendEntitySnapshotRequest(modelKey, partialTick,
                getPackedLightCoords(entity, partialTick), state.ageInTicks, state.x, state.y, state.z,
                entity.level().getGameTime(), entityRenderDispatcher.camera != null && !state.isInvisible,
                state.distanceToCameraSq)));
    }
    @Override public void render(BlendEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers, int light) {
        super.render(state, poseStack, buffers, light);
        ModelRenderSnapshot snapshot = state.snapshotOrNull();
        if (snapshot != null) {
            LegacySubmitNodeCollector collector = new LegacySubmitNodeCollector(buffers);
            renderer.submit(snapshot, poseStack, collector);
            SkinnedSocketMarkerSubmitter.submit(snapshot, poseStack, collector);
        }
    }
}
