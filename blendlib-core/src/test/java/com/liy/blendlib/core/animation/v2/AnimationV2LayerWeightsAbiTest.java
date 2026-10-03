package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendResourceId;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class AnimationV2LayerWeightsAbiTest {
    @Test
    void exactPublicWeightAndKeyDescriptorsAndGenericsArePinned() throws Exception {
        String weights = "Lcom/liy/blendlib/core/animation/v2/AnimationV2LayerWeights;";
        String key = "Lcom/liy/blendlib/core/animation/v2/AnimationV2LayerWeights$Key;";
        String id = "Lcom/liy/blendlib/api/BlendResourceId;";
        assertEquals(Set.of(
                "public <init>(Ljava/util/Map;)V",
                "public static empty()" + weights,
                "public multipliers()Ljava/util/Map;",
                "public multiplier(" + key + ")F"), exportedDescriptors(AnimationV2LayerWeights.class));
        assertEquals(Set.of(
                "public <init>(" + id + id + ")V",
                "public controllerId()" + id,
                "public layerId()" + id,
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"), exportedDescriptors(AnimationV2LayerWeights.Key.class));
        assertTrue(Modifier.isFinal(AnimationV2LayerWeights.class.getModifiers()));
        assertTrue(AnimationV2LayerWeights.Key.class.isRecord());
        assertEquals(List.of("controllerId", "layerId"), Arrays.stream(AnimationV2LayerWeights.Key.class.getRecordComponents())
                .map(component -> component.getName()).toList());
        assertEquals(List.of(BlendResourceId.class, BlendResourceId.class),
                Arrays.stream(AnimationV2LayerWeights.Key.class.getRecordComponents()).map(component -> component.getType()).toList());
        String genericMap = "java.util.Map<com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights$Key, java.lang.Float>";
        assertEquals(genericMap, AnimationV2LayerWeights.class.getConstructor(Map.class).getGenericParameterTypes()[0].getTypeName());
        assertEquals(genericMap, AnimationV2LayerWeights.class.getMethod("multipliers").getGenericReturnType().getTypeName());
        assertEquals(genericMap, AnimationV2EvaluationSnapshot.class.getMethod("effectiveLayerWeights").getGenericReturnType().getTypeName());
    }

    private static Set<String> exportedDescriptors(Class<?> type) {
        var descriptors = new TreeSet<String>();
        Arrays.stream(type.getDeclaredMethods()).filter(method -> exported(method.getModifiers())).forEach(method ->
                descriptors.add(Modifier.toString(method.getModifiers()) + " " + method.getName()
                        + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString()));
        Arrays.stream(type.getDeclaredConstructors()).filter(constructor -> exported(constructor.getModifiers())).forEach(constructor ->
                descriptors.add(Modifier.toString(constructor.getModifiers()) + " <init>"
                        + MethodType.methodType(void.class, constructor.getParameterTypes()).toMethodDescriptorString()));
        Arrays.stream(type.getDeclaredFields()).filter(field -> exported(field.getModifiers())).forEach(field ->
                descriptors.add(Modifier.toString(field.getModifiers()) + " " + field.getName() + ":" + field.getType().descriptorString()));
        return descriptors;
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
