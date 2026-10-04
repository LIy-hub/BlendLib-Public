package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.examples.runnable.*;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.api.*;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.render.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Actual packaged unequal-duration GLBs, production reload, layered CPU poses and retained owners. */
public final class RunnableDirectionalBlendSpaceVerification {
    private static final String NS = "blendlib_runnable_examples";
    private static final BlendAnimationKey IDLE = animation("idle"), ATTACK = animation("attack");
    private static final List<BlendResourceId> MEMBERS = List.of(
            ExampleDirectionalScene.IDLE, ExampleDirectionalScene.FORWARD, ExampleDirectionalScene.LEFT, ExampleDirectionalScene.BACK, ExampleDirectionalScene.RIGHT);
    private RunnableDirectionalBlendSpaceVerification() { }

    public static void verify() {
        verifyMotion();
        var h=new Harness(); var resources=new PackagedResources(false); h.reload(resources);
        var asset=((LoadedModelHandle)h.models.find(ExampleDirectionalScene.MODEL).orElseThrow()).asset();
        require(asset.clips().stream().map(c->c.name()).toList().containsAll(List.of("idle","forward","left","back","right","attack")),"real packaged directional clips and attack");
        for(var layer:ExampleDirectionalScene.layers().subList(0,5)) {
            var state=asset.animationDefinition().states().get(layer.initialState().resourceId());
            require(state.loop()&&state.speed()!=1&&state.events().isEmpty(),"phase-authored unequal loops; no automatic footstep deduplication claim");
            require(near(h.runtime.animationDuration(ExampleDirectionalScene.MODEL,layer.initialState()).orElseThrow(),ExampleDirectionalScene.packagedDurations().get(layer.id())),"real unequal clip durations");
        }
        double[][] inputs={{0,0},{.14,0},{0,.14},{-.14,0},{0,-.14},{.035,.035},{-.035,.035},{-.035,-.035},{.035,-.035},{1,1}};
        double[][] expected={{1,0,0,0,0},{0,1,0,0,0},{0,0,1,0,0},{0,0,0,1,0},{0,0,0,0,1},
                {.5,.25,.25,0,0},{.5,0,.25,.25,0},{.5,0,0,.25,.25},{.5,.25,0,0,.25},{0,.5,.5,0,0}};
        var samples=new ArrayList<Sample>();int reads=resources.reads;
        for(int i=0;i<inputs.length;i++) {
            Actor actor=new Actor(500+i);h.sample(actor,100,0,0);var sample=h.sample(actor,104,inputs[i][0],inputs[i][1]);
            phase(sample,.25);weights(sample,expected[i]);samples.add(sample);
            verifyIndependentPoseAndVertices(h,sample,asset,104,.25,expected[i],600+i);
        }
        require(!positions(samples.get(1).render).equals(positions(samples.get(2).render)),"forward and strafe have distinct final CPU geometry");
        int root=asset.nodes().stream().filter(n->n.name().equals("ShowcaseRootBone")).findFirst().orElseThrow().index();
        require(near(samples.get(5).layers.pose().transform(root).translation().x(),.5*.005+.25*.025+.25*.045),"center-plus-sector root pose mixes three real clips");
        var actor=new Actor(550);h.sample(actor,100,0,0);actor.cue(1,104);var attacking=h.sample(actor,104,.1,.02);
        require(attacking.upper().state().equals(ATTACK)&&attacking.upper().acceptedSequence()==1,"upper attack preserved");
        long sequence=attacking.layers.playheads().get(MEMBERS.getFirst()).acceptedSequence();
        for(int tick=105;tick<180;tick++) {
            double angle=(tick-105)*Math.PI/12;double speed=tick<130||tick>150?.08:0;
            var sample=h.sample(actor,tick,speed*Math.cos(angle),speed*Math.sin(angle));phase(sample,((tick-100)%16)/16.);
            for(var id:MEMBERS)require(sample.layers.playheads().get(id).acceptedSequence()==sequence,"seams and stop/start never issue member commands");
        }
        // Same tick around every axis, including atan2 wrap, changes only a continuous mixture.
        for(int axis=0;axis<4;axis++) {
            double angle=axis*Math.PI/2;
            var left=h.sample(actor,182,.08*Math.cos(angle-1e-8),.08*Math.sin(angle-1e-8));
            var right=h.sample(actor,182,.08*Math.cos(angle+1e-8),.08*Math.sin(angle+1e-8));
            phase(left,.125);phase(right,.125);compareVertices(positions(left.render),positions(right.render));
            for(int bone=0;bone<left.layers.pose().boneCount();bone++)require(near(left.layers.pose().transform(bone).translation().x(),right.layers.pose().transform(bone).translation().x()),"continuous final pose across quadrant seam");
        }
        require(resources.reads==reads,"runtime directional solving and attachments perform no resource I/O");
        var retained=h.sample(actor,184,.03,-.03);var oldVertices=positions(retained.render);
        h.reload(new PackagedResources(true));var reloaded=h.sample(actor,185,-.03,-.03);phase(reloaded,.3125);
        require(positions(retained.render).equals(oldVertices),"reload retains immutable captured vertices");
        require(reloaded.render.generation()!=retained.render.generation(),"new generation bound");
        phase(h.sample(actor,186,0,0),.375);weights(h.sample(actor,186,0,0),1,0,0,0,0);
        h.runtime.onEntityUnload(actor.id);h.owners.remove(actor.owner,h.runtime::retire);phase(h.sample(actor,190,.1,0),0);
        h.runtime.retire(h.runtime.entityKey(actor.id));phase(h.sample(actor,192,0,.1),0);
        h.owners.clear(h.runtime::retire);h.runtime.onWorldDisconnect();h.runtime.onPlayInit();phase(h.sample(actor,200,-.1,0),0);
        h.runtime.onWorldDisconnect();
        System.out.println("Verified packaged 2D directional blendspace: independently sampled unequal clips, exact center/directions/sectors/outside polygon, final poses and CPU vertices, quadrant seams and idle stop/start, shared phase and stable sequences, upper attack/procedural sockets/nested attachments, changed-rate reload and retirement; native graphics remains unverified");
    }

    private static void verifyIndependentPoseAndVertices(Harness h, Sample actual, com.liy.blendlib.core.model.ModelAsset asset,
            long tick,double phase,double[] expected,int id) {
        var sampler=com.liy.blendlib.core.animation.runtime.PoseSampler.fromModelAsset(asset);
        var definition=com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition.fromModelAsset(asset);
        int root=asset.nodes().stream().filter(n->n.name().equals("ShowcaseRootBone")).findFirst().orElseThrow().index();
        double x=0,y=0,z=0;var commands=new ArrayList<AnimationV2Command>();var weights=new LinkedHashMap<AnimationV2LayerWeights.Key,Float>();
        for(int i=0;i<MEMBERS.size();i++) {
            var layer=ExampleDirectionalScene.layers().get(i);double time=phase*ExampleDirectionalScene.packagedDurations().get(layer.id());
            var pose=sampler.sample(definition.states().get(layer.initialState()),time);
            x+=expected[i]*pose.transform(root).translation().x();y+=expected[i]*pose.transform(root).translation().y();z+=expected[i]*pose.transform(root).translation().z();
            commands.add(new AnimationV2Command(layer.id(),layer.initialState(),1,time,1));
            weights.put(new AnimationV2LayerWeights.Key(layer.id(),layer.id()),(float)expected[i]);
        }
        var translation=actual.layers.pose().transform(root).translation();
        require(near(translation.x(),x)&&near(translation.y(),y)&&near(translation.z(),z),"independently sampled GLB translations match final blend");
        var upper=actual.upper();commands.add(new AnimationV2Command(ExampleAnimationScene.UPPER,upper.state(),1,upper.timeSeconds(),1));
        weights.putAll(ExampleAnimationScene.clipLayerWeights(tick).multipliers());
        // Independent fixed-pose layered oracle has no group clock or solver. It receives manually known weights and raw clip playheads.
        var input=new SkinnedAnimationRuntimeInput(ExampleDirectionalScene.MODEL,h.runtime.entityKey(id),tick,0,IDLE,Optional.empty(),AnimationUpdateBucket.VISIBLE_NEAR,
                new SkinnedExtractionRequest(Transform.IDENTITY,0x00f000f0,0,-1,RenderVisibility.VISIBLE,new CullingMetadata(h.models.find(ExampleDirectionalScene.MODEL).orElseThrow().renderHandle().bounds(),true)));
        var oracle=h.runtime.extractLayered(input,ExampleDirectionalScene.layers(),commands,new AnimationV2LayerWeights(weights),ExampleAnimationScene.procedural()).orElseThrow();
        var expectedPose=h.runtime.layeredSnapshot(h.runtime.entityKey(id)).orElseThrow().pose();
        for(int bone=0;bone<expectedPose.boneCount();bone++) {
            var a=actual.layers.pose().transform(bone);var b=expectedPose.transform(bone);
            require(near(a.translation().x(),b.translation().x())&&near(a.translation().y(),b.translation().y())&&near(a.translation().z(),b.translation().z())
                    &&near(a.rotation().x(),b.rotation().x())&&near(a.rotation().y(),b.rotation().y())&&near(a.rotation().z(),b.rotation().z())&&near(a.rotation().w(),b.rotation().w()),"final pose matches independent explicit clip frame");
        }
        compareVertices(positions(actual.render),positions(oracle.frame().renderSnapshot()));
    }
    private static void compareVertices(List<com.liy.blendlib.core.model.Vec3> a,List<com.liy.blendlib.core.model.Vec3> b) {
        require(!a.isEmpty()&&a.size()==b.size(),"nonempty matching CPU geometry");
        for(int i=0;i<a.size();i++)require(near(a.get(i).x(),b.get(i).x())&&near(a.get(i).y(),b.get(i).y())&&near(a.get(i).z(),b.get(i).z()),"CPU-skinned vertex oracle/seam match");
    }
    private static void verifyMotion() {
        String before=System.getProperty(ExampleDirectionalScene.PROPERTY);
        try {System.clearProperty(ExampleDirectionalScene.PROPERTY);require(!ExampleDirectionalScene.enabled(),"2D mode disabled by default");System.setProperty(ExampleDirectionalScene.PROPERTY,"true");require(ExampleDirectionalScene.enabled(),"2D mode independently opt-in");}
        finally {if(before==null)System.clearProperty(ExampleDirectionalScene.PROPERTY);else System.setProperty(ExampleDirectionalScene.PROPERTY,before);}
        for(double yaw:new double[]{0,90,180,270,-47,720})for(int tick=0;tick<800;tick++) {
            var world=ExampleDirectionalMotion.requestedVelocity(tick,yaw);var local=ExampleDirectionalScene.localVelocity(world.x(),world.z(),yaw);var round=ExampleDirectionalMotion.toWorld(local.x(),local.y(),yaw);
            require(near(world.x(),round.x())&&near(world.z(),round.z()),"measured horizontal velocity transforms into local yaw axes");
            require(Math.hypot(world.x(),world.z())<=.120001,"bounded requested trajectory");
        }
        var facingEast=ExampleDirectionalScene.localVelocity(-.1,0,90);require(near(facingEast.x(),.1)&&near(facingEast.y(),0),"yaw 90 world -X is forward");
        var facingSouth=ExampleDirectionalScene.localVelocity(.1,0,0);require(near(facingSouth.x(),0)&&near(facingSouth.y(),.1),"yaw zero world +X is left strafe");
    }

    private static final class Actor {
        final int id;
        final Object owner = new Object();
        final ExampleLayerVisualEvents events = new ExampleLayerVisualEvents();
        long sequence, cueTick;
        Actor(int id) { this.id = id; }
        void cue(long sequence, long cueTick) { this.sequence = sequence; this.cueTick = cueTick; }
    }
    private record Sample(ModelRenderSnapshot render, ClientSkinnedExtractionFrame frame, AnimationV2EvaluationSnapshot layers) {
        AnimationV2ControllerPlayhead upper() { return layers.playheads().get(ExampleAnimationScene.UPPER); }
    }
    private static final class Harness {
        final ClientModelRegistry models = new ClientModelRegistry();
        final ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(32);
        final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models, lifecycle);
        final ClientModelReloadListener reload = new ClientModelReloadListener(models, runtime::onActiveGeneration);
        final ExampleAttachmentOwners owners = new ExampleAttachmentOwners();
        final Object source = new Object();
        final ClientModelLookup lookup = new ClientModelLookup() {
            public ClientRegistryView snapshot() {
                var views = new LinkedHashMap<BlendModelKey, ClientModelView>();
                models.current().handles().keySet().forEach(key -> views.put(key, resolve(key)));
                return new ClientRegistryView(models.current().generationId(), views, List.of());
            }
            public ClientModelView resolve(BlendModelKey key) {
                var generation = models.current();
                var found = generation.find(key);
                var handle = found.orElseGet(() -> MissingModelHandle.notDiscovered(key, generation.generationId()));
                return new ClientModelView(key, generation.generationId(), found.isPresent(), handle.renderHandle(), Optional.empty());
            }
        };
        Harness() { runtime.onPlayInit(); }
        void reload(PackagedResources resources) {
            var shared = new PreparableReloadListener.SharedState(resources);
            long oldGeneration = models.current().generationId();
            var prepared = reload.prepare(shared);
            require(models.current().generationId() == oldGeneration, "prepare leaves the previous generation intact");
            require(prepared.loadedAssets().containsKey(ExampleDirectionalScene.MODEL)
                    && prepared.primaryDiagnostics().isEmpty() && prepared.globalDiagnostics().isEmpty(),
                    "dedicated real GLB and descriptor load without rules or resource errors");
            require(prepared.locomotionRules(ExampleDirectionalScene.MODEL).isEmpty(), "continuous blendspace has no discrete-rule sidecar");
            int reads = resources.reads;
            reload.apply(prepared, shared);
            require(resources.reads == reads && models.current().generationId() == prepared.generationId(),
                    "apply atomically publishes without rereading resources");
        }
        Sample sample(Actor actor, long tick, double x, double y) {
            var instance = runtime.entityKey(actor.id);
            long generation = models.current().generationId();
            var commands = runtime.captureEntityLayerCues(source, actor.owner, actor.id, ExampleDirectionalScene.MODEL,
                    generation, tick, actor.sequence == 0 ? List.of() : List.of(new BlendEntityLayerCue(
                            ExampleAnimationScene.UPPER, ATTACK, actor.sequence, actor.cueTick, 1)));
            var handle = models.find(ExampleDirectionalScene.MODEL).orElseThrow().renderHandle();
            var input = new SkinnedAnimationRuntimeInput(ExampleDirectionalScene.MODEL, instance, tick, 0,
                    IDLE, Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, 0x00f000f0, 0, -1,
                            RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true)));
            var frame = runtime.extractBlendSpace2D(input, ExampleDirectionalScene.layers(), commands,
                    ExampleAnimationScene.clipLayerWeights(tick), ExampleDirectionalScene.definition(), new AnimationBlendSpace2D.Input(x,y),
                    source, actor.owner, ExampleAnimationScene.procedural(), actor.events::accept).orElseThrow().frame();
            var layers = runtime.layeredSnapshot(instance).orElseThrow();
            require(layers.diagnostics().stream().allMatch(diagnostic ->
                    diagnostic.code() == AnimationV2DiagnosticCode.OVERLAPPING_OVERRIDE
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMPETING_CONTROLLER_WRITE
                            || diagnostic.code() == AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED
                            || diagnostic.code() == AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER),
                    "only ordinary same-priority overlap/priority/zero-weight/repeated-cue diagnostics are expected: " + layers.diagnostics());
            var request = new BlendEntitySnapshotRequest(ExampleDirectionalScene.MODEL, 0, 0x00f000f0, tick,
                    0, 0, 0, tick, true, 1);
            var sockets = BlendEntitySockets.capture(request, frame);
            var children = ExampleAttachmentScene.capture(lookup, runtime,
                    owners.key(actor.owner, instance.connectionSession(), runtime::retire), request, sockets, 0);
            require(children.size() == 1 && children.getFirst().snapshot().attachments().size() == 1,
                    "real weapon and independently skinned ornament coexist with blendspace");
            require(children.getFirst().placement().equals(sockets.socket(ExampleAttachmentScene.TIP).orElseThrow().attachmentPlacement()),
                    "weapon placement consumes the actual final procedural socket");
            var render = frame.renderSnapshot().withAttachments(children);
            require(BlendEntityAttachmentComposition.capture(render).diagnostics().isEmpty(),
                    "current-generation three-level attachment composition remains valid");
            double upperWeight = ExampleAnimationScene.clipLayerWeights(tick).multipliers().get(
                    new AnimationV2LayerWeights.Key(ExampleAnimationScene.UPPER, ExampleAnimationScene.UPPER));
            require(near(layers.effectiveLayerWeights().get(new AnimationV2LayerWeights.Key(
                    ExampleAnimationScene.UPPER, ExampleAnimationScene.UPPER)), upperWeight),
                    "blendspace leaves independently captured upper layer weight unchanged");
            return new Sample(render, frame, layers);
        }
    }
    private static final class PackagedResources implements ResourceManager {
        final Map<Identifier, Resource> resources = new LinkedHashMap<>();
        int reads;
        PackagedResources(boolean changedDescriptorSpeed) {
            for (String path : List.of("blend_models/directional_actor.json", "models3d/directional_actor.glb",
                    "blend_models/marker.json", "models3d/marker.glb", "textures/marker.png",
                    "blend_models/wand.json", "models3d/actor.glb", "textures/actor.png")) {
                byte[] bytes = packaged(path);
                if (changedDescriptorSpeed && path.equals("blend_models/directional_actor.json"))
                    bytes = new String(bytes, StandardCharsets.UTF_8).replace("\"speed\": 0.5", "\"speed\": 0.75")
                            .replace("\"speed\": 1.5", "\"speed\": 2.5").replace("\"speed\": 2.0", "\"speed\": 0.8")
                            .getBytes(StandardCharsets.UTF_8);
                byte[] captured = bytes.clone();
                resources.put(Identifier.fromNamespaceAndPath(NS, path), new Resource(null, () -> {
                    reads++; return new ByteArrayInputStream(captured);
                }));
            }
        }
        public Set<String> getNamespaces() { return Set.of(NS); }
        public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(resources.get(id)); }
        public List<Resource> getResourceStack(Identifier id) { throw new AssertionError("final resources only"); }
        public Map<Identifier, Resource> listResources(String path, ResourceManager.Selector filter) {
            var selected = new LinkedHashMap<Identifier, Resource>();
            resources.forEach((id, resource) -> {
                if (id.getPath().startsWith(path + "/") && filter.isIncluded(id)) selected.put(id, resource);
            });
            return selected;
        }
        public Map<Identifier, List<Resource>> listResourceStacks(String path, ResourceManager.Selector filter) {
            throw new AssertionError("final resources only");
        }
        public Stream<PackResources> listPacks() { return Stream.empty(); }
    }
    private static void phase(Sample sample, double expected) {
        for (var id : MEMBERS) {
            var playhead = sample.layers.playheads().get(id);
            double actual = playhead.timeSeconds() / ExampleDirectionalScene.packagedDurations().get(id);
            require(near(actual, expected), "all real unequal-duration members share phase, including zero-weight ones: "
                    + id + " actual=" + actual + " expected=" + expected);
        }
    }
    private static void weights(Sample sample,double... expected) {
        for(int i=0;i<MEMBERS.size();i++) {var id=MEMBERS.get(i);require(near(sample.layers.effectiveLayerWeights().get(new AnimationV2LayerWeights.Key(id,id)),expected[i]),"directional weights "+id);}
    }
    private static List<com.liy.blendlib.core.model.Vec3> positions(ModelRenderSnapshot render) { return RunnableAttachmentRenderVerification.positions(render); }
    private static byte[] packaged(String path) {
        try (var input = RunnableDirectionalBlendSpaceVerification.class.getResourceAsStream("/assets/" + NS + "/" + path)) {
            if (input == null) throw new AssertionError("missing packaged resource: " + path);
            return input.readAllBytes();
        } catch (IOException e) { throw new AssertionError("cannot read packaged resource: " + path, e); }
    }
    private static BlendAnimationKey animation(String name) { return BlendAnimationKey.parse(NS + ":" + name); }
    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 1e-6; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
