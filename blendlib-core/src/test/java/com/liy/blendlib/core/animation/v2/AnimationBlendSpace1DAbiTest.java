package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnimationBlendSpace1DAbiTest {
    @Test void exactDefinitionSampleAndBindingDescriptorsArePinned() throws Exception {
        String base="Lcom/liy/blendlib/core/animation/v2/";
        String id="Lcom/liy/blendlib/api/BlendResourceId;";
        assertEquals(Set.of("public <init>(Ljava/util/List;D)V","public samples()Ljava/util/List;",
                "public memberLayerIds()Ljava/util/Set;","public cycleSeconds()D",
                "public weights(D)"+base+"AnimationV2LayerWeights;",
                "public validateExternalWeights("+base+"AnimationV2LayerWeights;)V",
                "public validateExternalCommands(Ljava/util/List;)V",
                "public bind("+base+"AnimationV2InstancePlan;)"+base+"AnimationBlendSpace1D$Binding;"),descriptors(AnimationBlendSpace1D.class));
        assertEquals(Set.of("public <init>(D"+id+")V","public position()D","public layerId()"+id,
                "public final equals(Ljava/lang/Object;)Z","public final hashCode()I","public final toString()Ljava/lang/String;"),descriptors(AnimationBlendSpace1D.Sample.class));
        assertEquals(Set.of("public commands(DJ)Ljava/util/List;"),descriptors(AnimationBlendSpace1D.Binding.class));
        assertTrue(Modifier.isFinal(AnimationBlendSpace1D.class.getModifiers()));
        assertTrue(Modifier.isFinal(AnimationBlendSpace1D.Binding.class.getModifiers()));
        assertEquals("java.util.List<com.liy.blendlib.core.animation.v2.AnimationBlendSpace1D$Sample>",AnimationBlendSpace1D.class.getConstructor(List.class,double.class).getGenericParameterTypes()[0].getTypeName());
        assertEquals("java.util.List<com.liy.blendlib.core.animation.v2.AnimationV2Command>",AnimationBlendSpace1D.Binding.class.getMethod("commands",double.class,long.class).getGenericReturnType().getTypeName());
        assertSame(void.class,AnimationV2InstanceRuntime.class.getMethod("validateImmediateFrameCommands",List.class).getReturnType());
        assertNotNull(AnimationV2InstanceRuntime.class.getMethod("advanceAtFrame",double.class,List.class));
        assertNotNull(AnimationV2EvaluationSnapshot.class.getConstructor(long.class,AnimationV2Pose.class,Map.class,List.class));
    }
    static Set<String> descriptors(Class<?> type) {
        Set<String> result=new TreeSet<>();
        for(var method:type.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())||Modifier.isProtected(method.getModifiers()))result.add(Modifier.toString(method.getModifiers())+" "+method.getName()+MethodType.methodType(method.getReturnType(),method.getParameterTypes()).toMethodDescriptorString());
        for(var constructor:type.getDeclaredConstructors())if(Modifier.isPublic(constructor.getModifiers())||Modifier.isProtected(constructor.getModifiers()))result.add(Modifier.toString(constructor.getModifiers())+" <init>"+MethodType.methodType(void.class,constructor.getParameterTypes()).toMethodDescriptorString());
        return result;
    }
}
