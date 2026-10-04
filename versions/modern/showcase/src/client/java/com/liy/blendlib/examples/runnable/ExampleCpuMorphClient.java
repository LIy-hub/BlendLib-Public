package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderers;
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
        // This separate GLB contains no animations and its descriptor has no animation states.
        BlendEntityRenderers.register(ExampleCpuMorphContent.STATIC_ACTOR, context ->
                BlendEntityRenderer.<StaticCpuMorphActor>builder(context,
                        com.liy.blendlib.api.BlendModelKey.parse("cpu_morph:static_face_actor"))
                        .staticMorph()
                        .morphControls((entity, request) -> {
                            double time = (request.clientGameTick() + request.partialTick()) / 20.0;
                            float blink = (float) Math.max(0, 1 - Math.abs((time % 4) - 2) * 8);
                            float smile = (float) (.5 + .5 * Math.sin(time));
                            return new com.liy.blendlib.core.animation.runtime.MorphFrameOverrides(java.util.Map.of(
                                    com.liy.blendlib.api.BlendResourceId.parse("cpu_morph:blink"), blink,
                                    com.liy.blendlib.api.BlendResourceId.parse("cpu_morph:smile"), smile));
                        })
                        .attachments((entity, request, sockets) -> ExampleCpuMorphScene.attachments(
                                BlendLibClientServices.models(), request, sockets,
                                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY))
                        .cullingEnvelope(ExampleCpuMorphScene.ENVELOPE)
                        .shadowRadius(.45F)
                        .build());
        // The same animation-free asset can be hosted at a block-local root, without attachments.
        BlendBlockEntityRenderers.register(ExampleCpuMorphContent.STATIC_BLOCK_ENTITY, context ->
                BlendBlockEntityRenderer.<ExampleCpuMorphBlockEntity>builder(context, ExampleCpuMorphBlockControls.MODEL)
                        .staticMorph()
                        .morphControls((blockEntity, request) -> {
                            var position = blockEntity.getBlockPos();
                            return ExampleCpuMorphBlockControls.capture(position.getX(), position.getY(), position.getZ(),
                                    request.clientGameTick(), request.partialTick());
                        })
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
