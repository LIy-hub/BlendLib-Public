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
public final class RunnableBlendSpaceVerification {
    private static final String NS = "blendlib_runnable_examples";
    private static final BlendAnimationKey IDLE = animation("idle"), ATTACK = animation("attack");
    private static final List<BlendResourceId> MEMBERS = List.of(
            ExampleBlendSpaceScene.IDLE, ExampleBlendSpaceScene.WALK, ExampleBlendSpaceScene.RUN);
    private RunnableBlendSpaceVerification() { }

    public static void verify() {
        verifyOptInAndMotion();
        var h = new Harness();
        var resources = new PackagedResources(false);
        h.reload(resources);
        var asset = ((LoadedModelHandle) h.models.find(ExampleBlendSpaceScene.MODEL).orElseThrow()).asset();
        require(asset.clips().stream().map(clip -> clip.name()).toList().containsAll(List.of("idle", "walk", "run", "attack")),
                "the built JAR supplies actual idle/walk/run clips and an independent attack");
        for (int i = 0; i < MEMBERS.size(); i++) {
            var layer = ExampleBlendSpaceScene.layers().get(i);
            var state = asset.animationDefinition().states().get(layer.initialState().resourceId());
            require(state.loop() && state.nextState() == null && state.speed() != 1,
                    "all blendspace members are continuous loops with deliberate non-unit descriptor speed");
            require(near(h.runtime.animationDuration(ExampleBlendSpaceScene.MODEL, layer.initialState()).orElseThrow(),
                    ExampleBlendSpaceScene.packagedDurations().get(layer.id())), "unequal real GLB duration matches authored contract");
            require(state.events().isEmpty(), "gait/contact deduplication is not claimed by member markers");
        }
        int root = asset.nodes().stream().filter(node -> node.name().equals("ShowcaseRootBone"))
                .findFirst().orElseThrow().index();
        Actor idleActor = new Actor(401), walkActor = new Actor(402), runActor = new Actor(403);
        Actor idleWalkActor = new Actor(404), walkRunActor = new Actor(405);
        List<Actor> actors = List.of(idleActor, walkActor, runActor, idleWalkActor, walkRunActor);
        for (var actor : actors) h.sample(actor, 100, 0);
        int readsAfterReload = resources.reads;
        Sample idle = h.sample(idleActor, 104, 0);
        Sample walk = h.sample(walkActor, 104, .06);
        Sample run = h.sample(runActor, 104, .14);
        Sample idleWalk = h.sample(idleWalkActor, 104, .03);
        Sample walkRun = h.sample(walkRunActor, 104, .10);
        for (var sample : List.of(idle, walk, run, idleWalk, walkRun)) phase(sample, .25);
        weights(idle, 1, 0, 0); weights(walk, 0, 1, 0); weights(run, 0, 0, 1);
        weights(idleWalk, .5, .5, 0); weights(walkRun, 0, .5, .5);
        require(near(idle.layers.pose().transform(root).translation().x(), .005)
                && near(walk.layers.pose().transform(root).translation().x(), .025)
                && near(run.layers.pose().transform(root).translation().x(), .045),
                "sample endpoints evaluate distinct authored root poses at the same normalized phase");
        require(near(idleWalk.layers.pose().transform(root).translation().x(), .015)
                && near(walkRun.layers.pose().transform(root).translation().x(), .035),
                "real intermediate final poses interpolate adjacent authored samples, not state labels");
        var idleVertices = positions(idle.render);
        var walkVertices = positions(walk.render);
        var runVertices = positions(run.render);
        require(!idleVertices.isEmpty() && !idleVertices.equals(walkVertices) && !walkVertices.equals(runVertices)
                && !positions(idleWalk.render).equals(idleVertices) && !positions(idleWalk.render).equals(walkVertices)
                && !positions(walkRun.render).equals(walkVertices) && !positions(walkRun.render).equals(runVertices),
                "post-procedural CPU-skinned geometry visibly differs at endpoints and midpoints");
        var rawPose = new ModelAnimationLayers(asset, ExampleBlendSpaceScene.layers()).localPose(idleWalk.layers.pose());
        var rawSocket = SocketWorldTransform.query(asset, NodePalette.from(rawPose, asset.nodes()), ExampleAttachmentScene.TIP).orElseThrow();
        require(!rawSocket.rotation().equals(idleWalk.frame.socketTransform(ExampleAttachmentScene.TIP).orElseThrow().rotation()),
                "procedural tip rotation follows synchronized clip blending");
        verifyInspection(h, idleWalkActor, idleWalk);
        require(resources.reads == readsAfterReload, "speed selection, inspection, attachment capture and extraction perform no resource I/O");

        idleWalkActor.cue(1, 108);
        var attack = h.sample(idleWalkActor, 108, .14);
        require(attack.upper().state().equals(ATTACK) && attack.upper().acceptedSequence() == 1
                && near(attack.upper().timeSeconds(), 0), "ordinary independently sequenced upper attack still starts");
        phase(attack, .5);
        long sequence = attack.layers.playheads().get(MEMBERS.getFirst()).acceptedSequence();
        var changed = h.sample(idleWalkActor, 110, .03);
        phase(changed, .625); weights(changed, .5, .5, 0);
        require(changed.upper().acceptedSequence() == 1 && near(changed.upper().timeSeconds(), .1),
                "changing blend weights cannot restart the upper attack");
        require(MEMBERS.stream().allMatch(id -> changed.layers.playheads().get(id).acceptedSequence() == sequence),
                "changing speed does not manufacture new member command sequences");
        for (long tick = 111; tick < 140; tick++) {
            var sample = h.sample(idleWalkActor, tick, Math.abs(ExampleBlendSpaceMotion.requestedHorizontalVelocity((int) tick)));
            phase(sample, ((tick - 100) % 16) / 16.0);
            require(MEMBERS.stream().allMatch(id -> sample.layers.playheads().get(id).acceptedSequence() == sequence),
                    "smooth changing inputs retain command sequences over multiple cycles");
        }
        var callbacks = idleWalkActor.events.snapshot();
        require(callbacks.callbacks() == 1 && callbacks.pairs().size() == 1
                && callbacks.pairs().getFirst().last().controllerId().equals(ExampleAnimationScene.UPPER),
                "the existing upper attack marker dispatches once; blendspace members invent no contact events");
        var otherBeforeReload = h.sample(runActor, 140, .14);
        phase(otherBeforeReload, .5);
        h.runtime.onEntityUnload(idleWalkActor.id);
        h.owners.remove(idleWalkActor.owner, h.runtime::retire);
        phase(h.sample(idleWalkActor, 142, .03), 0);
        phase(h.sample(runActor, 142, .14), .625);
        var retainedVertices = positions(changed.render);
        h.reload(new PackagedResources(true));
        var reloaded = h.sample(runActor, 145, .1);
        phase(reloaded, .8125); weights(reloaded, 0, .5, .5);
        require(reloaded.render.generation() != changed.render.generation()
                && positions(changed.render).equals(retainedVertices), "reload retains phase and immutable historical CPU geometry");
        var returnToIdle = h.sample(runActor, 147, 0);
        phase(returnToIdle, .9375); weights(returnToIdle, 1, 0, 0);
        require(returnToIdle.upper().state().equals(IDLE), "uncommanded upper controller remains independent idle");
        h.runtime.retire(h.runtime.entityKey(runActor.id));
        phase(h.sample(runActor, 150, .14), 0);
        var oldKey = h.runtime.entityKey(runActor.id);
        h.owners.clear(h.runtime::retire);
        h.runtime.onWorldDisconnect();
        require(h.lifecycle.registry().size() == 0 && h.runtime.activeEntityKey(runActor.id).isEmpty(),
                "disconnect clears actor and independently owned animated attachments");
        h.runtime.onPlayInit();
        phase(h.sample(runActor, 160, .14), 0);
        require(!h.runtime.entityKey(runActor.id).connectionSession().equals(oldKey.connectionSession()),
                "reconnect obtains a new owner epoch");
        h.runtime.onWorldDisconnect();
        System.out.println("Verified packaged synchronized 1D blendspace: unequal 2/1/.5-second GLBs and non-unit speeds, "
                + "adjacent final poses and CPU vertices, shared phase including zero-weight layers, stable sequences, "
                + "independent upper cues/weights, procedural sockets and nested attachments, read-only inspection, "
                + "reload with changed descriptor speeds, unload, retirement and reconnect; native rendering remains unverified");
    }

    private static void verifyInspection(Harness h, Actor actor, Sample sample) {
        var key = h.runtime.entityKey(actor.id);
        var before = h.runtime.layeredSnapshot(key).orElseThrow();
        var durations = new LinkedHashMap<BlendResourceId, Double>();
        for (var layer : ExampleBlendSpaceScene.layers().subList(0, 3))
            durations.put(layer.id(), h.runtime.animationDuration(ExampleBlendSpaceScene.MODEL, layer.initialState()).orElseThrow());
        var originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var ordinary = ExampleLayerInspection.format(ExampleBlendSpaceScene.layers(), sample.layers);
            require(ordinary.stream().anyMatch(line -> line.contains("effectiveWeight=0.500")), "inspection exposes actual captured blend weights");
            var normalized = ExampleLayerInspection.formatBlendSpace(sample.layers, Map.copyOf(durations));
            require(normalized.stream().filter(line -> line.contains("normalizedPlayhead=0.250")).count() == 3,
                    "read-only inspection derives all three synchronized phases from actual current loaded durations");
            require(normalized.stream().noneMatch(line -> line.contains("0,250")), "inspection is locale-independent");
        } finally { Locale.setDefault(originalLocale); }
        require(h.runtime.layeredSnapshot(key).orElseThrow() == before, "inspection cannot publish, advance or restart playback");
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
            require(prepared.loadedAssets().containsKey(ExampleBlendSpaceScene.MODEL)
                    && prepared.primaryDiagnostics().isEmpty() && prepared.globalDiagnostics().isEmpty(),
                    "dedicated real GLB and descriptor load without rules or resource errors");
            require(prepared.locomotionRules(ExampleBlendSpaceScene.MODEL).isEmpty(), "continuous blendspace has no discrete-rule sidecar");
            int reads = resources.reads;
            reload.apply(prepared, shared);
            require(resources.reads == reads && models.current().generationId() == prepared.generationId(),
                    "apply atomically publishes without rereading resources");
        }
        Sample sample(Actor actor, long tick, double speed) {
            var instance = runtime.entityKey(actor.id);
            long generation = models.current().generationId();
            var commands = runtime.captureEntityLayerCues(source, actor.owner, actor.id, ExampleBlendSpaceScene.MODEL,
                    generation, tick, actor.sequence == 0 ? List.of() : List.of(new BlendEntityLayerCue(
                            ExampleAnimationScene.UPPER, ATTACK, actor.sequence, actor.cueTick, 1)));
            var handle = models.find(ExampleBlendSpaceScene.MODEL).orElseThrow().renderHandle();
            var input = new SkinnedAnimationRuntimeInput(ExampleBlendSpaceScene.MODEL, instance, tick, 0,
                    IDLE, Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, 0x00f000f0, 0, -1,
                            RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true)));
            var frame = runtime.extractBlendSpace(input, ExampleBlendSpaceScene.layers(), commands,
                    ExampleAnimationScene.clipLayerWeights(tick), ExampleBlendSpaceScene.definition(), speed,
                    source, actor.owner, ExampleAnimationScene.procedural(), actor.events::accept).orElseThrow().frame();
            var layers = runtime.layeredSnapshot(instance).orElseThrow();
            require(layers.diagnostics().stream().allMatch(diagnostic ->
                    diagnostic.code() == AnimationV2DiagnosticCode.OVERLAPPING_OVERRIDE
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMPETING_CONTROLLER_WRITE
                            || diagnostic.code() == AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED
                            || diagnostic.code() == AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER),
                    "only ordinary same-priority overlap/priority/zero-weight/repeated-cue diagnostics are expected: " + layers.diagnostics());
            var request = new BlendEntitySnapshotRequest(ExampleBlendSpaceScene.MODEL, 0, 0x00f000f0, tick,
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
            for (String path : List.of("blend_models/blendspace_actor.json", "models3d/blendspace_actor.glb",
                    "blend_models/marker.json", "models3d/marker.glb", "textures/marker.png",
                    "blend_models/wand.json", "models3d/actor.glb", "textures/actor.png")) {
                byte[] bytes = packaged(path);
                if (changedDescriptorSpeed && path.equals("blend_models/blendspace_actor.json"))
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
    private static void verifyOptInAndMotion() {
        String before = System.getProperty(ExampleBlendSpaceScene.PROPERTY);
        try {
            System.clearProperty(ExampleBlendSpaceScene.PROPERTY);
            require(!ExampleBlendSpaceScene.enabled(), "blendspace consumer is disabled by default");
            System.setProperty(ExampleBlendSpaceScene.PROPERTY, "true");
            require(ExampleBlendSpaceScene.enabled(), "documented JVM property enables blendspace consumer");
            System.setProperty(ExampleBlendSpaceScene.PROPERTY, "false");
            require(!ExampleBlendSpaceScene.enabled(), "false returns to the other independent example choices");
        } finally {
            if (before == null) System.clearProperty(ExampleBlendSpaceScene.PROPERTY);
            else System.setProperty(ExampleBlendSpaceScene.PROPERTY, before);
        }
        double previous = ExampleBlendSpaceMotion.requestedHorizontalVelocity(0);
        for (int tick = 1; tick <= 640; tick++) {
            double value = ExampleBlendSpaceMotion.requestedHorizontalVelocity(tick);
            require(Double.isFinite(value) && Math.abs(value) <= .14 && Math.abs(value - previous) < .006,
                    "scripted requested velocity accelerates/decelerates smoothly, including reversal and loop seam");
            require(near(value, -ExampleBlendSpaceMotion.requestedHorizontalVelocity(tick + 160)),
                    "outward and return journeys have opposite smooth requested velocities");
            previous = value;
        }
    }
    private static void phase(Sample sample, double expected) {
        for (var id : MEMBERS) {
            var playhead = sample.layers.playheads().get(id);
            double actual = playhead.timeSeconds() / ExampleBlendSpaceScene.packagedDurations().get(id);
            require(near(actual, expected), "all real unequal-duration members share phase, including zero-weight ones: "
                    + id + " actual=" + actual + " expected=" + expected);
        }
    }
    private static void weights(Sample sample, double idle, double walk, double run) {
        double[] expected = {idle, walk, run};
        for (int i = 0; i < MEMBERS.size(); i++) {
            var id = MEMBERS.get(i);
            require(near(sample.layers.effectiveLayerWeights().get(new AnimationV2LayerWeights.Key(id, id)), expected[i]),
                    "effective captured weights match adjacent interpolation for " + id);
        }
    }
    private static List<com.liy.blendlib.core.model.Vec3> positions(ModelRenderSnapshot render) { return RunnableAttachmentRenderVerification.positions(render); }
    private static byte[] packaged(String path) {
        try (var input = RunnableBlendSpaceVerification.class.getResourceAsStream("/assets/" + NS + "/" + path)) {
            if (input == null) throw new AssertionError("missing packaged resource: " + path);
            return input.readAllBytes();
        } catch (IOException e) { throw new AssertionError("cannot read packaged resource: " + path, e); }
    }
    private static BlendAnimationKey animation(String name) { return BlendAnimationKey.parse(NS + ":" + name); }
    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 1e-6; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
