package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBuckets;
import com.liy.blendlib.fabric.client.animation.event.VisualEventDispatcher;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

/**
 * P5 extraction-only bridge from one entity to a captured strict-v1 animated render snapshot.
 *
 * <p>It resolves the already-published current model generation and advances only the
 * entrypoint-owned client runtime before building an immutable handoff. Rendering later sees no
 * entity, controller, resource lookup, parser, or lifecycle state.</p>
 */
final class SkinnedAnimationEntitySnapshotFactory<E extends Entity> implements BlendEntitySnapshotFactory<E> {
    private final BlendModelKey modelKey;
    private final SkinnedAnimationStateSelector<? super E> stateSelector;
    private final SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector;
    private final SkinnedAnimationVisualEventHandler<? super E> visualEventHandler;
    private final BlendEntityPoseModifier<? super E> poseModifier;
    private final BlendEntityRootRotationSelector<? super E> rootRotationSelector;
    private final BlendResourceId presentationSocketMarkerKey;
    private final java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers;
    private final BlendEntityLayerCommands<? super E> layerCommands;
    private final BlendEntityLayerWeights<? super E> layerWeights;
    private final com.liy.blendlib.core.animation.v2.AnimationBlendSpaceSyncGroup blendSpace;
    private final java.util.function.BiFunction<E, BlendEntitySnapshotRequest, com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights> blendSpaceWeights;
    private final BlendEntityBlendSpaceCadence<? super E> blendSpaceCadence;
    private final BlendEntityLayerVisualEventHandler<? super E> layerVisualEventHandler;
    private final com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents;
    private final BlendEntitySocketHandler<? super E> socketHandler;
    private final BlendEntityAttachmentProvider<? super E> attachmentProvider;
    private final VisualEventDispatcher visualEvents = new VisualEventDispatcher();

    SkinnedAnimationEntitySnapshotFactory(
            BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey) {
        this(modelKey, stateSelector, syncedStateSelector, visualEventHandler, poseModifier, rootRotationSelector,
                presentationSocketMarkerKey, null, null, null, null, null);
    }

    SkinnedAnimationEntitySnapshotFactory(BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey,
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers,
            BlendEntityLayerCommands<? super E> layerCommands,
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider) {
        this(modelKey, stateSelector, syncedStateSelector, visualEventHandler, poseModifier, rootRotationSelector,
                presentationSocketMarkerKey, animationLayers, layerCommands, poseComponents, socketHandler,
                attachmentProvider, null);
    }

    SkinnedAnimationEntitySnapshotFactory(BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey,
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers,
            BlendEntityLayerCommands<? super E> layerCommands,
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider,
            BlendEntityLayerWeights<? super E> layerWeights) {
        this(modelKey, stateSelector, syncedStateSelector, visualEventHandler, poseModifier, rootRotationSelector,
                presentationSocketMarkerKey, animationLayers, layerCommands, poseComponents, socketHandler,
                attachmentProvider, layerWeights, null);
    }

    SkinnedAnimationEntitySnapshotFactory(BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey,
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers,
            BlendEntityLayerCommands<? super E> layerCommands,
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider,
            BlendEntityLayerWeights<? super E> layerWeights,
            BlendEntityLayerVisualEventHandler<? super E> layerVisualEventHandler) {
        this(modelKey, stateSelector, syncedStateSelector, visualEventHandler, poseModifier, rootRotationSelector,
                presentationSocketMarkerKey, animationLayers, layerCommands, poseComponents, socketHandler,
                attachmentProvider, layerWeights, layerVisualEventHandler,
                (com.liy.blendlib.core.animation.v2.AnimationBlendSpaceSyncGroup) null, null);
    }

    SkinnedAnimationEntitySnapshotFactory(BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey,
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers,
            BlendEntityLayerCommands<? super E> layerCommands,
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider,
            BlendEntityLayerWeights<? super E> layerWeights,
            BlendEntityLayerVisualEventHandler<? super E> layerVisualEventHandler,
            com.liy.blendlib.core.animation.v2.AnimationBlendSpace1D blendSpace,
            BlendEntityBlendSpaceParameter<? super E> blendSpaceParameter) {
        this(modelKey, stateSelector, syncedStateSelector, visualEventHandler, poseModifier, rootRotationSelector,
                presentationSocketMarkerKey, animationLayers, layerCommands, poseComponents, socketHandler,
                attachmentProvider, layerWeights, layerVisualEventHandler, blendSpace == null ? null : blendSpace.syncGroup(),
                blendSpace == null ? null : (entity, request) -> blendSpace.weights(blendSpaceParameter.parameter(entity, request)));
    }

    SkinnedAnimationEntitySnapshotFactory(BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey,
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers,
            BlendEntityLayerCommands<? super E> layerCommands,
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider,
            BlendEntityLayerWeights<? super E> layerWeights,
            BlendEntityLayerVisualEventHandler<? super E> layerVisualEventHandler,
            com.liy.blendlib.core.animation.v2.AnimationBlendSpaceSyncGroup blendSpace,
            java.util.function.BiFunction<E, BlendEntitySnapshotRequest, com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights> blendSpaceWeights) {
        this(modelKey, stateSelector, syncedStateSelector, visualEventHandler, poseModifier, rootRotationSelector,
                presentationSocketMarkerKey, animationLayers, layerCommands, poseComponents, socketHandler,
                attachmentProvider, layerWeights, layerVisualEventHandler, blendSpace, blendSpaceWeights, null);
    }

    SkinnedAnimationEntitySnapshotFactory(BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey,
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers,
            BlendEntityLayerCommands<? super E> layerCommands,
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents,
            BlendEntitySocketHandler<? super E> socketHandler,
            BlendEntityAttachmentProvider<? super E> attachmentProvider,
            BlendEntityLayerWeights<? super E> layerWeights,
            BlendEntityLayerVisualEventHandler<? super E> layerVisualEventHandler,
            com.liy.blendlib.core.animation.v2.AnimationBlendSpaceSyncGroup blendSpace,
            java.util.function.BiFunction<E, BlendEntitySnapshotRequest, com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights> blendSpaceWeights,
            BlendEntityBlendSpaceCadence<? super E> blendSpaceCadence) {
        this.blendSpaceCadence = blendSpaceCadence;
        this.blendSpace = blendSpace;
        this.blendSpaceWeights = blendSpaceWeights;
        this.layerVisualEventHandler = layerVisualEventHandler;
        this.layerWeights = layerWeights;
        this.animationLayers = animationLayers;
        this.layerCommands = layerCommands;
        this.poseComponents = poseComponents;
        this.socketHandler = socketHandler;
        this.attachmentProvider = attachmentProvider;
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.stateSelector = Objects.requireNonNull(stateSelector, "stateSelector");
        this.syncedStateSelector = syncedStateSelector;
        this.visualEventHandler = visualEventHandler;
        this.poseModifier = poseModifier;
        this.rootRotationSelector = rootRotationSelector;
        this.presentationSocketMarkerKey = presentationSocketMarkerKey;
    }

    @Override
    public ModelRenderSnapshot create(E entity, BlendEntitySnapshotRequest request) {
        E checkedEntity = Objects.requireNonNull(entity, "entity");
        BlendEntitySnapshotRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (!modelKey.equals(checkedRequest.modelKey())) {
            throw new IllegalArgumentException("Skinned entity snapshot request does not match the renderer model key");
        }

        Transform rootTransform = EntityRootTransforms.selected(
                checkedEntity, checkedRequest, rootRotationSelector);
        ClientModelView model = BlendLibClientServices.models().resolve(modelKey);
        if (model.missing()) {
            return missingSnapshot(model, checkedRequest, rootTransform);
        }

        BlendAnimationKey desiredAnimation = Objects.requireNonNull(
                stateSelector.select(checkedEntity, checkedRequest), "selected animation key");
        Optional<SyncedAnimationState> syncedAnimation = syncedStateSelector == null
                ? Optional.empty()
                : Objects.requireNonNull(
                        syncedStateSelector.select(checkedEntity, checkedRequest), "selected synced animation state");
        var animationRuntime = BlendLibClientServices.skinnedAnimationRuntime();
        Optional<BlendInstanceKey.Entity> instanceKey = animationRuntime.activeEntityKey(checkedEntity.getId());
        if (instanceKey.isEmpty()) {
            return missingSnapshot(model, checkedRequest, rootTransform);
        }
        RenderVisibility visibility = visibilityFor(checkedRequest);
        SkinnedAnimationRuntimeInput runtimeInput = new SkinnedAnimationRuntimeInput(
                modelKey,
                instanceKey.orElseThrow(),
                checkedRequest.clientGameTick(),
                checkedRequest.partialTick(),
                desiredAnimation,
                syncedAnimation,
                AnimationUpdateBuckets.select(
                        checkedRequest.animationVisible(),
                        distanceSquaredToVisualEnvelope(
                                checkedRequest.distanceToCameraSq(), model.renderHandle().bounds())),
                new SkinnedExtractionRequest(
                        rootTransform,
                        checkedRequest.packedLight(),
                        OverlayTexture.NO_OVERLAY,
                        0xFFFFFFFF,
                        visibility,
                        new CullingMetadata(model.renderHandle().bounds(), true)));
        com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier combinedModifier =
                (animationContext, basePose) -> {
                    var current = basePose;
                    if (poseModifier != null) {
                        BlendEntityRotationPose capturedBase = BlendEntityRotationPoseAdapter.capture(current);
                        var modified = poseModifier.modify(checkedEntity,
                                new BlendEntityPoseContext(checkedRequest, animationContext), capturedBase);
                        current = BlendEntityRotationPoseAdapter.apply(current, capturedBase, modified);
                    }
                    return poseComponents == null ? current : poseComponents.modify(animationContext, current);
                };
        long capturedLifecycle = animationRuntime.captureExtractionLifecycleRevision();
        com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights memberWeights = null;
        double cadence = 1.0;
        if (blendSpace != null) {
            if (!animationRuntime.validateBlendSpaceBinding(modelKey, model.generationId(), animationLayers, blendSpace))
                return missingSnapshot(model, checkedRequest, rootTransform);
            memberWeights = Objects.requireNonNull(blendSpaceWeights.apply(checkedEntity, checkedRequest), "captured blendspace weights"); // preflight before any command/cue capture
            if (capturedLifecycle != animationRuntime.captureExtractionLifecycleRevision()
                    || !instanceKey.equals(animationRuntime.activeEntityKey(checkedEntity.getId()))
                    || model.generationId() != BlendLibClientServices.models().resolve(modelKey).generationId())
                return missingSnapshot(model, checkedRequest, rootTransform);
            cadence = blendSpaceCadence == null ? 1.0 : blendSpaceCadence.multiplier(checkedEntity, checkedRequest);
            if (capturedLifecycle != animationRuntime.captureExtractionLifecycleRevision()
                    || !instanceKey.equals(animationRuntime.activeEntityKey(checkedEntity.getId()))
                    || model.generationId() != BlendLibClientServices.models().resolve(modelKey).generationId())
                return missingSnapshot(model, checkedRequest, rootTransform);
            // Validate every derived member rate before external weights and cue transactions.
            if (!animationRuntime.validateBlendSpaceCadence(modelKey, model.generationId(), animationLayers, blendSpace, cadence))
                return missingSnapshot(model, checkedRequest, rootTransform);
        }
        var layerFrame = animationLayers == null ? null
                : captureBlendGroupFrame(animationLayers, layerWeights, layerCommands, checkedEntity, checkedRequest,
                        blendSpace, animationRuntime);
        if ((animationLayers != null && layerFrame == null) || capturedLifecycle != animationRuntime.captureExtractionLifecycleRevision()
                || !instanceKey.equals(animationRuntime.activeEntityKey(checkedEntity.getId()))
                || model.generationId() != BlendLibClientServices.models().resolve(modelKey).generationId()) {
            return missingSnapshot(model, checkedRequest, rootTransform);
        }
        var extraction = layerFrame == null
                ? animationRuntime.extract(runtimeInput, combinedModifier)
                : blendSpace != null
                ? animationRuntime.extractBlendSpaceFrame(runtimeInput, animationLayers, layerFrame.commands(),
                        layerFrame.weights(), blendSpace, memberWeights, cadence, this, checkedEntity, combinedModifier,
                        layerVisualEventHandler == null ? null
                                : event -> layerVisualEventHandler.onVisualEvent(checkedEntity, event))
                : animationRuntime.extractLayered(runtimeInput, animationLayers,
                        layerFrame.commands(), layerFrame.weights(), combinedModifier,
                        layerVisualEventHandler == null ? null
                                : event -> layerVisualEventHandler.onVisualEvent(checkedEntity, event));
        ModelRenderSnapshot extracted = extraction
                .map(result -> {
                    visualEvents.dispatch(
                            result.instanceKey(),
                            result.advance(),
                            visualEventHandler == null
                                    ? null
                                    : (eventInstanceKey, event) -> visualEventHandler.onVisualEvent(
                                            checkedEntity, event.eventKey()));
                    ModelRenderSnapshot capturedSnapshot = result.frame().renderSnapshot();
                    if (socketHandler != null || attachmentProvider != null) {
                        var sockets = BlendEntitySockets.capture(checkedRequest, result.frame());
                        if (socketHandler != null) socketHandler.onSockets(checkedEntity, checkedRequest, sockets);
                        if (attachmentProvider != null) capturedSnapshot = capturedSnapshot.withAttachments(
                                java.util.List.copyOf(attachmentProvider.attachments(checkedEntity, checkedRequest, sockets)));
                    }
                    if (presentationSocketMarkerKey != null) {
                        var socket = result.frame().socketTransform(presentationSocketMarkerKey);
                        if (socket.isPresent()) return capturedSnapshot.withPresentationSocketTransform(socket.get());
                    }
                    return capturedSnapshot;
                })
                .orElseGet(() -> missingSnapshot(model, checkedRequest, rootTransform));
        return extracted;
    }

    /** Preflight weights before command/cue sources can commit their first-capture state. */
    static <E extends Entity> CapturedLayerFrame captureLayerFrame(
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> layers,
            BlendEntityLayerWeights<? super E> weightSource, BlendEntityLayerCommands<? super E> commands,
            E entity, BlendEntitySnapshotRequest request) {
        return captureLayerFrame(layers, weightSource, commands, entity, request, null, null);
    }

    static <E extends Entity> CapturedLayerFrame captureLayerFrame(
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> layers,
            BlendEntityLayerWeights<? super E> weightSource, BlendEntityLayerCommands<? super E> commands,
            E entity, BlendEntitySnapshotRequest request,
            com.liy.blendlib.core.animation.v2.AnimationBlendSpace1D blendSpace,
            com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime runtime) {
        return captureBlendGroupFrame(layers, weightSource, commands, entity, request,
                blendSpace == null ? null : blendSpace.syncGroup(), runtime);
    }

    static <E extends Entity> CapturedLayerFrame captureBlendGroupFrame(
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> layers,
            BlendEntityLayerWeights<? super E> weightSource, BlendEntityLayerCommands<? super E> commands,
            E entity, BlendEntitySnapshotRequest request,
            com.liy.blendlib.core.animation.v2.AnimationBlendSpaceSyncGroup blendSpace,
            com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime runtime) {
        long revision = runtime == null ? -1 : runtime.captureExtractionLifecycleRevision();
        var weights = weightSource == null
                ? com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights.empty()
                : Objects.requireNonNull(weightSource.weights(entity, request), "captured layer weights");
        for (var key : weights.multipliers().keySet()) {
            boolean declared = false;
            for (var layer : layers) {
                if (layer.id().equals(key.controllerId()) && layer.id().equals(key.layerId())) {
                    declared = true;
                    break;
                }
            }
            if (!declared) throw new IllegalArgumentException("undeclared entity layer target: " + key);
        }
        if (runtime != null && revision != runtime.captureExtractionLifecycleRevision()) return null;
        if (blendSpace != null) blendSpace.validateExternalWeights(weights);
        var capturedCommands = blendSpace == null ? java.util.List.copyOf(commands.commands(entity, request))
                : runtime.captureBlendSpaceCommands(blendSpace, () -> commands.commands(entity, request));
        return new CapturedLayerFrame(capturedCommands, weights);
    }

    record CapturedLayerFrame(java.util.List<com.liy.blendlib.core.animation.v2.AnimationV2Command> commands,
            com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights weights) { }

    /**
     * Conservatively measures camera distance from a model's visible envelope instead of only its
     * entity origin. This keeps colossal models smooth while the camera is near any visible part,
     * without disabling distance buckets for ordinary or genuinely distant entities.
     */
    static double distanceSquaredToVisualEnvelope(double originDistanceSquared, Bounds modelBounds) {
        Objects.requireNonNull(modelBounds, "modelBounds");
        if (!Double.isFinite(originDistanceSquared) || originDistanceSquared < 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        double radiusX = Math.max(Math.abs(modelBounds.min().x()), Math.abs(modelBounds.max().x()));
        double radiusY = Math.max(Math.abs(modelBounds.min().y()), Math.abs(modelBounds.max().y()));
        double radiusZ = Math.max(Math.abs(modelBounds.min().z()), Math.abs(modelBounds.max().z()));
        double visualRadius = Math.sqrt(radiusX * radiusX + radiusY * radiusY + radiusZ * radiusZ);
        double distanceToEnvelope = Math.max(0.0D, Math.sqrt(originDistanceSquared) - visualRadius);
        return distanceToEnvelope * distanceToEnvelope;
    }

    private static ModelRenderSnapshot missingSnapshot(
            ClientModelView model, BlendEntitySnapshotRequest request, Transform rootTransform) {
        ModelRenderHandle handle = model.missing()
                ? model.renderHandle()
                : new MissingModelRenderHandle(request.modelKey(), model.generationId());
        return new ModelRenderSnapshot(
                handle,
                Objects.requireNonNull(rootTransform, "rootTransform"),
                request.packedLight(),
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF,
                visibilityFor(request),
                new CullingMetadata(handle.bounds(), true));
    }

    private static RenderVisibility visibilityFor(BlendEntitySnapshotRequest request) {
        return request.animationVisible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED;
    }
}
