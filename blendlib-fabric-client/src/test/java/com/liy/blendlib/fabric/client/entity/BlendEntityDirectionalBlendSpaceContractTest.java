package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.entity.BlendEntityLocomotionContractTest.allocate;
import static com.liy.blendlib.fabric.client.animation.runtime.BlendSpaceTestAssets.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import java.util.*;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityDirectionalBlendSpaceContractTest {
    static final AnimationBlendSpace2D SPACE2=BlendEntityDirectionalBlendSpaceLifecycleTest.SPACE2;
    static final List<ModelAnimationLayers.Layer> LAYERS2=BlendEntityDirectionalBlendSpaceLifecycleTest.LAYERS2;
    static AnimationBlendSpace2D.Input zero() {return new AnimationBlendSpace2D.Input(0,0);}
    @Test void optInRequiresLayersRejectsCompetingModesAndPreservesBuilderAfterInvalidConfiguration() {
        assertThrows(IllegalStateException.class,()->builder().animationBlendSpace2D(SPACE2,(e,r)->zero()));
        assertThrows(IllegalStateException.class,()->builder().skinnedAnimation((e,r)->IDLE).animationBlendSpace2D(SPACE2,(e,r)->zero()));
        var configured=animated().animationBlendSpace2D(SPACE2,(e,r)->zero());
        assertThrows(IllegalStateException.class,()->configured.animationBlendSpace2D(SPACE2,(e,r)->zero()));
        assertThrows(IllegalStateException.class,()->configured.animationBlendSpace1D(SPACE,(e,r)->0));
        assertThrows(IllegalStateException.class,()->animated().animationBlendSpace1D(SPACE,(e,r)->0).animationBlendSpace2D(SPACE2,(e,r)->zero()));
        assertThrows(IllegalStateException.class,()->configured.animationLayers(LAYERS2,(e,r)->List.of()));
        assertThrows(IllegalArgumentException.class,()->configured.animationLocomotionRules(A,(e,r)->null));
        assertThrows(IllegalArgumentException.class,()->animated().animationLocomotionRules(A,(e,r)->null).animationBlendSpace2D(SPACE2,(e,r)->zero()));
        var upper=animated().animationLocomotionRules(UPPER,(e,r)->null);assertSame(upper,upper.animationBlendSpace2D(SPACE2,(e,r)->zero()));
        var good=animated();assertThrows(NullPointerException.class,()->good.animationBlendSpace2D(SPACE2,null));assertSame(good,good.animationBlendSpace2D(SPACE2,(e,r)->zero()));
        assertThrows(NullPointerException.class,()->animated().animationBlendSpace2D(null,(e,r)->zero()));
        assertThrows(IllegalArgumentException.class,()->builder().skinnedAnimation((e,r)->IDLE).animationLayers(LAYERS,(e,r)->List.of()).animationBlendSpace2D(SPACE2,(e,r)->zero()));
    }
    @Test void publicVectorCaptureAndBuilderAreAdditive() throws Exception {
        assertTrue(BlendEntityBlendSpace2DParameter.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(1,BlendEntityBlendSpace2DParameter.class.getDeclaredMethods().length);
        assertSame(AnimationBlendSpace2D.Input.class,BlendEntityBlendSpace2DParameter.class.getMethod("parameter",Entity.class,BlendEntitySnapshotRequest.class).getReturnType());
        assertSame(BlendEntityRendererBuilder.class,BlendEntityRendererBuilder.class.getMethod("animationBlendSpace2D",AnimationBlendSpace2D.class,BlendEntityBlendSpace2DParameter.class).getReturnType());
        assertNotNull(BlendEntityRendererBuilder.class.getMethod("animationBlendSpace1D",AnimationBlendSpace1D.class,BlendEntityBlendSpaceParameter.class));
    }
    private static BlendEntityRendererBuilder<Entity> animated(){return builder().skinnedAnimation((e,r)->IDLE).animationLayers(LAYERS2,(e,r)->List.of());}
    private static BlendEntityRendererBuilder<Entity> builder(){return new BlendEntityRendererBuilder<>(allocate(EntityRendererProvider.Context.class),MODEL,new BlendRenderer((s,c)->{}));}
}
