package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.render.*;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

/** Stateless, extraction-only rest-pose CPU morph path. Controls are never retained between frames. */
final class StaticMorphEntitySnapshotFactory<E extends Entity> implements BlendEntitySnapshotFactory<E> {
    private final BlendModelKey modelKey;
    private final BlendEntityMorphControls<? super E> controls;

    StaticMorphEntitySnapshotFactory(BlendModelKey modelKey, BlendEntityMorphControls<? super E> controls) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.controls = controls;
    }

    @Override public ModelRenderSnapshot create(E entity, BlendEntitySnapshotRequest request) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(request, "request");
        if (!modelKey.equals(request.modelKey())) throw new IllegalArgumentException("Model key mismatch");
        var root = EntityRootTransforms.interpolatedYaw(entity, request.partialTick());
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        long revision = runtime.captureExtractionLifecycleRevision();
        var model = BlendLibClientServices.models().resolve(modelKey);
        var owner = runtime.activeEntityKey(entity.getId());
        var visibility = request.animationVisible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED;
        if (!model.missing() && owner.isPresent() && !entity.isRemoved()) {
            var weights = controls == null ? MorphFrameOverrides.empty()
                    : Objects.requireNonNull(controls.weights(entity, request), "captured morph controls");
            if (!entity.isRemoved()) {
                var frame = runtime.extractStaticMorph(modelKey, model.generationId(), owner.orElseThrow(), revision,
                        weights, new SkinnedExtractionRequest(root, request.packedLight(), OverlayTexture.NO_OVERLAY,
                                0xFFFFFFFF, visibility, new CullingMetadata(model.renderHandle().bounds(), true)));
                if (frame.isPresent()) return frame.orElseThrow().renderSnapshot();
            }
        }
        ModelRenderHandle fallback = model.missing() ? model.renderHandle()
                : new MissingModelRenderHandle(modelKey, model.generationId());
        return new ModelRenderSnapshot(fallback, root, request.packedLight(), OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF, visibility, new CullingMetadata(fallback.bounds(), true));
    }
}
