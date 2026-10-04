package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.entity.BlendEntityLocomotionContractTest.allocate;
import static com.liy.blendlib.fabric.client.animation.runtime.BlendSpaceTestAssets.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.entity.consumer.BlendSpaceConsumerSample;
import java.util.*;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityBlendSpaceContractTest {
    @Test void optInRequiresLayersAndRejectsMemberLocomotionCompetitionInEitherOrder() {
        assertThrows(IllegalStateException.class,()->builder().animationBlendSpace1D(SPACE,(e,r)->0));
        assertThrows(IllegalStateException.class,()->builder().skinnedAnimation((e,r)->IDLE).animationBlendSpace1D(SPACE,(e,r)->0));
        var configured=animated().animationBlendSpace1D(SPACE,(e,r)->0);
        assertThrows(IllegalStateException.class,()->configured.animationBlendSpace1D(SPACE,(e,r)->0));
        assertThrows(IllegalStateException.class,()->configured.animationLayers(LAYERS,(e,r)->List.of()));
        assertThrows(IllegalArgumentException.class,()->configured.animationLocomotionRules(A,(e,r)->null));
        var locomotion=animated().animationLocomotionRules(A,(e,r)->null);
        assertThrows(IllegalArgumentException.class,()->locomotion.animationBlendSpace1D(SPACE,(e,r)->0));
        var other=animated().animationLocomotionRules(UPPER,(e,r)->null);
        assertSame(other,other.animationBlendSpace1D(SPACE,(e,r)->0));
        assertThrows(NullPointerException.class,()->animated().animationBlendSpace1D(SPACE,null));
        assertThrows(NullPointerException.class,()->animated().animationBlendSpace1D(null,(e,r)->0));
    }
    @Test void exactNewSignaturesAndOriginalRecordComponentsRemainStable() throws Exception {
        assertEquals(1,BlendEntityBlendSpaceParameter.class.getDeclaredMethods().length);
        assertTrue(BlendEntityBlendSpaceParameter.class.isAnnotationPresent(FunctionalInterface.class));
        assertSame(double.class,BlendEntityBlendSpaceParameter.class.getMethod("parameter",Entity.class,BlendEntitySnapshotRequest.class).getReturnType());
        assertSame(BlendEntityRendererBuilder.class,BlendEntityRendererBuilder.class.getMethod("animationBlendSpace1D",AnimationBlendSpace1D.class,BlendEntityBlendSpaceParameter.class).getReturnType());
        assertEquals(List.of("id","priority","mode","weight","bones","initialState"),Arrays.stream(ModelAnimationLayers.Layer.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        assertNotNull(AnimationBlendSpace1D.class.getConstructor(List.class,double.class));
        assertNotNull(AnimationBlendSpace1D.Sample.class.getConstructor(double.class,com.liy.blendlib.api.BlendResourceId.class));
        assertSame(AnimationV2LayerWeights.class,AnimationBlendSpace1D.class.getMethod("weights",double.class).getReturnType());
    }
    @Test void externalConsumerCompilesAgainstPublicOptIn() {var builder=builder();assertSame(builder,BlendSpaceConsumerSample.configure(builder));}
    private static BlendEntityRendererBuilder<Entity> animated(){return builder().skinnedAnimation((e,r)->IDLE).animationLayers(LAYERS,(e,r)->List.of());}
    private static BlendEntityRendererBuilder<Entity> builder(){return new BlendEntityRendererBuilder<>(allocate(EntityRendererProvider.Context.class),MODEL,new BlendRenderer((s,c)->{}));}
}
