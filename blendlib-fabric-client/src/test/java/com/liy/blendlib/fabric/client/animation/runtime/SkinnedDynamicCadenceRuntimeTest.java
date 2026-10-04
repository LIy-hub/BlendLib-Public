package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.animation.runtime.BlendSpaceTestAssets.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared scheduler contract exercised through BOTH public solver entrypoints. */
class SkinnedDynamicCadenceRuntimeTest {
    static final BlendResourceId D=id("d");
    static final AnimationBlendSpace2D SPACE2=new AnimationBlendSpace2D(A,List.of(
            new AnimationBlendSpace2D.Direction(0,B),new AnimationBlendSpace2D.Direction(2*Math.PI/3,C),
            new AnimationBlendSpace2D.Direction(4*Math.PI/3,D)),1,2);
    static final List<ModelAnimationLayers.Layer> LAYERS2=java.util.stream.Stream.concat(LAYERS.stream(),
            java.util.stream.Stream.of(layer(D,RUN))).toList();
    final ClientModelRegistry models=new ClientModelRegistry();
    final ClientAnimationLifecycleBridge lifecycle=new ClientAnimationLifecycleBridge(16);
    final SkinnedAnimationRuntime runtime=new SkinnedAnimationRuntime(models,lifecycle);
    final Object source=new Object(),owner=new Object();
    final List<LayerAnimationVisualEvent> events=new ArrayList<>();
    SkinnedDynamicCadenceRuntimeTest(){runtime.onPlayInit();models.publish(generation(1));}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldCommittedRateIntegratesEachIntervalAndSequencesStayStable(boolean two) {
        frame(two,0,1);frame(two,10,2);phase(two,.25,1);
        frame(two,20,.5);phase(two,.75,1);frame(two,30,1);phase(two,.875,1);
        for(var id:members(two))assertEquals(0,snapshot().playheads().get(id).acceptedSequence());
        assertEquals(.75,snapshot().playheads().get(UPPER).timeSeconds(),1e-9);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void duplicateAndRollbackCapturesAreLastValidWinsAtTheHighWaterBoundary(boolean two) {
        frame(two,10,1);frame(two,20,2);events.clear();
        frame(two,20,.5);frame(two,12,3);phase(two,.25,1);assertTrue(events.isEmpty());
        frame(two,22,1);phase(two,.4,1);
        models.publish(generation(2));frame(two,21,2);phase(two,.4,1);events.clear();
        frame(two,24,1);phase(two,.5,1);assertTrue(events.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void reloadRebindsChangedDurationsAndDescriptorSpeedsAtIntegratedPhaseWithoutBackfill(boolean two) {
        frame(two,0,1);frame(two,10,2);frame(two,20,.5);events.clear();
        models.publish(generation(2,2,true,2));frame(two,30,2);phase(two,.875,2);assertTrue(events.isEmpty());
        frame(two,35,1);phase(two,.125,2);
        frame(two,40,1);phase(two,.25,2);assertTrue(events.stream().anyMatch(e->e.controllerId().equals(B)));
        events.clear();models.publish(generation(3,2,true,.5));frame(two,40,.5);phase(two,.25,2);assertTrue(events.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void longGapUsesPiecewiseHeldCadenceAndRecoversSilentlyThenResumes(boolean two) {
        frame(two,0,1);frame(two,10,2);frame(two,20,.5);events.clear();
        frame(two,13030,2);phase(two,.375,1);assertTrue(events.isEmpty());
        assertEquals(1,snapshot().playheads().get(A).acceptedSequence());
        frame(two,13050,1);phase(two,.375,1);assertFalse(events.isEmpty());
        assertEquals(1,snapshot().playheads().get(A).acceptedSequence());
        assertTrue(events.size()<=com.liy.blendlib.core.limits.BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void invalidCadencesAndDerivedRatesPreserveExactSnapshotAndHeldCadence(boolean two) {
        frame(two,0,1);frame(two,10,2);var before=snapshot();
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,0,-1,1.0/128,65,64}) {
            assertThrows(IllegalArgumentException.class,()->frame(two,30,bad));assertSame(before,snapshot());
        }
        frame(two,20,.5);phase(two,.75,1);models.publish(generation(2));frame(two,30,1);phase(two,.875,1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void invalidFirstCaptureCreatesNoLiveActivation(boolean two) {
        assertThrows(IllegalArgumentException.class,()->frame(two,0,64));
        assertEquals(0,runtime.trackedClockCount());assertEquals(0,lifecycle.registry().size());
        frame(two,100,2);phase(two,0,1);frame(two,110,1);phase(two,.5,1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void zeroWeightMembersConsumeMarkersAndRetainPhaseAcrossRateChanges(boolean two) {
        frame(two,0,0,1,source,owner,List.of(),null,events::add);
        frame(two,10,0,2,source,owner,List.of(),null,events::add);
        assertTrue(events.stream().noneMatch(e->e.controllerId().equals(B)||e.controllerId().equals(C)||e.controllerId().equals(D)));
        events.clear();frame(two,10,1,2,source,owner,List.of(),null,events::add);assertTrue(events.isEmpty());
        frame(two,30,.5);phase(two,.25,1);
        assertTrue(events.stream().anyMatch(e->e.controllerId().equals(B)&&e.event().eventKey().equals(id("step"))));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void replacementResetsMembersButPreservesNonmemberClockAndWatermark(boolean two) {
        frame(two,0,1,1,source,owner,List.of(new AnimationV2Command(UPPER,IDLE,7,.1,1)),null,events::add);
        frame(two,10,2);var nextOwner=new Object();
        frame(two,12,1,.5,source,nextOwner,List.of(),null,events::add);phase(two,0,1);
        assertEquals(7,snapshot().playheads().get(UPPER).acceptedSequence());assertEquals(.4,snapshot().playheads().get(UPPER).timeSeconds(),1e-9);
        frame(two,22,1,2,source,nextOwner,List.of(),null,events::add);phase(two,.125,1);
        frame(two,24,1,1,new Object(),nextOwner,List.of(),null,events::add);phase(two,0,1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lifecycleResetModesDropCadenceAndIndependentInstancesRemainSeparate(boolean two) {
        for(String reset:List.of("unload","retire","disconnect","play_init","plain")) {
            frame(two,0,1);frame(two,10,2);
            runtime.extractBlendSpace(input(43,10),LAYERS,List.of(),AnimationV2LayerWeights.empty(),SPACE,1,.5,source,owner,null,null).orElseThrow();
            assertEquals(0,runtime.layeredSnapshot(runtime.entityKey(43)).orElseThrow().playheads().get(A).timeSeconds());
            switch(reset){case "unload"->runtime.onEntityUnload(42);case "retire"->runtime.retire(runtime.entityKey(42));case "disconnect"->{runtime.onWorldDisconnect();runtime.onPlayInit();}case "play_init"->runtime.onPlayInit();case "plain"->runtime.extractLayered(input(42,10),layers(two),List.of(),null);}
            frame(two,12,.5);phase(two,0,1);frame(two,22,1);phase(two,.125,1);runtime.onPlayInit();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void postPublicationPoseAndEventFailureKeepCommittedRateAndConsumedEvents(boolean two) {
        frame(two,0,1);
        assertThrows(IllegalStateException.class,()->frame(two,10,1,2,source,owner,List.of(),(context,pose)->{throw new IllegalStateException("pose");},events::add));
        phase(two,.25,1);events.clear();frame(two,10,.5);assertTrue(events.isEmpty());
        frame(two,20,1);phase(two,.375,1);
        assertThrows(IllegalStateException.class,()->frame(two,55,1,2,source,owner,List.of(),null,event->{throw new IllegalStateException("listener");}));
        phase(two,.25,1);events.clear();frame(two,55,2);assertTrue(events.isEmpty());frame(two,60,1);phase(two,.5,1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void liveClockKeepsItsExactPlanAfterBothPreparationCachesEvictItsEntry(boolean two) {
        frame(two,0,1);frame(two,10,2);var before=snapshot();
        for(int i=1;i<=70;i++) {
            var otherLayers=new ArrayList<>(layers(two));
            int index=otherLayers.indexOf(otherLayers.stream().filter(layer->layer.id().equals(UPPER)).findFirst().orElseThrow());
            otherLayers.set(index,new ModelAnimationLayers.Layer(UPPER,1,AnimationV2LayerMode.ADDITIVE,i/100F,List.of(),IDLE));
            assertTrue(runtime.validateBlendSpaceCadence(MODEL,1,otherLayers,two?SPACE2.syncGroup():SPACE.syncGroup(),1));
        }
        assertSame(before,snapshot());frame(two,20,.5);phase(two,.75,1);
        assertEquals(0,snapshot().playheads().get(A).acceptedSequence());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void prospectiveCoreCounterFailureDoesNotCommitGroupPhaseOrNewRate(boolean two) throws Exception {
        frame(two,0,1);frame(two,10,2);var before=snapshot();
        var clocksField=SkinnedAnimationRuntime.class.getDeclaredField("clocks");clocksField.setAccessible(true);
        var clock=((Map<?,?>)clocksField.get(runtime)).get(runtime.entityKey(42));
        var layeredField=clock.getClass().getDeclaredField("layered");layeredField.setAccessible(true);var layered=layeredField.get(clock);
        var coreField=layered.getClass().getDeclaredField("runtime");coreField.setAccessible(true);var core=coreField.get(layered);
        var revision=AnimationV2InstanceRuntime.class.getDeclaredField("revision");revision.setAccessible(true);long saved=revision.getLong(core);revision.setLong(core,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->frame(two,20,.5));assertSame(before,snapshot());
        revision.setLong(core,saved);frame(two,20,1);phase(two,.75,1);
        models.publish(generation(2));frame(two,30,1);phase(two,0,1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void hugeIntervalsKeepPhaseFiniteWithoutUnboundedCycleHistory(boolean two) {
        frame(two,0,1);frame(two,10,2);
        // Generation replacement gives the independent legacy base controller a fresh clock;
        // its existing finite loop-work budget is intentionally not changed by this feature.
        models.publish(generation(2));frame(two,Long.MAX_VALUE,1);var snapshot=snapshot();
        double phase=snapshot.playheads().get(A).timeSeconds()/2;
        assertTrue(Double.isFinite(phase)&&phase>=0&&phase<1);phase(two,phase,1);
        events.clear();frame(two,Long.MAX_VALUE,.5);phase(two,phase,1);assertTrue(events.isEmpty());
    }

    void frame(boolean two,long tick,double cadence){frame(two,tick,1,cadence,source,owner,List.of(),null,events::add);}
    void frame(boolean two,long tick,double parameter,double cadence,Object source,Object owner,List<AnimationV2Command> commands,ClientAnimationPoseModifier modifier,Consumer<LayerAnimationVisualEvent> listener){
        if(two)runtime.extractBlendSpace2D(input(42,tick),layers(true),commands,AnimationV2LayerWeights.empty(),SPACE2,new AnimationBlendSpace2D.Input(parameter,0),cadence,source,owner,modifier,listener).orElseThrow();
        else runtime.extractBlendSpace(input(42,tick),LAYERS,commands,AnimationV2LayerWeights.empty(),SPACE,parameter,cadence,source,owner,modifier,listener).orElseThrow();
    }
    static List<ModelAnimationLayers.Layer> layers(boolean two){return two?LAYERS2:LAYERS;}
    static Set<BlendResourceId> members(boolean two){return two?SPACE2.memberLayerIds():SPACE.memberLayerIds();}
    AnimationV2EvaluationSnapshot snapshot(){return runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();}
    void phase(boolean two,double expected,double scale){for(var id:members(two)){double duration=id.equals(A)?2:id.equals(B)?1:.5;double actual=snapshot().playheads().get(id).timeSeconds()/(duration*scale);assertTrue(Math.min(Math.abs(actual-expected),1-Math.abs(actual-expected))<1e-8,id+" phase "+actual+" != "+expected);}}
    SkinnedAnimationRuntimeInput input(int id,long tick){return new SkinnedAnimationRuntimeInput(MODEL,runtime.entityKey(id),tick,0,IDLE,Optional.empty(),AnimationUpdateBucket.VISIBLE_NEAR,new SkinnedExtractionRequest(Transform.IDENTITY,0,0,-1,RenderVisibility.VISIBLE,new CullingMetadata(models.find(MODEL).orElseThrow().renderHandle().bounds(),true)));}
}
