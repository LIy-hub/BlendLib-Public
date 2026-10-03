package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeightedPoseComponentTest {
    private static final Quaternion TURN = new Quaternion(0, 0, (float)Math.sin(Math.PI/4), (float)Math.cos(Math.PI/4));
    private static final LocalPose BASE = new LocalPose(Map.of(0, Transform.IDENTITY,
            1, new Transform(new Vec3(0, 2, 0), Quaternion.IDENTITY, new Vec3(2, 2, 2))));
    private static final ClientAnimationRigView RIG = ClientAnimationRigViewTestAccess.fromNodes(List.of(
            new ModelNode(0, "root", BASE.transform(0), List.of(1), -1, -1, false),
            new ModelNode(1, "tip", BASE.transform(1), List.of(), -1, -1, false)));
    private static ClientAnimationPoseContext context(int instance, long generation, double ticks) {
        return new ClientAnimationPoseContext(BlendInstanceKey.entity("weighted", instance),
                BlendModelKey.of("test", "rig"), generation, BlendAnimationKey.of("test", "idle"), 0, ticks, RIG);
    }
    private static ProceduralPoseComponent turns() {
        return (c, p) -> PoseRotationMath.replace(p, Map.of(0, TURN, 1, TURN));
    }
    private static double angle(LocalPose pose, int node) {
        return 2 * Math.acos(Math.min(1, Math.abs(pose.transform(node).rotation().w())));
    }

    @Test void dynamicFadeIsMaskedMultipliedImmutableAndContextScoped() {
        Map<String, Float> mask = new HashMap<>(Map.of("tip", 0.5F));
        var weighted = new WeightedPoseComponent(turns(), c -> c.clientGameTimeInTicks()/20, mask);
        mask.put("root", 1F);
        assertSame(BASE, weighted.modify(context(1, 1, 0), BASE));
        var halfway = weighted.modify(context(1, 1, 10), BASE);
        assertEquals(Math.PI/8, angle(halfway, 1), 1e-5);
        assertSame(BASE.transform(0), halfway.transform(0));
        assertEquals(BASE.transform(1).translation(), halfway.transform(1).translation());
        assertEquals(BASE.transform(1).scale(), halfway.transform(1).scale());
        assertEquals(Quaternion.IDENTITY, BASE.transform(1).rotation());
        assertEquals(halfway.transforms(), weighted.modify(context(1, 1, 10), BASE).transforms());
        assertEquals(Math.PI/4, angle(weighted.modify(context(2, 2, 20), BASE), 1), 1e-5);
        assertEquals(Math.PI/2, angle(new WeightedPoseComponent(turns(), c -> 1).modify(context(1, 1, 0), BASE), 0), 1e-5);
    }

    @Test void shortestArcAndOrderedCompositionUseIncomingPose() {
        var negative = new Quaternion(-TURN.x(), -TURN.y(), -TURN.z(), -TURN.w());
        var a = new WeightedPoseComponent(turns(), c -> 0.5);
        var b = new WeightedPoseComponent((c,p) -> PoseRotationMath.replace(p, Map.of(0, negative)), c -> 0.5);
        assertEquals(Math.PI*3/8, angle(ProceduralPosePipeline.of(a,b).modify(context(1,1,0),BASE),0), 1e-5);
    }

    @Test void zeroWeightRunsDelegateAndForwardsBothResets() {
        int[] calls = new int[3];
        var key = context(1,1,0).instanceKey();
        var delegate = new ProceduralPoseComponent() {
            public LocalPose modify(ClientAnimationPoseContext c, LocalPose p) { calls[0]++; return p; }
            public void reset() { calls[1]++; }
            public void reset(BlendInstanceKey k) { assertEquals(key,k); calls[2]++; }
        };
        var wrapper = new WeightedPoseComponent(delegate,c -> 0, Map.of("tip", 0F));
        assertSame(BASE,wrapper.modify(context(1,1,0),BASE));
        wrapper.reset(); wrapper.reset(key);
        assertArrayEquals(new int[]{1,1,1},calls);
    }

    @Test void springStillAdvancesWhileInvisibleAndReloadIsForwardedByContext() {
        var spring = new SpringInertiaPoseComponent("tip",2,4);
        var wrapper = new WeightedPoseComponent(spring,c -> c.clientGameTimeInTicks() < 2 ? 0 : 1);
        wrapper.modify(context(1,1,0),BASE);
        var target = turns().modify(context(1,1,1),BASE);
        assertSame(target,wrapper.modify(context(1,1,1),target));
        var visible = wrapper.modify(context(1,1,2),target);
        assertTrue(angle(visible,1)>0 && angle(visible,1)<Math.PI/2);
        assertEquals(visible.transforms(),wrapper.modify(context(1,1,2),target).transforms());
        assertEquals(target.transforms(),wrapper.modify(context(2,1,2),target).transforms());
        assertSame(BASE,wrapper.modify(context(1,2,3),BASE));
        wrapper.reset(context(1,2,3).instanceKey());
        assertEquals(1,spring.retainedInstanceCount());
        wrapper.reset(); assertEquals(0,spring.retainedInstanceCount());
    }

    @Test void rejectsInvalidWeightsBeforeDelegateAndResolvesMaskAtZero() {
        for (double value : new double[]{Double.NaN,Double.POSITIVE_INFINITY,-0.01,1.01,1.0000000001}) {
            assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent((c,p) -> {fail("delegate called");return p;},c -> value)
                    .modify(context(1,1,0),BASE));
        }
        for (float value : new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,2}) {
            assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent(turns(),c -> 1,Map.of("tip",value)));
        }
        assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent(turns(),c -> 0,Map.of("absent",1F)).modify(context(1,1,0),BASE));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent(turns(),c -> 1,Map.of(" ",1F)));
        var duplicateRig = ClientAnimationRigViewTestAccess.fromNodes(List.of(
                new ModelNode(0,"tip",Transform.IDENTITY,List.of(1),-1,-1,false),
                new ModelNode(1,"tip",Transform.IDENTITY,List.of(),-1,-1,false)));
        var c = context(1,1,0);
        var ambiguous = new ClientAnimationPoseContext(c.instanceKey(),c.modelKey(),c.generation(),c.animationKey(),c.animationTimeSeconds(),0,duplicateRig);
        assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent(turns(),x -> 0,Map.of("tip",1F)).modify(ambiguous,BASE));
    }

    @Test void invalidDelegateChannelsCannotHideBehindMasksOrZeroWeight() {
        for(double weight : new double[]{0,0.5,1}) {
            for (LocalPose invalid : List.of(new LocalPose(Map.of(0,Transform.IDENTITY)),
                    new LocalPose(Map.of(0,new Transform(Vec3.ONE,Quaternion.IDENTITY,Vec3.ONE),1,BASE.transform(1))),
                    new LocalPose(Map.of(0,new Transform(Vec3.ZERO,Quaternion.IDENTITY,new Vec3(2,2,2)),1,BASE.transform(1))))) {
                assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent((c,p)->invalid,c->weight,Map.of("tip",1F)).modify(context(1,1,0),BASE));
            }
        }
        assertThrows(NullPointerException.class, () -> new WeightedPoseComponent((c,p)->null,c->0).modify(context(1,1,0),BASE));
        assertThrows(IllegalArgumentException.class, () -> new WeightedPoseComponent(turns(),c->0).modify(context(1,1,0),new LocalPose(Map.of(0,Transform.IDENTITY))));
    }
}
