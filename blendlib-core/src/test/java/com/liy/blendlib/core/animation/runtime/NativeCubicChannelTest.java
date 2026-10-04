package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.model.*;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeCubicChannelTest {
    @Test
    void hermiteUsesSecondsAndUnequalIntervalsWithExactClampedKeys() {
        AnimationChannel channel = cubic(AnimationPath.TRANSLATION, new float[] {1, 3, 6},
                new float[] {0, 2, 0, 4, 2, 0, 10, 2, 0},
                new float[] {0, 0, 0, -1, 0, 0, 2, 0, 0},
                new float[] {3, 0, 0, 0, 0, 0, 0, 0, 0});
        assertArrayEquals(new float[] {0, 2, 0}, channel.sample(-100));
        assertArrayEquals(new float[] {4, 2, 0}, channel.sample(3));
        assertArrayEquals(new float[] {10, 2, 0}, channel.sample(100));
        assertEquals(3, channel.sample(2)[0], 1e-6); // 0.5*0 + .125*2*3 + .5*4 - .125*2*(-1)
        assertEquals(6.25, channel.sample(4.5f)[0], 1e-6);
        assertEquals(3, channel.keyCount());
        assertEquals(9, channel.values().length);
        assertEquals(9, channel.inTangents().length);
        for (float t : new float[] {0, 1, 1.125f, 2, 3, 4.5f, 6, 9}) {
            assertEquals(channel.sample(t)[0], sample(channel, t).translation().x(), 1e-6);
        }
    }

    @Test
    void cubicQuaternionUsesComponentHermiteWithoutSlerpOrSignRepair() {
        float s = (float) Math.sqrt(0.5);
        AnimationChannel channel = cubic(AnimationPath.ROTATION, new float[] {0, 2},
                new float[] {0, 0, 0, -1, 0, 0, -s, -s},
                new float[] {0, 0, 0, 0, 0, 0, -.2f, 0},
                new float[] {0, 0, -.8f, .1f, 0, 0, 0, 0});
        float t = .5f;
        double u = t / 2.0, h00 = 2*u*u*u-3*u*u+1, h10 = u*u*u-2*u*u+u;
        double h01 = -2*u*u*u+3*u*u, h11=u*u*u-u*u;
        double z = h10*2*(-.8f)+h01*(-s)+h11*2*(-.2f);
        double w = -h00+h10*2*.1f+h01*(-s), norm = Math.hypot(z,w);
        float[] value = channel.sample(t);
        assertEquals(z/norm, value[2], 1e-6);
        assertEquals(w/norm, value[3], 1e-6);
        assertTrue(value[3] < 0, "authored negative quaternion signs survive");
        Quaternion sampled = sample(channel,t).rotation();
        assertEquals(value[2],sampled.z(),1e-6);
        assertEquals(value[3],sampled.w(),1e-6);
        assertArrayEquals(new float[] {0,0,-.8f,.1f,0,0,0,0},channel.outTangents());
        assertNotEquals(Quaternion.slerp(new Quaternion(0,0,0,-1),new Quaternion(0,0,-s,-s),.25f).z(), value[2], 1e-3);
    }

    @Test
    void scaleRequiresUniformCoefficientsAndPositiveWholeControlHull() {
        var safe = cubic(AnimationPath.SCALE,new float[] {0,2},new float[] {1,1,1,2,2,2},
                new float[] {0,0,0,-1,-1,-1},new float[] {1,1,1,0,0,0});
        for(int i=0;i<=100;i++) assertTrue(sample(safe,i/50.0).scale().x()>0);
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.SCALE,new float[] {0,1},
                new float[] {1,1,1,1,1,1},new float[] {0,0,0,10,10,10},new float[] {-10,-10,-10,0,0,0}));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.SCALE,new float[] {0,1},
                new float[] {1,1,1,1,1,1},new float[6],new float[] {1,0,1,0,0,0}));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.SCALE,new float[] {0,1},
                new float[] {1e-9f,1e-9f,1e-9f,1,1,1},new float[6],new float[6]));
    }

    @Test
    void quaternionCancellationAndInconclusiveHullFailBeforeSampling() {
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.ROTATION,new float[] {0,1},
                new float[] {0,0,0,1,0,0,0,-1},new float[8],new float[8]));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.ROTATION,new float[] {0,1},
                new float[] {0,0,0,1,0,0,0,1},new float[] {0,0,0,0,0,0,0,12},new float[] {0,0,0,-12,0,0,0,0}));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.ROTATION,new float[] {0,1},
                new float[] {0,0,0,2,0,0,0,1},new float[8],new float[8]));
    }

    @Test
    void malformedCardinalityTimesTangentsAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.TRANSLATION,new float[] {0},new float[3],new float[3],new float[3]));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.TRANSLATION,new float[] {0,0},new float[6],new float[6],new float[6]));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.TRANSLATION,new float[] {0,1},new float[6],new float[3],new float[6]));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.TRANSLATION,new float[] {0,1},new float[6],new float[6],new float[] {Float.NaN,0,0,0,0,0}));
        assertThrows(IllegalArgumentException.class,()->cubic(AnimationPath.TRANSLATION,new float[] {0,600},new float[6],new float[6],new float[] {Float.MAX_VALUE,0,0,0,0,0}));
        assertThrows(IllegalArgumentException.class,()->new AnimationChannel(0,AnimationPath.TRANSLATION,Interpolation.CUBICSPLINE,new float[] {0,1},new float[6]));
        assertThrows(IllegalArgumentException.class,()->Interpolation.fromSerializedName("CUBICSPLINE"));
    }

    @Test
    void arraysAreImmutableAndCompiledStatesShareGenerationOwnedChannel() throws Exception {
        float[] values={0,0,0,1,0,0}, tangents=new float[6];
        AnimationChannel channel=cubic(AnimationPath.TRANSLATION,new float[] {0,1},values,tangents,tangents);
        values[0]=99; tangents[0]=99; channel.values()[0]=99; channel.inTangents()[0]=99;
        assertEquals(0,channel.sample(0)[0]);
        var one=CompiledAnimationChannel.compile(channel); var two=CompiledAnimationChannel.compile(channel);
        Field source=CompiledAnimationChannel.class.getDeclaredField("source"); source.setAccessible(true);
        assertSame(channel,source.get(one)); assertSame(channel,source.get(two));
        for(Field field:CompiledAnimationChannel.class.getDeclaredFields()) assertFalse(field.getType().isArray());
    }

    @Test
    void newProfileClampsLinearButFrozenV1KeepsItsPreviousBeforeFirstKeyBehavior() {
        float[] times={1,2}, values={1,0,0,2,0,0};
        AnimationChannel old=new AnimationChannel(0,AnimationPath.TRANSLATION,Interpolation.LINEAR,times,values);
        AnimationChannel explicit=AnimationChannel.forCubicProfile(0,AnimationPath.TRANSLATION,Interpolation.LINEAR,times,values,new float[0],new float[0]);
        assertEquals(0,old.sample(0)[0]); assertEquals(1,explicit.sample(0)[0]);
        assertEquals(1,sample(explicit,0).translation().x());
    }

    private static AnimationChannel cubic(AnimationPath path,float[] times,float[] values,float[] in,float[] out) {
        return AnimationChannel.forCubicProfile(0,path,Interpolation.CUBICSPLINE,times,values,in,out);
    }
    private static Transform sample(AnimationChannel channel,double time) {
        AnimationState state=new AnimationState(BlendAnimationKey.parse("test:clip"),new AnimationClip("clip",List.of(channel)),false,1,0,null,List.of());
        return new PoseSampler(List.of(new ModelNode(0,"Joint",Transform.IDENTITY,List.of(),-1,-1,false))).sampleNode(state,time,0);
    }
}
