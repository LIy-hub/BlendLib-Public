package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.fabric.v262.model.Fabric262FrameState;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSubmitter;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Public 26.2 entity dispatcher that performs extraction-time snapshot acquisition and submit-time
 * standard collector submission.
 */
final class Fabric262EntityRenderer<E extends Entity> extends EntityRenderer<E, Fabric262EntityRenderState> {
    private final Fabric262RegisteredHost registered;
    private final Fabric262HostRenderDispatcher dispatcher;
    private final Fabric262RenderSubmitter submitter;

    Fabric262EntityRenderer(
            EntityRendererProvider.Context context,
            Fabric262RegisteredHost registered,
            Fabric262HostRenderDispatcher dispatcher,
            Fabric262RenderSubmitter submitter) {
        super(Objects.requireNonNull(context, "context"));
        this.registered = Objects.requireNonNull(registered, "registered");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    @Override
    public Fabric262EntityRenderState createRenderState() {
        return new Fabric262EntityRenderState();
    }

    @Override
    public void extractRenderState(E entity, Fabric262EntityRenderState state, float partialTick) {
        Objects.requireNonNull(entity, "entity");
        Fabric262EntityRenderState checkedState = Objects.requireNonNull(state, "state");
        super.extractRenderState(entity, checkedState, partialTick);
        checkedState.replaceSnapshot(dispatcher.acquireEntityOrNull(
                registered,
                entity,
                Fabric262FrameState.white(checkedState.lightCoords, 0),
                entity.level().getGameTime() + (double) partialTick));
    }

    @Override
    public void submit(
            Fabric262EntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraRenderState) {
        Fabric262EntityRenderState checkedState = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        Objects.requireNonNull(cameraRenderState, "cameraRenderState");
        super.submit(checkedState, poseStack, collector, cameraRenderState);
        Fabric262DispatchSnapshot snapshot = checkedState.snapshotForSubmit();
        if (snapshot != null) {
            Throwable submitFailure = null;
            try {
                var rawSnapshot = snapshot.snapshot();
                submitter.submit(rawSnapshot, poseStack, collector, rawSnapshot.frame(), snapshot.pose());
            } catch (RuntimeException | Error failure) {
                submitFailure = failure;
                throw failure;
            } finally {
                try {
                    checkedState.releaseSubmittedSnapshot(snapshot);
                } catch (RuntimeException closeFailure) {
                    if (submitFailure != null) {
                        submitFailure.addSuppressed(closeFailure);
                    } else {
                        throw closeFailure;
                    }
                }
            }
        }
    }
}
