package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.fabric.v262.model.Fabric262FrameState;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSubmitter;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Public 26.2 block-entity dispatcher with exact extraction-to-submit snapshot ownership. */
final class Fabric262BlockEntityRenderer<T extends BlockEntity>
        implements BlockEntityRenderer<T, Fabric262BlockEntityRenderState> {
    private final Fabric262RegisteredHost registered;
    private final Fabric262HostRenderDispatcher dispatcher;
    private final Fabric262RenderSubmitter submitter;

    Fabric262BlockEntityRenderer(
            BlockEntityRendererProvider.Context context,
            Fabric262RegisteredHost registered,
            Fabric262HostRenderDispatcher dispatcher,
            Fabric262RenderSubmitter submitter) {
        Objects.requireNonNull(context, "context");
        this.registered = Objects.requireNonNull(registered, "registered");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    @Override
    public Fabric262BlockEntityRenderState createRenderState() {
        return new Fabric262BlockEntityRenderState();
    }

    @Override
    public void extractRenderState(
            T blockEntity,
            Fabric262BlockEntityRenderState state,
            float partialTick,
            Vec3 cameraPos,
            CrumblingOverlay crumblingOverlay) {
        T checkedBlockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
        Fabric262BlockEntityRenderState checkedState = Objects.requireNonNull(state, "state");
        BlockEntityRenderer.super.extractRenderState(
                checkedBlockEntity,
                checkedState,
                partialTick,
                Objects.requireNonNull(cameraPos, "cameraPos"),
                crumblingOverlay);
        if (checkedBlockEntity.getLevel() == null) {
            checkedState.clearSnapshot();
            return;
        }
        checkedState.replaceSnapshot(dispatcher.acquireBlockEntityOrNull(
                registered,
                checkedBlockEntity,
                Fabric262FrameState.white(checkedState.lightCoords, 0),
                checkedBlockEntity.getLevel().getGameTime() + (double) partialTick));
    }

    @Override
    public void submit(
            Fabric262BlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraRenderState) {
        Fabric262BlockEntityRenderState checkedState = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(collector, "collector");
        Objects.requireNonNull(cameraRenderState, "cameraRenderState");
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
