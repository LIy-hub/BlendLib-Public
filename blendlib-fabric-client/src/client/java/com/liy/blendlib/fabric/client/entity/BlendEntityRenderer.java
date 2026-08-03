package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.SkinnedSocketMarkerSubmitter;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Basic public 26.1.2 entity-renderer adapter.
 *
 * <p>Entity access is limited to vanilla culling and
 * {@link #extractRenderState(Entity, BlendEntityRenderState, float)}. Submit receives only the
 * immutable snapshot already stored in {@link BlendEntityRenderState}; it does not query the model
 * registry, resource manager, parser, controller, or world.</p>
 */
public final class BlendEntityRenderer<E extends Entity> extends EntityRenderer<E, BlendEntityRenderState> {
    private final BlendModelKey modelKey;
    private final BlendRenderer renderer;
    private final BlendEntitySnapshotFactory<? super E> snapshotFactory;
    private final boolean rotationInvariantCulling;

    BlendEntityRenderer(
            EntityRendererProvider.Context context,
            BlendModelKey modelKey,
            BlendRenderer renderer,
            BlendEntitySnapshotFactory<? super E> snapshotFactory,
            boolean rotationInvariantCulling,
            float shadowRadius,
            float shadowStrength) {
        super(Objects.requireNonNull(context, "context"));
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        this.rotationInvariantCulling = rotationInvariantCulling;
        this.shadowRadius = shadowRadius;
        this.shadowStrength = shadowStrength;
    }

    /**
     * Convenience builder using the renderer installed by the BlendLib client entrypoint.
     * Consumers that need deterministic registration ordering can use the overload accepting a
     * {@link BlendRenderer} explicitly.
     */
    public static <E extends Entity> BlendEntityRendererBuilder<E> builder(
            EntityRendererProvider.Context context, BlendModelKey modelKey) {
        return builder(context, modelKey, BlendLibClientServices.renderer());
    }

    /** Builder overload with an explicitly supplied snapshot-only renderer facade. */
    public static <E extends Entity> BlendEntityRendererBuilder<E> builder(
            EntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer) {
        return new BlendEntityRendererBuilder<>(context, modelKey, renderer);
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    @Override
    public BlendEntityRenderState createRenderState() {
        return new BlendEntityRenderState();
    }

    /**
     * Supplies vanilla's frustum path with the union of ordinary entity bounds and the current
     * generation's precomputed static/rigid model bounds. This lookup occurs before extraction and
     * submit; the submit method still consumes only its bound immutable snapshot.
     */
    @Override
    protected AABB getBoundingBoxForCulling(E entity) {
        E checkedEntity = Objects.requireNonNull(entity, "entity");
        return EntityCullingBounds.unionWithCurrentModelBounds(
                BlendLibClientServices.models(),
                modelKey,
                super.getBoundingBoxForCulling(checkedEntity),
                checkedEntity.getX(),
                checkedEntity.getY(),
                checkedEntity.getZ(),
                rotationInvariantCulling);
    }

    @Override
    public void extractRenderState(E entity, BlendEntityRenderState state, float partialTick) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(state, "state");
        super.extractRenderState(entity, state, partialTick);
        state.setSnapshot(snapshotFactory.create(entity, new BlendEntitySnapshotRequest(
                modelKey,
                partialTick,
                state.lightCoords,
                state.ageInTicks,
                state.x,
                state.y,
                state.z,
                entity.level().getGameTime(),
                entityRenderDispatcher.camera != null && !state.isInvisible,
                state.distanceToCameraSq)));
    }

    @Override
    public void submit(
            BlendEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraRenderState) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        Objects.requireNonNull(cameraRenderState, "cameraRenderState");
        super.submit(state, poseStack, collector, cameraRenderState);
        ModelRenderSnapshot snapshot = state.snapshotOrNull();
        if (snapshot != null) {
            renderer.submit(snapshot, poseStack, collector);
            SkinnedSocketMarkerSubmitter.submit(snapshot, poseStack, collector);
        }
    }
}
