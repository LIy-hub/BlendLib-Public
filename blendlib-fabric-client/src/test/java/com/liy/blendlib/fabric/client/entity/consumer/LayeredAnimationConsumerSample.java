package com.liy.blendlib.fabric.client.entity.consumer;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier;
import java.util.List;
import net.minecraft.world.entity.Entity;

/** Compiled consumer example; the caller supplies authoritative action-sequence commands. */
public final class LayeredAnimationConsumerSample {
    private LayeredAnimationConsumerSample() {}
    public static <E extends Entity> BlendEntityRendererBuilder<E> configure(
            BlendEntityRendererBuilder<E> builder, BlendEntityLayerCommands<E> commands,
            ClientAnimationPoseModifier components, BlendEntitySocketHandler<E> sockets) {
        var walk = BlendAnimationKey.parse("example:walk");
        var idle = BlendAnimationKey.parse("example:idle");
        return builder.skinnedAnimation((entity, request) -> walk).animationLayers(List.of(
                new ModelAnimationLayers.Layer(BlendResourceId.parse("example:base"), 0,
                        AnimationV2LayerMode.OVERRIDE, 1F, List.of(), walk),
                new ModelAnimationLayers.Layer(BlendResourceId.parse("example:upper"), 10,
                        AnimationV2LayerMode.OVERRIDE, 1F,
                        List.of(new BoneMask.NamedWeight("Arm", 1F)), idle)), commands)
                .onAnimationLayerVisualEvent((entity, event) -> {
                    // Connect this immutable per-layer marker to your client sound/effect dispatcher.
                    java.util.Objects.requireNonNull(event.event().eventKey());
                }).poseComponents(components).sockets(sockets);
    }
}
