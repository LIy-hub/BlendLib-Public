package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.BoneMask;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.LookAtPoseComponent;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.ProceduralPosePipeline;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.RotationLimitPoseComponent;
import java.util.List;
import java.util.Map;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.WeightedPoseComponent;

/** Minecraft-free scene configuration shared by the live renderer and headless asset verification. */
public final class ExampleAnimationScene {
    public static final BlendResourceId BASE = BlendResourceId.parse(ExampleContent.MOD_ID + ":base");
    public static final BlendResourceId UPPER = BlendResourceId.parse(ExampleContent.MOD_ID + ":upper");
    public static final String TIP_BONE = "ShowcaseTipBone";
    private ExampleAnimationScene() { }

    public static List<ModelAnimationLayers.Layer> layers() {
        return List.of(
                new ModelAnimationLayers.Layer(BASE, 0, AnimationV2LayerMode.OVERRIDE, 1F, List.of(),
                        BlendAnimationKey.parse(ExampleContent.MOD_ID + ":walk")),
                new ModelAnimationLayers.Layer(UPPER, 10, AnimationV2LayerMode.OVERRIDE, 1F,
                        List.of(new BoneMask.NamedWeight(TIP_BONE, 1F)),
                        BlendAnimationKey.parse(ExampleContent.MOD_ID + ":idle")));
    }

    public static ProceduralPosePipeline procedural() {
        return ProceduralPosePipeline.of(
                new WeightedPoseComponent(new LookAtPoseComponent(TIP_BONE, new Vec3(0, 1, 0), 0.3F,
                        pose -> new Vec3((float) Math.sin(pose.clientGameTimeInTicks() / 23.0) * 1.2F,
                                1.4F, 0.5F)),
                        pose -> 0.5 - 0.5 * Math.cos(pose.clientGameTimeInTicks() * Math.PI / 40.0),
                        Map.of(TIP_BONE, 1F)),
                new RotationLimitPoseComponent(TIP_BONE, Quaternion.IDENTITY, 1.2F));
    }
}
