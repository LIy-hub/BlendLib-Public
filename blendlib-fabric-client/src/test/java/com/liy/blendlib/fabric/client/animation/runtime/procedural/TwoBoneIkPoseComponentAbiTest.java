package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TwoBoneIkPoseComponentAbiTest {
    private static final String PROCEDURAL =
            "com/liy/blendlib/fabric/client/animation/runtime/procedural/";
    private static final String CONTEXT =
            "Lcom/liy/blendlib/fabric/client/animation/runtime/ClientAnimationPoseContext;";
    private static final String POSE = "Lcom/liy/blendlib/core/animation/runtime/LocalPose;";
    private static final String VECTOR = "Lcom/liy/blendlib/core/model/Vec3;";
    private static final String STATUS = "L" + PROCEDURAL + "TwoBoneIkPoseComponent$Status;";

    @Test
    void exactComponentPublicAbiIsPinned() throws Exception {
        assertEquals(Set.of(
                "public <init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/function/Function;)V",
                "public <init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/function/Function;Ljava/util/function/BiConsumer;)V",
                "public modify(" + CONTEXT + POSE + ")" + POSE),
                exportedDescriptors(TwoBoneIkPoseComponent.class));
        assertEquals(Modifier.PUBLIC | Modifier.FINAL, TwoBoneIkPoseComponent.class.getModifiers());
        assertEquals(Object.class, TwoBoneIkPoseComponent.class.getSuperclass());
        assertEquals(List.of(ProceduralPoseComponent.class),
                List.of(TwoBoneIkPoseComponent.class.getInterfaces()));
        assertEquals(Set.of(TwoBoneIkPoseComponent.Result.class, TwoBoneIkPoseComponent.Status.class),
                exportedNestedTypes(TwoBoneIkPoseComponent.class));
        assertEquals(0, TwoBoneIkPoseComponent.class.getTypeParameters().length);
        assertEquals(ProceduralPoseComponent.class,
                TwoBoneIkPoseComponent.class.getMethod("reset").getDeclaringClass());
        assertEquals(ProceduralPoseComponent.class,
                TwoBoneIkPoseComponent.class.getMethod("reset", BlendInstanceKey.class).getDeclaringClass());
    }

    @Test
    void constructorCallbackGenericSignaturesArePinned() throws Exception {
        String context = ClientAnimationPoseContext.class.getName();
        String target = "java.util.function.Function<" + context + ", " + TwoBoneIkTarget.class.getName() + ">";
        String observer = "java.util.function.BiConsumer<" + context + ", "
                + TwoBoneIkPoseComponent.Result.class.getName() + ">";
        var simple = TwoBoneIkPoseComponent.class.getConstructor(
                String.class, String.class, String.class, Function.class);
        var observed = TwoBoneIkPoseComponent.class.getConstructor(
                String.class, String.class, String.class, Function.class, BiConsumer.class);
        assertEquals(List.of("java.lang.String", "java.lang.String", "java.lang.String", target),
                Arrays.stream(simple.getGenericParameterTypes()).map(type -> type.getTypeName()).toList());
        assertEquals(List.of("java.lang.String", "java.lang.String", "java.lang.String", target, observer),
                Arrays.stream(observed.getGenericParameterTypes()).map(type -> type.getTypeName()).toList());
        assertEquals(0, simple.getTypeParameters().length);
        assertEquals(0, observed.getTypeParameters().length);
        assertEquals(0, simple.getExceptionTypes().length);
        assertEquals(0, observed.getExceptionTypes().length);
    }

    @Test
    void exactTargetPublicAbiIsPinned() {
        assertEquals(Set.of(
                "public <init>(" + VECTOR + VECTOR + ")V",
                "public targetModelSpace()" + VECTOR,
                "public poleModelSpace()" + VECTOR,
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"), exportedDescriptors(TwoBoneIkTarget.class));
        assertEquals(Modifier.PUBLIC | Modifier.FINAL, TwoBoneIkTarget.class.getModifiers());
        assertEquals(List.of("targetModelSpace:" + VECTOR, "poleModelSpace:" + VECTOR),
                recordComponents(TwoBoneIkTarget.class));
        assertImmutableRecord(TwoBoneIkTarget.class);
    }

    @Test
    void exactResultPublicAbiIsPinned() {
        assertEquals(Set.of(
                "public <init>(" + STATUS + VECTOR + "ZZ)V",
                "public status()" + STATUS,
                "public effectiveTargetModelSpace()" + VECTOR,
                "public usedDirectionFallback()Z",
                "public usedPoleFallback()Z",
                "public final equals(Ljava/lang/Object;)Z",
                "public final hashCode()I",
                "public final toString()Ljava/lang/String;"),
                exportedDescriptors(TwoBoneIkPoseComponent.Result.class));
        assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL,
                TwoBoneIkPoseComponent.Result.class.getModifiers());
        assertEquals(TwoBoneIkPoseComponent.class, TwoBoneIkPoseComponent.Result.class.getDeclaringClass());
        assertEquals(List.of("status:" + STATUS, "effectiveTargetModelSpace:" + VECTOR,
                "usedDirectionFallback:Z", "usedPoleFallback:Z"),
                recordComponents(TwoBoneIkPoseComponent.Result.class));
        assertImmutableRecord(TwoBoneIkPoseComponent.Result.class);
    }

    @Test
    void exactStatusPublicAbiIsPinned() {
        assertEquals(Set.of(
                "public static values()[" + STATUS,
                "public static valueOf(Ljava/lang/String;)" + STATUS,
                "public static final REACHED:" + STATUS,
                "public static final CLAMPED_NEAR:" + STATUS,
                "public static final CLAMPED_FAR:" + STATUS,
                "public static final DEGENERATE_CHAIN:" + STATUS),
                exportedDescriptors(TwoBoneIkPoseComponent.Status.class));
        assertTrue(TwoBoneIkPoseComponent.Status.class.isEnum());
        assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL,
                TwoBoneIkPoseComponent.Status.class.getModifiers() & ~0x4000);
        assertEquals(TwoBoneIkPoseComponent.class, TwoBoneIkPoseComponent.Status.class.getDeclaringClass());
        assertEquals("java.lang.Enum<" + TwoBoneIkPoseComponent.Status.class.getName() + ">",
                TwoBoneIkPoseComponent.Status.class.getGenericSuperclass().getTypeName());
        assertEquals(List.of("REACHED", "CLAMPED_NEAR", "CLAMPED_FAR", "DEGENERATE_CHAIN"),
                Arrays.stream(TwoBoneIkPoseComponent.Status.values()).map(Enum::name).toList());
        assertEquals(List.of(), List.of(TwoBoneIkPoseComponent.Status.class.getInterfaces()));
        assertEquals(Set.of(), exportedNestedTypes(TwoBoneIkPoseComponent.Status.class));
    }

    @Test
    void executableConsumerUsesOnlyPublicContracts() {
        TwoBoneIkConsumerProbe.main(new String[0]);
    }

    private static void assertImmutableRecord(Class<?> type) {
        assertTrue(type.isRecord());
        assertEquals(Record.class, type.getSuperclass());
        assertEquals(List.of(), List.of(type.getInterfaces()));
        assertEquals(0, type.getTypeParameters().length);
        assertEquals(Set.of(), exportedNestedTypes(type));
        Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()))
                .forEach(field -> assertEquals(Modifier.PRIVATE | Modifier.FINAL, field.getModifiers()));
    }

    private static List<String> recordComponents(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().descriptorString()).toList();
    }

    private static Set<Class<?>> exportedNestedTypes(Class<?> type) {
        return Set.copyOf(Arrays.stream(type.getDeclaredClasses())
                .filter(nested -> exported(nested.getModifiers())).toList());
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
                descriptors.add(Modifier.toString(field.getModifiers() & ~0x4000) + " " + field.getName()
                        + ":" + field.getType().descriptorString()));
        return descriptors;
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
