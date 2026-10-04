package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.examples.runnable.ExampleNativeCubicClient;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.render.*;
import java.io.*;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.*;

/** Loads only the packaged preview assets and uses the actual consumer's layer definition. */
public final class RunnableNativeCubicVerification {
    private static final BlendModelKey MODEL = ExampleNativeCubicClient.MODEL;
    private static final BlendResourceId LAYER = ExampleNativeCubicClient.LAYER;
    private RunnableNativeCubicVerification() { }

    public static void verify() {
        var registry = new ClientModelRegistry();
        var runtime = new SkinnedAnimationRuntime(registry, new ClientAnimationLifecycleBridge(16));
        runtime.onPlayInit();
        var resources = new PackagedResources();
        var shared = new PreparableReloadListener.SharedState(resources);
        var reload = new ClientModelReloadListener(registry, runtime::onActiveGeneration);
        var prepared = reload.prepare(shared);
        require(prepared.primaryDiagnostics().isEmpty() && prepared.globalDiagnostics().isEmpty(),
                "native cubic packaged resource must prepare without diagnostics: " + prepared.primaryDiagnostics());
        var asset = prepared.loadedAssets().get(MODEL);
        require(asset != null && asset.profile() == ModelProfile.SKINNED_CUBIC_V1, "explicit cubic runtime profile");
        require(asset.clips().size() == 1 && asset.clips().getFirst().channels().stream()
                .allMatch(channel -> channel.interpolation() == Interpolation.CUBICSPLINE), "real sparse Blender cubic channels");
        reload.apply(prepared, shared);
        require(registry.find(MODEL).orElseThrow().renderHandle() instanceof SkinnedRenderHandle, "ordinary CPU skinned handle");
        var events = new ArrayList<LayerAnimationVisualEvent>();
        var rest = sample(runtime,registry,42,0,List.of(),events);
        var first = sample(runtime,registry,42,5,List.of(),events);
        var frozen = RunnableAttachmentRenderVerification.positions(first.frame().renderSnapshot());
        require(!frozen.equals(RunnableAttachmentRenderVerification.positions(rest.frame().renderSnapshot())), "off-key native easing deforms CPU geometry");
        require(!first.frame().socketTransform(ExampleNativeCubicClient.TIP).equals(rest.frame().socketTransform(ExampleNativeCubicClient.TIP)), "final socket follows native pose");
        var independent = sample(runtime,registry,43,5,List.of(),events);
        require(RunnableAttachmentRenderVerification.positions(independent.frame().renderSnapshot())
                .equals(RunnableAttachmentRenderVerification.positions(rest.frame().renderSnapshot())), "new actor has independent playback origin");
        int reads = resources.reads;
        for (int tick = 6; tick <= 29; tick++) {
            var frame = sample(runtime,registry,42,tick,List.of(),events);
            Bounds bound = frame.frame().renderSnapshot().handle().bounds();
            for (Vec3 p : RunnableAttachmentRenderVerification.positions(frame.frame().renderSnapshot())) {
                require(p.x() >= bound.min().x() && p.x() <= bound.max().x()
                        && p.y() >= bound.min().y() && p.y() <= bound.max().y()
                        && p.z() >= bound.min().z() && p.z() <= bound.max().z(), "native cubic CPU output remains within prepared bounds");
            }
        }
        require(events.stream().anyMatch(event -> event.event().eventKey().value().equals("native_cubic:apex")), "real descriptor event delivered through normal layer callback");
        require(resources.reads == reads, "native runtime evaluation never rereads packaged resources");
        var selected = first.frame().renderSnapshot().withMaterialAppearance(Map.of("CubicSurface", new MaterialSlotAppearance(0x4080FF,false)));
        require(!RunnableAttachmentRenderVerification.appearance(selected,0).visible(), "normal material appearance works on cubic capture");
        require(RunnableAttachmentRenderVerification.appearance(first.frame().renderSnapshot(),0).visible(), "appearance selection preserves other captures");
        var oneShot = List.of(new AnimationV2Command(LAYER,ExampleNativeCubicClient.ONCE,7,0,1));
        sample(runtime,registry,42,30,oneShot,events);
        sample(runtime,registry,42,62,oneShot,events);
        require(runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(LAYER).state()
                .equals(ExampleNativeCubicClient.WAVE), "authored once state returns to wave");
        var second = reload.prepare(shared);
        reload.apply(second,shared);
        var replaced = sample(runtime,registry,42,62,List.of(),events);
        require(replaced.frame().renderSnapshot().generation() != first.frame().renderSnapshot().generation(), "reload rebinds cubic model generation");
        require(frozen.equals(RunnableAttachmentRenderVerification.positions(first.frame().renderSnapshot())), "old cubic CPU capture remains immutable after reload");
        reload.apply(prepared,shared);
        require(registry.current().generationId() == second.generationId(), "stale native generation cannot replace active data");
        runtime.onEntityUnload(42);
        sample(runtime,registry,42,100,List.of(),events);
        require(runtime.layeredSnapshot(runtime.entityKey(42)).orElseThrow().playheads().get(LAYER).timeSeconds() == 0, "entity unload resets native playback");
        runtime.onWorldDisconnect();
        require(runtime.activeEntityKey(42).isEmpty() && runtime.activeEntityKey(43).isEmpty(), "disconnect retires native clocks");
        System.out.println("Verified packaged native cubic preview: actual Blender curves, normal CPU geometry/bounds, final socket, layered events and one-shot return, independent actors, material appearance, immutable captures, reload and lifecycle; native graphics remains unverified");
    }

    private static SkinnedAnimationRuntimeResult sample(SkinnedAnimationRuntime runtime,ClientModelRegistry registry,
            int entity,long tick,List<AnimationV2Command> commands,List<LayerAnimationVisualEvent> events) {
        var handle = registry.find(MODEL).orElseThrow().renderHandle();
        var input = new SkinnedAnimationRuntimeInput(MODEL,runtime.entityKey(entity),tick,0,ExampleNativeCubicClient.WAVE,
                Optional.empty(),AnimationUpdateBucket.VISIBLE_NEAR,new SkinnedExtractionRequest(Transform.IDENTITY,
                0xF000F0,0,-1,RenderVisibility.VISIBLE,new CullingMetadata(handle.bounds(),true)));
        return runtime.extractLayered(input,ExampleNativeCubicClient.LAYERS,commands,
                AnimationV2LayerWeights.empty(),null,events::add).orElseThrow();
    }
    private static final class PackagedResources implements ResourceManager {
        private final Map<Identifier,Resource> files = new LinkedHashMap<>();
        int reads;
        PackagedResources() {
            for (String file : List.of("blend_models/eased_actor.json","models3d/eased_actor.glb",
                    "textures/blendlib/eased_actor__cubicsurface.png")) {
                byte[] bytes;
                try (var input = RunnableNativeCubicVerification.class.getResourceAsStream("/assets/native_cubic/"+file)) {
                    if (input == null) throw new AssertionError("missing packaged native cubic asset: "+file);
                    bytes = input.readAllBytes();
                } catch (IOException e) { throw new AssertionError(e); }
                files.put(Identifier.fromNamespaceAndPath("native_cubic",file),new Resource(null,() -> { reads++; return new ByteArrayInputStream(bytes); }));
            }
        }
        public Set<String> getNamespaces() { return Set.of("native_cubic"); }
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
    private static void require(boolean valid,String message) { if(!valid) throw new AssertionError(message); }
}
