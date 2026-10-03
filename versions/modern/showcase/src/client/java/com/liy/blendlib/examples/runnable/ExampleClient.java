package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachment;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import com.liy.blendlib.fabric.client.entity.BlendEntitySnapshotRequest;
import com.liy.blendlib.fabric.client.entity.BlendEntitySockets;
import com.liy.blendlib.fabric.client.item.BlendLibItemAnimations;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;

/** A real client consumer of the shipped version-specific public animation adapters. */
public final class ExampleClient implements ClientModInitializer {
    private static final ExampleAttachmentOwners ATTACHMENT_OWNERS = new ExampleAttachmentOwners();
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":attack");

    @Override
    public void onInitializeClient() {
        BlendLibItemAnimations.register(new BlendLibItemBinding(
                ExampleContent.WAND_ID, ExampleContent.WAND_MODEL, ExampleContent.id("item/animated_wand")),
                ExampleContent.IDLE);
        BlendEntityRenderers.register(ExampleContent.ACTOR,
                context -> BlendEntityRenderer.<LayeredActor>builder(context, ExampleContent.APPEARANCE_ACTOR_MODEL)
                        .materialAppearance((entity, request) -> ExampleMaterialAppearance.forName(
                                entity.getCustomName() == null ? null : entity.getCustomName().getString()))
                        .skinnedAnimation((entity, request) -> ExampleContent.WALK)
                        .animationLayerCues(ExampleAnimationScene.layers(), ExampleClient::cues)
                        .onAnimationLayerVisualEvent((entity, event) -> entity.visualEvents().accept(event))
                        .animationLayerWeights((entity, request) -> ExampleAnimationScene.clipLayerWeights(
                                request.clientGameTick() + (double) request.partialTick() + entity.getId() % 8 * 10.0))
                        .poseComponents(ExampleAnimationScene.procedural())
                        .attachments(ExampleClient::attachments)
                        .shadowRadius(0.45F)
                        .build());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register(
                (entity, level) -> ATTACHMENT_OWNERS.remove(entity,
                        BlendLibClientServices.skinnedAnimationRuntime()::retire));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> ATTACHMENT_OWNERS.clear(
                        BlendLibClientServices.skinnedAnimationRuntime()::retire));
        ExampleItemCommands.register();
        ExampleInspectionCommands.register();
    }

    private static List<BlendEntityLayerCue> cues(LayeredActor entity, BlendEntitySnapshotRequest request) {
        return entity.cueSequence() == 0 ? List.of() : List.of(new BlendEntityLayerCue(
                ExampleAnimationScene.UPPER, ATTACK, entity.cueSequence(), entity.cueTick(), 1.0));
    }

    private static List<BlendEntityAttachment> attachments(
            LayeredActor entity, BlendEntitySnapshotRequest request, BlendEntitySockets sockets) {
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        var active = runtime.activeEntityKey(entity.getId());
        if (entity.isRemoved() || active.isEmpty()) {
            ATTACHMENT_OWNERS.remove(entity, runtime::retire);
            return List.of();
        }
        var owner = ATTACHMENT_OWNERS.key(entity, active.orElseThrow().connectionSession(), runtime::retire);
        return ExampleAttachmentScene.capture(BlendLibClientServices.models(), runtime, owner, request, sockets,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }
}
