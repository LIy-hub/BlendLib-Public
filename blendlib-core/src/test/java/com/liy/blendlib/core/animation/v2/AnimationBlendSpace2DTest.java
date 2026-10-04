package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.core.animation.v2.AnimationBlendSpace1DTest.*;
import com.liy.blendlib.api.BlendResourceId;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnimationBlendSpace2DTest {
    static final BlendResourceId CENTER=id("center"), EAST=id("east"), NORTH=id("north"), WEST=id("west"), SOUTH=id("south");
    static BlendResourceId id(String s) {return BlendResourceId.parse("direction:"+s);}
    static AnimationBlendSpace2D.Direction direction(double a,BlendResourceId id) {return new AnimationBlendSpace2D.Direction(a,id);}
    static AnimationBlendSpace2D space() {return new AnimationBlendSpace2D(CENTER,List.of(direction(Math.PI,WEST),direction(0,EAST),direction(Math.PI*1.5,SOUTH),direction(Math.PI/2,NORTH)),1,2);}
    @Test void exactCenterAxesSectorInteriorsAndRadialPolygonClamping() {
        var s=space(); assertWeights(s.weights(0,0),1,0,0,0,0);
        assertWeights(s.weights(-0.,0),1,0,0,0,0);
        assertWeights(s.weights(1,0),0,1,0,0,0);
        assertWeights(s.weights(0,1),0,0,1,0,0);
        assertWeights(s.weights(-1,0),0,0,0,1,0);
        assertWeights(s.weights(0,-1),0,0,0,0,1);
        assertWeights(s.weights(.2,.3),.5,.2,.3,0,0);
        assertWeights(s.weights(-.2,.3),.5,0,.3,.2,0);
        assertWeights(s.weights(-.2,-.3),.5,0,0,.2,.3);
        assertWeights(s.weights(.2,-.3),.5,.2,0,0,.3);
        assertWeights(s.weights(2,3),0,.4,.6,0,0);
        assertWeights(s.weights(1,1),0,.5,.5,0,0);
        assertEquals(5,s.weights(0,0).multipliers().size());
    }
    @Test void hullAndExactDirectionKeepInactiveMembersExactlyZeroForEventSuppression() {
        for(double[] v:new double[][]{{9,1},{.9,.1},{1,0},{0,1},{0,-1}})assertEquals(0F,weight(space().weights(v[0],v[1]),CENTER));
        var s=new AnimationBlendSpace2D(CENTER,List.of(direction(.2,EAST),direction(2,NORTH),direction(4.4,SOUTH)),.14,1);
        for(var d:s.directions()) {
            var w=s.weights(.14*Math.cos(d.angleRadians()),.14*Math.sin(d.angleRadians()));
            for(var member:s.memberLayerIds())assertEquals(member.equals(d.layerId())?1F:0F,weight(w,member));
        }
    }
    @Test void nearlyFlatAllowedSectorsCannotLeakOtherMembersOnAuthoredRays() {
        Random random=new Random(283);
        for(int i=0;i<5000;i++) {
            double base=random.nextDouble(0,Math.PI),gap=1.000001e-6,radius=random.nextDouble(1e-5,1e6);
            var s=new AnimationBlendSpace2D(CENTER,List.of(direction(base,EAST),direction((base+Math.PI-gap)%(2*Math.PI),NORTH),direction((base+Math.PI+gap)%(2*Math.PI),SOUTH)),radius,1);
            for(var d:s.directions())for(double amount:new double[]{.5,1,2}) {
                double x=radius*amount*Math.cos(d.angleRadians()),y=radius*amount*Math.sin(d.angleRadians());
                if(Math.abs(x)>1e6||Math.abs(y)>1e6)continue;
                var w=s.weights(x,y);for(var member:s.memberLayerIds())assertEquals(member.equals(d.layerId())?(float)Math.min(1,amount):member.equals(CENTER)?(float)Math.max(0,1-amount):0F,weight(w,member));
            }
        }
    }
    @Test void arbitraryRingAndSeamsRemainContinuousNormalizedNonnegativeAndDeterministic() {
        var s=new AnimationBlendSpace2D(CENTER,List.of(direction(.2,EAST),direction(2,NORTH),direction(4.4,SOUTH)),.14,1);
        Random r=new Random(181);
        for(int i=0;i<20000;i++) {
            double x=r.nextDouble(-1e6,1e6),y=r.nextDouble(-1e6,1e6);
            var w=s.weights(x,y);assertEquals(w.multipliers(),s.weights(new AnimationBlendSpace2D.Input(x,y)).multipliers());
            assertEquals(1,w.multipliers().values().stream().mapToDouble(Float::doubleValue).sum(),1e-6);
            assertTrue(w.multipliers().values().stream().allMatch(v->Float.isFinite(v)&&v>=0&&v<=1));
            assertTrue(w.multipliers().values().stream().filter(v->v>1e-6).count()<=3);
        }
        for(double angle:new double[]{0,.2,2,4.4,2*Math.PI}) for(double radius:new double[]{0,.03,.14,1e6}) {
            var a=s.weights(radius*Math.cos(angle-1e-10),radius*Math.sin(angle-1e-10));
            var b=s.weights(radius*Math.cos(angle+1e-10),radius*Math.sin(angle+1e-10));
            for(var key:a.multipliers().keySet())assertEquals(a.multiplier(key),b.multiplier(key),1e-6);
        }
        var tiny=new AnimationBlendSpace2D(CENTER,space().directions(),1e-6,1);
        assertWeights(tiny.weights(1e6,1e6),0,.5,.5,0,0);
    }
    @Test void boundedImmutableDefinitionRejectsDuplicatesDegeneracyAndInvalidInputs() {
        var dirs=new ArrayList<>(space().directions());var s=new AnimationBlendSpace2D(CENTER,dirs,1,1);dirs.clear();
        assertEquals(4,s.directions().size());assertThrows(UnsupportedOperationException.class,()->s.directions().clear());
        assertThrows(UnsupportedOperationException.class,()->s.memberLayerIds().clear());
        assertThrows(UnsupportedOperationException.class,()->s.weights(0,0).multipliers().clear());
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1e6+1,-1e6-1}) {
            assertThrows(IllegalArgumentException.class,()->s.weights(bad,0));assertThrows(IllegalArgumentException.class,()->s.weights(0,bad));
        }
        for(double bad:new double[]{-1,2*Math.PI,Double.NaN,Double.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->direction(bad,EAST));
        for(double bad:new double[]{0,-1,1e-7,1e6+1,Double.NaN})assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace2D(CENTER,s.directions(),bad,1));
        for(double bad:new double[]{0,-1,601,Double.NaN})assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace2D(CENTER,s.directions(),1,bad));
        for(var bad:List.of(List.of(direction(0,EAST),direction(0,NORTH),direction(4,SOUTH)),
                List.of(direction(0,EAST),direction(1,NORTH),direction(2,SOUTH)),
                List.of(direction(0,EAST),direction(Math.PI,NORTH),direction(4,SOUTH)),
                List.of(direction(0,EAST),direction(2,EAST),direction(4,SOUTH)),
                List.of(direction(0,CENTER),direction(2,NORTH),direction(4,SOUTH))))
            assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace2D(CENTER,bad,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace2D(CENTER,s.directions().subList(0,2),1,1));
        var many=new ArrayList<AnimationBlendSpace2D.Direction>();for(int i=0;i<16;i++)many.add(direction(i*2*Math.PI/16,id("d"+i)));
        assertThrows(IllegalArgumentException.class,()->new AnimationBlendSpace2D(CENTER,many,1,1));
        assertThrows(IllegalArgumentException.class,()->s.bind(new AnimationV2InstancePlan(SCHEMA,List.of(controller(CENTER,1,1,0,1,1,true)))));
    }
    @Test void sharedBindingPreservesUnequalClipPhaseStableCommandsAndOwnership() {
        var s=space();var ids=new ArrayList<>(s.memberLayerIds());var controllers=new ArrayList<AnimationV2ControllerDefinition>();
        double[] durations={2,1,.5,1.5,2.5};for(int i=0;i<ids.size();i++)controllers.add(controller(ids.get(i),durations[i],.5+i*.5,0,1,1,true));
        var runtime=new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(SCHEMA,controllers));
        var commands=s.bind(new AnimationV2InstancePlan(SCHEMA,controllers)).commands(0,4);
        for(int tick=0;tick<200;tick++) {
            double angle=tick*.1;var snapshot=runtime.advanceWeightedAtFrame(tick==0?0:.05,tick==0?commands:List.of(),tick%40<10?s.weights(0,0):s.weights(Math.cos(angle),Math.sin(angle)));
            for(int i=0;i<ids.size();i++) {var head=snapshot.playheads().get(ids.get(i));double p=head.timeSeconds()/durations[i];double expected=(tick%40)/40.;assertTrue(Math.abs(p-expected)<1e-8||Math.abs(p-expected-1)<1e-8);assertEquals(4,head.acceptedSequence());}
        }
        assertSame(s.syncGroup(),s.syncGroup());
        assertThrows(IllegalArgumentException.class,()->s.validateExternalCommands(commands));
        assertThrows(IllegalArgumentException.class,()->s.validateExternalWeights(s.weights(0,0)));
    }
    static void assertWeights(AnimationV2LayerWeights w,double... expected) {int i=0;for(var id:List.of(CENTER,EAST,NORTH,WEST,SOUTH))assertEquals(expected[i++],weight(w,id),1e-6);}
}
