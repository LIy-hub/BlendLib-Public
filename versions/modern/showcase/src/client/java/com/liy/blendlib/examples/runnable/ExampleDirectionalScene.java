package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.v2.*;
import java.util.*;

/** Separate opt-in center + forward/left/back/right fixed-cycle consumer. */
public final class ExampleDirectionalScene {
    public static final String PROPERTY = "blendlib.examples.blendspace2d";
    public static final BlendModelKey MODEL = BlendModelKey.parse(ExampleContent.MOD_ID + ":directional_actor");
    public static final BlendResourceId IDLE=id("idle"), FORWARD=id("forward"), LEFT=id("left"), BACK=id("back"), RIGHT=id("right");
    public static final double RADIUS=.14, CYCLE_SECONDS=.8;
    private static final AnimationBlendSpace2D DEFINITION = new AnimationBlendSpace2D(IDLE, List.of(
            new AnimationBlendSpace2D.Direction(0, FORWARD), new AnimationBlendSpace2D.Direction(Math.PI/2, LEFT),
            new AnimationBlendSpace2D.Direction(Math.PI, BACK), new AnimationBlendSpace2D.Direction(3*Math.PI/2, RIGHT)), RADIUS, CYCLE_SECONDS);
    private static final List<ModelAnimationLayers.Layer> LAYERS=List.of(member(IDLE,"idle"),member(FORWARD,"forward"),member(LEFT,"left"),
            member(BACK,"back"),member(RIGHT,"right"),ExampleAnimationScene.layers().get(1));
    private ExampleDirectionalScene() { }
    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public static AnimationBlendSpace2D definition() {return DEFINITION;}
    public static List<ModelAnimationLayers.Layer> layers() {return LAYERS;}
    public static Map<BlendResourceId,Double> packagedDurations() {return Map.of(IDLE,2.,FORWARD,1.,LEFT,.5,BACK,1.5,RIGHT,2.5);}
    public static AnimationBlendSpace2D.Input localVelocity(double dx,double dz,double yawDegrees) {
        var local=ExampleDirectionalMotion.toLocal(dx,dz,yawDegrees);
        return new AnimationBlendSpace2D.Input(local.forward(),local.left());
    }
    private static ModelAnimationLayers.Layer member(BlendResourceId id,String clip) {return new ModelAnimationLayers.Layer(id,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),BlendAnimationKey.parse(ExampleContent.MOD_ID+":"+clip));}
    private static BlendResourceId id(String name) {return BlendResourceId.parse(ExampleContent.MOD_ID+":direction_"+name);}
}
