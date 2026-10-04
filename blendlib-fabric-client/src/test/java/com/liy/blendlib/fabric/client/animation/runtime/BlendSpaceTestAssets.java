package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;

/** Small CPU-only asset for focused lifecycle tests; packaged verification separately loads real GLB bytes. */
public final class BlendSpaceTestAssets {
    public static final BlendModelKey MODEL=BlendModelKey.parse("space:actor");
    public static final BlendResourceId A=id("a"),B=id("b"),C=id("c"),UPPER=id("upper");
    public static final BlendAnimationKey IDLE=key("idle"),WALK=key("walk"),RUN=key("run");
    public static final AnimationBlendSpace1D SPACE=new AnimationBlendSpace1D(List.of(
            new AnimationBlendSpace1D.Sample(0,A),new AnimationBlendSpace1D.Sample(1,B),new AnimationBlendSpace1D.Sample(2,C)),2);
    public static final List<ModelAnimationLayers.Layer> LAYERS=List.of(layer(A,IDLE),layer(B,WALK),layer(C,RUN),
            new ModelAnimationLayers.Layer(UPPER,1,AnimationV2LayerMode.ADDITIVE,.5F,List.of(),IDLE));
    static ModelAnimationLayers.Layer layer(BlendResourceId id,BlendAnimationKey state) { return new ModelAnimationLayers.Layer(id,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),state); }
    public static BlendResourceId id(String name) { return BlendResourceId.parse("space:"+name); }
    static BlendAnimationKey key(String name) { return BlendAnimationKey.parse("space:"+name); }
    public static ModelRegistryGeneration generation(long generation) { return generation(generation,1,true); }
    public static ModelRegistryGeneration generation(long generation,float durationScale,boolean loops) {
        var definition=new AnimationDefinition(IDLE.resourceId(),Map.of(
                IDLE.resourceId(),state("idle",loops,.5,2*durationScale),WALK.resourceId(),state("walk",loops,2,durationScale),RUN.resourceId(),state("run",loops,1.5,.5*durationScale)));
        var geometry=new MeshPrimitive("Skin",new float[]{0,0,0,1,0,0,0,1,0},new float[]{0,0,1,0,0,1,0,0,1},new float[]{0,0,1,0,0,1},new int[]{0,1,2},new int[12],new float[]{1,0,0,0,1,0,0,0,1,0,0,0});
        var asset=new ModelAsset(MODEL.resourceId(),MODEL.descriptorResourceId(),generation,ModelProfile.SKINNED_V1,1,
                Map.of("Skin",new MaterialDefinition(id("textures/skin.png"),MaterialDefinition.Mode.OPAQUE,false,false,null)),definition,
                List.of(new ModelNode(0,"Mesh",Transform.IDENTITY,List.of(1),0,0,false),new ModelNode(1,"Bone",Transform.IDENTITY,List.of(),-1,-1,false)),List.of(0),List.of(new ModelPrimitive(0,0,0,geometry)),
                new Skeleton(List.of(new Skin("Rig",1,List.of(1),new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}))),
                List.of(clip("idle",2*durationScale,0),clip("walk",durationScale,1),clip("run",.5F*durationScale,2)),new SocketTable(Map.of()),Bounds.fromPositions(geometry.positions()),List.of());
        var loaded=new LoadedModelHandle(MODEL,asset,SkinnedRenderHandle.prepare(MODEL,asset));
        return new ModelRegistryGeneration(generation,Map.of(MODEL,loaded),Map.of(),List.of());
    }
    static AnimationStateDefinition state(String clip,boolean loop,double speed,double duration) {
        return new AnimationStateDefinition(clip,loop,speed,0,null,List.of(new AnimationEventDefinition(0,id("zero")),new AnimationEventDefinition(duration*.25,id("step"))));
    }
    static AnimationClip clip(String name,float duration,float amplitude) {
        return new AnimationClip(name,List.of(new AnimationChannel(1,AnimationPath.TRANSLATION,Interpolation.LINEAR,
                new float[]{0,duration/2,duration},new float[]{0,0,0,amplitude,0,0,0,0,0})));
    }
}
