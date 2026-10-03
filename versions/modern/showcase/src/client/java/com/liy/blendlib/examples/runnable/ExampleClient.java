package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachment;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import com.liy.blendlib.fabric.client.entity.BlendEntityRotation;
import com.liy.blendlib.fabric.client.entity.BlendEntitySocketPose;
import com.liy.blendlib.fabric.client.entity.BlendEntitySnapshotRequest;
import com.liy.blendlib.fabric.client.entity.BlendEntitySockets;
import com.liy.blendlib.fabric.client.item.BlendLibItemAnimations;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** A real client consumer of the shipped version-specific public animation adapters. */
public final class ExampleClient implements ClientModInitializer {
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":attack");

    @Override
    public void onInitializeClient() {
        BlendLibItemAnimations.register(new BlendLibItemBinding(
                ExampleContent.WAND_ID, ExampleContent.WAND_MODEL, ExampleContent.id("item/animated_wand")),
                ExampleContent.IDLE);
        BlendEntityRenderers.register(ExampleContent.ACTOR,
                context -> BlendEntityRenderer.<LayeredActor>builder(context, ExampleContent.ACTOR_MODEL)
                        .skinnedAnimation((entity, request) -> ExampleContent.WALK)
                        .animationLayerCues(ExampleAnimationScene.layers(), ExampleClient::cues)
                        .animationLayerWeights((entity, request) -> ExampleAnimationScene.clipLayerWeights(
                                request.clientGameTick() + (double) request.partialTick() + entity.getId() % 8 * 10.0))
                        .poseComponents(ExampleAnimationScene.procedural())
                        .attachments(ExampleClient::attachments)
                        .shadowRadius(0.45F)
                        .build());
        ExampleItemCommands.register();
        ExampleInspectionCommands.register();
    }

    private static List<BlendEntityLayerCue> cues(LayeredActor entity, BlendEntitySnapshotRequest request) {
        return entity.cueSequence() == 0 ? List.of() : List.of(new BlendEntityLayerCue(
                ExampleAnimationScene.UPPER, ATTACK, entity.cueSequence(), entity.cueTick(), 1.0));
    }

    private static List<BlendEntityAttachment> attachments(
            LayeredActor entity, BlendEntitySnapshotRequest request, BlendEntitySockets sockets) {
        var socket = sockets.socket(ExampleContent.TIP);
        if (socket.isEmpty()) return List.of();
        // Resolve an already-loaded immutable handle only during extraction. Never read assets,
        // retain a generation across reload, or look up sockets from a render-submit callback.
        var model = BlendLibClientServices.models().resolve(ExampleContent.MARKER_MODEL);
        var handle = model.renderHandle();
        if (model.missing() || handle.generation() != sockets.generation()) return List.of();
        var child = new ModelRenderSnapshot(handle, Transform.IDENTITY, request.packedLight(),
                OverlayTexture.NO_OVERLAY, 0xFFFFC040, RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        var offset = new BlendEntitySocketPose(0, 0.45, 0, BlendEntityRotation.IDENTITY, 0.18F);
        return List.of(BlendEntityAttachment.at(socket.orElseThrow(), offset, child));
    }
}
