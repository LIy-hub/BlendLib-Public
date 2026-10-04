package com.liy.blendlib.fabric.client.entity.consumer;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.rules.LocomotionInputs;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import com.liy.blendlib.fabric.client.entity.*;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;

/** Public consumer compilation proof: ordinary upper cues coexist with a rule-owned base layer. */
public final class LocomotionRulesConsumerSample {
    private LocomotionRulesConsumerSample() {}

    public static <E extends Entity> BlendEntityRendererBuilder<E> configure(
            BlendEntityRendererBuilder<E> builder, BlendEntityLayerCues<? super E> upperCues) {
        var idle = BlendAnimationKey.parse("locomotion:idle");
        var base = BlendResourceId.parse("locomotion:base");
        return builder.skinnedAnimation((entity, request) -> idle)
                .animationLayerCues(List.of(
                        new ModelAnimationLayers.Layer(base, 0, AnimationV2LayerMode.OVERRIDE, 1F, List.of(), idle),
                        new ModelAnimationLayers.Layer(BlendResourceId.parse("locomotion:upper"), 1,
                                AnimationV2LayerMode.ADDITIVE, 1F, List.of(), idle)), upperCues)
                .animationLocomotionRules(base, (entity, request) -> new LocomotionInputs(
                        Map.of("grounded", entity.onGround()),
                        Map.of("speed", entity.getDeltaMovement().horizontalDistance())));
    }
}
