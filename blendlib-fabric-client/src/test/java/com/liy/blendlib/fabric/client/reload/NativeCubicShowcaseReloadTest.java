package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.*;
import com.liy.blendlib.fabric.client.render.*;
import com.liy.blendlib.core.model.Transform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.*;
import org.junit.jupiter.api.Test;

/** The actual Blender export must survive normal resource reload beside an unchanged strict-v1 model. */
class NativeCubicShowcaseReloadTest {
    static final BlendModelKey CUBIC = BlendModelKey.parse("native_cubic:eased_actor");
    static final BlendModelKey OLD = BlendModelKey.parse("blendlib_showcase:showcase_animation/showcase_actor");
    static final BlendAnimationKey WAVE = BlendAnimationKey.parse("native_cubic:wave");
    static final BlendResourceId TIP = BlendResourceId.parse("native_cubic:tip");
    static final List<String> CUBIC_FILES = List.of("blend_models/eased_actor.json", "models3d/eased_actor.glb",
            "textures/blendlib/eased_actor__cubicsurface.png");

    @Test void packagedResourcesAreExactExporterCopiesAndCoexistWithStrictV1ThroughProductionReload() throws IOException {
        Path root = root();
        for (String file : CUBIC_FILES) {
            byte[] exported = Files.readAllBytes(root.resolve("test-assets/native-cubic/exported/assets/native_cubic").resolve(file));
            for (String packaged : List.of("blendlib-showcase/src/main/resources/assets/native_cubic",
                    "versions/modern/showcase/src/main/resources/assets/native_cubic")) {
                assertArrayEquals(exported, Files.readAllBytes(root.resolve(packaged).resolve(file)), packaged + "/" + file);
            }
        }
        var registry = new ClientModelRegistry();
        var runtime = new SkinnedAnimationRuntime(registry, new ClientAnimationLifecycleBridge(16));
        runtime.onPlayInit();
        var listener = new ClientModelReloadListener(registry);
        var state = new PreparableReloadListener.SharedState(resources());
        var first = listener.prepare(state);
        assertTrue(first.primaryDiagnostics().isEmpty(), () -> first.primaryDiagnostics().toString());
        assertEquals(Set.of(CUBIC, OLD), first.loadedAssets().keySet());
        assertEquals(ModelProfile.SKINNED_V1, first.loadedAssets().get(OLD).profile());
        var asset = first.loadedAssets().get(CUBIC);
        assertEquals(ModelProfile.SKINNED_CUBIC_V1, asset.profile());
        assertTrue(asset.clips().stream().flatMap(clip -> clip.channels().stream())
                .anyMatch(channel -> channel.interpolation() == Interpolation.CUBICSPLINE));
        listener.apply(first, state);
        var handle = assertInstanceOf(SkinnedRenderHandle.class, registry.find(CUBIC).orElseThrow().renderHandle());
        assertFalse(handle.skinnedPrimitives().isEmpty());
        assertEquals(1.5, runtime.animationDuration(CUBIC, WAVE).orElseThrow(), 1e-6);
        var rest = runtime.extract(input(runtime,handle,0)).orElseThrow();
        var eased = runtime.extract(input(runtime,handle,5)).orElseThrow();
        assertNotEquals(rest.frame().socketTransform(TIP), eased.frame().socketTransform(TIP));
        assertTrue(eased.frame().socketTransform(TIP).isPresent());
        var second = listener.prepare(state);
        listener.apply(second,state);
        var replacement = assertInstanceOf(SkinnedRenderHandle.class, registry.find(CUBIC).orElseThrow().renderHandle());
        assertNotSame(handle,replacement);
        assertEquals(second.generationId(), replacement.generation());
        assertEquals(second.generationId(), runtime.extract(input(runtime,replacement,5)).orElseThrow().frame().renderSnapshot().generation());
        listener.apply(first,state);
        assertSame(replacement,registry.find(CUBIC).orElseThrow().renderHandle(),"stale prepared generation must not replace active cubic data");
        assertEquals(1,registry.reloadRetentionMetrics().staleGenerationCount());
    }

    private static SkinnedAnimationRuntimeInput input(SkinnedAnimationRuntime runtime,SkinnedRenderHandle handle,long tick) {
        return new SkinnedAnimationRuntimeInput(CUBIC,runtime.entityKey(72),tick,0,WAVE,Optional.empty(),
                AnimationUpdateBucket.VISIBLE_NEAR,new SkinnedExtractionRequest(Transform.IDENTITY,0,0,-1,
                RenderVisibility.VISIBLE,new CullingMetadata(handle.bounds(),true)));
    }
    private static ResourceManager resources() throws IOException {
        var files = new LinkedHashMap<Identifier,Resource>();
        for (String file : CUBIC_FILES) add(files,"native_cubic",file);
        for (String file : List.of("blend_models/showcase_animation/showcase_actor.json",
                "models3d/showcase_animation/showcase_actor.glb",
                "textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png")) add(files,"blendlib_showcase",file);
        return new ResourceManager() {
            public Set<String> getNamespaces() { return Set.of("native_cubic","blendlib_showcase"); }
            public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(files.get(id)); }
            public List<Resource> getResourceStack(Identifier id) { throw new AssertionError("final resource selection only"); }
            public Map<Identifier,Resource> listResources(String prefix,Predicate<Identifier> filter) {
                var selected = new LinkedHashMap<Identifier,Resource>();
                files.forEach((id,value) -> { if (id.getPath().startsWith(prefix+"/") && filter.test(id)) selected.put(id,value); });
                return selected;
            }
            public Map<Identifier,List<Resource>> listResourceStacks(String prefix,Predicate<Identifier> filter) { throw new AssertionError("final resource selection only"); }
            public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }
    private static void add(Map<Identifier,Resource> files,String namespace,String file) throws IOException {
        byte[] bytes = Files.readAllBytes(root().resolve("blendlib-showcase/src/main/resources/assets").resolve(namespace).resolve(file));
        files.put(Identifier.fromNamespaceAndPath(namespace,file),new Resource(null,() -> new ByteArrayInputStream(bytes)));
    }
    private static Path root() { return Path.of(System.getProperty("blendlib.projectDir")).getParent(); }
}
