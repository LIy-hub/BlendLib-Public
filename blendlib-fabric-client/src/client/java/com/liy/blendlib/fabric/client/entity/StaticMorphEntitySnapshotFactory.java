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

    private final BlendEntitySocketHandler<? super E> socketHandler;
    private final BlendEntityAttachmentProvider<? super E> attachmentProvider;

    StaticMorphEntitySnapshotFactory(BlendModelKey modelKey, BlendEntityMorphControls<? super E> controls,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.controls = controls;
        this.socketHandler = socketHandler;
        this.attachmentProvider = attachmentProvider;
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
                if (frame.isPresent()) {
                    var captured = captureAccessories(entity, request, frame.orElseThrow(), socketHandler,
                            attachmentProvider, () -> !entity.isRemoved()
                                    && revision == runtime.captureExtractionLifecycleRevision()
                                    && owner.equals(runtime.activeEntityKey(entity.getId()))
                                    && model.generationId() == BlendLibClientServices.models().resolve(modelKey).generationId());
                    if (captured.isPresent()) return captured.orElseThrow();
                }
            }
        }
        ModelRenderHandle fallback = model.missing() ? model.renderHandle()
                : new MissingModelRenderHandle(modelKey, model.generationId());
        return new ModelRenderSnapshot(fallback, root, request.packedLight(), OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF, visibility, new CullingMetadata(fallback.bounds(), true));
    }

    /** Capture callbacks once, with a lifecycle/generation fence before and after each callback.
     * Rest-pose sockets do not follow morph-displaced vertices. No callbacks survive into submit.
     */
    static <E extends Entity> java.util.Optional<ModelRenderSnapshot> captureAccessories(E entity,
            BlendEntitySnapshotRequest request,
            com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame frame,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider,
            java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean()) return java.util.Optional.empty();
        var snapshot = frame.renderSnapshot();
        if (socketHandler != null || attachmentProvider != null) {
            var sockets = BlendEntitySockets.capture(request, frame);
            if (socketHandler != null) {
                socketHandler.onSockets(entity, request, sockets);
                if (!current.getAsBoolean()) return java.util.Optional.empty();
            }
            if (attachmentProvider != null) {
                var attachments = java.util.List.copyOf(
                        attachmentProvider.attachments(entity, request, sockets));
                if (!current.getAsBoolean()) return java.util.Optional.empty();
                snapshot = snapshot.withAttachments(attachments);
            }
        }
        return java.util.Optional.of(snapshot);
    }
}
