package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.animation.runtime.BlendSpaceTestAssets.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkinnedBlendSpaceRuntimeTest {
    final ClientModelRegistry models=new ClientModelRegistry();
    final ClientAnimationLifecycleBridge lifecycle=new ClientAnimationLifecycleBridge(16);
    final SkinnedAnimationRuntime runtime=new SkinnedAnimationRuntime(models,lifecycle);
    final Object source=new Object(),owner=new Object();
    final List<LayerAnimationVisualEvent> events=new ArrayList<>();
    SkinnedBlendSpaceRuntimeTest() { runtime.onPlayInit();models.publish(generation(1)); }

    @Test void parameterChangesKeepAllPhasesAndSequencesWhileUpperLayerRemainsIndependent() {
        var upper=new AnimationV2Command(UPPER,IDLE,7,.1,1);
        frame(0,0,List.of(upper),source,owner);
        for (int tick=1;tick<=120;tick++) {
            frame(tick,(tick%40)/20.0,List.of(),source,owner);
            var snapshot=snapshot();
            phase(snapshot,(tick%40)/40.0,1);
            for(var id:SPACE.memberLayerIds()) assertEquals(0,snapshot.playheads().get(id).acceptedSequence());
            assertEquals(7,snapshot.playheads().get(UPPER).acceptedSequence());
        }
        assertEquals(1.1,snapshot().playheads().get(UPPER).timeSeconds(),1e-8);
        assertEquals(.25F,snapshot().effectiveLayerWeights().get(new AnimationV2LayerWeights.Key(UPPER,UPPER)));
    }

    @Test void duplicateAndBackwardTimesNeverAdvanceRewindOrRestartAndWeightsStillChange() {
        frame(10,0);frame(20,1);var before=snapshot();
        frame(20,2);phase(snapshot(),.25,1);
        frame(12,.5);phase(snapshot(),.25,1);
        frame(24,0);phase(snapshot(),.35,1);
        assertEquals(before.playheads().get(A).acceptedSequence(),snapshot().playheads().get(A).acceptedSequence());
    }

    @Test void reloadRetainsActivationOriginRebindsChangedDurationsAndDoesNotBackfillEvents() {
        frame(100,1);frame(110,1);assertFalse(events.isEmpty()); events.clear();
        models.publish(generation(2,2,true));
        assertTrue(runtime.layeredSnapshot(runtime.entityKey(42)).isEmpty());
        frame(120,1);phase(snapshot(),.5,2);assertTrue(events.isEmpty());
        frame(150,1);phase(snapshot(),.25,2);assertFalse(events.isEmpty());
        assertTrue(events.stream().noneMatch(e->e.event().eventKey().equals(id("zero"))));
    }

    @Test void longGapRecoversCurrentCycleWithOneDiscontinuityAndSubsequentFramesResumeEvents() {
        frame(0,1);frame(13010,1);phase(snapshot(),.25,1);assertTrue(events.isEmpty());
        long sequence=snapshot().playheads().get(A).acceptedSequence();assertEquals(1,sequence);
        frame(13020,1);phase(snapshot(),.5,1);assertEquals(sequence,snapshot().playheads().get(A).acceptedSequence());
        frame(13050,1);assertFalse(events.isEmpty());
        assertTrue(events.size()<=com.liy.blendlib.core.limits.BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE);
    }

    @Test void zeroWeightConsumesMarkersWithoutBackfillAndTwoActiveSamplesKeepSeparateMarkers() {
        frame(0,0);frame(10,0);
        assertTrue(events.stream().allMatch(e->e.controllerId().equals(A)||e.controllerId().equals(UPPER)));
        events.clear();frame(10,1);assertTrue(events.isEmpty());
        frame(40,.5);events.clear();frame(50,.5);
        assertTrue(events.stream().anyMatch(e->e.controllerId().equals(A)));
        assertTrue(events.stream().anyMatch(e->e.controllerId().equals(B)));
        assertTrue(events.stream().noneMatch(e->e.controllerId().equals(C)));
    }

    @Test void invalidScalarBindingMemberCommandsAndWeightsAreAtomicForLiveClocks() {
        frame(0,0);frame(8,1);var before=snapshot();long revision=before.revision();
        assertThrows(IllegalArgumentException.class,()->frame(20,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->frame(20,1,List.of(new AnimationV2Command(A,IDLE,8,0,1)),new Object(),new Object()));
        var weights=new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(B,B),0F));
        assertThrows(IllegalArgumentException.class,()->runtime.extractBlendSpace(input(42,20),LAYERS,List.of(),weights,SPACE,1,source,owner,null,null));
        var wrong=new ArrayList<>(LAYERS);wrong.set(0,new ModelAnimationLayers.Layer(A,2,AnimationV2LayerMode.OVERRIDE,1,List.of(),IDLE));
        assertThrows(IllegalArgumentException.class,()->runtime.extractBlendSpace(input(42,20),wrong,List.of(),AnimationV2LayerWeights.empty(),SPACE,1,source,owner,null,null));
        assertSame(before,snapshot());assertEquals(revision,snapshot().revision());
        frame(10,1);phase(snapshot(),.25,1);
    }

    @Test void cueConflictRejectedBeforeItsFirstCaptureAndScopeAlwaysUnwinds() {
        var cueSource=new Object();
        assertThrows(IllegalArgumentException.class,()->runtime.captureBlendSpaceCommands(SPACE,()->runtime.captureEntityLayerCues(cueSource,owner,42,MODEL,1,100,List.of(new BlendEntityLayerCue(A,IDLE,0,0,1)))));
        var captured=runtime.captureEntityLayerCues(cueSource,owner,42,MODEL,1,20,List.of(new BlendEntityLayerCue(A,IDLE,0,0,1)));
        assertEquals(.5,captured.getFirst().requestedPlayheadSeconds(),1e-9,"failed preflight must not freeze old elapsed time");
        assertThrows(IllegalStateException.class,()->runtime.captureBlendSpaceCommands(SPACE,()->runtime.captureBlendSpaceCommands(SPACE,List::of)));
        assertTrue(runtime.captureBlendSpaceCommands(SPACE,List::of).isEmpty());
    }

    @Test void ownerAndSourceIdentityReplacementResetOnlyMembersAndOtherEntitiesAreIndependent() {
        var upper=new AnimationV2Command(UPPER,IDLE,9,.1,1);frame(0,0,List.of(upper),source,owner);frame(10,1);
        runtime.extractBlendSpace(input(43,10),LAYERS,List.of(),AnimationV2LayerWeights.empty(),SPACE,2,source,owner,null,null).orElseThrow();
        phase(runtime.layeredSnapshot(runtime.entityKey(43)).orElseThrow(),0,1);
        frame(12,1,List.of(),new Object(),owner);phase(snapshot(),0,1);assertEquals(9,snapshot().playheads().get(UPPER).acceptedSequence());
        assertEquals(.4,snapshot().playheads().get(UPPER).timeSeconds(),1e-9);
        frame(14,1,List.of(),source,new Object());phase(snapshot(),0,1);
    }

    @Test void unloadRetireDisconnectPlayInitAndNonspaceExtractionRestartActivation() {
        for(String mutation:List.of("unload","retire","disconnect","play_init","plain")) {
            frame(100,0);frame(110,1);
            switch(mutation) {
                case "unload" -> runtime.onEntityUnload(42);
                case "retire" -> runtime.retire(runtime.entityKey(42));
                case "disconnect" -> {runtime.onWorldDisconnect();runtime.onPlayInit();}
                case "play_init" -> runtime.onPlayInit();
                case "plain" -> runtime.extractLayered(input(42,110),LAYERS,List.of(),null);
            }
            frame(112,1);phase(snapshot(),0,1);runtime.onEntityUnload(42);
        }
    }

    @Test void oversizedOrPriorDeferredBatchesCannotPartiallyInitializeOrMutateLiveClocks() {
        var many=new ArrayList<AnimationV2Command>();
        for(int i=0;i<127;i++)many.add(new AnimationV2Command(UPPER,IDLE,i,0,1));
        assertThrows(IllegalArgumentException.class,()->frame(0,1,many,source,owner));
        assertEquals(0,runtime.trackedClockCount());assertEquals(0,lifecycle.registry().size());
        frame(0,1);var before=snapshot();
        for(int i=127;i<129;i++)many.add(new AnimationV2Command(UPPER,IDLE,i,0,1));
        assertThrows(IllegalArgumentException.class,()->frame(10,1,many,source,owner));assertSame(before,snapshot());
        runtime.onEntityUnload(42);
        runtime.extractLayered(input(42,0),LAYERS,many,null).orElseThrow();before=snapshot();
        assertThrows(IllegalStateException.class,()->frame(10,1));assertSame(before,snapshot());
        runtime.extractLayered(input(42,0),LAYERS,List.of(),null).orElseThrow();
        frame(10,1);phase(snapshot(),0,1);
    }

    @Test void takeoverAfterRejectedMemberSequenceUsesSubmittedWatermarkAndDoesNotPoisonGroup() {
        var bad=new AnimationV2Command(A,com.liy.blendlib.api.BlendAnimationKey.parse("space:missing"),99,0,1);
        runtime.extractLayered(input(42,0),LAYERS,List.of(bad),null).orElseThrow();
        assertEquals(-1,snapshot().playheads().get(A).acceptedSequence());
        frame(10,1);assertEquals(100,snapshot().playheads().get(A).acceptedSequence());
        frame(20,1);phase(snapshot(),.25,1);
    }

    @Test void replacementDuringRollbackUsesRetainedMonotonicOriginAcrossSameTickReload() {
        frame(10,0);frame(20,1);var replacement=new Object();
        frame(12,1,List.of(),source,replacement);phase(snapshot(),0,1);
        frame(22,1,List.of(),source,replacement);phase(snapshot(),.05,1);
        models.publish(generation(2));frame(22,1,List.of(),source,replacement);phase(snapshot(),.05,1);
    }

    @Test void lateCompoundConflictRollsBackNonmemberCueCaptureWithoutResurrectingLifecycle() {
        var cueSource=new Object();
        assertThrows(IllegalArgumentException.class,()->runtime.captureBlendSpaceCommands(SPACE,()->{
            var combined=new ArrayList<>(runtime.captureEntityLayerCues(cueSource,owner,42,MODEL,1,100,List.of(new BlendEntityLayerCue(UPPER,IDLE,0,0,1))));
            combined.add(new AnimationV2Command(A,IDLE,0,0,1));return combined;
        }));
        var fresh=runtime.captureEntityLayerCues(cueSource,owner,42,MODEL,1,20,List.of(new BlendEntityLayerCue(UPPER,IDLE,0,0,1)));
        assertEquals(.5,fresh.getFirst().requestedPlayheadSeconds(),1e-9);
        runtime.captureBlendSpaceCommands(SPACE,()->{runtime.onEntityUnload(42);return List.of();});
        var after=runtime.captureEntityLayerCues(cueSource,owner,42,MODEL,1,40,List.of(new BlendEntityLayerCue(UPPER,IDLE,0,0,1)));
        assertEquals(1,after.getFirst().requestedPlayheadSeconds(),1e-9);
    }

    @Test void nonmemberLocomotionConflictOrCapacityFailureCannotRetireExistingPlayback() {
        var loaded=(LoadedModelHandle)generation(2).find(MODEL).orElseThrow();
        var rules=com.liy.blendlib.core.animation.rules.LocomotionRuleParser.parse(
                "{\"schema_version\":1,\"default\":\"space:idle\",\"rules\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),loaded.asset().animationDefinition());
        models.publish(new ModelRegistryGeneration(2,Map.of(MODEL,loaded),Map.of(),List.of(),Map.of(MODEL,rules)));
        frame(0,1);frame(10,1);var before=snapshot();
        var bad=new AnimationV2Command(A,IDLE,9,0,1);
        var many=new ArrayList<AnimationV2Command>();
        for(int i=0;i<125;i++)many.add(new AnimationV2Command(id("unknown"),IDLE,i,0,1));
        for(var commands:List.of(List.of(bad),many)) {
            assertThrows(IllegalArgumentException.class,()->runtime.captureBlendSpaceCommands(SPACE,
                    ()->runtime.captureEntityLocomotionRules(new Object(),owner,42,MODEL,2,20,UPPER,
                            ()->new com.liy.blendlib.core.animation.rules.LocomotionInputs(Map.of(),Map.of()),()->commands)));
            assertSame(before,snapshot());assertEquals(1,lifecycle.registry().size());
        }
    }

    @Test void copyOnWriteCueStagingRetiresUntouchedOwnersOnlyOnCommitAndKeepsOtherInstances() {
        var cache=new EntityLayerCueCache();var oldOwner=new Object();var otherOwner=new Object();var newOwner=new Object();
        var key=runtime.entityKey(42);var other=runtime.entityKey(43);
        var cues=List.of(new BlendEntityLayerCue(UPPER,IDLE,1,0,1));
        cache.capture(source,oldOwner,key,MODEL,1,20,cues);
        var independent=cache.capture(source,otherOwner,other,MODEL,1,20,cues).getFirst();
        var staged=cache.copy();var replacement=staged.capture(source,newOwner,key,MODEL,1,40,cues);
        staged.retireExceptCaptured(key,newOwner,replacement);
        assertEquals(2,cache.ownerCount(),"uncommitted cleanup leaves original ownership intact");
        assertSame(cache,staged.commit());assertEquals(2,cache.ownerCount());
        assertSame(independent,cache.capture(source,otherOwner,other,MODEL,1,100,cues).getFirst());
        assertEquals(3,cache.capture(source,oldOwner,key,MODEL,1,60,cues).getFirst().requestedPlayheadSeconds(),1e-9,"retired old owner must recapture");
    }

    void frame(long tick,double parameter) { frame(tick,parameter,List.of(),source,owner); }
    void frame(long tick,double parameter,List<AnimationV2Command> commands,Object source,Object owner) {
        runtime.extractBlendSpace(input(42,tick),LAYERS,commands,new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(UPPER,UPPER),.5F)),SPACE,parameter,source,owner,null,events::add).orElseThrow();
    }
    AnimationV2EvaluationSnapshot snapshot() {return runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();}
    static void phase(AnimationV2EvaluationSnapshot snapshot,double phase,double scale) {
        int i=0;for(var id:List.of(A,B,C)) {double actual=snapshot.playheads().get(id).timeSeconds()/(new double[]{2,1,.5}[i++]*scale);assertTrue(Math.abs(actual-phase)<1e-8||Math.abs(actual-phase-1)<1e-8||Math.abs(actual-phase+1)<1e-8,"normalized phase "+actual+" != "+phase);}
    }
    SkinnedAnimationRuntimeInput input(int id,long tick) {return new SkinnedAnimationRuntimeInput(MODEL,runtime.entityKey(id),tick,0,IDLE,Optional.empty(),AnimationUpdateBucket.VISIBLE_NEAR,new SkinnedExtractionRequest(Transform.IDENTITY,0,0,-1,RenderVisibility.VISIBLE,new CullingMetadata(models.find(MODEL).orElseThrow().renderHandle().bounds(),true)));}
}
