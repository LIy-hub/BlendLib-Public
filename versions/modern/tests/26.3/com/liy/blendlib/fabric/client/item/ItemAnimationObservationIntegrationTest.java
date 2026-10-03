package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs on the shipped 26.3 Minecraft/Fabric classpath with real stacks, extraction and publication. */
class ItemAnimationObservationIntegrationTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("observation_test:item");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("observation_test:idle");
    private static final BlendAnimationKey MISSING = BlendAnimationKey.parse("observation_test:missing");

    @BeforeAll static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void actualStacksKeepHistoricalSamplesAcrossExtractionControlsReloadAndRetirement() {
        var registry = new ClientModelRegistry();
        var runtime = new SkinnedAnimationRuntime(registry, new ClientAnimationLifecycleBridge(512));
        BlendLibClientServices.initialize(registry, (snapshot, context) -> fail("observation must never submit"), runtime);
        runtime.onPlayInit();
        var binding = new BlendLibItemBinding(Identifier.withDefaultNamespace("stick"), MODEL,
                Identifier.withDefaultNamespace("item/stick"));
        BlendLibItemAnimations.register(binding, IDLE);
        var first = fixture(1);
        publish(registry, first);
        var renderer = new BlendLibItemSpecialRenderer(binding);
        ItemStack stack = stack(Items.STICK);
        ItemStack copy = stack.copy();
        ItemStack unregistered = stack(Items.DIRT);
        try {
            var emptyMetrics = runtime.measurementSnapshot();
            assertTrue(BlendLibItemAnimations.observe(ItemStack.EMPTY).isEmpty());
            assertTrue(BlendLibItemAnimations.observe(stack).isEmpty());
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty());
            assertTrue(BlendLibItemAnimations.observe(unregistered).isEmpty());
            assertThrows(NullPointerException.class, () -> BlendLibItemAnimations.observe(null));
            assertEquals(emptyMetrics, runtime.measurementSnapshot(), "unseen queries create no runtime state");

            var playback = BlendLibItemAnimations.playback(stack).pause().seek(0.25);
            var unsampled = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertEquals(0.25, unsampled.storedSeconds());
            assertTrue(unsampled.lastSample().isEmpty());
            assertFalse(unsampled.sampleCurrentGeneration());
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty(), "ItemStack.copy is a new identity");
            var guiArgument = renderer.extractArgument(stack);
            assertNotNull(guiArgument.animatedSnapshot());
            var before = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertEquals(new ItemAnimationObservation.Sample(MODEL, IDLE, 1, 0.25, 1), before.lastSample().orElseThrow());
            assertTrue(before.sampleCurrentGeneration());
            var sampledMetrics = runtime.measurementSnapshot();
            for (int index = 0; index < 10; index++) assertEquals(before, BlendLibItemAnimations.observe(stack).orElseThrow());
            assertEquals(sampledMetrics, runtime.measurementSnapshot(), "observation does not sample or retire caches");
            var handArgument = renderer.extractArgument(stack);
            assertNotNull(handArgument.animatedSnapshot());
            assertEquals(before, BlendLibItemAnimations.observe(stack).orElseThrow(), "same stack shares GUI/hand extraction state");
            assertSame(playback, BlendLibItemAnimations.playback(stack));
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty());
            BlendLibItemAnimations.playback(copy).pause().seek(0.75);
            renderer.extractArgument(copy);
            assertEquals(0.75, BlendLibItemAnimations.observe(copy).orElseThrow().lastSample().orElseThrow().seconds());
            assertEquals(0.25, BlendLibItemAnimations.observe(stack).orElseThrow().lastSample().orElseThrow().seconds());

            playback.seek(12);
            var sought = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertEquals(12, sought.storedSeconds());
            assertFalse(sought.playing());
            assertEquals(before.lastSample(), sought.lastSample());
            playback.play(MISSING, ItemAnimationPlayback.Mode.HOLD).pause();
            assertThrows(IllegalArgumentException.class, () -> renderer.extractArgument(stack),
                    "undeclared-state validation currently throws before extraction");
            var failed = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertEquals(MISSING, failed.animation());
            assertEquals(before.lastSample(), failed.lastSample(), "failed extraction does not publish a successful sample");
            playback.play(IDLE, ItemAnimationPlayback.Mode.LOOP).pause().seek(0.5);

            var replacement = fixture(2);
            publish(registry, replacement);
            var preCallbackMetrics = runtime.measurementSnapshot();
            var stale = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertFalse(stale.sampleCurrentGeneration(), "registry publication invalidates sample before runtime callback");
            assertEquals(before.lastSample(), stale.lastSample());
            assertEquals(0.5, stale.storedSeconds());
            assertEquals(preCallbackMetrics, runtime.measurementSnapshot(), "stale lookup does not retire old runtime state");
            runtime.onActiveGeneration(2);
            assertSame(playback, BlendLibItemAnimations.playback(stack));
            assertFalse(BlendLibItemAnimations.observe(stack).orElseThrow().sampleCurrentGeneration());
            assertEquals(2, renderer.extractArgument(stack).snapshot(0, 0).generation());
            var reloaded = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertTrue(reloaded.sampleCurrentGeneration());
            assertEquals(new ItemAnimationObservation.Sample(MODEL, IDLE, 2, 0.5, 1), reloaded.lastSample().orElseThrow());
            assertEquals(1, before.lastSample().orElseThrow().generation());
            assertTrue(before.sampleCurrentGeneration(), "previous observation remains immutable after reload");

            registry.publish(ModelRegistryGeneration.empty(3));
            assertFalse(BlendLibItemAnimations.observe(stack).orElseThrow().sampleCurrentGeneration());
            assertNull(renderer.extractArgument(stack).animatedSnapshot(), "a model absent after reload yields no extraction");
            assertEquals(reloaded.lastSample(), BlendLibItemAnimations.observe(stack).orElseThrow().lastSample());

            BlendLibItemAnimations.release(stack);
            assertTrue(BlendLibItemAnimations.observe(stack).isEmpty());
            assertNotSame(playback, BlendLibItemAnimations.playback(stack));
            playback.seek(99);
            assertEquals(0, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());

            // These are the production disconnect boundary operations; no graphics or network client is needed.
            BlendLibItemAnimations.clear();
            runtime.onWorldDisconnect();
            assertTrue(BlendLibItemAnimations.observe(stack).isEmpty());
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty());
            assertEquals(0, runtime.measurementSnapshot().trackedAnimationInstances());
            assertEquals(0, runtime.measurementSnapshot().poseCacheEntries());
            runtime.onPlayInit();

            var retained = new ArrayList<ItemStack>();
            for (int index = 0; index < BlendLibItemAnimations.MAX_RETAINED_INSTANCES; index++) {
                var next = stack(Items.STICK);
                retained.add(next);
                BlendLibItemAnimations.playback(next).pause();
            }
            var oldest = retained.getFirst();
            var oldestControl = BlendLibItemAnimations.playback(oldest);
            // Reestablish oldest as least recent after obtaining its control.
            for (int index = 1; index < retained.size(); index++) BlendLibItemAnimations.playback(retained.get(index));
            for (int index = 0; index < 10; index++) assertTrue(BlendLibItemAnimations.observe(oldest).isPresent());
            var newcomer = stack(Items.STICK);
            BlendLibItemAnimations.playback(newcomer);
            assertTrue(BlendLibItemAnimations.observe(oldest).isEmpty(), "public observation must not refresh LRU");
            assertTrue(BlendLibItemAnimations.observe(retained.get(1)).isPresent());
            assertNotSame(oldestControl, BlendLibItemAnimations.playback(oldest));
        } finally {
            BlendLibItemAnimations.clear();
            runtime.onWorldDisconnect();
            registry.close();
        }
    }

    private static ItemStack stack(Item item) {
        // 26.3 binds built-in holder components only during data-pack loading. Use the public
        // component-bearing holder constructor to make real stacks without booting a client world.
        return new ItemStack(Holder.direct(item, DataComponents.COMMON_ITEM_COMPONENTS));
    }

    private static void publish(ClientModelRegistry registry, LoadedModelHandle loaded) {
        registry.publish(new ModelRegistryGeneration(loaded.generationId(), Map.of(MODEL, loaded), Map.of(), List.of()));
    }

    private static LoadedModelHandle fixture(long generation) {
        var mesh = new MeshPrimitive("Surface",
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2},
                new int[12], new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0});
        var clip = new AnimationClip("idle", List.of(new AnimationChannel(1, AnimationPath.TRANSLATION,
                Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, 1, 0, 0})));
        var animation = new AnimationDefinition(IDLE.resourceId(), Map.of(IDLE.resourceId(),
                new AnimationStateDefinition("idle", true, 1, 0, null, List.of())));
        var material = new MaterialDefinition(BlendResourceId.parse("observation_test:textures/item.png"),
                MaterialDefinition.Mode.OPAQUE, false, false, null);
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), generation,
                ModelProfile.SKINNED_V1, 1, Map.of("Surface", material), animation,
                List.of(new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0), List.of(new ModelPrimitive(0, 0, 0, mesh)),
                new Skeleton(List.of(new Skin("ItemSkin", 1, List.of(1), new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}))),
                List.of(clip), new SocketTable(Map.of()), Bounds.fromPositions(mesh.positions()), List.of());
        return new LoadedModelHandle(MODEL, asset, SkinnedRenderHandle.prepare(MODEL, asset));
    }
}
