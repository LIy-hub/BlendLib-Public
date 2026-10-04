package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.reload.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** End-to-end immutable CPU extraction, including the actual legacy controller and public frame controls. */
class CpuMorphRuntimeIntegrationTest {
    static final BlendModelKey MODEL = BlendModelKey.parse("morph_test:actor");
    static final BlendAnimationKey IDLE=key("idle"), PULSE=key("pulse"), STEP=key("step"), FULL=key("full");
    static final BlendResourceId SMILE=id("smile"), BREATH=id("breath"), SOCKET=id("tip"), LAYER=id("base");
    final ClientModelRegistry models=new ClientModelRegistry();
    final ClientAnimationLifecycleBridge lifecycle=new ClientAnimationLifecycleBridge(2);
    final SkinnedAnimationRuntime runtime=new SkinnedAnimationRuntime(models,lifecycle);
    CpuMorphRuntimeIntegrationTest(){runtime.onPlayInit();publish(1);}

    @Test void animationFreeRestMorphUsesDefaultsAndNeverCreatesControllers() {
        publishStatic(2);
        var defaults = staticFrame(MorphFrameOverrides.empty()).orElseThrow();
        assertEquals(.25, firstX(defaults), 1e-6);
        var overridden = staticFrame(controls(1, 0)).orElseThrow();
        assertEquals(1, firstX(overridden), 1e-6);
        assertFalse(defaults.socketTransforms().isEmpty());
        assertEquals(defaults.socketTransforms(), overridden.socketTransforms(),
                "Morph vertices change without moving authored bone-rest sockets");
        assertThrows(UnsupportedOperationException.class, () -> defaults.socketTransforms().clear());
        assertEquals(.25, firstX(staticFrame(MorphFrameOverrides.empty()).orElseThrow()), 1e-6);
        assertEquals(.25, firstX(defaults), 1e-6);
        assertTrue(lifecycle.registry().find(runtime.entityKey(42)).isEmpty());
        assertNull(asset().animationDefinition());
        assertTrue(asset().clips().isEmpty());
        assertThrows(IllegalStateException.class, () -> overridden.renderSnapshot().skinnedRenderSnapshot()
                .x7FrameProvenanceAt(0, handle().skinnedPrimitives().getFirst()));
    }

    @Test void invalidStaticBatchDoesNotLeaveStateBehind() {
        publishStatic(2);
        assertThrows(IllegalArgumentException.class, () -> staticFrame(new MorphFrameOverrides(Map.of(id("absent"), 1F))));
        assertThrows(IllegalArgumentException.class, () -> staticFrame(controls(0, 2)));
        assertEquals(.25, firstX(staticFrame(MorphFrameOverrides.empty()).orElseThrow()), 1e-6);
        assertTrue(lifecycle.registry().find(runtime.entityKey(42)).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"unload", "disconnect", "play_init", "reload"})
    void staticMorphDiscardsCallbacksAcrossLifecycleBoundaries(String action) {
        publishStatic(2);
        var owner = runtime.entityKey(42);
        long revision = runtime.captureExtractionLifecycleRevision();
        var frozen = staticFrame(controls(1, 0)).orElseThrow();
        var request = input(42,0,IDLE).extractionRequest();
        switch (action) {
            case "unload" -> runtime.onEntityUnload(42);
            case "disconnect" -> runtime.onWorldDisconnect();
            case "play_init" -> runtime.onPlayInit();
            case "reload" -> publishStatic(3);
        }
        assertTrue(runtime.extractStaticMorph(MODEL, 2, owner, revision, controls(0,0), request).isEmpty());
        assertEquals(1, firstX(frozen), 1e-6);
    }

    private Optional<com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame> staticFrame(MorphFrameOverrides overrides) {
        long revision = runtime.captureExtractionLifecycleRevision();
        return runtime.extractStaticMorph(MODEL, models.current().generationId(), runtime.entityKey(42), revision,
                overrides, input(42,0,IDLE).extractionRequest());
    }
    private static float firstX(com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame frame) {
        var positions = new ArrayList<Float>();
        frame.renderSnapshot().skinnedRenderSnapshot().meshes().getFirst().emit((x,y,z,nx,ny,nz,u,v) -> positions.add(x));
        return positions.getFirst();
    }
    private void publishStatic(long generation) {
        var a = asset(generation);
        var targets = new IdentityHashMap<MeshPrimitive,MorphTargetSet>();
        a.primitives().forEach(p -> targets.put(p.geometry(), a.morphTargets(p)));
        var staticAsset = new ModelAsset(a.modelKey(), a.descriptorId(), a.generation(), a.profile(), a.unitsPerBlock(),
                a.materials(), null, a.nodes(), a.defaultSceneRoots(), a.primitives(), a.skeleton(), List.of(),
                a.sockets(), a.bounds(), a.diagnostics(), a.morphBindings(), targets);
        models.publish(new ModelRegistryGeneration(generation, Map.of(MODEL,
                new LoadedModelHandle(MODEL, staticAsset, SkinnedRenderHandle.prepare(MODEL, staticAsset))), Map.of(), List.of()));
    }

    @Test void realWeightOnlyClipDurationSamplingDefaultsStepAndClampedEndpoints() {
        var asset=asset(); var sampler=MorphWeightSampler.fromModelAsset(asset);
        var clip=asset.clips().stream().filter(c->c.name().equals("pulse")).findFirst().orElseThrow();
        assertTrue(clip.channels().isEmpty()); assertEquals(1,clip.durationSeconds());
        assertEquals(.5,sampler.sample(clip,.5).weight(SMILE),1e-7);
        assertEquals(1,sampler.sample(clip,100).weight(SMILE));
        var stepped=asset.clips().stream().filter(c->c.name().equals("step")).findFirst().orElseThrow();
        assertEquals(1,sampler.sample(stepped,.499).weight(SMILE));
        assertEquals(-1,sampler.sample(stepped,.5).weight(SMILE));
        var idle=asset.clips().getFirst();
        assertEquals(.25,sampler.sample(idle,.9).weight(SMILE));
        assertEquals(.1,sampler.sample(idle,.9).weight(BREATH),1e-7);
        var weights=sampler.sample(clip,.5); weights.values()[0]=2;
        assertEquals(.5,weights.weight(SMILE));
        assertThrows(IllegalArgumentException.class,()->sampler.sample(clip,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->sampler.sample(asset(2).clips().getFirst(),.5));
    }

    @Test void fusedMorphBeforeCubicSkinPreservesMaterialTopologyAndNormalMath() {
        var result=runtime.extractMorphClipAt(input(42,0,FULL),.5,MorphFrameOverrides.empty(),null).orElseThrow();
        var meshes=result.frame().renderSnapshot().skinnedRenderSnapshot().meshes();
        assertEquals(2,meshes.size());
        var vertices=vertices(result); // cubic translation at .5 = 2; morph local x=.5 and z=.15
        assertEquals(2.5,vertices.getFirst()[0],1e-6); assertEquals(.15,vertices.getFirst()[2],1e-6);
        double norm=Math.sqrt(1+.05*.05);
        assertEquals(.05/norm,vertices.getFirst()[3],1e-6); assertEquals(1/norm,vertices.getFirst()[5],1e-6);
        assertEquals(2,result.frame().socketTransform(SOCKET).orElseThrow().translation().x(),1e-6);
        for(var v:vertices) assertInside(handle().bounds(),v);
        assertTrue(handle().cpuMorphProfile());
        var before=result.frame().renderSnapshot();
        var appearance=before.withMaterialAppearance(Map.of("Skin",new MaterialSlotAppearance(0x4080ff,false)));
        assertSame(before.skinnedRenderSnapshot(),appearance.skinnedRenderSnapshot());
        assertEquals(before.handle().materialSlots(),List.of("Skin","Eyes"));
    }

    @Test void allZeroMorphFrameNeverReceivesSkinOnlyProvenance() {
        var result=runtime.extractMorphClipAt(input(42,0,IDLE),0,controls(0,0),null).orElseThrow();
        var captured=result.frame().renderSnapshot().skinnedRenderSnapshot();
        assertTrue(handle().cpuMorphProfile());
        assertThrows(IllegalStateException.class,()->captured.x7FrameProvenanceAt(0,handle().skinnedPrimitives().getFirst()));
        assertThrows(IllegalArgumentException.class,()->SkinnedRenderSnapshot.captureWithT4Provenance(handle(),List.of()));
        // The stable CPU capture intentionally has no provenance even when source geometry is unchanged.
        assertEquals(0,vertices(result).getFirst()[0]);
    }

    @Test void manualOverridesArePerFrameAndTwoActorsRemainIndependent() {
        var a=runtime.extractMorphClipAt(input(42,0,PULSE),.5,controls(-1,1),null).orElseThrow();
        var b=runtime.extractMorphClipAt(input(43,0,PULSE),.5,controls(2,0),null).orElseThrow();
        assertEquals(-1,vertices(a).getFirst()[0]); assertEquals(2,vertices(b).getFirst()[0]);
        var reset=runtime.extractMorphClipAt(input(42,0,PULSE),.5,MorphFrameOverrides.empty(),null).orElseThrow();
        assertEquals(.5,vertices(reset).getFirst()[0]);
        assertEquals(-1,vertices(a).getFirst()[0]); assertEquals(2,vertices(b).getFirst()[0]);
    }

    @Test void cadenceHeldTransformsStillCaptureNewManualControlsAndReset() {
        var initial=runtime.extractMorph(input(42,0,IDLE,AnimationUpdateBucket.VISIBLE_FAR),controls(0,0),null).orElseThrow();
        var held=runtime.extractMorph(input(42,0,IDLE,AnimationUpdateBucket.VISIBLE_FAR),controls(2,0),null).orElseThrow();
        var reset=runtime.extract(input(42,0,IDLE,AnimationUpdateBucket.VISIBLE_FAR)).orElseThrow();
        assertEquals(0,vertices(initial).getFirst()[0]); assertEquals(2,vertices(held).getFirst()[0]);
        assertEquals(.25,vertices(reset).getFirst()[0]);
        assertEquals(initial.advance().timeSeconds(),held.advance().timeSeconds());
    }

    @Test void invalidBatchFailsBeforeClockStateOrCueMutation() {
        var frozen=runtime.extract(input(42,0,IDLE)).orElseThrow();
        var instance=lifecycle.registry().find(runtime.entityKey(42)).orElseThrow();
        double before=instance.controller().currentTimeSeconds();
        var bad=new MorphFrameOverrides(Map.of(SMILE,1F,id("absent"),.3F));
        assertThrows(IllegalArgumentException.class,()->runtime.extractMorph(input(42,20,PULSE),bad,null));
        assertEquals(IDLE,instance.controller().currentState()); assertEquals(before,instance.controller().currentTimeSeconds());
        assertThrows(IllegalArgumentException.class,()->runtime.extractMorph(input(42,20,PULSE),controls(0,2),null));
        assertEquals(.25,vertices(frozen).getFirst()[0]);
        assertThrows(IllegalArgumentException.class,()->new MorphFrameOverrides(Map.of(SMILE,Float.NaN)));
    }

    @Test void controllerCrossfadesCompleteVectorsUsingSameClocksAndResetsOmittedTracks() {
        var asset=asset(); var definition=AnimationControllerDefinition.fromModelAsset(asset);
        var controller=new AnimationController(runtime.entityKey(42),definition);
        var sampler=MorphWeightSampler.fromModelAsset(asset); var pose=PoseSampler.fromModelAsset(asset);
        controller.synchronizeClip(FULL,.5); controller.trigger(IDLE); controller.advance(.1);
        // half of the .2s transition: previous FULL at .6 (weight .6); current IDLE default .25.
        assertEquals(.425,controller.sampleMorphWeights(sampler).weight(SMILE),1e-6);
        assertEquals(.5*8*.6*.4,controller.sample(pose).transform(1).translation().x(),1e-6);
        var retained=controller.sampleMorphWeights(sampler);
        controller.advance(.1); assertEquals(.25,controller.sampleMorphWeights(sampler).weight(SMILE));
        assertEquals(.425,retained.weight(SMILE),1e-6);
        controller.trigger(PULSE); controller.advance(.05); controller.trigger(IDLE); controller.advance(.05);
        // Interrupted transitions deliberately follow the existing controller's own source/current rules.
        assertEquals(.25*.15625+.1*.84375,controller.sampleMorphWeights(sampler).weight(SMILE),1e-6);
    }

    @Test void boneOnlyLayersAcceptManualControlsAndRejectMorphInitialAndCommandsAtomically() {
        var layers=List.of(new ModelAnimationLayers.Layer(LAYER,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),IDLE));
        var frame=runtime.extractLayeredMorph(input(42,0,IDLE),layers,List.of(),AnimationV2LayerWeights.empty(),controls(1,0),null).orElseThrow();
        assertEquals(1,vertices(frame).getFirst()[0]);
        var before=runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
        assertThrows(IllegalArgumentException.class,()->runtime.extractLayeredMorph(input(42,10,IDLE),layers,
                List.of(new AnimationV2Command(LAYER,PULSE,1,0,1)),AnimationV2LayerWeights.empty(),controls(0,0),null));
        assertSame(before,runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->new ModelAnimationLayers(asset(),List.of(
                new ModelAnimationLayers.Layer(LAYER,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),PULSE))));
        var state=AnimationControllerDefinition.fromModelAsset(asset()).state(PULSE);
        assertThrows(IllegalArgumentException.class,()->AnimationV2Clip.fromState(PoseSampler.fromModelAsset(asset()),state,List.of(0,1)));
    }

    @Test void blendspaceMembersAndBoneNextLinksRejectWeightsBeforeCreatingPlayback() {
        var layers=List.of(new ModelAnimationLayers.Layer(LAYER,0,AnimationV2LayerMode.OVERRIDE,1,List.of(),IDLE),
                new ModelAnimationLayers.Layer(id("weight_member"),0,AnimationV2LayerMode.OVERRIDE,1,List.of(),PULSE));
        var space=new AnimationBlendSpace1D(List.of(new AnimationBlendSpace1D.Sample(0,LAYER),
                new AnimationBlendSpace1D.Sample(1,id("weight_member"))),1);
        assertThrows(IllegalArgumentException.class,()->runtime.extractBlendSpace(input(42,0,IDLE),layers,List.of(),
                AnimationV2LayerWeights.empty(),space,.5,new Object(),new Object(),null,null));
        assertTrue(lifecycle.registry().find(runtime.entityKey(42)).isEmpty());
        var a=asset();var states=new HashMap<>(a.animationDefinition().states());
        states.put(IDLE.resourceId(),new AnimationStateDefinition("idle",false,1,.2,PULSE.resourceId(),List.of()));
        var targets=new IdentityHashMap<MeshPrimitive,MorphTargetSet>();a.primitives().forEach(p->targets.put(p.geometry(),a.morphTargets(p)));
        var linked=new ModelAsset(a.modelKey(),a.descriptorId(),a.generation(),a.profile(),a.unitsPerBlock(),a.materials(),
                new AnimationDefinition(IDLE.resourceId(),states),a.nodes(),a.defaultSceneRoots(),a.primitives(),a.skeleton(),a.clips(),
                a.sockets(),a.bounds(),a.diagnostics(),a.morphBindings(),targets);
        assertThrows(IllegalArgumentException.class,()->new ModelAnimationLayers(linked,layers.subList(0,1)));
    }

    @Test void mixedCueBatchRejectsWeightClipBeforeAcceptingAnyOtherSequence() {
        Object source = new Object(), owner = new Object();
        var first = new com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue(LAYER,IDLE,1,0,1);
        var accepted = runtime.captureEntityLayerCues(source,owner,42,MODEL,1,0,List.of(first)).getFirst();
        var newer = new com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue(LAYER,IDLE,2,0,1);
        var unsupported = new com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue(id("other"),PULSE,2,0,1);
        assertThrows(IllegalArgumentException.class,()->runtime.captureEntityLayerCues(source,owner,42,MODEL,1,10,List.of(newer,unsupported)));
        assertSame(accepted,runtime.captureEntityLayerCues(source,owner,42,MODEL,1,10,List.of(first)).getFirst());
    }

    @Test void poseModifierRetainsWeightsAndSnapshotBindingRemainsImmutable() {
        var asset=asset(); var instances=lifecycle.registry();
        ClientAnimationPoseContext[] observed = new ClientAnimationPoseContext[1];
        var actual = runtime.extractMorphClipAt(input(42,0,PULSE),.5,controls(2,0),
                (context,pose)->{observed[0]=context;return pose;}).orElseThrow();
        assertEquals(2,vertices(actual).getFirst()[0]);
        var instance=instances.bind(runtime.entityKey(42),MODEL,1,AnimationControllerDefinition.fromModelAsset(asset));
        instance.controller().synchronizeClip(PULSE,.5);
        var snapshot=instances.preparePoseSnapshot(new PoseCacheKey(runtime.entityKey(42),MODEL,1,PULSE,1),
                PoseSampler.fromModelAsset(asset),MorphWeightSampler.fromModelAsset(asset));
        var overridden=instances.withMorphWeights(snapshot,snapshot.morphWeights().overridden(controls(2,0)));
        var modified=instances.applyPoseModifier(overridden,observed[0],(context,pose)->pose);
        assertSame(overridden.morphWeights(),modified.morphWeights());
        assertEquals(.5,snapshot.morphWeights().weight(SMILE));
        assertThrows(IllegalArgumentException.class,()->instances.withMorphWeights(snapshot,MorphWeights.defaults(asset(2).morphBindings())));
        instances.remove(runtime.entityKey(42));
        assertEquals(2,modified.morphWeights().weight(SMILE));
        assertThrows(IllegalArgumentException.class,()->instances.requireCurrentPoseSnapshot(snapshot));
    }

    @ParameterizedTest @ValueSource(strings={"unload","retire","disconnect","play_init","reload"})
    void lifecycleClearsMutableStateAndRetainedCaptureSurvives(String mode) {
        var prior=runtime.extractMorphClipAt(input(42,0,PULSE),.75,controls(2,0),null).orElseThrow();
        var oldHandle=handle();
        switch(mode){
            case "unload"->runtime.onEntityUnload(42);
            case "retire"->runtime.retire(runtime.entityKey(42));
            case "disconnect"->{runtime.onWorldDisconnect();runtime.onPlayInit();}
            case "play_init"->runtime.onPlayInit();
            case "reload"->publish(2);
        }
        var fresh=runtime.extract(input(42,100,IDLE)).orElseThrow();
        assertEquals(.25,vertices(fresh).getFirst()[0]); assertEquals(2,vertices(prior).getFirst()[0]);
        if(mode.equals("reload")){
            assertNotSame(oldHandle,handle());
            var frozen=prior.frame().renderSnapshot();
            assertThrows(IllegalArgumentException.class,()->ModelRenderSnapshot.skinned(handle(),frozen.rootTransform(),
                    frozen.packedLight(),frozen.packedOverlay(),frozen.tintArgb(),frozen.visibility(),frozen.culling(),frozen.skinnedRenderSnapshot()));
        }
    }

    private static MorphFrameOverrides controls(float smile,float breath){return new MorphFrameOverrides(Map.of(SMILE,smile,BREATH,breath));}
    private SkinnedAnimationRuntimeInput input(int entity,long tick,BlendAnimationKey state){return input(entity,tick,state,AnimationUpdateBucket.VISIBLE_NEAR);}
    private SkinnedAnimationRuntimeInput input(int entity,long tick,BlendAnimationKey state,AnimationUpdateBucket bucket){
        return new SkinnedAnimationRuntimeInput(MODEL,runtime.entityKey(entity),tick,0,state,Optional.empty(),bucket,
                new SkinnedExtractionRequest(Transform.IDENTITY,0xA000B,7,0xFFFFFFFF,RenderVisibility.VISIBLE,new CullingMetadata(handle().bounds(),true)));
    }
    private ModelAsset asset(){return ((LoadedModelHandle)models.find(MODEL).orElseThrow()).asset();}
    private SkinnedRenderHandle handle(){return (SkinnedRenderHandle)models.find(MODEL).orElseThrow().renderHandle();}
    private void publish(long generation){var asset=asset(generation);models.publish(new ModelRegistryGeneration(generation,
            Map.of(MODEL,new LoadedModelHandle(MODEL,asset,SkinnedRenderHandle.prepare(MODEL,asset))),Map.of(),List.of()));}
    static ModelAsset asset(long generation){
        var g=geometry("Skin"); var eyes=geometry("Eyes");
        var binding=new MorphBindingTable.Binding(0,List.of("Smile","Breath"),0,new float[]{.25F,.1F},new float[]{-1,-1},new float[]{2,1});
        var bindings=new MorphBindingTable(List.of(binding),Map.of(SMILE,new MorphBindingTable.Control(0,0,0,-1,2),BREATH,new MorphBindingTable.Control(0,1,1,-1,1)));
        var targets=new MorphTargetSet(List.of("Smile","Breath"),3,new float[][]{
                {1,0,0,1,0,0,1,0,0},{0,0,1,0,0,1,0,0,1}},new float[][]{
                {.1F,0,0,.1F,0,0,.1F,0,0},new float[9]});
        var nod=AnimationChannel.forCubicProfile(1,AnimationPath.TRANSLATION,Interpolation.CUBICSPLINE,new float[]{0,1},new float[6],new float[]{0,0,0,-8,0,0},new float[]{8,0,0,0,0,0});
        var idle=new AnimationClip("idle",List.of(new AnimationChannel(1,AnimationPath.TRANSLATION,Interpolation.LINEAR,new float[]{0,1},new float[6])));
        var morph=new MorphWeightChannel(0,2,Interpolation.LINEAR,new float[]{0,1},new float[]{0,.1F,1,.2F});
        var pulse=new AnimationClip("pulse",List.of(),List.of(morph));
        var step=new AnimationClip("step",List.of(),List.of(new MorphWeightChannel(0,2,Interpolation.STEP,new float[]{0,.5F,1},new float[]{1,.1F,-1,.1F,0,.1F})));
        var full=new AnimationClip("full",List.of(nod),List.of(morph));
        return new ModelAsset(MODEL.resourceId(),MODEL.descriptorResourceId(),generation,ModelProfile.SKINNED_MORPH_CPU_V1,1,
                Map.of("Skin",material(),"Eyes",material()),new AnimationDefinition(IDLE.resourceId(),Map.of(IDLE.resourceId(),state("idle"),PULSE.resourceId(),state("pulse"),STEP.resourceId(),state("step"),FULL.resourceId(),state("full"))),
                List.of(new ModelNode(0,"Mesh",Transform.IDENTITY,List.of(1),0,0,false),new ModelNode(1,"Bone",Transform.IDENTITY,List.of(),-1,-1,false)),List.of(0),
                List.of(new ModelPrimitive(0,0,0,g),new ModelPrimitive(0,0,1,eyes)),new Skeleton(List.of(new Skin("Rig",1,List.of(1),new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}))),
                List.of(idle,pulse,step,full),new SocketTable(Map.of(SOCKET,new SocketTable.Socket(1,"Mesh/Bone"))),Bounds.fromPositions(g.positions()),List.of(),bindings,Map.of(g,targets,eyes,targets));
    }
    private static MeshPrimitive geometry(String slot){return new MeshPrimitive(slot,new float[]{0,0,0,1,0,0,0,1,0},new float[]{0,0,1,0,0,1,0,0,1},new float[]{0,0,1,0,0,1},new int[]{0,1,2},new int[12],new float[]{1,0,0,0,1,0,0,0,1,0,0,0});}
    private static MaterialDefinition material(){return new MaterialDefinition(id("textures/skin.png"),MaterialDefinition.Mode.OPAQUE,false,false,null);}
    private static AnimationStateDefinition state(String clip){return new AnimationStateDefinition(clip,true,1,.2,null,List.of());}
    private static BlendResourceId id(String path){return BlendResourceId.parse("morph_test:"+path);}
    private static BlendAnimationKey key(String path){return BlendAnimationKey.parse("morph_test:"+path);}
    private static List<float[]> vertices(SkinnedAnimationRuntimeResult result){var list=new ArrayList<float[]>();result.frame().renderSnapshot().skinnedRenderSnapshot().meshes().forEach(m->m.emit((x,y,z,nx,ny,nz,u,v)->list.add(new float[]{x,y,z,nx,ny,nz,u,v})));return list;}
    private static void assertInside(Bounds b,float[] p){assertTrue(p[0]>=b.min().x()&&p[0]<=b.max().x());assertTrue(p[1]>=b.min().y()&&p[1]<=b.max().y());assertTrue(p[2]>=b.min().z()&&p[2]<=b.max().z());}
}
