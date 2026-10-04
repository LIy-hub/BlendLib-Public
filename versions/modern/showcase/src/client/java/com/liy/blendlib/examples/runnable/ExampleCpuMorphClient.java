package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** CPU shape keys use the ordinary full-body controller, per-frame controls and immutable snapshots. */
public final class ExampleCpuMorphClient implements ClientModInitializer {
    private static final ExampleCpuMorphOwners OWNERS = new ExampleCpuMorphOwners();

    @Override public void onInitializeClient() {
        BlendEntityRenderers.register(ExampleCpuMorphContent.ACTOR, context ->
                BlendEntityRenderer.<CpuMorphActor>builder(context, ExampleCpuMorphControls.MODEL)
                        .skinnedAnimation((entity, request) -> controls(entity).animation())
                        .morphControls((entity, request) -> controls(entity).capture())
                        .onSkinnedVisualEvent((entity, event) -> controls(entity).event(event))
                        .materialAppearance((entity, request) -> ExampleCpuMorphScene.appearance(
                                entity.getCustomName() == null ? null : entity.getCustomName().getString()))
                        .attachments((entity, request, sockets) -> ExampleCpuMorphScene.attachments(
                                BlendLibClientServices.models(), request, sockets,
                                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY))
                        .cullingEnvelope(ExampleCpuMorphScene.ENVELOPE)
                        .shadowRadius(.45F)
                        .build());
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof CpuMorphActor actor) OWNERS.remove(actor.controls());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> OWNERS.clear());
        ExampleCpuMorphCommands.register();
    }

    static ExampleCpuMorphControls controls(CpuMorphActor actor) {
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        var active = runtime.activeEntityKey(actor.getId());
        var model = BlendLibClientServices.models().resolve(ExampleCpuMorphControls.MODEL);
        if (actor.isRemoved() || active.isEmpty() || model.missing()) {
            OWNERS.remove(actor.controls());
            return actor.controls();
        }
        return OWNERS.use(actor.controls(), model.generationId(), active.orElseThrow().connectionSession());
    }
}
