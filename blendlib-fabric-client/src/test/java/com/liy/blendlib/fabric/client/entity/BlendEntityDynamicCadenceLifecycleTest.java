package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;
import static com.liy.blendlib.fabric.client.entity.BlendEntityLocomotionContractTest.allocate;
import static com.liy.blendlib.fabric.client.animation.runtime.BlendSpaceTestAssets.*;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.reload.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Temporarily installs a CPU-only client service facade and restores it")
class BlendEntityDynamicCadenceLifecycleTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    @Test void bothSolversCaptureParameterThenCadenceExactlyOnceBeforeWeightsAndCommands() throws Exception {
        for(boolean two:List.of(false,true))withServices((models,lifecycle,runtime)->{
            var entity=entity();var order=new ArrayList<String>();
            var factory=factory(two,(e,r)->{order.add("parameter");return 1;},(e,r)->{order.add("cadence");return r.clientGameTick()==0?1:2;},(e,r)->{order.add("weights");return AnimationV2LayerWeights.empty();},(e,r)->{order.add("commands");return List.of();});
            for(long tick:new long[]{0,10,10}){assertFalse(factory.create(entity,request(tick)).handle().missingModel());assertEquals(List.of("parameter","cadence","weights","commands"),order);order.clear();}
            assertEquals(.5,runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(A).timeSeconds(),1e-9);
        });
    }
    @Test void invalidOrThrowingCadenceRunsNeitherExternalWeightsNorCueCaptureAndPreservesSnapshot() throws Exception {
        for(boolean two:List.of(false,true))withServices((models,lifecycle,runtime)->{
            var entity=entity();double[] rate={1};int[] weights={0},commands={0};
            var cueSource=new Object();
            var factory=factory(two,(e,r)->1,(e,r)->{if(rate[0]==-999)throw new IllegalStateException("cadence callback");return rate[0];},(e,r)->{weights[0]++;return AnimationV2LayerWeights.empty();},(e,r)->{commands[0]++;return runtime.captureEntityLayerCues(cueSource,e,42,MODEL,1,r.clientGameTick(),List.of(new BlendEntityLayerCue(UPPER,IDLE,0,0,1)));});
            for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,65,64,-999}){rate[0]=invalid;assertThrows(RuntimeException.class,()->factory.create(entity,request(100)));}
            assertEquals(0,weights[0]);assertEquals(0,commands[0]);assertEquals(0,lifecycle.registry().size());
            rate[0]=1;factory.create(entity,request(20));var before=runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertEquals(.5,before.playheads().get(UPPER).timeSeconds(),1e-9,"invalid capture did not freeze a cue at tick100");
            rate[0]=64;assertThrows(IllegalArgumentException.class,()->factory.create(entity,request(100)));assertSame(before,runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow());
            assertEquals(1,weights[0]);assertEquals(1,commands[0]);
            rate[0]=2;factory.create(entity,request(30));assertEquals(.5,runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(A).timeSeconds(),1e-9);
        });
    }
    @Test void lifecycleMutationInParameterOrCadenceAbortsBeforeSubsequentCallbacksAndNeverResurrects() throws Exception {
        for(boolean two:List.of(false,true))for(String stage:List.of("parameter","cadence"))for(String mutation:List.of("unload","retire","disconnect","play_init","reload"))withServices((models,lifecycle,runtime)->{
            var entity=entity();var key=runtime.entityKey(42);int[] cadenceCalls={0},weights={0},commands={0};
            Runnable mutate=()->{switch(mutation){case "unload"->runtime.onEntityUnload(42);case "retire"->runtime.retire(key);case "disconnect"->runtime.onWorldDisconnect();case "play_init"->runtime.onPlayInit();case "reload"->models.publish(generation(2));}};
            var factory=factory(two,(e,r)->{if(stage.equals("parameter"))mutate.run();return 1;},(e,r)->{cadenceCalls[0]++;if(stage.equals("cadence"))mutate.run();return 2;},(e,r)->{weights[0]++;return AnimationV2LayerWeights.empty();},(e,r)->{commands[0]++;return List.of();});
            assertTrue(factory.create(entity,request(0)).handle().missingModel(),two+"/"+stage+"/"+mutation);
            assertEquals(stage.equals("cadence")?1:0,cadenceCalls[0]);assertEquals(0,weights[0]);assertEquals(0,commands[0]);assertEquals(0,lifecycle.registry().size());assertTrue(runtime.layeredSnapshot(key).isEmpty());
            runtime.onPlayInit();assertFalse(factory(two,(e,r)->1,(e,r)->.5,null,(e,r)->List.of()).create(entity,request(20)).handle().missingModel());
        });
    }
    private static SkinnedAnimationEntitySnapshotFactory<Entity> factory(boolean two,BlendEntityBlendSpaceParameter<Entity> parameter,BlendEntityBlendSpaceCadence<Entity> cadence,BlendEntityLayerWeights<Entity> weights,BlendEntityLayerCommands<Entity> commands){
        var space=two?BlendEntityDirectionalBlendSpaceLifecycleTest.SPACE2.syncGroup():SPACE.syncGroup();
        var layers=two?BlendEntityDirectionalBlendSpaceLifecycleTest.LAYERS2:LAYERS;
        return new SkinnedAnimationEntitySnapshotFactory<>(MODEL,(e,r)->IDLE,null,null,null,(e,r)->BlendEntityRotation.IDENTITY,null,layers,commands,null,null,null,weights,null,space,
                (e,r)->two?BlendEntityDirectionalBlendSpaceLifecycleTest.SPACE2.weights(parameter.parameter(e,r),0):SPACE.weights(parameter.parameter(e,r)),cadence);
    }
    private static Entity entity(){Entity entity=allocate(Marker.class);entity.setId(42);return entity;}
    private static BlendEntitySnapshotRequest request(long tick){return new BlendEntitySnapshotRequest(MODEL,0,0,tick,0,0,0,tick,true,0);}
    @SuppressWarnings("unchecked") private static void withServices(CheckedTest test)throws Exception{
        Field field=BlendLibClientServices.class.getDeclaredField("ACTIVE");field.setAccessible(true);AtomicReference<Object> active=(AtomicReference<Object>)field.get(null);Object previous=active.getAndSet(null);
        try{var models=new ClientModelRegistry();var lifecycle=new ClientAnimationLifecycleBridge(16);var runtime=new SkinnedAnimationRuntime(models,lifecycle);runtime.onPlayInit();models.publish(generation(1));BlendLibClientServices.initialize(models,(s,c)->{throw new AssertionError("unexpected submit");},runtime);test.run(models,lifecycle,runtime);}finally{active.set(previous);}
    }
    @FunctionalInterface private interface CheckedTest{void run(ClientModelRegistry models,ClientAnimationLifecycleBridge lifecycle,SkinnedAnimationRuntime runtime)throws Exception;}
}
