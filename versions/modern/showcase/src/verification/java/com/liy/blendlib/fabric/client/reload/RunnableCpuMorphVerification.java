package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.examples.runnable.*;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.api.*;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.render.*;
import java.io.*;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.*;

/** Production reload and actual runnable control/scene logic, using only the built consumer JAR. */
public final class RunnableCpuMorphVerification {
    private static final BlendModelKey MODEL = ExampleCpuMorphControls.MODEL;
    private static final BlendResourceId BLINK = id("blink"), SMILE = id("smile"), BREATH = id("breath");
    private RunnableCpuMorphVerification() { }

    public static void verify() {
        var h = new Harness();
        var resources = new PackagedResources();
        var shared = new PreparableReloadListener.SharedState(resources);
        var prepared = h.reload.prepare(shared);
        require(prepared.primaryDiagnostics().isEmpty() && prepared.globalDiagnostics().isEmpty(),
                "CPU morph packaged resources prepare without diagnostics: " + prepared.primaryDiagnostics());
        var asset = prepared.loadedAssets().get(MODEL);
        require(asset != null && asset.profile() == ModelProfile.SKINNED_MORPH_CPU_V1, "explicit format2 CPU morph profile");
        require(asset.primitives().size() == 2 && asset.materials().keySet().equals(Set.of("MorphSurface", "FaceDetails")),
                "two real authored materials, not a descriptor-only appearance demo");
        var nod = asset.clips().stream().filter(clip -> clip.name().equals("Nod")).findFirst().orElseThrow();
        require(!nod.hasMorphChannels() && nod.channels().stream().allMatch(channel -> channel.interpolation() == Interpolation.CUBICSPLINE),
                "native cubic nod has no fake weight track");
        for (String name : List.of("Blink", "Smile", "Breath")) {
            var clip = asset.clips().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
            require(clip.channels().isEmpty() && clip.hasMorphChannels() && clip.durationSeconds() > 0,
                    name + " is a genuine weight-only clip with a real duration");
            require(clip.morphChannels().stream().allMatch(channel -> channel.interpolation()
                    == (name.equals("Blink") ? Interpolation.STEP : Interpolation.LINEAR)),
                    name + " preserves its actual supported weight interpolation");
        }
        require(asset.clips().size() == 4 && asset.clips().stream().allMatch(clip -> near(clip.durationSeconds(),1.5)),
                "all four actual consumer clips preserve their 1.5-second authored duration");
        var defaults = MorphWeights.defaults(asset.morphBindings());
        require(defaults.weight(BLINK) == 0 && defaults.weight(SMILE) == 0 && near(defaults.weight(BREATH), .15),
                "nonzero Breath default remains distinct from zero/reset");
        require(asset.morphBindings().bindings().getFirst().targetNames().equals(List.of("Blink", "Smile", "Breath")),
                "exact authored mesh-local target order");
        h.reload.apply(prepared, shared);
        require(X7GenerationResourceBridge.authoritativeInventory(h.models.current()).keysInDeterministicOrder()
                .stream().noneMatch(key -> key.modelId().equals(MODEL.value())), "GPU inventory excludes CPU morph generations");
        verifyStaticMorph(h, prepared);
        int reads = resources.reads;
        var a = h.controls(new ExampleCpuMorphControls(), 42);
        var b = h.controls(new ExampleCpuMorphControls(), 43);
        var rest = h.sample(a, 42, 0);
        var secondRest = h.sample(b, 43, 0);
        require(positions(rest).equals(positions(secondRest)), "two new actors begin at the same authored default");
        var eased = h.sample(a, 42, 5);
        require(!positions(eased).equals(positions(rest)), "off-key cubic nod deforms real CPU geometry");
        require(!eased.frame().socketTransform(ExampleCpuMorphControls.FACE).equals(rest.frame().socketTransform(ExampleCpuMorphControls.FACE)),
                "final bone socket follows the cubic nod");
        for (int tick = 6; tick <= 12; tick++) h.sample(a, 42, tick);
        require(a.visualEvents() == 1 && id("nod_apex").equals(a.lastEvent()), "authored nod event reaches the actual consumer recorder: " + a.status() + "; time=" + h.lifecycle.registry().find(h.runtime.entityKey(42)).orElseThrow().controller().currentTimeSeconds());

        // The public renderer callback emits a fresh immutable batch even while pose cadence is held.
        var atSameTime = h.at(a, 42, 12, .25);
        a.set("smile", -1); a.set("blink", 1); a.set("breath", 1);
        var frozenBatch = a.capture();
        var manual = h.at(a, 42, 12, .25);
        var retained = positions(manual);
        require(!retained.equals(positions(atSameTime)), "signed named controls actually change vertices");
        var other = h.at(b, 43, 12, .25);
        require(positions(other).equals(positions(atSameTime)), "actor A controls never leak to actor B");
        a.reset();
        var reset = h.at(a, 42, 12, .25);
        require(positions(reset).equals(positions(atSameTime)), "reset omits overrides and resumes authored .15 Breath default");
        require(frozenBatch.values().get(SMILE) == -1 && retained.equals(positions(manual)), "old batches and CPU captures remain immutable");
        a.set("blink", 0); a.set("smile", 0); a.set("breath", 0);
        var zero = h.at(a, 42, 12, .25);
        require(!positions(zero).equals(positions(reset)), "zero is observably different from nonzero authored default");
        RunnableCpuMorphRenderVerification.verify(zero.frame().renderSnapshot());
        a.reset();

        for (String clip : List.of("blink", "smile", "breath")) {
            a.selectClip(clip);
            var beginning = h.at(a, 42, 12, 0);
            var middle = h.at(a, 42, 12, clip.equals("blink") ? .25 : .5);
            require(!positions(beginning).equals(positions(middle)), clip + " weight-only animation changes CPU vertices");
            require(beginning.frame().socketTransform(ExampleCpuMorphControls.FACE).equals(middle.frame().socketTransform(ExampleCpuMorphControls.FACE)),
                    "weight-only deformation does not fabricate bone socket motion");
            a.set(clip, clip.equals("smile") ? -1 : 0);
            var held = h.at(a, 42, 12, clip.equals("blink") ? .25 : .5);
            require(!positions(held).equals(positions(middle)), "manual " + clip + " is applied after the selected clip");
            a.reset();
            require(positions(h.at(a, 42, 12, clip.equals("blink") ? .25 : .5)).equals(positions(middle)),
                    "omitting " + clip + " resumes the selected animation next frame");
        }
        verifyInvalidEdits(h, a);
        a.selectClip("nod"); h.sample(a, 42, 20);
        a.selectClip("smile");
        var transitionStart = h.sample(a, 42, 21);
        require(transitionStart.advance().state().equals(a.animation()), "consumer clip selection enters the full-body controller");
        var transitioned = h.sample(a, 42, 27);
        require(transitioned.advance().timeSeconds() > 0 && !positions(transitioned).equals(positions(transitionStart)),
                "ordinary controller transition advances weight-only state");

        // Every signed interval corner, sampled on the real cubic motion, is inside prepared bounds.
        a.selectClip("nod");
        for (float blink : new float[]{0,1}) for (float smile : new float[]{-1,1}) for (float breath : new float[]{-.5F,1}) {
            a.set("blink",blink); a.set("smile",smile); a.set("breath",breath);
            for (double seconds : new double[]{0,.125,.25,.5,.9,1.25,1.5}) verifyBoundsAndAssembly(h, h.at(a,42,27,seconds));
        }
        require(resources.reads == reads, "selection, extraction, sockets, appearances and culling perform no resource reads");
        var captured = manual.frame().renderSnapshot();
        var hidden = captured.withMaterialAppearance(ExampleCpuMorphScene.appearance("morph_hide_details"));
        int details = hidden.handle().materialSlots().indexOf("FaceDetails");
        require(!RunnableAttachmentRenderVerification.appearance(hidden, details).visible()
                && RunnableAttachmentRenderVerification.appearance(captured, details).visible(), "actual consumer material selector is snapshot-local");
        var tinted = captured.withMaterialAppearance(ExampleCpuMorphScene.appearance("morph_amber"));
        int surface = tinted.handle().materialSlots().indexOf("MorphSurface");
        require(RunnableAttachmentRenderVerification.appearance(tinted,surface).rgbTint() == 0xFFB640, "actual surface tint selector applies");

        b.set("breath",.65F); b.selectClip("breath");
        var second = h.reload.prepare(shared); h.reload.apply(second,shared);
        var afterReload = h.sample(a,42,50);
        require(a.capture().isEmpty() && a.animation().equals(ExampleCpuMorphControls.NOD)
                && b.capture().isEmpty() && b.animation().equals(ExampleCpuMorphControls.NOD), "generation fence clears all live actor control owners");
        require(afterReload.frame().renderSnapshot().generation() != captured.generation(), "reload binds the current morph generation");
        RunnableCpuMorphRenderVerification.rejectStale(captured, h.handle());
        h.reload.apply(prepared,shared);
        require(h.models.current().generationId() == second.generationId(), "stale prepared generation cannot replace active data");
        require(retained.equals(positions(manual)), "retained CPU capture stays immutable across resource reload");
        a.set("blink",1); a.selectClip("blink");
        h.runtime.onEntityUnload(42); h.owners.remove(a);
        var replacement = h.controls(new ExampleCpuMorphControls(),42);
        require(replacement.capture().isEmpty() && a.capture().isEmpty(), "unload and numeric ID reuse cannot inherit controls");
        require(h.sample(replacement,42,100).advance().timeSeconds() == 0, "reused ID starts an independent playback origin");
        replacement.set("smile",1); h.controls(b,43).set("breath",1);
        h.runtime.onWorldDisconnect(); h.owners.clear();
        require(replacement.capture().isEmpty() && b.capture().isEmpty() && h.runtime.activeEntityKey(42).isEmpty(),
                "disconnect clears controls, event observations and active clocks");
        h.runtime.onPlayInit();
        require(h.sample(replacement,42,200).advance().timeSeconds() == 0, "reconnect starts a fresh generation/session-bound owner");
        require(retained.equals(positions(manual)), "disconnect cannot mutate retained CPU geometry");
        System.out.println("Verified packaged CPU morph consumer: real cubic/weight-only clips, object-owned controls/reset, independent actors, events/socket attachment/materials/bounds, zero-weight CPU-only provenance, immutable captures, stale reload and lifecycle; native Minecraft graphics remains unverified");
    }

    private static void verifyStaticMorph(Harness h, PreparedModelGeneration prepared) {
        var key = BlendModelKey.parse("cpu_morph:static_face_actor");
        var asset = prepared.loadedAssets().get(key);
        require(asset != null && asset.animationDefinition() == null && asset.clips().isEmpty(),
                "static example has no descriptor states or GLB animation clips");
        var model = h.lookup.resolve(key);
        var request = new SkinnedExtractionRequest(Transform.IDENTITY, 0xF000F0, 0, 0xFFFFFFFF,
                RenderVisibility.VISIBLE, new CullingMetadata(model.renderHandle().bounds(), true));
        long revision = h.runtime.captureExtractionLifecycleRevision();
        var owner = h.runtime.entityKey(91);
        var rest = h.runtime.extractStaticMorph(key, model.generationId(), owner, revision,
                MorphFrameOverrides.empty(), request).orElseThrow().renderSnapshot();
        var manual = h.runtime.extractStaticMorph(key, model.generationId(), owner, revision,
                new MorphFrameOverrides(Map.of(BLINK, 1F, SMILE, 1F)), request).orElseThrow().renderSnapshot();
        require(!RunnableAttachmentRenderVerification.positions(rest).equals(RunnableAttachmentRenderVerification.positions(manual)),
                "animation-free blink and smile deform actual packaged geometry");
        var reset = h.runtime.extractStaticMorph(key, model.generationId(), owner, revision,
                MorphFrameOverrides.empty(), request).orElseThrow().renderSnapshot();
        require(RunnableAttachmentRenderVerification.positions(rest).equals(RunnableAttachmentRenderVerification.positions(reset)),
                "omitted static controls return to authored defaults");
        require(h.lifecycle.registry().find(owner).isEmpty(), "static extraction creates no controller");
        RunnableCpuMorphRenderVerification.verify(manual);
        h.runtime.onEntityUnload(91);
        require(h.runtime.extractStaticMorph(key, model.generationId(), owner, revision,
                MorphFrameOverrides.empty(), request).isEmpty(), "unload rejects a previously captured static callback");
    }

    private static void verifyInvalidEdits(Harness h, ExampleCpuMorphControls controls) {
        controls.set("smile",.5F);
        var before = controls.capture().values();
        for (Runnable invalid : List.<Runnable>of(() -> controls.set("blink",-1), () -> controls.set("breath",-.6F),
                () -> controls.set("smile",Float.NaN), () -> controls.set("missing",0), () -> controls.selectClip("missing"))) {
            rejects(invalid); require(controls.capture().values().equals(before), "invalid consumer edit is atomic");
        }
        var controller = h.lifecycle.registry().find(h.runtime.entityKey(42)).orElseThrow().controller();
        double time = controller.currentTimeSeconds(); var state = controller.currentState();
        rejects(() -> h.runtime.extractMorph(h.input(controls,42,200),
                new MorphFrameOverrides(Map.of(SMILE,1F,id("missing"),0F)),null));
        require(controller.currentTimeSeconds() == time && controller.currentState().equals(state), "invalid runtime batch cannot advance state or clock");
        controls.reset();
    }

    private static void verifyBoundsAndAssembly(Harness h, SkinnedAnimationRuntimeResult sample) {
        var render = sample.frame().renderSnapshot();
        Bounds bounds = render.handle().bounds();
        for (var p : positions(sample)) require(p.x() >= bounds.min().x() && p.x() <= bounds.max().x()
                && p.y() >= bounds.min().y() && p.y() <= bounds.max().y() && p.z() >= bounds.min().z() && p.z() <= bounds.max().z(),
                "full signed intervals and cubic motion remain inside conservative model bounds");
        RunnableCpuMorphRenderVerification.verify(render);
        var request = new BlendEntitySnapshotRequest(MODEL,0,0xF000F0,0,0,0,0,27,true,0);
        var sockets = BlendEntitySockets.capture(request,sample.frame());
        var attachments = ExampleCpuMorphScene.attachments(h.lookup,request,sockets,0);
        require(attachments.size() == 1, "actual consumer attaches its gold marker to final face socket");
        var e = ExampleCpuMorphScene.ENVELOPE;
        for (var p : RunnableAttachmentRenderVerification.entityPositions(render.withAttachments(attachments)))
            require(p.x() >= e.minX() && p.x() <= e.maxX() && p.y() >= e.minY() && p.y() <= e.maxY()
                    && p.z() >= e.minZ() && p.z() <= e.maxZ(), "configured assembly envelope contains actual morphed body and socket marker");
    }

    private static final class Harness {
        final ClientModelRegistry models = new ClientModelRegistry();
        final ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(16);
        final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models,lifecycle);
        final ClientModelReloadListener reload = new ClientModelReloadListener(models,runtime::onActiveGeneration);
        final ExampleCpuMorphOwners owners = new ExampleCpuMorphOwners();
        final ClientModelLookup lookup = new ClientModelLookup() {
            public ClientRegistryView snapshot() {
                var views = new LinkedHashMap<BlendModelKey,ClientModelView>();
                models.current().handles().keySet().forEach(key -> views.put(key,resolve(key)));
                return new ClientRegistryView(models.current().generationId(),views,List.of());
            }
            public ClientModelView resolve(BlendModelKey key) {
                var generation = models.current(); var found = generation.find(key);
                var handle = found.orElseGet(() -> MissingModelHandle.notDiscovered(key,generation.generationId()));
                return new ClientModelView(key,generation.generationId(),found.isPresent(),handle.renderHandle(),Optional.empty());
            }
        };
        Harness() { runtime.onPlayInit(); }
        SkinnedRenderHandle handle() { return (SkinnedRenderHandle) models.find(MODEL).orElseThrow().renderHandle(); }
        ExampleCpuMorphControls controls(ExampleCpuMorphControls owner,int id) {
            return owners.use(owner,models.current().generationId(),runtime.entityKey(id).connectionSession());
        }
        SkinnedAnimationRuntimeInput input(ExampleCpuMorphControls owner,int id,long tick) {
            var state = controls(owner,id);
            return new SkinnedAnimationRuntimeInput(MODEL,runtime.entityKey(id),tick,0,state.animation(),Optional.empty(),
                    AnimationUpdateBucket.VISIBLE_NEAR,new SkinnedExtractionRequest(Transform.IDENTITY,0xF000F0,0,-1,
                    RenderVisibility.VISIBLE,new CullingMetadata(handle().bounds(),true)));
        }
        SkinnedAnimationRuntimeResult sample(ExampleCpuMorphControls owner,int id,long tick) {
            var result = runtime.extractMorph(input(owner,id,tick),owner.capture(),null).orElseThrow();
            result.advance().visualEvents().forEach(event -> owner.event(event.eventKey())); return result;
        }
        SkinnedAnimationRuntimeResult at(ExampleCpuMorphControls owner,int id,long tick,double seconds) {
            return runtime.extractMorphClipAt(input(owner,id,tick),seconds,owner.capture(),null).orElseThrow();
        }
    }

    private static final class PackagedResources implements ResourceManager {
        private final Map<Identifier,Resource> files = new LinkedHashMap<>();
        int reads;
        PackagedResources() {
            for (String file : List.of("blend_models/face_actor.json","models3d/face_actor.glb",
                    "blend_models/static_face_actor.json","models3d/static_face_actor.glb",
                    "textures/blendlib/face_actor__morphsurface.png","textures/blendlib/face_actor__facedetails.png")) add("cpu_morph",file);
            for (String file : List.of("blend_models/marker.json","models3d/marker.glb","textures/marker.png")) add("blendlib_runnable_examples",file);
        }
        private void add(String namespace,String file) {
            byte[] bytes;
            try (var input = RunnableCpuMorphVerification.class.getResourceAsStream("/assets/"+namespace+"/"+file)) {
                if (input == null) throw new AssertionError("missing packaged CPU morph dependency: "+namespace+":"+file);
                bytes = input.readAllBytes();
            } catch (IOException e) { throw new AssertionError(e); }
            files.put(Identifier.fromNamespaceAndPath(namespace,file),new Resource(null,() -> { reads++; return new ByteArrayInputStream(bytes); }));
        }
        public Set<String> getNamespaces() { return Set.of("cpu_morph","blendlib_runnable_examples"); }
        public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(files.get(id)); }
        public List<Resource> getResourceStack(Identifier id) { throw new AssertionError("final selection only"); }
        public Map<Identifier,Resource> listResources(String prefix,ResourceManager.Selector filter) {
            var selected = new LinkedHashMap<Identifier,Resource>();
            files.forEach((id,resource) -> { if(id.getPath().startsWith(prefix+"/") && filter.isIncluded(id)) selected.put(id,resource); });
            return selected;
        }
        public Map<Identifier,List<Resource>> listResourceStacks(String prefix,ResourceManager.Selector filter) { throw new AssertionError("final selection only"); }
        public Stream<PackResources> listPacks() { return Stream.empty(); }
    }
    private static List<Vec3> positions(SkinnedAnimationRuntimeResult sample) { return RunnableAttachmentRenderVerification.positions(sample.frame().renderSnapshot()); }
    private static BlendResourceId id(String name) { return BlendResourceId.parse("cpu_morph:"+name); }
    private static boolean near(double a,double b) { return Math.abs(a-b) < 1e-6; }
    private static void rejects(Runnable action) { try { action.run(); throw new AssertionError("expected rejected invalid input"); } catch (IllegalArgumentException expected) { } }
    private static void require(boolean valid,String message) { if(!valid) throw new AssertionError(message); }
}
