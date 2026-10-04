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
                "public syncGroup()"+base+"AnimationBlendSpaceSyncGroup;",
                "public weights(D)"+base+"AnimationV2LayerWeights;",
                "public validateExternalWeights("+base+"AnimationV2LayerWeights;)V",
                "public validateExternalCommands(Ljava/util/List;)V",
                "public bind("+base+"AnimationV2InstancePlan;)"+base+"AnimationBlendSpace1D$Binding;"),descriptors(AnimationBlendSpace1D.class));
        assertEquals(Set.of("public <init>(D"+id+")V","public position()D","public layerId()"+id,
                "public final equals(Ljava/lang/Object;)Z","public final hashCode()I","public final toString()Ljava/lang/String;"),descriptors(AnimationBlendSpace1D.Sample.class));
        assertEquals(Set.of("public commands(DJ)Ljava/util/List;",
                "public rateUpdate(D)"+base+"AnimationBlendSpaceSyncGroup$RateUpdate;"),descriptors(AnimationBlendSpace1D.Binding.class));
        assertTrue(Modifier.isFinal(AnimationBlendSpace1D.class.getModifiers()));
        assertTrue(Modifier.isFinal(AnimationBlendSpace1D.Binding.class.getModifiers()));
        assertEquals("java.util.List<com.liy.blendlib.core.animation.v2.AnimationBlendSpace1D$Sample>",AnimationBlendSpace1D.class.getConstructor(List.class,double.class).getGenericParameterTypes()[0].getTypeName());
        assertEquals("java.util.List<com.liy.blendlib.core.animation.v2.AnimationV2Command>",AnimationBlendSpace1D.Binding.class.getMethod("commands",double.class,long.class).getGenericReturnType().getTypeName());
        assertSame(void.class,AnimationV2InstanceRuntime.class.getMethod("validateImmediateFrameCommands",List.class).getReturnType());
        assertNotNull(AnimationV2InstanceRuntime.class.getMethod("advanceAtFrame",double.class,List.class));
        assertNotNull(AnimationV2EvaluationSnapshot.class.getConstructor(long.class,AnimationV2Pose.class,Map.class,List.class));
    }

    @Test void sharedGroupRateUpdateAndAdditiveRuntimeDescriptorsArePinned() throws Exception {
        String base = "Lcom/liy/blendlib/core/animation/v2/";
        String update = base + "AnimationBlendSpaceSyncGroup$RateUpdate;";
        String weights = base + "AnimationV2LayerWeights;";
        String snapshot = base + "AnimationV2EvaluationSnapshot;";
        assertEquals(Set.of("public memberLayerIds()Ljava/util/Set;", "public cycleSeconds()D",
                "public static validateCadenceMultiplier(D)V",
                "public validateExternalWeights(" + weights + ")V",
                "public validateExternalCommands(Ljava/util/List;)V",
                "public bind(" + base + "AnimationV2InstancePlan;)" + base + "AnimationBlendSpaceSyncGroup$Binding;"),
                descriptors(AnimationBlendSpaceSyncGroup.class));
        assertEquals(Set.of("public commands(DJ)Ljava/util/List;", "public rateUpdate(D)" + update),
                descriptors(AnimationBlendSpaceSyncGroup.Binding.class));
        assertEquals(Set.of("public cadenceMultiplier()D", "public commandRates()Ljava/util/Map;"),
                descriptors(AnimationBlendSpaceSyncGroup.RateUpdate.class));
        Set<String> addedRuntime = new TreeSet<>();
        for (String descriptor : descriptors(AnimationV2InstanceRuntime.class)) {
            if (descriptor.contains(" advanceBlendSpaceAtFrame(") || descriptor.contains(" validateRateUpdate("))
                addedRuntime.add(descriptor);
        }
        assertEquals(Set.of("public validateRateUpdate(" + update + ")V",
                "public advanceBlendSpaceAtFrame(DLjava/util/List;" + weights + update + ")" + snapshot,
                "public advanceBlendSpaceAtFrame(DLjava/util/List;Ljava/util/List;" + weights + update + ")" + snapshot),
                addedRuntime);
        assertEquals("java.util.Map<com.liy.blendlib.api.BlendResourceId, java.lang.Double>",
                AnimationBlendSpaceSyncGroup.RateUpdate.class.getMethod("commandRates").getGenericReturnType().getTypeName());
        assertTrue(Modifier.isFinal(AnimationBlendSpaceSyncGroup.RateUpdate.class.getModifiers()));
        assertTrue(Modifier.isFinal(AnimationBlendSpaceSyncGroup.Binding.class.getModifiers()));
        assertEquals(0, AnimationBlendSpaceSyncGroup.RateUpdate.class.getConstructors().length);
        assertEquals(1D / 64, AnimationBlendSpaceSyncGroup.MIN_CADENCE_MULTIPLIER);
        assertEquals(64D, AnimationBlendSpaceSyncGroup.MAX_CADENCE_MULTIPLIER);
        for (String name : List.of("MIN_CADENCE_MULTIPLIER", "MAX_CADENCE_MULTIPLIER")) {
            var field = AnimationBlendSpaceSyncGroup.class.getField(name);
            assertSame(double.class, field.getType());
            assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, field.getModifiers());
        }
    }
    static Set<String> descriptors(Class<?> type) {
        Set<String> result=new TreeSet<>();
        for(var method:type.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())||Modifier.isProtected(method.getModifiers()))result.add(Modifier.toString(method.getModifiers())+" "+method.getName()+MethodType.methodType(method.getReturnType(),method.getParameterTypes()).toMethodDescriptorString());
        for(var constructor:type.getDeclaredConstructors())if(Modifier.isPublic(constructor.getModifiers())||Modifier.isProtected(constructor.getModifiers()))result.add(Modifier.toString(constructor.getModifiers())+" <init>"+MethodType.methodType(void.class,constructor.getParameterTypes()).toMethodDescriptorString());
        return result;
    }
}
