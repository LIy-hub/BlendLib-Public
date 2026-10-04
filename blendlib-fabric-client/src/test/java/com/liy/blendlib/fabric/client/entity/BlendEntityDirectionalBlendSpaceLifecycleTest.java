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
class BlendEntityDirectionalBlendSpaceLifecycleTest {
    static final AnimationBlendSpace2D SPACE2=new AnimationBlendSpace2D(A,List.of(new AnimationBlendSpace2D.Direction(0,B),
            new AnimationBlendSpace2D.Direction(2*Math.PI/3,C),new AnimationBlendSpace2D.Direction(4*Math.PI/3,id("direction_d"))),1,2);
    static final List<ModelAnimationLayers.Layer> LAYERS2=java.util.stream.Stream.concat(LAYERS.stream(),java.util.stream.Stream.of(
            new ModelAnimationLayers.Layer(id("direction_d"),0,AnimationV2LayerMode.OVERRIDE,1,List.of(),RUN))).toList();
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void eachFrameCapturesParameterWeightsAndCommandsExactlyOnceInPreflightOrder() throws Exception {
        withServices((models,lifecycle,runtime)->{
            models.publish(generation(1));var entity=entity();var order=new ArrayList<String>();
            var factory=factory((e,r)->{order.add("parameter");return 1;},(e,r)->{order.add("weights");return AnimationV2LayerWeights.empty();},(e,r)->{order.add("commands");return List.of();});
            assertFalse(factory.create(entity,request(0)).handle().missingModel());
            assertEquals(List.of("parameter","weights","commands"),order);order.clear();
            factory.create(entity,request(10));assertEquals(List.of("parameter","weights","commands"),order);
            var snapshot=runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertEquals(.25,snapshot.playheads().get(C).timeSeconds()/.5,1e-8);
        });
    }

    @Test void lifecycleMutationInParameterWeightsOrCommandsAbandonsFrameWithoutRebinding() throws Exception {
        for(String stage:List.of("parameter","weights","commands")) for(String mutation:List.of("unload","retire","disconnect","play_init","reload")) {
            withServices((models,lifecycle,runtime)->{
                models.publish(generation(1));var entity=entity();var key=runtime.entityKey(42);int[] calls={0};
                Runnable mutate=()->{
                    calls[0]++;
                    switch(mutation) {case "unload"->runtime.onEntityUnload(42);case "retire"->runtime.retire(key);case "disconnect"->runtime.onWorldDisconnect();case "play_init"->runtime.onPlayInit();case "reload"->models.publish(generation(2));}
                };
                var factory=factory((e,r)->{if(stage.equals("parameter"))mutate.run();return 1;},(e,r)->{if(stage.equals("weights"))mutate.run();return AnimationV2LayerWeights.empty();},(e,r)->{if(stage.equals("commands"))mutate.run();return List.of();});
                assertTrue(factory.create(entity,request(0)).handle().missingModel(),stage+"/"+mutation);
                assertEquals(1,calls[0]);assertEquals(0,lifecycle.registry().size());assertTrue(runtime.layeredSnapshot(key).isEmpty());
                runtime.onPlayInit();assertFalse(factory((e,r)->1,null,(e,r)->List.of()).create(entity,request(2)).handle().missingModel());
            });
        }
    }

    @Test void invalidParameterWeightAndGenerationBindingRejectBeforeCommandSourceRuns() throws Exception {
        withServices((models,lifecycle,runtime)->{
            models.publish(generation(1));var entity=entity();int[] commands={0},parameters={0};
            BlendEntityLayerCommands<Entity> command=(e,r)->{commands[0]++;return List.of();};
            assertThrows(IllegalArgumentException.class,()->factory((e,r)->Double.NaN,null,command).create(entity,request(0)));
            assertThrows(IllegalArgumentException.class,()->factory((e,r)->1,(e,r)->new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(A,A),0F)),command).create(entity,request(0)));
            models.publish(generation(2,1,false));
            assertThrows(IllegalArgumentException.class,()->factory((e,r)->{parameters[0]++;return 1;},null,command).create(entity,request(0)));
            assertEquals(0,commands[0]);assertEquals(0,parameters[0]);assertEquals(0,lifecycle.registry().size());
        });
    }

    @Test void twoEqualIdsWithDifferentOwnerObjectsActivateFreshGroupAndPreserveUpperSequence() throws Exception {
        withServices((models,lifecycle,runtime)->{
            models.publish(generation(1));var first=entity();var second=entity();
            var cue=new AnimationV2Command(UPPER,IDLE,7,.1,1);
            var factory=factory((e,r)->1,null,(e,r)->r.clientGameTick()==0?List.of(cue):List.of());
            factory.create(first,request(0));factory.create(first,request(10));factory.create(second,request(12));
            var snapshot=runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow();
            assertEquals(0,snapshot.playheads().get(A).timeSeconds(),1e-8);
            assertEquals(7,snapshot.playheads().get(UPPER).acceptedSequence());
        });
    }

    private static SkinnedAnimationEntitySnapshotFactory<Entity> factory(BlendEntityBlendSpaceParameter<Entity> parameter,BlendEntityLayerWeights<Entity> weights,BlendEntityLayerCommands<Entity> commands) {
        return new SkinnedAnimationEntitySnapshotFactory<>(MODEL,(e,r)->IDLE,null,null,null,(e,r)->BlendEntityRotation.IDENTITY,null,LAYERS2,commands,null,null,null,weights,null,SPACE2.syncGroup(),
                (entity, request)->SPACE2.weights(parameter.parameter(entity,request),0));
    }
    private static Entity entity() {Entity entity=allocate(Marker.class);entity.setId(42);return entity;}
    private static BlendEntitySnapshotRequest request(long tick) {return new BlendEntitySnapshotRequest(MODEL,0,0,tick,0,0,0,tick,true,0);}
    @SuppressWarnings("unchecked") private static void withServices(CheckedTest test) throws Exception {
        Field field=BlendLibClientServices.class.getDeclaredField("ACTIVE");field.setAccessible(true);
        AtomicReference<Object> active=(AtomicReference<Object>)field.get(null);Object previous=active.getAndSet(null);
        try {var models=new ClientModelRegistry();var lifecycle=new ClientAnimationLifecycleBridge(16);var runtime=new SkinnedAnimationRuntime(models,lifecycle);runtime.onPlayInit();BlendLibClientServices.initialize(models,(s,c)->{throw new AssertionError("unexpected submit");},runtime);test.run(models,lifecycle,runtime);} finally {active.set(previous);}
    }
    @FunctionalInterface private interface CheckedTest {void run(ClientModelRegistry models,ClientAnimationLifecycleBridge lifecycle,SkinnedAnimationRuntime runtime)throws Exception;}
}
