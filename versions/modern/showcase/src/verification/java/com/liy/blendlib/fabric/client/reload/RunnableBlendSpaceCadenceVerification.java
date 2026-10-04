package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
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

/** Additive packaged proof: actual GLB samples and ordinary explicit-playhead frames form the oracle. */
public final class RunnableBlendSpaceCadenceVerification {
    private static final String NS = "blendlib_runnable_examples";
    private static final BlendAnimationKey IDLE = animation("idle"), ATTACK = animation("attack");
    private RunnableBlendSpaceCadenceVerification() { }

    public static void verify() {
        verifyConsumerPolicy();
        verifyScene(new Scene(false));
        verifyScene(new Scene(true));
        System.out.println("Verified packaged positive dynamic cadence in 1D and 2D: actual unequal-duration/non-unit-speed GLBs, "
                + "validated [0.5,2] speed policy, piecewise old-rate intervals, idle/zero-weight progression, duplicate/backward boundaries, "
                + "raw clip samples and independent final-pose/CPU-vertex oracles across cadence changes, stable member sequences, "
                + "upper marker/weight/clock independence, procedural final sockets and nested attachments, changed-rate reload, "
                + "silent long-gap recovery and owner lifecycle; native graphics remains unverified");
    }

    private static void verifyScene(Scene scene) {
        var h = new Harness(scene);
        var resources = new PackagedResources(false);
        h.reload(resources);
        validateAuthoredRange(h);
        var actor = new Actor(700);
        actor.cue(1, 100);
        var initial = h.sample(actor, 100, 0, 0);
        phase(scene, initial, 0);
        Map<BlendResourceId, Long> sequences = new LinkedHashMap<>();
        scene.members().forEach(id -> sequences.put(id, initial.layers.playheads().get(id).acceptedSequence()));
        var isolated = new Actor(701);
        h.sample(isolated, 100, 0, 0);
        int reads = resources.reads;
        // Both routes use the same measured speed history. For 2D the vector also crosses sectors.
        long[] ticks = {104, 108, 110, 114, 118, 126, 126, 124, 130};
        double[] speeds = {.09, .12, 0, .06, .03, .06, .12, .06, .06};
        double[][] vectors = {{.09, 0}, {0, .12}, {0, 0}, {-.06, 0}, {.018, .024}, {.036, -.048},
                {0, -.12}, {.06, 0}, {-.036, -.048}};
        // Each interval uses the LAST committed multiplier, including the last same/backward-time capture.
        double[] expectedPhases = {.125, .5, .75, .875, .125, .375, .375, .375, .625};
        Sample retained = null;
        List<Vec3> retainedVertices = null;
        for (int i = 0; i < ticks.length; i++) {
            double x = scene.directional ? vectors[i][0] : speeds[i];
            double y = scene.directional ? vectors[i][1] : 0;
            require(near(Math.hypot(x, y), speeds[i]), "both consumers receive the intended measured speed");
            var sample = h.sample(actor, ticks[i], x, y);
            phase(scene, sample, expectedPhases[i]);
            for (var id : scene.members()) {
                var head = sample.layers.playheads().get(id);
                require(head.acceptedSequence() == sequences.get(id) && head.previousState() == null,
                        "cadence changes neither command sequence nor member transition");
            }
            verifyIndependentPoseAndVertices(h, sample, ticks[i], expectedPhases[i], scene.weights(x, y), 800 + i);
            if (i == 0 || i == 1) require(sample.upper().state().equals(ATTACK)
                    && sample.upper().acceptedSequence() == 1 && near(sample.upper().timeSeconds(), (ticks[i] - 100) / 20.0),
                    "upper attack time advances at its own rate through group cadence changes");
            if (i == 1) {
                retained = sample;
                retainedVertices = positions(sample.render);
                var rawPose = new ModelAnimationLayers(h.asset(), scene.layers()).localPose(sample.layers.pose());
                var rawSocket = SocketWorldTransform.query(h.asset(), NodePalette.from(rawPose, h.asset().nodes()),
                        ExampleAttachmentScene.TIP).orElseThrow();
                require(!rawSocket.rotation().equals(sample.frame.socketTransform(ExampleAttachmentScene.TIP).orElseThrow().rotation()),
                        "procedural rotation still changes the final socket after changing cadence");
            }
        }
        var callbacks = actor.events.snapshot();
        require(callbacks.callbacks() == 1 && callbacks.pairs().size() == 1
                && callbacks.pairs().getFirst().last().controllerId().equals(ExampleAnimationScene.UPPER)
                && callbacks.pairs().getFirst().last().event().eventKey().equals(BlendResourceId.parse(NS + ":attack_whoosh")),
                "the actual authored upper marker crosses exactly once without cadence-triggered replay");
        phase(scene, h.sample(isolated, 130, 0, 0), .9375);
        require(resources.reads == reads, "cadence, solving, sampling, markers and attachments perform no resource I/O");
        require(positions(retained.render).equals(retainedVertices), "later cadence updates retain immutable captured geometry");

        // Reload compensation changes S while the real packaged raw durations D stay unequal.
        var reloadActor = new Actor(702);
        h.sample(reloadActor, 100, 0, 0);
        phase(scene, h.sample(reloadActor, 104, .06, 0), .125);
        var beforeReload = h.sample(reloadActor, 110, .12, 0);
        phase(scene, beforeReload, .5);
        var beforeVertices = positions(beforeReload.render);
        h.reload(new PackagedResources(true));
        validateAuthoredRange(h);
        var reloaded = h.sample(reloadActor, 114, 0, 0);
        phase(scene, reloaded, 0);
        verifyIndependentPoseAndVertices(h, reloaded, 114, 0, scene.weights(0, 0), 820);
        require(reloaded.render.generation() != beforeReload.render.generation()
                && positions(beforeReload.render).equals(beforeVertices), "reload rebinds rates at integrated phase and preserves old frames");
        var afterReload = h.sample(reloadActor, 118, .06, 0);
        phase(scene, afterReload, .125);
        verifyIndependentPoseAndVertices(h, afterReload, 118, .125, scene.weights(.06, 0), 821);
        // 602 seconds at held cadence one is 752.5 normalized cycles; no skipped markers are replayed.
        var recovered = h.sample(reloadActor, 12158, .12, 0, true);
        phase(scene, recovered, .625);
        require(reloadActor.events.snapshot().callbacks() == 0, "long-gap recovery is silent");
        require(recovered.layers.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == AnimationV2DiagnosticCode.ADVANCE_DELTA_CLAMPED), "actual long-gap bounded recovery was exercised");
        for (var id : scene.members()) require(recovered.layers.playheads().get(id).acceptedSequence()
                > afterReload.layers.playheads().get(id).acceptedSequence(), "exceptional recovery uses one initialization-style discontinuity");
        verifyIndependentPoseAndVertices(h, recovered, 12158, .625, scene.weights(.12, 0), 822);
        phase(scene, h.sample(reloadActor, 12160, 0, 0), .875);
        h.runtime.onEntityUnload(reloadActor.id);
        h.owners.remove(reloadActor.owner, h.runtime::retire);
        phase(scene, h.sample(reloadActor, 12164, .12, 0), 0);
        h.runtime.retire(h.runtime.entityKey(reloadActor.id));
        phase(scene, h.sample(reloadActor, 12168, 0, 0), 0);
        h.owners.clear(h.runtime::retire);
        h.runtime.onWorldDisconnect();
        h.runtime.onPlayInit();
        phase(scene, h.sample(reloadActor, 12172, .06, 0), 0);
        h.runtime.onWorldDisconnect();
    }

    private static void validateAuthoredRange(Harness h) {
        var asset = h.asset();
        var plan = new ModelAnimationLayers(asset, h.scene.layers()).plan();
        var binding = h.scene.directional ? ExampleDirectionalScene.definition().syncGroup().bind(plan)
                : ExampleBlendSpaceScene.definition().syncGroup().bind(plan);
        for (double multiplier : new double[]{.5, .625, 1, 1.5, 2}) {
            var rates = binding.rateUpdate(multiplier).commandRates();
            require(rates.size() == h.scene.members().size(), "validated rate vector includes inactive members");
            for (var layer : h.scene.layers().subList(0, h.scene.members().size())) {
                var state = asset.animationDefinition().states().get(layer.initialState().resourceId());
                double duration = h.runtime.animationDuration(h.scene.model(), layer.initialState()).orElseThrow();
                require(near(duration, h.scene.durations().get(layer.id())) && state.speed() != 1 && state.loop()
                        && state.nextState() == null, "authored GLB durations and non-unit descriptor rates are real");
                double expectedRate = (duration / .8) * multiplier / state.speed();
                require(near(rates.get(layer.id()), expectedRate) && expectedRate >= 1.0 / 64 && expectedRate <= 64
                        && expectedRate * state.speed() >= 1.0 / 64 && expectedRate * state.speed() <= 64,
                        "the complete authored [0.5,2] range respects command and effective-rate bounds");
            }
        }
    }

    private static void verifyIndependentPoseAndVertices(Harness h, Sample actual, long tick,
            double expectedPhase, double[] expectedWeights, int oracleId) {
        var asset = h.asset();
        var sampler = PoseSampler.fromModelAsset(asset);
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        int root = asset.nodes().stream().filter(node -> node.name().equals("ShowcaseRootBone")).findFirst().orElseThrow().index();
        double x = 0, y = 0, z = 0;
        var commands = new ArrayList<AnimationV2Command>();
        var weights = new LinkedHashMap<AnimationV2LayerWeights.Key, Float>();
        for (int i = 0; i < h.scene.members().size(); i++) {
            var layer = h.scene.layers().get(i);
            double rawTime = expectedPhase * h.scene.durations().get(layer.id());
            // Direct sampling bypasses both the cadence integration and the blendspace weight solver.
            var pose = sampler.sample(definition.states().get(layer.initialState()), rawTime);
            x += expectedWeights[i] * pose.transform(root).translation().x();
            y += expectedWeights[i] * pose.transform(root).translation().y();
            z += expectedWeights[i] * pose.transform(root).translation().z();
            var key = new AnimationV2LayerWeights.Key(layer.id(), layer.id());
            require(near(actual.layers.effectiveLayerWeights().get(key), expectedWeights[i]), "cadence does not change the mixture policy");
            commands.add(new AnimationV2Command(layer.id(), layer.initialState(), 1, rawTime, 1));
            weights.put(key, (float) expectedWeights[i]);
        }
        var translation = actual.layers.pose().transform(root).translation();
        require(near(translation.x(), x) && near(translation.y(), y) && near(translation.z(), z),
                "independently sampled raw GLB translations match integrated cadence phase");
        var upper = actual.upper();
        require(upper.previousState() == null, "explicit-pose oracle samples beyond any upper transition");
        // Start the oracle upper in its sampled state, avoiding a newly manufactured transition.
        var oracleLayers = new ArrayList<>(h.scene.layers());
        var upperLayer = oracleLayers.removeLast();
        oracleLayers.add(new ModelAnimationLayers.Layer(upperLayer.id(), upperLayer.priority(), upperLayer.mode(),
                upperLayer.weight(), upperLayer.bones(), upper.state()));
        commands.add(new AnimationV2Command(ExampleAnimationScene.UPPER, upper.state(), 1, upper.timeSeconds(), 1));
        weights.putAll(ExampleAnimationScene.clipLayerWeights(tick).multipliers());
        var oracle = h.runtime.extractLayered(h.input(oracleId, tick), oracleLayers, commands,
                new AnimationV2LayerWeights(weights), ExampleAnimationScene.procedural()).orElseThrow().frame();
        var expectedPose = h.runtime.layeredSnapshot(h.runtime.entityKey(oracleId)).orElseThrow().pose();
        for (int bone = 0; bone < expectedPose.boneCount(); bone++) {
            var a = actual.layers.pose().transform(bone);
            var b = expectedPose.transform(bone);
            require(near(a.translation().x(), b.translation().x()) && near(a.translation().y(), b.translation().y())
                    && near(a.translation().z(), b.translation().z()) && near(a.rotation().x(), b.rotation().x())
                    && near(a.rotation().y(), b.rotation().y()) && near(a.rotation().z(), b.rotation().z())
                    && near(a.rotation().w(), b.rotation().w()) && near(a.scale().x(), b.scale().x())
                    && near(a.scale().y(), b.scale().y()) && near(a.scale().z(), b.scale().z()),
                    "every final layered transform matches independently positioned real clips");
        }
        compareVertices(positions(actual.render), positions(oracle.renderSnapshot()));
        var actualSocket = actual.frame.socketTransform(ExampleAttachmentScene.TIP).orElseThrow();
        var oracleSocket = oracle.socketTransform(ExampleAttachmentScene.TIP).orElseThrow();
        require(near(actualSocket.translation().x(), oracleSocket.translation().x())
                && near(actualSocket.translation().y(), oracleSocket.translation().y())
                && near(actualSocket.translation().z(), oracleSocket.translation().z())
                && near(actualSocket.rotation().x(), oracleSocket.rotation().x())
                && near(actualSocket.rotation().y(), oracleSocket.rotation().y())
                && near(actualSocket.rotation().z(), oracleSocket.rotation().z())
                && near(actualSocket.rotation().w(), oracleSocket.rotation().w()),
                "post-procedural socket matches the independent fixed-pose oracle");
    }

    private static void verifyConsumerPolicy() {
        String before = System.getProperty(ExampleBlendSpaceCadence.PROPERTY);
        try {
            System.clearProperty(ExampleBlendSpaceCadence.PROPERTY);
            require(!ExampleBlendSpaceCadence.enabled(), "old fixed consumers remain the default");
            System.setProperty(ExampleBlendSpaceCadence.PROPERTY, "true");
            require(ExampleBlendSpaceCadence.enabled(), "one explicit JVM opt-in enables cadence for either blendspace");
        } finally {
            if (before == null) System.clearProperty(ExampleBlendSpaceCadence.PROPERTY);
            else System.setProperty(ExampleBlendSpaceCadence.PROPERTY, before);
        }
        require(near(ExampleBlendSpaceCadence.multiplier(0), .5)
                && near(ExampleBlendSpaceCadence.multiplier(.03), .5)
                && near(ExampleBlendSpaceCadence.multiplier(.06), 1)
                && near(ExampleBlendSpaceCadence.multiplier(.09), 1.5)
                && near(ExampleBlendSpaceCadence.multiplier(.12), 2)
                && near(ExampleBlendSpaceCadence.multiplier(Double.MAX_VALUE), 2),
                "positive idle, authored reference normalization and both clamps");
        for (double speed : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            try { ExampleBlendSpaceCadence.multiplier(speed); throw new AssertionError("invalid measured speed accepted"); }
            catch (IllegalArgumentException expected) { /* Input failure cannot masquerade as valid idle. */ }
        }
    }

    private record Scene(boolean directional) {
        BlendModelKey model() { return directional ? ExampleDirectionalScene.MODEL : ExampleBlendSpaceScene.MODEL; }
        List<ModelAnimationLayers.Layer> layers() { return directional ? ExampleDirectionalScene.layers() : ExampleBlendSpaceScene.layers(); }
        Map<BlendResourceId, Double> durations() { return directional ? ExampleDirectionalScene.packagedDurations() : ExampleBlendSpaceScene.packagedDurations(); }
        List<BlendResourceId> members() { return layers().subList(0, directional ? 5 : 3).stream().map(ModelAnimationLayers.Layer::id).toList(); }
        double[] weights(double x, double y) {
            if (!directional) {
                double value = Math.max(0, Math.min(.14, x));
                return value <= .06 ? new double[]{1 - value / .06, value / .06, 0}
                        : new double[]{0, (.14 - value) / .08, (value - .06) / .08};
            }
            // Analytic diamond weights, independent of the production angular/sector solver.
            double scale = Math.max(.14, Math.abs(x) + Math.abs(y));
            return new double[]{Math.max(0, 1 - (Math.abs(x) + Math.abs(y)) / .14),
                    Math.max(0, x) / scale, Math.max(0, y) / scale, Math.max(0, -x) / scale, Math.max(0, -y) / scale};
        }
    }
    private static final class Actor {
        final int id;
        final Object owner = new Object();
        final ExampleLayerVisualEvents events = new ExampleLayerVisualEvents();
        long sequence, cueTick;
        Actor(int id) { this.id = id; }
        void cue(long sequence, long tick) { this.sequence = sequence; this.cueTick = tick; }
    }
    private record Sample(ModelRenderSnapshot render, ClientSkinnedExtractionFrame frame, AnimationV2EvaluationSnapshot layers) {
        AnimationV2ControllerPlayhead upper() { return layers.playheads().get(ExampleAnimationScene.UPPER); }
    }
    private static final class Harness {
        final Scene scene;
        final ClientModelRegistry models = new ClientModelRegistry();
        final ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(64);
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
        Harness(Scene scene) { this.scene = scene; runtime.onPlayInit(); }
        ModelAsset asset() { return ((LoadedModelHandle) models.find(scene.model()).orElseThrow()).asset(); }
        void reload(PackagedResources resources) {
            var shared = new PreparableReloadListener.SharedState(resources);
            var prepared = reload.prepare(shared);
            require(prepared.loadedAssets().containsKey(scene.model()) && prepared.primaryDiagnostics().isEmpty()
                    && prepared.globalDiagnostics().isEmpty() && prepared.locomotionRules(scene.model()).isEmpty(),
                    "production reload loads actual packaged blendspace assets without resource/rule diagnostics");
            int reads = resources.reads;
            reload.apply(prepared, shared);
            require(resources.reads == reads, "reload apply does not reread resources");
        }
        SkinnedAnimationRuntimeInput input(int actorId, long tick) {
            var handle = models.find(scene.model()).orElseThrow().renderHandle();
            return new SkinnedAnimationRuntimeInput(scene.model(), runtime.entityKey(actorId), tick, 0, IDLE,
                    Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, 0x00f000f0, 0, -1, RenderVisibility.VISIBLE,
                            new CullingMetadata(handle.bounds(), true)));
        }
        Sample sample(Actor actor, long tick, double x, double y) {
            return sample(actor, tick, x, y, false);
        }
        Sample sample(Actor actor, long tick, double x, double y, boolean recovery) {
            var instance = runtime.entityKey(actor.id);
            var commands = runtime.captureEntityLayerCues(source, actor.owner, actor.id, scene.model(),
                    models.current().generationId(), tick, actor.sequence == 0 ? List.of() : List.of(
                            new BlendEntityLayerCue(ExampleAnimationScene.UPPER, ATTACK, actor.sequence, actor.cueTick, 1)));
            double cadence = ExampleBlendSpaceCadence.multiplier(Math.hypot(x, y));
            var captured = scene.directional
                    ? runtime.extractBlendSpace2D(input(actor.id, tick), scene.layers(), commands,
                            ExampleAnimationScene.clipLayerWeights(tick), ExampleDirectionalScene.definition(),
                            new AnimationBlendSpace2D.Input(x, y), cadence, source, actor.owner,
                            ExampleAnimationScene.procedural(), actor.events::accept)
                    : runtime.extractBlendSpace(input(actor.id, tick), scene.layers(), commands,
                            ExampleAnimationScene.clipLayerWeights(tick), ExampleBlendSpaceScene.definition(), x,
                            cadence, source, actor.owner, ExampleAnimationScene.procedural(), actor.events::accept);
            var frame = captured.orElseThrow().frame();
            var layers = runtime.layeredSnapshot(instance).orElseThrow();
            require(layers.diagnostics().stream().allMatch(diagnostic ->
                    diagnostic.code() == AnimationV2DiagnosticCode.OVERLAPPING_OVERRIDE
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMPETING_CONTROLLER_WRITE
                            || diagnostic.code() == AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED
                            || diagnostic.code() == AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER
                            || (recovery && (diagnostic.code() == AnimationV2DiagnosticCode.ADVANCE_DELTA_CLAMPED
                                    || diagnostic.code() == AnimationV2DiagnosticCode.OBSERVER_TRAVERSAL_TRUNCATED))),
                    "cadence yields only ordinary layer diagnostics, plus bounded traversal/clamp at explicit recovery: " + layers.diagnostics());
            var request = new BlendEntitySnapshotRequest(scene.model(), 0, 0x00f000f0, tick, 0, 0, 0, tick, true, 1);
            var sockets = BlendEntitySockets.capture(request, frame);
            var children = ExampleAttachmentScene.capture(lookup, runtime,
                    owners.key(actor.owner, instance.connectionSession(), runtime::retire), request, sockets, 0);
            require(children.size() == 1 && children.getFirst().snapshot().attachments().size() == 1,
                    "real weapon and independently animated nested ornament coexist with dynamic cadence");
            require(children.getFirst().placement().equals(sockets.socket(ExampleAttachmentScene.TIP).orElseThrow().attachmentPlacement()),
                    "weapon follows the actual final procedural socket");
            var render = frame.renderSnapshot().withAttachments(children);
            require(BlendEntityAttachmentComposition.capture(render).diagnostics().isEmpty(), "three-level attachment composition remains valid");
            var upperKey = new AnimationV2LayerWeights.Key(ExampleAnimationScene.UPPER, ExampleAnimationScene.UPPER);
            require(near(layers.effectiveLayerWeights().get(upperKey), ExampleAnimationScene.clipLayerWeights(tick).multipliers().get(upperKey)),
                    "independent upper fade is unchanged by group cadence");
            return new Sample(render, frame, layers);
        }
    }
    private static final class PackagedResources implements ResourceManager {
        final Map<Identifier, Resource> resources = new LinkedHashMap<>();
        int reads;
        PackagedResources(boolean changedDescriptorSpeed) {
            for (String path : List.of("blend_models/blendspace_actor.json", "models3d/blendspace_actor.glb",
                    "blend_models/directional_actor.json", "models3d/directional_actor.glb", "blend_models/marker.json",
                    "models3d/marker.glb", "textures/marker.png", "blend_models/wand.json", "models3d/actor.glb", "textures/actor.png")) {
                byte[] bytes = packaged(path);
                if (changedDescriptorSpeed && (path.equals("blend_models/blendspace_actor.json") || path.equals("blend_models/directional_actor.json")))
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
        public Map<Identifier, List<Resource>> listResourceStacks(String path, ResourceManager.Selector filter) { throw new AssertionError("final resources only"); }
        public Stream<PackResources> listPacks() { return Stream.empty(); }
    }
    private static void phase(Scene scene, Sample sample, double expected) {
        for (var id : scene.members()) {
            double actual = sample.layers.playheads().get(id).timeSeconds() / scene.durations().get(id);
            require(near(actual, expected), "all unequal-duration members, including zero-weight members, share integrated phase: "
                    + id + " actual=" + actual + " expected=" + expected);
        }
    }
    private static void compareVertices(List<Vec3> actual, List<Vec3> expected) {
        require(!actual.isEmpty() && actual.size() == expected.size(), "nonempty matching CPU geometry");
        for (int i = 0; i < actual.size(); i++) require(near(actual.get(i).x(), expected.get(i).x())
                && near(actual.get(i).y(), expected.get(i).y()) && near(actual.get(i).z(), expected.get(i).z()),
                "post-procedural CPU-skinned vertex matches independent explicit-playhead oracle");
    }
    private static List<Vec3> positions(ModelRenderSnapshot render) { return RunnableAttachmentRenderVerification.positions(render); }
    private static byte[] packaged(String path) {
        try (var input = RunnableBlendSpaceCadenceVerification.class.getResourceAsStream("/assets/" + NS + "/" + path)) {
            if (input == null) throw new AssertionError("missing packaged resource: " + path);
            return input.readAllBytes();
        } catch (IOException exception) { throw new AssertionError("cannot read packaged resource: " + path, exception); }
    }
    private static BlendAnimationKey animation(String name) { return BlendAnimationKey.parse(NS + ":" + name); }
    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 1e-6; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
