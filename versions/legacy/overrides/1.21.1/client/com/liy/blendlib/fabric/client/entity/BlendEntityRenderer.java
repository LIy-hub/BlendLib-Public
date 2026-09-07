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
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** 1.21.1 renderer retaining model-aware culling and extraction before immutable CPU submission. */
public final class BlendEntityRenderer<E extends Entity> extends EntityRenderer<E> {
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
    public BlendEntityRenderState createRenderState() { return new BlendEntityRenderState(); }
    @Override public ResourceLocation getTextureLocation(E entity) { return MissingTextureAtlasSprite.getLocation(); }
    @Override public boolean shouldRender(E entity, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
        if (!entity.shouldRender(cameraX, cameraY, cameraZ)) return false;
        if (entity.noCulling) return true;
        AABB bounds = EntityCullingBounds.unionWithCurrentModelBounds(BlendLibClientServices.models(), modelKey,
                entity.getBoundingBoxForCulling(), entity.getX(), entity.getY(), entity.getZ(), rotationInvariantCulling);
        if (bounds.hasNaN() || bounds.getSize() == 0.0) {
            bounds = new AABB(entity.getX() - 2.0, entity.getY() - 2.0, entity.getZ() - 2.0,
                    entity.getX() + 2.0, entity.getY() + 2.0, entity.getZ() + 2.0);
        }
        return frustum.isVisible(bounds.inflate(0.5));
    }
    public void extractRenderState(E entity, BlendEntityRenderState state, float partialTick) {
        state.setSnapshot(snapshotFactory.create(entity, new BlendEntitySnapshotRequest(modelKey, partialTick,
                getPackedLightCoords(entity, partialTick), entity.tickCount + partialTick,
                Mth.lerp(partialTick, entity.xOld, entity.getX()), Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()), entity.level().getGameTime(),
                entityRenderDispatcher.camera != null && !entity.isInvisible(), entityRenderDispatcher.distanceToSqr(entity))));
    }
    @Override public void render(E entity, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        BlendEntityRenderState state = createRenderState();
        extractRenderState(entity, state, partialTick);
        super.render(entity, yaw, partialTick, poses, buffers, light);
        ModelRenderSnapshot snapshot = state.snapshotOrNull();
        if (snapshot != null) {
            LegacySubmitNodeCollector collector = new LegacySubmitNodeCollector(buffers);
            renderer.submit(snapshot, poses, collector);
            SkinnedSocketMarkerSubmitter.submit(snapshot, poses, collector);
        }
    }
}
