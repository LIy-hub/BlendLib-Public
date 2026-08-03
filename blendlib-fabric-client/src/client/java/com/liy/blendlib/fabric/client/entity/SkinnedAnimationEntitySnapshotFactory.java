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
    private final VisualEventDispatcher visualEvents = new VisualEventDispatcher();

    SkinnedAnimationEntitySnapshotFactory(
            BlendModelKey modelKey,
            SkinnedAnimationStateSelector<? super E> stateSelector,
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler,
            BlendEntityPoseModifier<? super E> poseModifier,
            BlendEntityRootRotationSelector<? super E> rootRotationSelector,
            BlendResourceId presentationSocketMarkerKey) {
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
        var extraction = poseModifier == null
                ? animationRuntime.extract(runtimeInput)
                : animationRuntime.extract(
                        runtimeInput,
                        (animationContext, basePose) -> {
                            BlendEntityRotationPose capturedBase = BlendEntityRotationPoseAdapter.capture(basePose);
                            BlendEntityRotationPose modifiedPose = poseModifier.modify(
                                    checkedEntity,
                                    new BlendEntityPoseContext(checkedRequest, animationContext),
                                    capturedBase);
                            return BlendEntityRotationPoseAdapter.apply(basePose, capturedBase, modifiedPose);
                        });
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
                    if (presentationSocketMarkerKey != null) {
                        return result.frame().socketTransform(presentationSocketMarkerKey)
                                .map(capturedSnapshot::withPresentationSocketTransform)
                                .orElse(capturedSnapshot);
                    }
                    return capturedSnapshot;
                })
                .orElseGet(() -> missingSnapshot(model, checkedRequest, rootTransform));
        return extracted;
    }

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
