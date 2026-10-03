package com.liy.blendlib.fabric.client.animation.runtime.procedural;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;
class WeightedPoseComponentAbiTest {
    @Test void exactPublicAbiIsPinned() throws Exception {
        String p = "Lcom/liy/blendlib/fabric/client/animation/runtime/procedural/ProceduralPoseComponent;";
        assertEquals(Set.of(
            "public <init>(" + p + "Ljava/util/function/ToDoubleFunction;)V",
            "public <init>(" + p + "Ljava/util/function/ToDoubleFunction;Ljava/util/Map;)V",
            "public modify(Lcom/liy/blendlib/fabric/client/animation/runtime/ClientAnimationPoseContext;Lcom/liy/blendlib/core/animation/runtime/LocalPose;)Lcom/liy/blendlib/core/animation/runtime/LocalPose;",
            "public reset()V",
            "public reset(Lcom/liy/blendlib/api/BlendInstanceKey;)V"),exportedDescriptors(WeightedPoseComponent.class));
        assertTrue(Modifier.isFinal(WeightedPoseComponent.class.getModifiers()));
        assertEquals(List.of(ProceduralPoseComponent.class),List.of(WeightedPoseComponent.class.getInterfaces()));
        var constructor = WeightedPoseComponent.class.getConstructor(ProceduralPoseComponent.class,java.util.function.ToDoubleFunction.class,Map.class);
        assertEquals("java.util.function.ToDoubleFunction<com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext>",constructor.getGenericParameterTypes()[1].getTypeName());
        assertEquals("java.util.Map<java.lang.String, java.lang.Float>",constructor.getGenericParameterTypes()[2].getTypeName());
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
                descriptors.add(Modifier.toString(field.getModifiers() & ~0x4000) + " " + field.getName() + ":" + field.getType().descriptorString()));
        return descriptors;
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
