package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationBlendSpace1D;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import java.util.List;
import java.util.Map;

/** Independent, opt-in synchronized continuous locomotion consumer; no resource-pack rules. */
public final class ExampleBlendSpaceScene {
    public static final String PROPERTY = "blendlib.examples.blendspace1d";
    public static final BlendModelKey MODEL = BlendModelKey.parse(ExampleContent.MOD_ID + ":blendspace_actor");
    public static final BlendResourceId IDLE = id("blend_idle");
    public static final BlendResourceId WALK = id("blend_walk");
    public static final BlendResourceId RUN = id("blend_run");
    public static final double CYCLE_SECONDS = .8;
    private static final AnimationBlendSpace1D DEFINITION = new AnimationBlendSpace1D(List.of(
            new AnimationBlendSpace1D.Sample(0, IDLE),
            new AnimationBlendSpace1D.Sample(.06, WALK),
            new AnimationBlendSpace1D.Sample(.14, RUN)), CYCLE_SECONDS);
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(
            member(IDLE, "idle"), member(WALK, "walk"), member(RUN, "run"),
            ExampleAnimationScene.layers().get(1));

    private ExampleBlendSpaceScene() { }

    /** Read once during registration. It is a JVM property, not a Gradle project property. */
    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public static AnimationBlendSpace1D definition() { return DEFINITION; }
    public static List<ModelAnimationLayers.Layer> layers() { return LAYERS; }

    /** Packaged fixture expectations for verification; live inspection queries current loaded durations. */
    public static Map<BlendResourceId, Double> packagedDurations() {
        return Map.of(IDLE, 2.0, WALK, 1.0, RUN, .5);
    }

    private static ModelAnimationLayers.Layer member(BlendResourceId id, String animation) {
        return new ModelAnimationLayers.Layer(id, 0, AnimationV2LayerMode.OVERRIDE, 1F, List.of(),
                BlendAnimationKey.parse(ExampleContent.MOD_ID + ":" + animation));
    }
    private static BlendResourceId id(String name) { return BlendResourceId.parse(ExampleContent.MOD_ID + ":" + name); }
}
