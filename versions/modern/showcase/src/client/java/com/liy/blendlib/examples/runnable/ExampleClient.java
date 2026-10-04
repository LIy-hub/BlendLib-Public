package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachment;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import com.liy.blendlib.fabric.client.entity.BlendEntitySnapshotRequest;
import com.liy.blendlib.fabric.client.entity.BlendEntitySockets;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.world.item.ItemStack;

/** A real client consumer of the shipped version-specific public animation adapters. */
public final class ExampleClient implements ClientModInitializer {
    private static final ExampleAttachmentScene.Mode ATTACHMENT_MODE = ExampleAttachmentScene.configuredMode();
    private static final ExampleAttachmentOwners ATTACHMENT_OWNERS = new ExampleAttachmentOwners();
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse(ExampleContent.MOD_ID + ":attack");
    private static final boolean ITEM_VISUAL_EVENTS_ENABLED = Boolean.getBoolean("blendlib.examples.itemVisualEvents");
    private static final ExampleItemVisualEvents<ItemStack> ITEM_VISUAL_EVENTS = new ExampleItemVisualEvents<>();

    @Override
    public void onInitializeClient() {
        boolean itemAppearance = Boolean.getBoolean("blendlib.examples.itemAppearance");
        boolean namedSkins = Boolean.getBoolean("blendlib.examples.namedSkins");
        boolean locomotionRules = ExampleLocomotionScene.enabled();
        if (namedSkins) ExampleNamedSkins.register();
        if (ITEM_VISUAL_EVENTS_ENABLED) {
            ExampleItemMaterialAppearance.register(itemAppearance, namedSkins, ITEM_VISUAL_EVENTS::accept);
        } else {
            ExampleItemMaterialAppearance.register(itemAppearance, namedSkins);
        }
        BlendEntityRenderers.register(ExampleContent.ACTOR,
                context -> {
                    if (ExampleTwoBoneIkScene.enabled()) {
                        return BlendEntityRenderer.<LayeredActor>builder(context, ExampleTwoBoneIkScene.MODEL)
                                .skinnedAnimation((entity, request) -> ExampleTwoBoneIkScene.IDLE)
                                .poseComponents(ExampleTwoBoneIkScene.procedural())
                                .attachments((entity, request, sockets) -> ExampleTwoBoneIkScene.attachments(
                                        BlendLibClientServices.models(), request, sockets,
                                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY))
                                .cullingEnvelope(ExampleTwoBoneIkScene.ENVELOPE)
                                .shadowRadius(0.45F)
                                .build();
                    }
                    var builder = BlendEntityRenderer.<LayeredActor>builder(context, locomotionRules
                            ? ExampleLocomotionScene.MODEL : ExampleContent.APPEARANCE_ACTOR_MODEL)
                        .materialAppearance((entity, request) -> ExampleMaterialAppearance.forName(
                                appearanceName(entity, namedSkins)))
                        .skinnedAnimation((entity, request) -> ExampleContent.WALK)
                        .animationLayerCues(locomotionRules ? ExampleLocomotionScene.layers() : ExampleAnimationScene.layers(),
                                ExampleClient::cues)
                        .onAnimationLayerVisualEvent((entity, event) -> entity.visualEvents().accept(event))
                        .animationLayerWeights((entity, request) -> ExampleAnimationScene.clipLayerWeights(
                                request.clientGameTick() + (double) request.partialTick() + entity.getId() % 8 * 10.0))
                        .poseComponents(ExampleAnimationScene.procedural())
                        .attachments(ExampleClient::attachments)
                        .shadowRadius(0.45F);
                    if (locomotionRules) builder.animationLocomotionRules(ExampleAnimationScene.BASE,
                            (entity, request) -> ExampleLocomotionScene.inputs(
                                    entity.locomotionGrounded(), entity.locomotionSpeed()));
                    if (namedSkins) builder.skin((entity, request) -> ExampleNamedSkins.forName(
                            entity.getCustomName() == null ? null : entity.getCustomName().getString()));
                    ATTACHMENT_MODE.cullingEnvelope().ifPresent(builder::cullingEnvelope);
                    return builder.build();
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register(
                (entity, level) -> ATTACHMENT_OWNERS.remove(entity,
                        BlendLibClientServices.skinnedAnimationRuntime()::retire));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    ATTACHMENT_OWNERS.clear(BlendLibClientServices.skinnedAnimationRuntime()::retire);
                    ITEM_VISUAL_EVENTS.clear();
                });
        ExampleItemCommands.register(ITEM_VISUAL_EVENTS_ENABLED, ITEM_VISUAL_EVENTS);
        ExampleInspectionCommands.register(locomotionRules && !ExampleTwoBoneIkScene.enabled());
    }

    private static String appearanceName(LayeredActor entity, boolean namedSkins) {
        String name = entity.getCustomName() == null ? null : entity.getCustomName().getString();
        return namedSkins ? ExampleNamedSkins.appearanceName(name) : name;
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
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, ATTACHMENT_MODE);
    }
}
