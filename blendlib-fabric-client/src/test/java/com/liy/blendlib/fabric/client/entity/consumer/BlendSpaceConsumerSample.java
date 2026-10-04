package com.liy.blendlib.fabric.client.entity.consumer;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.entity.*;
import java.util.List;
import net.minecraft.world.entity.Entity;

/** Compiled external-package consumer: no implementation access or per-entity cache. */
public final class BlendSpaceConsumerSample {
    public static BlendEntityRendererBuilder<Entity> configure(BlendEntityRendererBuilder<Entity> builder) {
        var idle=BlendAnimationKey.parse("space:idle");var walk=BlendAnimationKey.parse("space:walk");
        var a=BlendResourceId.parse("space:a");var b=BlendResourceId.parse("space:b");
        return builder.skinnedAnimation((e,r)->idle).animationLayerCues(List.of(
                new ModelAnimationLayers.Layer(a,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),idle),
                new ModelAnimationLayers.Layer(b,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),walk)),(e,r)->List.of())
                .animationBlendSpace1D(new AnimationBlendSpace1D(List.of(new AnimationBlendSpace1D.Sample(0,a),new AnimationBlendSpace1D.Sample(1,b)),1),(e,r)->0.5);
    }
}
