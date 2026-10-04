package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerPlayhead;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2DiagnosticCode;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.examples.runnable.ExampleAnimationScene;
import com.liy.blendlib.examples.runnable.ExampleLocomotionScene;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.ProceduralPosePipeline;
import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.RunnableAttachmentRenderVerification;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Real prepare/apply and actual CPU layered extraction against resources in the built example JAR. */
public final class RunnableLocomotionVerification {
    private static final String NS = "blendlib_runnable_examples";
    private static final String RULE_PATH = "blend_animation_rules/locomotion_actor.json";
    private static final BlendAnimationKey IDLE = animation("idle"), WALK = animation("walk"), RUN = animation("run");
    private static final BlendAnimationKey ATTACK = animation("attack");
    private RunnableLocomotionVerification() { }

    public static void verify() {
        verifyOptIn();
        var h = new Harness();
        var resources = new PackagedResources(packaged(RULE_PATH));
        h.reload(resources, true);
        var asset = ((LoadedModelHandle) h.models.find(ExampleLocomotionScene.MODEL).orElseThrow()).asset();
        require(asset.clips().stream().map(clip -> clip.name()).toList().containsAll(List.of("idle", "walk", "run", "attack")),
                "packaged model must have distinct actual idle/walk/run clips and the independent attack");
        for (var key : List.of(IDLE, WALK, RUN)) {
            var state = asset.animationDefinition().states().get(key.resourceId());
            require(state.loop() && state.nextState() == null, "all rule targets are continuous loops without next");
        }
        int rootIndex = asset.nodes().stream().filter(node -> node.name().equals("ShowcaseRootBone"))
                .findFirst().orElseThrow().index();
        int readsAfterReload = resources.reads;
        Actor first = new Actor(301), second = new Actor(302);
        Sample idle = h.sample(first, 100, true, 0);
        require(idle.base().state().equals(IDLE) && idle.base().timeSeconds() == 0, "rest starts real base idle");
        long initialSequence = idle.base().acceptedSequence();
        first.cue(1, 108);
        Sample walking = h.sample(first, 108, true, .065);
        require(walking.base().state().equals(WALK) && walking.base().timeSeconds() == 0
                && walking.base().acceptedSequence() == initialSequence + 1, "walk transition starts exactly once at zero");
        require(walking.upper().state().equals(ATTACK) && walking.upper().acceptedSequence() == 1,
                "upper attack is still the separately sequenced live cue path");
        Sample walkAdvance = h.sample(first, 112, true, .065);
        require(walkAdvance.base().acceptedSequence() == walking.base().acceptedSequence()
                && near(walkAdvance.base().timeSeconds(), .2), "stable walk advances instead of restarting");
        require(near(walkAdvance.upper().timeSeconds(), .2), "upper attack advances independently");
        Sample jitter = h.sample(first, 114, true, .03);
        require(jitter.base().state().equals(WALK) && jitter.base().acceptedSequence() == walking.base().acceptedSequence(),
                "packaged walk exit threshold survives speed below its enter threshold");
        Sample running = h.sample(first, 120, true, .18);
        require(running.base().state().equals(RUN) && running.base().timeSeconds() == 0
                && running.base().acceptedSequence() == walking.base().acceptedSequence() + 1,
                "ordered run rule preempts walk and starts its own real clip at zero");
        require(running.upper().acceptedSequence() == 1 && near(running.upper().timeSeconds(), .6),
                "base transition cannot restart or replace the upper cue");
        Sample runAdvance = h.sample(first, 124, true, .18);
        require(runAdvance.base().acceptedSequence() == running.base().acceptedSequence()
                && near(runAdvance.base().timeSeconds(), .2), "stable run continues at the existing playhead");
        require(!idle.layers.pose().transform(rootIndex).translation()
                .equals(walkAdvance.layers.pose().transform(rootIndex).translation())
                && !walkAdvance.layers.pose().transform(rootIndex).translation()
                .equals(runAdvance.layers.pose().transform(rootIndex).translation()),
                "actual layered root poses differ between idle, walk and authored run");
        var idleVertices = RunnableAttachmentRenderVerification.positions(idle.render);
        var walkVertices = RunnableAttachmentRenderVerification.positions(walkAdvance.render);
        var runVertices = RunnableAttachmentRenderVerification.positions(runAdvance.render);
        require(!idleVertices.isEmpty() && !idleVertices.equals(walkVertices) && !walkVertices.equals(runVertices),
                "final procedural CPU-skinned vertices, not just selector keys, really change");
        require(h.sample(first, 125, true, .08).base().state().equals(RUN),
                "six-tick selected-state hold blocks an early return to walk");
        require(h.sample(first, 126, true, .11).base().state().equals(RUN),
                "packaged run exit threshold survives enter-threshold jitter");
        require(h.sample(first, 128, true, .08).base().state().equals(WALK), "run exits to walk below exit threshold");
        first.cue(2, 134);
        Sample airborne = h.sample(first, 134, false, .18);
        require(airborne.base().state().equals(IDLE) && airborne.upper().state().equals(ATTACK)
                && airborne.upper().acceptedSequence() == 2 && airborne.upper().timeSeconds() == 0,
                "grounded=false selects idle while a new independent upper cue can retrigger");
        Sample other = h.sample(second, 134, true, .18);
        require(other.base().state().equals(RUN) && other.base().acceptedSequence() == initialSequence,
                "shared renderer source has independent per-owner rule state");
        require(resources.reads == readsAfterReload, "input capture and layered extraction perform no resource reads");

        h.runtime.onEntityUnload(first.id);
        require(h.runtime.layeredSnapshot(h.runtime.entityKey(first.id)).isEmpty(), "unload retires the actual layered frame");
        require(h.sample(first, 140, true, .065).base().acceptedSequence() == initialSequence,
                "unloaded owner restarts fresh rule sequence");
        Sample otherAdvanced = h.sample(second, 140, true, .18);
        require(otherAdvanced.base().acceptedSequence() == initialSequence && near(otherAdvanced.base().timeSeconds(), .3),
                "unloading another actor preserves this actor's loop clock");
        var retained = runAdvance.render;
        h.reload(new PackagedResources(packaged(RULE_PATH)), true);
        require(h.sample(first, 150, true, .18).base().acceptedSequence() == initialSequence,
                "real resource reload retires the old rule state and accepts current inputs");
        require(retained.generation() != h.models.current().generationId()
                && RunnableAttachmentRenderVerification.positions(retained).equals(runVertices),
                "retained CPU frame stays immutable across reload");

        // An invalid optional sidecar disables only rules; the model and upper cue still work.
        h.reload(new PackagedResources("{\"schema_version\":1,\"default\":\"blendlib_runnable_examples:attack\",\"rules\":[]}"
                .getBytes(StandardCharsets.UTF_8)), false);
        first.cue(3, 160);
        Sample invalid = h.sample(first, 160, true, .18);
        require(invalid.base().state().equals(IDLE) && invalid.upper().state().equals(ATTACK)
                && invalid.commands.stream().noneMatch(c -> c.controllerId().equals(ExampleAnimationScene.BASE)),
                "invalid optional rules retain the ordinary declared layer and unrelated upper cue");
        h.reload(new PackagedResources(null), false);
        require(h.sample(first, 170, true, .18).base().state().equals(IDLE), "missing sidecar remains opt-in fallback");
        h.reload(new PackagedResources(packaged(RULE_PATH)), true);
        require(h.sample(first, 180, true, .18).base().state().equals(RUN), "restored packaged resource recovers on reload");
        var priorConnection = h.runtime.entityKey(first.id).connectionSession();
        h.runtime.onWorldDisconnect();
        require(h.runtime.activeEntityKey(first.id).isEmpty() && h.lifecycle.registry().size() == 0,
                "disconnect clears rule and layered owners");
        h.runtime.onPlayInit();
        require(!h.runtime.entityKey(first.id).connectionSession().equals(priorConnection), "reconnect has a fresh connection key");
        require(h.sample(first, 200, true, .065).base().acceptedSequence() == initialSequence,
                "reconnect starts fresh controller sequence for the same actor ID");
        h.runtime.retire(h.runtime.entityKey(first.id));
        require(h.sample(first, 210, true, .18).base().acceptedSequence() == initialSequence,
                "explicit runtime retirement also resets the locomotion rule state");
        h.runtime.onWorldDisconnect();
        System.out.println("Verified packaged locomotion rules via real reload prepare/apply: distinct idle/walk/run final CPU poses, "
                + "stable playback, threshold hold/hysteresis, independent upper cues and owners, no extraction I/O, reload disable/recovery, "
                + "unload, explicit retirement and disconnect/reconnect");
    }

    private static final class Actor {
        final int id;
        final Object owner = new Object();
        long sequence, cueTick;
        Actor(int id) { this.id = id; }
        void cue(long sequence, long cueTick) { this.sequence = sequence; this.cueTick = cueTick; }
    }

    private record Sample(ModelRenderSnapshot render, AnimationV2EvaluationSnapshot layers, List<AnimationV2Command> commands) {
        AnimationV2ControllerPlayhead base() { return layers.playheads().get(ExampleAnimationScene.BASE); }
        AnimationV2ControllerPlayhead upper() { return layers.playheads().get(ExampleAnimationScene.UPPER); }
    }

    private static final class Harness {
        final ClientModelRegistry models = new ClientModelRegistry();
        final ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(16);
        final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models, lifecycle);
        final ClientModelReloadListener reload = new ClientModelReloadListener(models, runtime::onActiveGeneration);
        final Object source = new Object();
        final ProceduralPosePipeline procedural = ExampleAnimationScene.procedural();
        Harness() { runtime.onPlayInit(); }

        void reload(PackagedResources resources, boolean expectedRules) {
            var state = new PreparableReloadListener.SharedState(resources);
            long oldGeneration = models.current().generationId();
            var prepared = reload.prepare(state);
            require(models.current().generationId() == oldGeneration, "prepare never publishes a half-loaded rules generation");
            require(prepared.loadedAssets().containsKey(ExampleLocomotionScene.MODEL)
                    && prepared.primaryDiagnostics().isEmpty(), "packaged model survives every optional-rule outcome");
            require(prepared.locomotionRules(ExampleLocomotionScene.MODEL).isPresent() == expectedRules,
                    "prepare reads and validates the packaged optional sidecar");
            require(prepared.globalDiagnostics().size() == (resources.malformed ? 1 : 0),
                    "malformed optional rules have one bounded diagnostic; missing/valid have none");
            int reads = resources.reads;
            reload.apply(prepared, state);
            require(models.current().generationId() == prepared.generationId()
                    && models.current().locomotionRules(ExampleLocomotionScene.MODEL).isPresent() == expectedRules,
                    "apply atomically publishes rules with the actual model generation");
            require(resources.reads == reads, "apply consumes prepared rules without additional resource I/O");
        }

        Sample sample(Actor actor, long tick, boolean grounded, double speed) {
            long generation = models.current().generationId();
            int[] captures = {0};
            var commands = runtime.captureEntityLocomotionRules(source, actor.owner, actor.id,
                    ExampleLocomotionScene.MODEL, generation, tick, ExampleAnimationScene.BASE,
                    () -> { captures[0]++; return ExampleLocomotionScene.inputs(grounded, speed); },
                    () -> runtime.captureEntityLayerCues(source, actor.owner, actor.id, ExampleLocomotionScene.MODEL,
                            generation, tick, actor.sequence == 0 ? List.of() : List.of(new BlendEntityLayerCue(
                                    ExampleAnimationScene.UPPER, ATTACK, actor.sequence, actor.cueTick, 1))));
            require(captures[0] == (models.current().locomotionRules(ExampleLocomotionScene.MODEL).isPresent() ? 1 : 0),
                    "live input callback is captured exactly once when valid rules are active");
            var handle = models.find(ExampleLocomotionScene.MODEL).orElseThrow().renderHandle();
            BlendInstanceKey instance = runtime.entityKey(actor.id);
            var input = new SkinnedAnimationRuntimeInput(ExampleLocomotionScene.MODEL, instance, tick, 0,
                    WALK, Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                    new SkinnedExtractionRequest(Transform.IDENTITY, 0x00F000F0, 0, -1,
                            RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true)));
            var result = runtime.extractLayered(input, ExampleLocomotionScene.layers(), commands,
                    ExampleAnimationScene.clipLayerWeights(tick), procedural).orElseThrow();
            var layers = runtime.layeredSnapshot(instance).orElseThrow();
            require(layers.diagnostics().stream().allMatch(diagnostic ->
                    diagnostic.code() == AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED
                            || diagnostic.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED
                            || diagnostic.code() == AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER),
                    "consumer permits only expected masked-layer and duplicate-replay diagnostics: " + layers.diagnostics());
            return new Sample(result.frame().renderSnapshot(), layers, commands);
        }
    }

    /** Final-resource lookup reads only bytes copied from the built example JAR before reload. */
    private static final class PackagedResources implements ResourceManager {
        final Map<Identifier, Resource> resources = new LinkedHashMap<>();
        final boolean malformed;
        int reads;
        PackagedResources(byte[] rules) {
            for (String path : List.of("blend_models/locomotion_actor.json", "models3d/locomotion_actor.glb", "textures/actor.png"))
                add(path, packaged(path));
            if (rules != null) add(RULE_PATH, rules);
            malformed = rules != null && !java.util.Arrays.equals(rules, packaged(RULE_PATH));
        }
        private void add(String path, byte[] bytes) {
            byte[] copy = bytes.clone();
            resources.put(Identifier.fromNamespaceAndPath(NS, path), new Resource(null, () -> {
                reads++;
                return new ByteArrayInputStream(copy);
            }));
        }
        public Set<String> getNamespaces() { return Set.of(NS); }
        public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(resources.get(id)); }
        public List<Resource> getResourceStack(Identifier id) { throw new AssertionError("final resource only"); }
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

    private static void verifyOptIn() {
        String before = System.getProperty(ExampleLocomotionScene.PROPERTY);
        try {
            System.clearProperty(ExampleLocomotionScene.PROPERTY);
            require(!ExampleLocomotionScene.enabled(), "locomotion consumer is disabled by default");
            System.setProperty(ExampleLocomotionScene.PROPERTY, "true");
            require(ExampleLocomotionScene.enabled(), "documented client JVM property enables consumer");
            System.setProperty(ExampleLocomotionScene.PROPERTY, "false");
            require(!ExampleLocomotionScene.enabled(), "false restores default actor selection");
        } finally {
            if (before == null) System.clearProperty(ExampleLocomotionScene.PROPERTY);
            else System.setProperty(ExampleLocomotionScene.PROPERTY, before);
        }
    }
    private static byte[] packaged(String path) {
        try (var input = RunnableLocomotionVerification.class.getResourceAsStream("/assets/" + NS + "/" + path)) {
            if (input == null) throw new AssertionError("missing packaged resource: " + path);
            return input.readAllBytes();
        } catch (IOException e) { throw new AssertionError("cannot read packaged resource: " + path, e); }
    }
    private static BlendAnimationKey animation(String name) { return BlendAnimationKey.parse(NS + ":" + name); }
    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 1e-8; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
