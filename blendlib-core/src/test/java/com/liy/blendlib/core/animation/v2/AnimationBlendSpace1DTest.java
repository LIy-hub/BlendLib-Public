package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnimationBlendSpace1DTest {
    static final BlendResourceId A = BlendResourceId.parse("space:a"), B = BlendResourceId.parse("space:b"), C = BlendResourceId.parse("space:c");
    static final BlendAnimationKey STATE = BlendAnimationKey.parse("space:loop");
    static final BoneSchema SCHEMA = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
    static AnimationBlendSpace1D space() { return new AnimationBlendSpace1D(List.of(sample(0,A),sample(1,B),sample(3,C)), 2); }
    static AnimationBlendSpace1D.Sample sample(double p, BlendResourceId id) { return new AnimationBlendSpace1D.Sample(p,id); }
    static float weight(AnimationV2LayerWeights weights, BlendResourceId id) { return weights.multiplier(new AnimationV2LayerWeights.Key(id,id)); }

    @Test void clampsHitsAndInterpolatesOnlyAdjacentSamplesWithExplicitZeros() {
        var space = space();
        for (double parameter : new double[]{-Double.MAX_VALUE, 0}) assertWeights(space.weights(parameter),1,0,0);
        assertWeights(space.weights(.25),.75F,.25F,0);
        assertWeights(space.weights(1),0,1,0);
        assertWeights(space.weights(2),0,.5F,.5F);
        for (double parameter : new double[]{3,Double.MAX_VALUE}) assertWeights(space.weights(parameter),0,0,1);
        assertEquals(1F, space.weights(2).multiplier(new AnimationV2LayerWeights.Key(BlendResourceId.parse("other:x"),B)));
        assertEquals(3,space.weights(1).multipliers().size());
    }

    @Test void extremeIntervalsAndTinyIntervalsRemainFiniteContinuousAndNormalized() {
        var extreme = new AnimationBlendSpace1D(List.of(sample(-Double.MAX_VALUE,A), sample(Double.MAX_VALUE,B)),1);
        assertEquals(.5F,weight(extreme.weights(0),A)); assertEquals(.5F,weight(extreme.weights(0),B));
        assertEquals(.75F,weight(extreme.weights(Double.MAX_VALUE*.5),B));
        var tiny = new AnimationBlendSpace1D(List.of(sample(0,A),sample(Double.MIN_VALUE*2,B)),1);
        assertEquals(.5F,weight(tiny.weights(Double.MIN_VALUE),B));
        var random = new Random(17);
        for (int i=0;i<10000;i++) {
            double parameter=random.nextDouble(-Double.MAX_VALUE,Double.MAX_VALUE);
            var weights=extreme.weights(parameter);
            assertEquals(1F,weight(weights,A)+weight(weights,B));
            assertTrue(Float.isFinite(weight(weights,A)));
        }
        assertEquals(weight(space().weights(1),B),weight(space().weights(Math.nextDown(1.0)),B),1e-6F);
    }

    @Test void capturesImmutableBoundedValuesAndRejectsAllInvalidScalars() {
        var input=new ArrayList<>(List.of(sample(0,A),sample(1,B)));
        var space=new AnimationBlendSpace1D(input,1); input.clear(); assertEquals(2,space.samples().size());
        assertThrows(UnsupportedOperationException.class,()->space.samples().clear());
        assertThrows(UnsupportedOperationException.class,()->space.memberLayerIds().clear());
        assertThrows(UnsupportedOperationException.class,()->space.weights(.5).multipliers().clear());
        for(double bad:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->sample(bad,A));
            assertThrows(IllegalArgumentException.class,()->space.weights(bad));
        }
        for(double bad:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,601})
            assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace1D(space.samples(),bad));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace1D(List.of(sample(0,A)),1));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace1D(List.of(sample(-0.0,A),sample(0.0,B)),1));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace1D(List.of(sample(1,A),sample(0,B)),1));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace1D(List.of(sample(0,A),sample(1,A)),1));
        var many=new ArrayList<AnimationBlendSpace1D.Sample>();
        for(int i=0;i<17;i++) many.add(sample(i,BlendResourceId.parse("space:s"+i)));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace1D(many,1));
    }

    @Test void bindingCompensatesUnequalDurationsAndDescriptorSpeedsAndZeroWeightStillAdvances() {
        var plan=new AnimationV2InstancePlan(SCHEMA,List.of(controller(A,2,.5,0,1,1,true),controller(B,1,2,0,1,1,true),controller(C,.5,1.5,0,1,1,true)));
        var space=space(); var binding=space.bind(plan); var runtime=new AnimationV2InstanceRuntime(plan);
        var commands=binding.commands(.25,0);
        assertEquals(2,commands.get(0).playbackSpeed()); assertEquals(.25,commands.get(1).playbackSpeed());
        var first=runtime.advanceWeightedAtFrame(0,commands,space.weights(0));
        for(int i=0;i<3;i++) assertEquals(.25,first.playheads().get(space.samples().get(i).layerId()).timeSeconds()/new double[]{2,1,.5}[i],1e-9);
        var next=runtime.advanceWeightedAtFrame(.5,List.of(),space.weights(3));
        for(int i=0;i<3;i++) {
            var head=next.playheads().get(space.samples().get(i).layerId());
            assertEquals(.5,head.timeSeconds()/new double[]{2,1,.5}[i],1e-9); assertEquals(0,head.acceptedSequence());
        }
        // Identical initialization is harmless; generated new phases with that sequence would not be.
        var duplicate=runtime.advanceWeightedAtFrame(.25,commands,space.weights(2));
        assertEquals(.625,duplicate.playheads().get(A).timeSeconds()/2,1e-9);
    }

    @Test void bindingRejectsMissingMembersMismatchedMasksPrioritiesModesWeightsLoopsAndRates() {
        var two=new AnimationBlendSpace1D(List.of(sample(0,A),sample(1,B)),1);
        var valid=controller(A,1,1,0,1,1,true);
        assertThrows(IllegalArgumentException.class,()->two.bind(new AnimationV2InstancePlan(SCHEMA,List.of(valid))));
        for(var bad:List.of(controller(B,1,1,1,1,1,true),controller(B,1,1,0,.5F,1,true),
                controller(B,1,1,0,1,.5F,true),controller(B,1,1,0,1,1,false),controller(B,0,1,0,1,1,true)))
            assertThrows(IllegalArgumentException.class,()->two.bind(new AnimationV2InstancePlan(SCHEMA,List.of(valid,bad))));
        var additive=new AnimationV2ControllerDefinition(B,0,List.of(new AnimationV2LayerDefinition(B,0,AnimationV2LayerMode.ADDITIVE,1,BoneMask.all(SCHEMA),false)),STATE,controller(B,1,1,0,1,1,true).states());
        assertThrows(IllegalArgumentException.class,()->two.bind(new AnimationV2InstancePlan(SCHEMA,List.of(valid,additive))));
        for (double cycle:new double[]{Double.MIN_VALUE,600}) {
            var bad=new AnimationBlendSpace1D(two.samples(),cycle);
            assertThrows(IllegalArgumentException.class,()->bad.bind(new AnimationV2InstancePlan(SCHEMA,List.of(valid,controller(B,1,1,0,1,1,true)))));
        }
        // Effective 64 is valid but command 4096 (descriptor 1/64) is not.
        assertThrows(IllegalArgumentException.class,()->two.bind(new AnimationV2InstancePlan(SCHEMA,List.of(valid,controller(B,64,1.0/64,0,1,1,true)))));
        // Command 1/64 is valid but effective 1/4096 is not.
        assertThrows(IllegalArgumentException.class,()->two.bind(new AnimationV2InstancePlan(SCHEMA,List.of(valid,controller(B,1.0/4096,1.0/64,0,1,1,true)))));
    }

    @Test void memberOwnershipRejectsEvenZeroExternalWeightsAndLeavesNonmembersAlone() {
        var space=space();
        assertThrows(IllegalArgumentException.class,()->space.validateExternalWeights(new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(A,A),0F))));
        assertThrows(IllegalArgumentException.class,()->space.validateExternalCommands(List.of(new AnimationV2Command(A,STATE,0,0,1))));
        var other=BlendResourceId.parse("space:upper");
        space.validateExternalWeights(new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(other,other),.5F)));
        space.validateExternalCommands(List.of(new AnimationV2Command(other,STATE,0,0,1)));
    }

    static AnimationV2ControllerDefinition controller(BlendResourceId id,double duration,double speed,int priority,float weight,float mask,boolean loop) {
        var pose=new AnimationV2Pose(List.of(Transform.IDENTITY));
        var clip=new AnimationV2Clip(duration==0?List.of(new AnimationV2Keyframe(0,pose)):List.of(new AnimationV2Keyframe(0,pose),new AnimationV2Keyframe(duration,pose)));
        var layer=new AnimationV2LayerDefinition(id,0,AnimationV2LayerMode.OVERRIDE,weight,BoneMask.named(SCHEMA,List.of(new BoneMask.NamedWeight("root",mask))),false);
        var state=new AnimationV2ControllerState(STATE,loop?AnimationV2PlaybackMode.LOOP:AnimationV2PlaybackMode.HOLD,speed,0,null,Map.of(id,clip));
        return new AnimationV2ControllerDefinition(id,priority,List.of(layer),STATE,Map.of(STATE,state));
    }
    static void assertWeights(AnimationV2LayerWeights weights,float a,float b,float c) { assertEquals(a,weight(weights,A));assertEquals(b,weight(weights,B));assertEquals(c,weight(weights,C)); }
}
