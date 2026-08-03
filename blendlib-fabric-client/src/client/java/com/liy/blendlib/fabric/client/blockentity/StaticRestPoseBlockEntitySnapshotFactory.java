package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Internal extraction-only construction for a block-local static or rigid pose. */
final class StaticRestPoseBlockEntitySnapshotFactory {
    private StaticRestPoseBlockEntitySnapshotFactory() {
    }

    /**
     * Resolves and binds the current immutable handle and its all-clip culling envelope before submit.
     *
     * <p>Minecraft has already translated the block-entity pose stack to the block position when
     * submit runs, so the captured root transform remains block-local identity.</p>
     */
    static ModelRenderSnapshot create(ClientModelLookup models, BlendBlockEntitySnapshotRequest request) {
        ClientModelLookup checkedModels = Objects.requireNonNull(models, "models");
        BlendBlockEntitySnapshotRequest checkedRequest = Objects.requireNonNull(request, "request");
        ClientModelView model = checkedModels.resolve(checkedRequest.modelKey());
        ModelRenderHandle handle = model.renderHandle();
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                checkedRequest.packedLight(),
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF,
                checkedRequest.animationVisible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED,
                new CullingMetadata(handle.bounds(), true));
    }
}
