package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

/** Internal extraction-only construction for the P4 static/rigid rest-pose convenience path. */
final class StaticRestPoseEntitySnapshotFactory {
    private StaticRestPoseEntitySnapshotFactory() {
    }

    /**
     * Resolves and binds the current immutable render handle before submit. This method is called
     * only from an entity snapshot factory on the extraction path; no renderer submit code calls
     * the model lookup.
     */
    static ModelRenderSnapshot create(ClientModelLookup models, BlendEntitySnapshotRequest request) {
        return create(models, request, Transform.IDENTITY);
    }

    static ModelRenderSnapshot create(
            ClientModelLookup models, Entity entity, BlendEntitySnapshotRequest request) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(request, "request");
        return create(models, request, EntityRootTransforms.interpolatedYaw(entity, request.partialTick()));
    }

    private static ModelRenderSnapshot create(
            ClientModelLookup models, BlendEntitySnapshotRequest request, Transform rootTransform) {
        ClientModelLookup checkedModels = Objects.requireNonNull(models, "models");
        BlendEntitySnapshotRequest checkedRequest = Objects.requireNonNull(request, "request");
        ClientModelView model = checkedModels.resolve(checkedRequest.modelKey());
        ModelRenderHandle handle = model.renderHandle();
        return new ModelRenderSnapshot(
                handle,
                Objects.requireNonNull(rootTransform, "rootTransform"),
                checkedRequest.packedLight(),
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }
}
