package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;

/** Native Blender curves use the ordinary layered CPU renderer and final-pose attachment API. */
public final class ExampleNativeCubicClient implements ClientModInitializer {
    public static final BlendModelKey MODEL = BlendModelKey.parse("native_cubic:eased_actor");
    public static final BlendAnimationKey WAVE = BlendAnimationKey.parse("native_cubic:wave");
    public static final BlendAnimationKey ONCE = BlendAnimationKey.parse("native_cubic:once");
    public static final BlendResourceId TIP = BlendResourceId.parse("native_cubic:tip");
    public static final BlendResourceId LAYER = BlendResourceId.parse("native_cubic:base");
    public static final List<ModelAnimationLayers.Layer> LAYERS = List.of(new ModelAnimationLayers.Layer(
            LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1, List.of(), WAVE));
    private static final BlendEntitySocketPose MARKER_OFFSET = new BlendEntitySocketPose(
            0, 0, 0, BlendEntityRotation.IDENTITY, .15F);

    @Override public void onInitializeClient() {
        BlendEntityRenderers.register(ExampleNativeCubicContent.ACTOR, context ->
                BlendEntityRenderer.<LayeredActor>builder(context, MODEL)
                        .skinnedAnimation((entity, request) -> WAVE)
                        .animationLayerCues(LAYERS, (entity, request) -> entity.cueSequence() == 0 ? List.of()
                                : List.of(new BlendEntityLayerCue(LAYER, ONCE, entity.cueSequence(), entity.cueTick(), 1)))
                        .onAnimationLayerVisualEvent((entity, event) -> entity.visualEvents().accept(event))
                        .attachments((entity, request, sockets) -> {
                            var tip = sockets.socket(TIP);
                            var marker = BlendLibClientServices.models().resolve(ExampleContent.MARKER_MODEL);
                            if (tip.isEmpty() || marker.missing() || marker.generationId() != sockets.generation()) return List.of();
                            var handle = marker.renderHandle();
                            var snapshot = new ModelRenderSnapshot(handle, Transform.IDENTITY, request.packedLight(),
                                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xFFFFC040,
                                    RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
                            return List.of(BlendEntityAttachment.at(tip.orElseThrow(), MARKER_OFFSET, snapshot));
                        })
                        .cullingEnvelope(new BlendEntityCullingEnvelope(-4,-4,-4,4,4,4))
                        .shadowRadius(.4F)
                        .build());
    }
}
