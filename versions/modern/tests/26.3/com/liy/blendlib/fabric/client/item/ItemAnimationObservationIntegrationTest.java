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
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test void actualStacksKeepHistoricalSamplesAcrossExtractionControlsReloadAndRetirement() throws Exception {
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
            assertThrows(NullPointerException.class, () -> BlendLibItemAnimations.extractionStatus(null));
            for (var unseen : new ItemStack[] {ItemStack.EMPTY, stack, copy, unregistered}) {
                assertTrue(BlendLibItemAnimations.extractionStatus(unseen).isEmpty());
            }
            assertEquals(emptyMetrics, runtime.measurementSnapshot(), "unseen queries create no runtime state");

            var playback = BlendLibItemAnimations.playback(stack).pause().seek(0.25);
            var unsampled = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertEquals(0.25, unsampled.storedSeconds());
            assertTrue(unsampled.lastSample().isEmpty());
            assertTrue(BlendLibItemAnimations.extractionStatus(stack).isEmpty(), "controls alone create no extraction attempt");
            assertFalse(unsampled.sampleCurrentGeneration());
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty(), "ItemStack.copy is a new identity");
            var guiArgument = renderer.extractArgument(stack);
            assertNotNull(guiArgument.animatedSnapshot());
            var before = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertEquals(new ItemAnimationObservation.Sample(MODEL, IDLE, 1, 0.25, 1), before.lastSample().orElseThrow());
            assertTrue(before.sampleCurrentGeneration());
            var firstStatus = status(stack, 1, IDLE, ItemAnimationExtractionStatus.Outcome.ANIMATED,
                    ItemAnimationExtractionStatus.Fallback.NONE, true);
            var sampledMetrics = runtime.measurementSnapshot();
            for (int index = 0; index < 10; index++) {
                assertEquals(before, BlendLibItemAnimations.observe(stack).orElseThrow());
                assertEquals(firstStatus, BlendLibItemAnimations.extractionStatus(stack).orElseThrow());
            }
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
            assertEquals(firstStatus, BlendLibItemAnimations.extractionStatus(stack).orElseThrow(),
                    "play changes requested controls, not the historical attempt");
            var unavailable = assertDoesNotThrow(() -> renderer.extractArgument(stack));
            assertNull(unavailable.animatedSnapshot());
            assertTrue(unavailable.handle().missingModel(), "unavailable skinned animation uses the missing-model fallback");
            status(stack, 1, MISSING, ItemAnimationExtractionStatus.Outcome.ANIMATION_UNAVAILABLE,
                    ItemAnimationExtractionStatus.Fallback.MISSING_MODEL, true);
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
            status(stack, 1, MISSING, ItemAnimationExtractionStatus.Outcome.ANIMATION_UNAVAILABLE,
                    ItemAnimationExtractionStatus.Fallback.MISSING_MODEL, false);
            assertEquals(preCallbackMetrics, runtime.measurementSnapshot(), "stale lookup does not retire old runtime state");
            runtime.onActiveGeneration(2);
            assertSame(playback, BlendLibItemAnimations.playback(stack));
            assertFalse(BlendLibItemAnimations.observe(stack).orElseThrow().sampleCurrentGeneration());
            assertEquals(2, renderer.extractArgument(stack).snapshot(0, 0).generation());
            var reloaded = BlendLibItemAnimations.observe(stack).orElseThrow();
            assertTrue(reloaded.sampleCurrentGeneration());
            status(stack, 2, IDLE, ItemAnimationExtractionStatus.Outcome.ANIMATED,
                    ItemAnimationExtractionStatus.Fallback.NONE, true);
            assertEquals(1, firstStatus.generation());
            assertTrue(firstStatus.currentGeneration(), "previous status remains immutable after reload");
            assertEquals(new ItemAnimationObservation.Sample(MODEL, IDLE, 2, 0.5, 1), reloaded.lastSample().orElseThrow());
            assertEquals(1, before.lastSample().orElseThrow().generation());
            assertTrue(before.sampleCurrentGeneration(), "previous observation remains immutable after reload");

            registry.publish(ModelRegistryGeneration.empty(3));
            assertFalse(BlendLibItemAnimations.observe(stack).orElseThrow().sampleCurrentGeneration());
            status(stack, 2, IDLE, ItemAnimationExtractionStatus.Outcome.ANIMATED,
                    ItemAnimationExtractionStatus.Fallback.NONE, false);
            var missingModel = renderer.extractArgument(stack);
            assertNull(missingModel.animatedSnapshot(), "a model absent after reload yields no extraction");
            assertTrue(missingModel.handle().missingModel());
            assertEquals(3, missingModel.handle().generation());
            status(stack, 3, IDLE, ItemAnimationExtractionStatus.Outcome.MODEL_UNAVAILABLE,
                    ItemAnimationExtractionStatus.Fallback.MISSING_MODEL, true);
            assertEquals(reloaded.lastSample(), BlendLibItemAnimations.observe(stack).orElseThrow().lastSample());

            BlendLibItemAnimations.release(stack);
            assertTrue(BlendLibItemAnimations.observe(stack).isEmpty());
            assertTrue(BlendLibItemAnimations.extractionStatus(stack).isEmpty());
            assertNotSame(playback, BlendLibItemAnimations.playback(stack));
            assertTrue(BlendLibItemAnimations.extractionStatus(stack).isEmpty());
            playback.seek(99);
            assertEquals(0, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());

            // These are the production disconnect boundary operations; no graphics or network client is needed.
            BlendLibItemAnimations.clear();
            runtime.onWorldDisconnect();
            assertTrue(BlendLibItemAnimations.observe(stack).isEmpty());
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty());
            assertTrue(BlendLibItemAnimations.extractionStatus(stack).isEmpty());
            assertTrue(BlendLibItemAnimations.extractionStatus(copy).isEmpty());
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
            renderer.extractArgument(oldest);
            assertTrue(BlendLibItemAnimations.extractionStatus(oldest).isPresent());
            // Reestablish oldest as least recent after obtaining its control.
            for (int index = 1; index < retained.size(); index++) BlendLibItemAnimations.playback(retained.get(index));
            for (int index = 0; index < 10; index++) {
                assertTrue(BlendLibItemAnimations.observe(oldest).isPresent());
                assertTrue(BlendLibItemAnimations.extractionStatus(oldest).isPresent());
            }
            var newcomer = stack(Items.STICK);
            BlendLibItemAnimations.playback(newcomer);
            assertTrue(BlendLibItemAnimations.observe(oldest).isEmpty(), "public observation must not refresh LRU");
            assertTrue(BlendLibItemAnimations.extractionStatus(oldest).isEmpty(), "status must not refresh LRU either");
            assertTrue(BlendLibItemAnimations.observe(retained.get(1)).isPresent());
            assertNotSame(oldestControl, BlendLibItemAnimations.playback(oldest));
            BlendLibItemAnimations.clear();
            publishedReloadsPreserveControlsAndRecover(registry, runtime, renderer);
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

    private enum Unavailability { STATE_REMOVED, DECLARATION_REMOVED, MODEL_MISSING, UNSUPPORTED_HANDLE }

    private static void publishedReloadsPreserveControlsAndRecover(ClientModelRegistry registry,
            SkinnedAnimationRuntime runtime, BlendLibItemSpecialRenderer renderer) throws Exception {
        long generation = registry.current().generationId();
        for (var profile : ModelProfile.values()) {
            for (var mode : ItemAnimationPlayback.Mode.values()) {
                for (boolean paused : new boolean[] {false, true}) {
                    for (var unavailability : Unavailability.values()) {
                        String scenario = profile + "/" + mode + "/paused=" + paused + "/" + unavailability;
                        ItemStack stack = stack(Items.STICK);
                        var nanos = new AtomicLong();
                        var clockReads = new AtomicInteger();
                        var playback = deterministicPlayback(stack, nanos, clockReads);
                        playback.play(IDLE, mode).speed(2).seek(0.25);
                        var initial = fixture(++generation, profile, IDLE, 1);
                        publish(registry, initial);
                        var firstArgument = renderer.extractArgument(stack);
                        assertNotNull(firstArgument.animatedSnapshot(), scenario);
                        var historical = BlendLibItemAnimations.observe(stack).orElseThrow().lastSample();
                        var firstStatus = status(stack, generation, IDLE, ItemAnimationExtractionStatus.Outcome.ANIMATED,
                                ItemAnimationExtractionStatus.Fallback.NONE, true);
                        if (paused) playback.pause().seek(7.25);
                        var requested = BlendLibItemAnimations.observe(stack).orElseThrow();
                        int readsBeforeOutage = clockReads.get();
                        nanos.set(3_250_000_000L);
                        LoadedModelHandle unavailable = null;
                        generation++;
                        if (unavailability == Unavailability.MODEL_MISSING) {
                            registry.publish(ModelRegistryGeneration.empty(generation));
                        } else {
                            unavailable = fixture(generation, profile, switch (unavailability) {
                                case STATE_REMOVED -> MISSING;
                                case DECLARATION_REMOVED -> null;
                                default -> IDLE;
                            }, 1);
                            if (unavailability == Unavailability.UNSUPPORTED_HANDLE) {
                                unavailable = new LoadedModelHandle(MODEL, unavailable.asset(),
                                        new UnsupportedHandle(unavailable.renderHandle()));
                            }
                            publish(registry, unavailable);
                        }
                        var metricsBeforeQueries = runtime.measurementSnapshot();
                        status(stack, generation - 1, IDLE, ItemAnimationExtractionStatus.Outcome.ANIMATED,
                                ItemAnimationExtractionStatus.Fallback.NONE, false);
                        assertFalse(BlendLibItemAnimations.observe(stack).orElseThrow().sampleCurrentGeneration(), scenario);
                        assertEquals(metricsBeforeQueries, runtime.measurementSnapshot(), "query must not retire stale runtime state: " + scenario);
                        assertEquals(readsBeforeOutage, clockReads.get(), "query must not read the playback clock: " + scenario);

                        for (int attempt = 0; attempt < 2; attempt++) {
                            var fallbackArgument = assertDoesNotThrow(() -> renderer.extractArgument(stack), scenario);
                            assertNull(fallbackArgument.animatedSnapshot(), "old poses must not survive extraction: " + scenario);
                            assertEquals(generation, fallbackArgument.handle().generation(), scenario);
                            boolean missingFallback = profile == ModelProfile.SKINNED_V1 || unavailable == null;
                            assertEquals(missingFallback, fallbackArgument.handle().missingModel(), scenario);
                            if (!missingFallback) {
                                assertSame(unavailable.renderHandle(), fallbackArgument.handle(), scenario);
                                assertEquals(Transform.IDENTITY, fallbackArgument.handle().nodeTransform(0), scenario);
                            }
                            status(stack, generation, IDLE,
                                    switch (unavailability) {
                                        case MODEL_MISSING -> ItemAnimationExtractionStatus.Outcome.MODEL_UNAVAILABLE;
                                        case UNSUPPORTED_HANDLE -> ItemAnimationExtractionStatus.Outcome.EXTRACTION_UNAVAILABLE;
                                        default -> ItemAnimationExtractionStatus.Outcome.ANIMATION_UNAVAILABLE;
                                    },
                                    missingFallback ? ItemAnimationExtractionStatus.Fallback.MISSING_MODEL
                                            : ItemAnimationExtractionStatus.Fallback.STATIC_MODEL, true);
                            var after = BlendLibItemAnimations.observe(stack).orElseThrow();
                            assertEquals(IDLE, after.animation(), "never substitute the replacement's initial state: " + scenario);
                            assertEquals(requested.mode(), after.mode(), scenario);
                            assertEquals(requested.speed(), after.speed(), scenario);
                            assertEquals(requested.playing(), after.playing(), scenario);
                            assertEquals(requested.storedSeconds(), after.storedSeconds(), "unavailable time stays raw: " + scenario);
                            assertEquals(historical, after.lastSample(), scenario);
                            assertFalse(after.sampleCurrentGeneration(), scenario);
                            assertEquals(readsBeforeOutage + (unavailability == Unavailability.UNSUPPORTED_HANDLE ? attempt + 1 : 0),
                                    clockReads.get(), "only an available clip may attempt to sample the clock: " + scenario);
                        }
                        assertTrue(firstStatus.currentGeneration(), "captured status is immutable: " + scenario);
                        int readsBeforeRecovery = clockReads.get();
                        var restored = fixture(++generation, profile, IDLE, 2);
                        publish(registry, restored);
                        assertFalse(BlendLibItemAnimations.extractionStatus(stack).orElseThrow().currentGeneration(), scenario);
                        var recovered = assertDoesNotThrow(() -> renderer.extractArgument(stack), scenario);
                        assertNotNull(recovered.animatedSnapshot(), scenario);
                        assertSame(restored.renderHandle(), recovered.handle(), scenario);
                        assertNotSame(firstArgument.animatedSnapshot(), recovered.animatedSnapshot(), scenario);
                        assertSame(playback, BlendLibItemAnimations.playback(stack), scenario);
                        var recoveredObservation = BlendLibItemAnimations.observe(stack).orElseThrow();
                        double rawSeconds = paused ? 7.25 : 6.75;
                        double expected = switch (mode) {
                            case LOOP -> rawSeconds % 2;
                            case HOLD -> 2;
                            case ONCE -> 0;
                        };
                        assertEquals(expected, recoveredObservation.storedSeconds(), scenario);
                        assertEquals(!paused && mode == ItemAnimationPlayback.Mode.LOOP, recoveredObservation.playing(), scenario);
                        assertEquals(2, recoveredObservation.speed(), scenario);
                        assertEquals(mode, recoveredObservation.mode(), scenario);
                        assertEquals(new ItemAnimationObservation.Sample(MODEL, IDLE, generation, expected, 2),
                                recoveredObservation.lastSample().orElseThrow(), scenario);
                        assertTrue(recoveredObservation.sampleCurrentGeneration(), scenario);
                        assertEquals(readsBeforeRecovery + 1, clockReads.get(), "recovery samples the clock once: " + scenario);
                        status(stack, generation, IDLE, ItemAnimationExtractionStatus.Outcome.ANIMATED,
                                ItemAnimationExtractionStatus.Fallback.NONE, true);
                        BlendLibItemAnimations.release(stack);
                    }
                }
            }
        }
    }

    /** Valid public handle metadata with an unsupported concrete backend, so extraction returns empty. */
    private record UnsupportedHandle(ModelRenderHandle delegate) implements ModelRenderHandle {
        @Override public BlendModelKey modelKey() { return delegate.modelKey(); }
        @Override public long generation() { return delegate.generation(); }
        @Override public Bounds bounds() { return delegate.bounds(); }
        @Override public float unitsToBlocksScale() { return delegate.unitsToBlocksScale(); }
        @Override public List<PreparedRenderPrimitive> primitives() { return delegate.primitives(); }
        @Override public List<PreparedSkinnedRenderPrimitive> skinnedPrimitives() { return delegate.skinnedPrimitives(); }
        @Override public boolean skinned() { return delegate.skinned(); }
        @Override public Transform nodeTransform(int node) { return delegate.nodeTransform(node); }
        @Override public boolean missingModel() { return false; }
    }

    /** Swap only this retained test entry's control; the production facade and renderer still run unchanged. */
    @SuppressWarnings("unchecked")
    private static ItemAnimationPlayback deterministicPlayback(ItemStack stack, AtomicLong nanos, AtomicInteger reads)
            throws Exception {
        var original = BlendLibItemAnimations.playback(stack);
        var field = BlendLibItemAnimations.class.getDeclaredField("INSTANCES");
        field.setAccessible(true);
        var instances = (ItemAnimationInstances) field.get(null);
        var entriesField = ItemAnimationInstances.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        var entries = (Map<?, ItemAnimationInstances.Entry>) entriesField.get(instances);
        for (var entry : entries.entrySet()) {
            if (entry.getValue().playback() == original) {
                var replacement = new ItemAnimationPlayback(IDLE, () -> { reads.incrementAndGet(); return nanos.get(); });
                entry.setValue(new ItemAnimationInstances.Entry(entry.getValue().key(), replacement));
                return replacement;
            }
        }
        throw new AssertionError("Retained stack identity was not found");
    }

    private static ItemAnimationExtractionStatus status(ItemStack stack, long generation,
            BlendAnimationKey requested, ItemAnimationExtractionStatus.Outcome outcome,
            ItemAnimationExtractionStatus.Fallback fallback, boolean current) {
        var expected = new ItemAnimationExtractionStatus(MODEL, requested, generation, outcome, fallback, current);
        var actual = BlendLibItemAnimations.extractionStatus(stack).orElseThrow();
        assertEquals(expected, actual);
        return actual;
    }

    private static LoadedModelHandle fixture(long generation) {
        return fixture(generation, ModelProfile.SKINNED_V1, IDLE, 1);
    }

    private static LoadedModelHandle fixture(long generation, ModelProfile profile,
            BlendAnimationKey declaredState, float duration) {
        boolean skinned = profile == ModelProfile.SKINNED_V1;
        var mesh = new MeshPrimitive("Surface",
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2},
                skinned ? new int[12] : null,
                skinned ? new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0} : null);
        var clip = new AnimationClip("idle", List.of(new AnimationChannel(skinned ? 1 : 0, AnimationPath.TRANSLATION,
                Interpolation.LINEAR, new float[] {0, duration}, new float[] {0, 0, 0, 1, 0, 0})));
        var animation = declaredState == null ? null : new AnimationDefinition(declaredState.resourceId(), Map.of(declaredState.resourceId(),
                new AnimationStateDefinition("idle", true, 1, 0, null, List.of())));
        var material = new MaterialDefinition(BlendResourceId.parse("observation_test:textures/item.png"),
                MaterialDefinition.Mode.OPAQUE, false, false, null);
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), generation,
                profile, 1, Map.of("Surface", material), animation,
                List.of(new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, skinned ? 0 : -1, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0), List.of(new ModelPrimitive(0, 0, 0, mesh)),
                skinned ? new Skeleton(List.of(new Skin("ItemSkin", 1, List.of(1),
                        new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}))) : null,
                List.of(clip), new SocketTable(Map.of()), Bounds.fromPositions(mesh.positions()), List.of());
        return new LoadedModelHandle(MODEL, asset, skinned ? SkinnedRenderHandle.prepare(MODEL, asset)
                : StaticRigidRenderHandle.prepare(MODEL, asset));
    }
}
