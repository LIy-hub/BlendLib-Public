package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.entity.BlendEntityLocomotionContractTest.allocate;
import static com.liy.blendlib.fabric.client.animation.runtime.BlendSpaceTestAssets.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityDynamicCadenceContractTest {
    @Test void builderRequiresConfiguredBlendspaceAndKeepsPriorConfigurationOnNull() {
        assertThrows(IllegalStateException.class,()->builder().animationBlendSpaceCadence((e,r)->1));
        assertThrows(IllegalStateException.class,()->builder().skinnedAnimation((e,r)->IDLE).animationBlendSpaceCadence((e,r)->1));
        var one=builder().skinnedAnimation((e,r)->IDLE).animationLayers(LAYERS,(e,r)->List.of()).animationBlendSpace1D(SPACE,(e,r)->1);
        assertSame(one,one.animationBlendSpaceCadence((e,r)->2));assertThrows(NullPointerException.class,()->one.animationBlendSpaceCadence(null));
        assertSame(one,one.animationBlendSpaceCadence((e,r)->.5));
        var two=builder().skinnedAnimation((e,r)->IDLE).animationLayers(BlendEntityDirectionalBlendSpaceLifecycleTest.LAYERS2,(e,r)->List.of())
                .animationBlendSpace2D(BlendEntityDirectionalBlendSpaceLifecycleTest.SPACE2,(e,r)->new AnimationBlendSpace2D.Input(1,0));
        assertSame(two,two.animationBlendSpaceCadence((e,r)->2));
    }
    @Test void newCallbackAndEveryOldAndNewExtractionDescriptorArePinned() throws Exception {
        assertTrue(BlendEntityBlendSpaceCadence.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(1,BlendEntityBlendSpaceCadence.class.getDeclaredMethods().length);
        assertSame(double.class,BlendEntityBlendSpaceCadence.class.getMethod("multiplier",Entity.class,BlendEntitySnapshotRequest.class).getReturnType());
        assertSame(BlendEntityRendererBuilder.class,BlendEntityRendererBuilder.class.getMethod("animationBlendSpaceCadence",BlendEntityBlendSpaceCadence.class).getReturnType());
        for(var entry:List.of(new Object[]{"extractBlendSpace",AnimationBlendSpace1D.class,double.class},new Object[]{"extractBlendSpace2D",AnimationBlendSpace2D.class,AnimationBlendSpace2D.Input.class},new Object[]{"extractBlendSpaceFrame",AnimationBlendSpaceSyncGroup.class,AnimationV2LayerWeights.class})) {
            var types=new ArrayList<Class<?>>(List.of(SkinnedAnimationRuntimeInput.class,List.class,List.class,AnimationV2LayerWeights.class,(Class<?>)entry[1],(Class<?>)entry[2]));
            types.addAll(List.of(Object.class,Object.class,ClientAnimationPoseModifier.class,Consumer.class));
            assertSame(Optional.class,SkinnedAnimationRuntime.class.getMethod((String)entry[0],types.toArray(Class<?>[]::new)).getReturnType());
            types.add(6,double.class);
            assertSame(Optional.class,SkinnedAnimationRuntime.class.getMethod((String)entry[0],types.toArray(Class<?>[]::new)).getReturnType());
        }
        assertNotNull(SkinnedAnimationRuntime.class.getMethod("validateBlendSpaceCadence",com.liy.blendlib.api.BlendModelKey.class,long.class,List.class,AnimationBlendSpaceSyncGroup.class,double.class));
        // Existing constructor descriptor remains present; the added callback overload is additive.
        assertTrue(Arrays.stream(SkinnedAnimationEntitySnapshotFactory.class.getDeclaredConstructors()).anyMatch(c->c.getParameterCount()==16&&c.getParameterTypes()[15]==java.util.function.BiFunction.class));
        assertTrue(Arrays.stream(SkinnedAnimationEntitySnapshotFactory.class.getDeclaredConstructors()).anyMatch(c->c.getParameterCount()==17&&c.getParameterTypes()[16]==BlendEntityBlendSpaceCadence.class));
    }
    private static BlendEntityRendererBuilder<Entity> builder(){return new BlendEntityRendererBuilder<>(allocate(EntityRendererProvider.Context.class),MODEL,new BlendRenderer((s,c)->{}));}
}
