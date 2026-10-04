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
import com.liy.blendlib.core.descriptor.AnimationEventDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.*;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/** Real 26.3 stacks, prepared descriptors, runtime extraction and synchronous public handlers. */
@Isolated("Temporarily installs and restores the CPU-only client service facade")
class ItemAnimationVisualEventIntegrationTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("item_visual_event_test:item");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("item_visual_event_test:idle");
    private static final BlendAnimationKey NEXT = BlendAnimationKey.parse("item_visual_event_test:next");
    private static final BlendAnimationKey MISSING = BlendAnimationKey.parse("item_visual_event_test:missing");
    private static final List<AnimationEventDefinition> MARKERS = List.of(
            marker(0, "zero"), marker(.25, "quarter"), marker(.5, "half"),
            marker(.75, "three_quarters"), marker(1, "end"));

    @BeforeAll static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void actualHandlersGetExactStacksAndImmutableMetadataOnlyForNewCrossings() throws Exception {
        try (var h = new Harness(Items.BLAZE_ROD)) {
            var stack = stack(h.item);
            var copy = stack.copy();
            var first = h.control(stack);
            h.extract(stack); // The initial zero marker is not replayed.
            h.at(.25); h.extract(stack);
            assertEquals(List.of("quarter"), h.keys());
            var captured = h.deliveries.getFirst();
            assertSame(stack, captured.stack());
            assertEquals(MODEL, captured.event().model());
            assertEquals(IDLE, captured.event().animation());
            assertEquals(1, captured.event().generation());
            assertEquals(.25, captured.event().event().timeSeconds());
            assertTrue(captured.event().getClass().isRecord(), "event envelopes are immutable values");
            var firstObservation = BlendLibItemAnimations.observe(stack).orElseThrow();
            for (int i = 0; i < 5; i++) {
                h.extract(stack); // GUI and hand both call this exact renderer path.
                BlendLibItemAnimations.observe(stack);
                BlendLibItemAnimations.extractionStatus(stack);
            }
            assertEquals(List.of("quarter"), h.keys());
            assertEquals(firstObservation, BlendLibItemAnimations.observe(stack).orElseThrow());
            assertSame(first, BlendLibItemAnimations.playback(stack));
            assertTrue(BlendLibItemAnimations.observe(copy).isEmpty());
            h.control(copy).seek(.25);
            h.extract(copy);
            assertEquals(1, h.deliveries.size(), "equal contents and copied stacks get a silent independent baseline");
            h.at(.5); h.extract(stack); h.extract(copy);
            assertEquals(List.of("quarter", "half", "half"), h.keys());
            assertSame(stack, h.deliveries.get(1).stack());
            assertSame(copy, h.deliveries.get(2).stack());
            h.at(1); h.extract(stack);
            assertEquals(List.of("quarter", "half", "half", "three_quarters", "end", "zero"), h.keys());
            h.extract(stack);
            assertEquals(6, h.deliveries.size(), "both sides of a loop boundary are consumed exactly once");
            h.publish(2, ModelProfile.SKINNED_V1, 1, MARKERS, false, 4);
            h.at(1.25); h.extract(stack);
            assertEquals(6, h.deliveries.size(), "new generation silently establishes its baseline");
            assertEquals(1, captured.event().generation(), "previous payload does not change on reload");
            h.at(1.5); h.extract(stack);
            assertEquals(2, h.deliveries.getLast().event().generation());
            assertEquals("half", h.keys().getLast());
            assertTrue(h.submitted.isEmpty(), "extraction delivers callbacks without submitting graphics");
            var argument = h.renderer.extractArgument(stack);
            var observed = BlendLibItemAnimations.observe(stack);
            var status = BlendLibItemAnimations.extractionStatus(stack);
            var metrics = h.runtime.measurementSnapshot();
            int delivered = h.deliveries.size();
            h.at(9);
            var collector = (net.minecraft.client.renderer.SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{net.minecraft.client.renderer.SubmitNodeCollector.class},
                    (proxy, method, args) -> null);
            for (int i = 0; i < 3; i++) {
                h.renderer.submit(argument, new com.mojang.blaze3d.vertex.PoseStack(), collector, 41, 51, false, 0);
            }
            assertEquals(3, h.submitted.size());
            assertEquals(delivered, h.deliveries.size(), "submission cannot deliver markers even as the clock advances");
            assertEquals(observed, BlendLibItemAnimations.observe(stack));
            assertEquals(status, BlendLibItemAnimations.extractionStatus(stack));
            assertEquals(metrics, h.runtime.measurementSnapshot());
        }
    }

    @Test void itemModesIgnoreDescriptorLoopSpeedAndNextAndRetainTheTerminalMarker() throws Exception {
        try (var h = new Harness(Items.BONE)) {
            long generation = 1;
            for (var profile : ModelProfile.values()) {
                for (var mode : ItemAnimationPlayback.Mode.values()) {
                    h.publish(++generation, profile, 1, MARKERS, mode != ItemAnimationPlayback.Mode.LOOP, 4);
                    var stack = stack(h.item);
                    var playback = h.control(stack).play(IDLE, mode);
                    h.clearDeliveries(); h.extract(stack);
                    h.advance(.25); h.extract(stack);
                    assertEquals(List.of("quarter"), h.keys(), "descriptor speed must not multiply item time: " + mode);
                    h.advance(.75); h.extract(stack);
                    assertEquals(mode == ItemAnimationPlayback.Mode.LOOP
                                    ? List.of("quarter", "half", "three_quarters", "end", "zero")
                                    : List.of("quarter", "half", "three_quarters", "end"),
                            h.keys(), profile + "/" + mode);
                    assertEquals(IDLE, playback.animation(), "descriptor next does not select a different state");
                    assertTrue(h.deliveries.stream().allMatch(value -> value.event().animation().equals(IDLE)));
                    var observation = BlendLibItemAnimations.observe(stack).orElseThrow();
                    assertEquals(mode == ItemAnimationPlayback.Mode.HOLD ? 1 : 0, observation.storedSeconds());
                    assertEquals(mode == ItemAnimationPlayback.Mode.LOOP, playback.playing());
                    h.clearDeliveries(); h.advance(.25); h.extract(stack);
                    assertEquals(mode == ItemAnimationPlayback.Mode.LOOP ? List.of("quarter") : List.of(), h.keys());
                    BlendLibItemAnimations.release(stack);
                }
            }
        }
    }

    @Test void seekRestartAndStopRebaseSilentlyButPauseResumeAndSpeedPreserveRealAdvancement() throws Exception {
        try (var h = new Harness(Items.CARROT)) {
            var stack = stack(h.item);
            var playback = h.control(stack);
            h.extract(stack);
            h.at(.25); playback.pause();
            h.at(10); h.extract(stack);
            assertEquals(List.of("quarter"), h.keys(), "pause retains advancement before it was called");
            h.extract(stack); playback.resume();
            h.at(10.125); playback.speed(2);
            h.at(10.1875); h.extract(stack);
            assertEquals(List.of("quarter", "half"), h.keys(), "speed change preserves both real subintervals");
            playback.speed(0); h.at(20); h.extract(stack);
            assertEquals(2, h.deliveries.size());
            playback.speed(1).seek(.75);
            h.extract(stack);
            assertEquals(2, h.deliveries.size(), "seek never replays skipped markers");
            h.advance(.25); h.extract(stack);
            assertEquals(List.of("quarter", "half", "end", "zero"), h.keys());
            playback.play(IDLE, ItemAnimationPlayback.Mode.HOLD);
            h.advance(.75); h.extract(stack);
            assertEquals(4, h.deliveries.size(), "the first extraction after restart is silent even after elapsed time");
            h.advance(.25); h.extract(stack);
            assertEquals("end", h.keys().getLast());
            assertEquals(5, h.deliveries.size());
            playback.stop().resume();
            h.advance(.75); h.extract(stack);
            assertEquals(5, h.deliveries.size(), "stop also establishes a silent first extraction");
            h.advance(.25); h.extract(stack);
            assertEquals(6, h.deliveries.size());
            assertEquals("end", h.keys().getLast());
        }
    }

    @Test void everyUnavailablePathAndReloadRecoverWithASilentBaseline() throws Exception {
        try (var h = new Harness(Items.WHEAT)) {
            long generation = 1;
            for (var profile : ModelProfile.values()) {
                for (var failure : Failure.values()) {
                    h.publish(++generation, profile, 1, MARKERS, true, 1);
                    var stack = stack(h.item);
                    var playback = h.control(stack);
                    h.clearDeliveries(); h.extract(stack);
                    h.advance(.25); h.extract(stack);
                    assertEquals(List.of("quarter"), h.keys());
                    var successful = BlendLibItemAnimations.observe(stack).orElseThrow();
                    h.advance(.25);
                    generation++;
                    switch (failure) {
                        case MISSING_MODEL -> h.registry.publish(ModelRegistryGeneration.empty(generation));
                        case MISSING_STATE -> h.publish(fixture(generation, profile, 1, MARKERS, true, 1, MISSING));
                        case MISSING_DECLARATION -> h.publish(fixture(generation, profile, 1, MARKERS, true, 1, null));
                        case UNSUPPORTED_HANDLE -> {
                            var loaded = fixture(generation, profile, 1, MARKERS, true, 1, IDLE);
                            h.publish(new LoadedModelHandle(MODEL, loaded.asset(), new UnsupportedHandle(loaded.renderHandle())));
                        }
                    }
                    for (int attempt = 0; attempt < 2; attempt++) {
                        assertNull(h.renderer.extractArgument(stack).animatedSnapshot(), profile + "/" + failure);
                        assertEquals(List.of("quarter"), h.keys());
                        assertEquals(successful.storedSeconds(), BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());
                        assertEquals(successful.lastSample(), BlendLibItemAnimations.observe(stack).orElseThrow().lastSample());
                    }
                    h.advance(.25);
                    h.publish(++generation, profile, 1, MARKERS, true, 1);
                    h.extract(stack);
                    assertSame(playback, BlendLibItemAnimations.playback(stack));
                    assertEquals(List.of("quarter"), h.keys(), "outage history is discarded: " + profile + "/" + failure);
                    h.advance(.25); h.extract(stack);
                    assertEquals(List.of("quarter", "end", "zero"), h.keys());
                    assertEquals(generation, h.deliveries.getLast().event().generation());
                    BlendLibItemAnimations.release(stack);
                }
            }
        }
    }

    @Test void exceptionPropagatesAfterTheWholeBatchIsConsumedAndDoesNotPoisonLaterDelivery() throws Exception {
        try (var h = new Harness(Items.FEATHER)) {
            var stack = stack(h.item);
            h.control(stack); h.extract(stack);
            var failure = new IllegalStateException("listener failure");
            h.onDelivery = ignored -> { throw failure; };
            h.at(.75);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> h.renderer.extractArgument(stack)));
            assertEquals(List.of("quarter"), h.keys());
            assertEquals(.75, BlendLibItemAnimations.observe(stack).orElseThrow().lastSample().orElseThrow().seconds());
            h.onDelivery = ignored -> { };
            h.extract(stack);
            assertEquals(List.of("quarter"), h.keys(), "undelivered events from the failed batch never replay");
            h.at(1); h.extract(stack);
            assertEquals(List.of("quarter", "end", "zero"), h.keys());
        }
    }

    @Test void nestedExtractionAdvancesSilentlyWithoutRecursionOrReplayingItsInterval() throws Exception {
        try (var h = new Harness(Items.FLINT)) {
            var stack = stack(h.item);
            h.control(stack); h.extract(stack);
            h.onDelivery = ignored -> {
                if (h.deliveries.size() == 1) {
                    h.at(.75);
                    h.extract(stack);
                    assertEquals(1, h.deliveries.size(), "nested extraction cannot invoke the handler");
                }
            };
            h.at(.5); h.extract(stack);
            assertEquals(List.of("quarter", "half"), h.keys());
            h.extract(stack);
            assertEquals(List.of("quarter", "half"), h.keys(), "nested three-quarter crossing was silently consumed");
            h.at(1); h.extract(stack);
            assertEquals(List.of("quarter", "half", "end", "zero"), h.keys());
        }
    }

    @Test void nestedExtractionOfAnotherStackConsumesSilentlyWithoutStealingTheOuterBatch() throws Exception {
        try (var h = new Harness(Items.GUNPOWDER)) {
            var stack = stack(h.item);
            var other = stack.copy();
            h.control(stack); h.control(other);
            h.extract(stack); h.extract(other);
            h.onDelivery = delivery -> {
                if (delivery.stack() == stack && h.deliveries.size() == 1) {
                    h.at(.75); h.extract(other);
                    assertEquals(1, h.deliveries.size(), "nested extraction of a different identity is also silent");
                }
            };
            h.at(.5); h.extract(stack);
            assertEquals(List.of("quarter", "half"), h.keys());
            assertTrue(h.deliveries.stream().allMatch(value -> value.stack() == stack));
            h.extract(other);
            assertEquals(2, h.deliveries.size(), "the other stack consumed its nested interval");
            h.at(1); h.extract(other);
            assertEquals(List.of("quarter", "half", "end", "zero"), h.keys());
            assertSame(other, h.deliveries.getLast().stack());
        }
    }

    @Test void releaseAndReacquireInsideACallbackCannotCreateANestedDispatchLoop() throws Exception {
        try (var h = new Harness(Items.LEATHER)) {
            var stack = stack(h.item);
            var original = h.control(stack);
            h.extract(stack);
            h.onDelivery = ignored -> {
                BlendLibItemAnimations.release(stack);
                assertNotSame(original, assertDoesNotThrow(() -> h.control(stack)));
                h.at(.75); h.extract(stack); // New identity establishes a baseline at .25.
                h.at(1); h.extract(stack); // Then crosses half, still within the outer callback.
                assertEquals(1, h.deliveries.size(), "retirement must not bypass the nested-callback guard");
            };
            h.at(.5); h.extract(stack);
            assertEquals(List.of("quarter"), h.keys(), "retirement stops the old batch");
            h.onDelivery = ignored -> { };
            h.extract(stack);
            assertEquals(1, h.deliveries.size());
            h.advance(.25); h.extract(stack);
            assertEquals(List.of("quarter", "three_quarters"), h.keys());
        }
    }

    @Test void generationPublicationInsideACallbackStopsStalePayloadsAndRebasesTheNextExtraction() throws Exception {
        try (var h = new Harness(Items.PRISMARINE_SHARD)) {
            var stack = stack(h.item);
            h.control(stack); h.extract(stack);
            h.onDelivery = ignored -> h.publish(2, ModelProfile.SKINNED_V1, 1, MARKERS, true, 1);
            h.at(.75); h.extract(stack);
            assertEquals(List.of("quarter"), h.keys());
            assertEquals(1, h.deliveries.getFirst().event().generation());
            h.onDelivery = ignored -> { };
            h.extract(stack);
            assertEquals(1, h.deliveries.size());
            h.at(1); h.extract(stack);
            assertEquals(List.of("quarter", "end", "zero"), h.keys());
            assertEquals(2, h.deliveries.getLast().event().generation());
        }
    }

    @Test void eachReentrantControlMutationStopsTheRemainingCallbackBatch() throws Exception {
        try (var h = new Harness(Items.STRING)) {
            var mutations = List.<Consumer<ItemAnimationPlayback>>of(
                    value -> value.play(IDLE, ItemAnimationPlayback.Mode.LOOP),
                    value -> value.seek(0), ItemAnimationPlayback::stop, ItemAnimationPlayback::pause,
                    value -> value.speed(2), ItemAnimationPlayback::resume);
            for (int index = 0; index < mutations.size(); index++) {
                var stack = stack(h.item);
                var playback = h.control(stack);
                h.clearDeliveries(); h.extract(stack);
                var mutation = mutations.get(index);
                h.onDelivery = ignored -> mutation.accept(playback);
                h.advance(.75); h.extract(stack);
                assertEquals(List.of("quarter"), h.keys(), "mutation " + index + " must stop stale remainder");
                h.onDelivery = ignored -> { };
                h.extract(stack);
                assertEquals(List.of("quarter"), h.keys(), "mutation " + index + " must not replay the consumed batch");
                BlendLibItemAnimations.release(stack);
            }
        }
    }

    @Test void releaseClearAndLruEvictionDuringDispatchRetireTheCursorAndStopItsRemainder() throws Exception {
        try (var h = new Harness(Items.SUGAR)) {
            for (var retirement : Retirement.values()) {
                BlendLibItemAnimations.clear();
                var stack = stack(h.item);
                var oldPlayback = h.control(stack);
                var retained = new ArrayList<ItemStack>();
                h.clearDeliveries(); h.extract(stack);
                h.onDelivery = ignored -> {
                    switch (retirement) {
                        case RELEASE -> BlendLibItemAnimations.release(stack);
                        case CLEAR -> BlendLibItemAnimations.clear();
                        case EVICT -> {
                            for (int i = 0; i < BlendLibItemAnimations.MAX_RETAINED_INSTANCES; i++) {
                                var next = stack(h.item); retained.add(next); BlendLibItemAnimations.playback(next);
                            }
                        }
                    }
                };
                h.advance(.75); h.extract(stack);
                assertEquals(List.of("quarter"), h.keys(), retirement.toString());
                assertTrue(BlendLibItemAnimations.observe(stack).isEmpty(), retirement.toString());
                h.onDelivery = ignored -> { };
                var replacement = h.control(stack);
                assertNotSame(oldPlayback, replacement);
                oldPlayback.seek(.75);
                h.extract(stack);
                assertEquals(List.of("quarter"), h.keys(), "retained old controls cannot revive the old cursor");
                h.advance(.25); h.extract(stack);
                assertEquals(List.of("quarter", "quarter"), h.keys());
                Reference.reachabilityFence(retained);
            }
        }
    }

    @Test void catchUpKeepsOnlyTheLastClipTimeSecondAndDropsItsLeftEndpoint() throws Exception {
        try (var h = new Harness(Items.EGG)) {
            var markers = List.of(marker(0, "zero"), marker(.25, "old"), marker(2.75, "left"),
                    marker(3.25, "recent"), marker(3.75, "right"), marker(4, "end"));
            h.publish(2, ModelProfile.SKINNED_V1, 4, markers, false, 1);
            var stack = stack(h.item);
            var playback = h.control(stack);
            h.extract(stack); h.at(3.75); h.extract(stack);
            assertEquals(List.of("recent", "right"), h.keys());
            h.extract(stack);
            assertEquals(2, h.deliveries.size());
            playback.play(IDLE, ItemAnimationPlayback.Mode.LOOP).speed(4);
            h.extract(stack); h.clearDeliveries();
            h.advance(1); h.extract(stack);
            assertEquals(List.of("recent", "right", "end", "zero"), h.keys(),
                    "a wall-clock second at speed 4 still keeps only one clip-time second");
        }
    }

    @Test void eventBudgetIsInclusiveAndOverflowDropsTheWholeBatchWithoutReplay() throws Exception {
        try (var h = new Harness(Items.SNOWBALL)) {
            var dense = new ArrayList<AnimationEventDefinition>();
            for (int i = 0; i < BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE; i++) dense.add(marker(.0625, "dense_" + i));
            h.publish(2, ModelProfile.SKINNED_V1, .25F, dense, true, 1);
            var exact = stack(h.item); h.control(exact); h.extract(exact);
            h.advance(1); h.extract(exact);
            assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE, h.deliveries.size(), "exact event budget is permitted");
            h.extract(exact);
            assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE, h.deliveries.size());
            h.publish(3, ModelProfile.SKINNED_V1, .125F, dense, true, 1);
            var overflow = stack(h.item); h.control(overflow); h.extract(overflow); h.clearDeliveries();
            h.advance(1); h.extract(overflow);
            assertTrue(h.deliveries.isEmpty(), "over-budget emission must be atomic, never a prefix");
            h.extract(overflow);
            assertTrue(h.deliveries.isEmpty(), "dropped work is consumed");
            h.advance(.0625); h.extract(overflow);
            assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE, h.deliveries.size(), "the next bounded interval recovers");
        }
    }

    @Test void cycleBudgetDropsAnOversizedIntervalAndCanRecoverOnTheNextSmallAdvance() throws Exception {
        try (var h = new Harness(Items.SLIME_BALL)) {
            float exactDuration = 1.0F / BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE;
            h.publish(2, ModelProfile.SKINNED_V1, exactDuration,
                    List.of(marker(exactDuration / 2, "cycle")), true, 1);
            var exact = stack(h.item); h.control(exact); h.extract(exact);
            h.advance(1); h.extract(exact);
            assertEquals(BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE, h.deliveries.size());
            float shortDuration = exactDuration / 2;
            h.publish(3, ModelProfile.SKINNED_V1, shortDuration,
                    List.of(marker(shortDuration / 2, "cycle")), true, 1);
            var overflow = stack(h.item); h.control(overflow); h.extract(overflow); h.clearDeliveries();
            h.advance(1); h.extract(overflow);
            assertTrue(h.deliveries.isEmpty());
            h.extract(overflow);
            assertTrue(h.deliveries.isEmpty());
            h.nanos.addAndGet(100_000); h.extract(overflow);
            assertEquals(List.of("cycle"), h.keys());
        }
    }

    @Test void floatAuthoredDurationIncludesTheExactRightEndpointAfterWrapping() throws Exception {
        try (var h = new Harness(Items.RABBIT_HIDE)) {
            float authoredDuration = .1F;
            double duration = authoredDuration;
            h.publish(2, ModelProfile.SKINNED_V1, authoredDuration,
                    List.of(marker(.05, "rounded_endpoint")), true, 1);
            var stack = stack(h.item);
            h.control(stack).speed(duration + .05);
            h.extract(stack);
            h.at(1); h.extract(stack);
            assertEquals(List.of("rounded_endpoint", "rounded_endpoint"), h.keys(),
                    "the occurrence at duration + .05 is the included right endpoint");
            assertEquals(.05, h.deliveries.getLast().event().event().timeSeconds());
            h.extract(stack);
            assertEquals(2, h.deliveries.size());
        }
    }

    @Test void moduloPoseRoundingCannotReplayTheEndpointOnTheNextNanosecond() throws Exception {
        try (var h = new Harness(Items.PRISMARINE_CRYSTALS)) {
            float authoredDuration = .1F;
            double duration = authoredDuration;
            h.publish(2, ModelProfile.SKINNED_V1, authoredDuration,
                    List.of(marker(.025, "rounded_boundary")), true, 1);
            var stack = stack(h.item);
            h.control(stack).speed(duration + .025);
            h.extract(stack);
            h.at(1); h.extract(stack);
            assertEquals(List.of("rounded_boundary", "rounded_boundary"), h.keys());
            h.nanos.incrementAndGet(); h.extract(stack);
            assertEquals(2, h.deliveries.size(),
                    "a modulo-rounded pose below .025 must not re-emit the consumed endpoint");
            h.extract(stack);
            assertEquals(2, h.deliveries.size());
        }
    }

    @Test void aNumericallyUnrepresentableCatchUpRebasesAndRecoversAtOrdinarySpeed() throws Exception {
        try (var h = new Harness(Items.MAGMA_CREAM)) {
            var stack = stack(h.item);
            var playback = h.control(stack);
            h.extract(stack);
            playback.speed(Double.MAX_VALUE);
            h.at(1); h.extract(stack);
            assertTrue(h.deliveries.isEmpty(), "the unrepresentable huge interval is dropped atomically");
            assertEquals(0, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());
            h.extract(stack);
            assertTrue(h.deliveries.isEmpty());
            playback.speed(1);
            h.at(1.25); h.extract(stack);
            assertEquals(List.of("quarter"), h.keys(), "discarding numerical history cannot permanently disable events");
            h.at(1.5); h.extract(stack);
            assertEquals(List.of("quarter", "half"), h.keys());
        }
    }

    @Test void anOverBudgetFractionalEndpointCannotReplayOnTheNextNanosecond() throws Exception {
        try (var h = new Harness(Items.FIRE_CHARGE)) {
            float authoredDuration = .1F;
            double duration = authoredDuration;
            var markers = new ArrayList<AnimationEventDefinition>();
            for (int i = 0; i < BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE; i++) {
                markers.add(marker(.025, "fractional_dense_" + i));
            }
            h.publish(2, ModelProfile.SKINNED_V1, authoredDuration, markers, true, 1);
            var stack = stack(h.item);
            h.control(stack).speed(4 * duration + .025);
            h.extract(stack);
            h.at(1); h.extract(stack);
            assertTrue(h.deliveries.isEmpty(), "five groups of 4096 crossings exceed the atomic event budget");
            h.nanos.incrementAndGet(); h.extract(stack);
            assertTrue(h.deliveries.isEmpty(), "the discarded inclusive endpoint must remain consumed after modulo rounding");
            h.extract(stack);
            assertTrue(h.deliveries.isEmpty());
            h.advance(.25); h.extract(stack);
            assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE, h.deliveries.size(),
                    "the next bounded crossing still delivers normally");
        }
    }

    @Test void resumingACompletedOnceAnimationAdvancesFromItsReturnedZeroPose() throws Exception {
        try (var h = new Harness(Items.GHAST_TEAR)) {
            var stack = stack(h.item);
            var playback = h.control(stack).play(IDLE, ItemAnimationPlayback.Mode.ONCE);
            h.extract(stack);
            h.at(1); h.extract(stack);
            assertEquals(List.of("quarter", "half", "three_quarters", "end"), h.keys());
            assertEquals(0, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());
            assertFalse(playback.playing());
            h.clearDeliveries();
            playback.resume();
            h.at(1.25); h.extract(stack);
            assertEquals(List.of("quarter"), h.keys(), "resume retains advancement rather than inventing another baseline");
            assertEquals(.25, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());
            assertTrue(playback.playing());
            h.at(2); h.extract(stack);
            assertEquals(List.of("quarter", "half", "three_quarters", "end"), h.keys());
            assertEquals(0, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());
            assertFalse(playback.playing());
        }
    }

    @Test void zeroDurationAndLegacyRegistrationDoNotEmitAndRemainExtractable() throws Exception {
        try (var h = new Harness(Items.GLOWSTONE_DUST)) {
            h.publish(2, ModelProfile.SKINNED_V1, 0, List.of(marker(0, "zero")), true, 1);
            for (var mode : ItemAnimationPlayback.Mode.values()) {
                var stack = stack(h.item); var playback = h.control(stack).play(IDLE, mode);
                h.extract(stack); h.advance(10); h.extract(stack);
                assertEquals(0, BlendLibItemAnimations.observe(stack).orElseThrow().storedSeconds());
                assertEquals(mode == ItemAnimationPlayback.Mode.LOOP, playback.playing());
            }
            assertTrue(h.deliveries.isEmpty());
            var binding = binding(Items.ENDER_PEARL);
            BlendLibItemAnimations.register(binding, IDLE);
            var renderer = new BlendLibItemSpecialRenderer(binding);
            var stack = stack(Items.ENDER_PEARL);
            controlledPlayback(stack, h.nanos);
            h.publish(3, ModelProfile.SKINNED_V1, 1, MARKERS, true, 1);
            assertNotNull(renderer.extractArgument(stack).animatedSnapshot());
            h.advance(.75);
            assertNotNull(renderer.extractArgument(stack).animatedSnapshot());
            assertTrue(h.deliveries.isEmpty(), "the original registration overload stays opt-out");
        }
    }

    private enum Failure { MISSING_MODEL, MISSING_STATE, MISSING_DECLARATION, UNSUPPORTED_HANDLE }
    private enum Retirement { RELEASE, CLEAR, EVICT }
    private record Delivery(ItemStack stack, ItemAnimationVisualEvent event) { }

    private static final class Harness implements AutoCloseable {
        final Item item;
        final AtomicReference<Object> activeServices;
        final Object previousServices;
        final AtomicLong nanos = new AtomicLong();
        final ClientModelRegistry registry = new ClientModelRegistry();
        final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(registry, new ClientAnimationLifecycleBridge(512));
        final ArrayList<ModelRenderSnapshot> submitted = new ArrayList<>();
        final ArrayList<Delivery> deliveries = new ArrayList<>();
        final BlendLibItemSpecialRenderer renderer;
        Consumer<Delivery> onDelivery = ignored -> { };

        Harness(Item item) throws Exception {
            this.item = item;
            activeServices = activeServices();
            previousServices = activeServices.getAndSet(null);
            BlendLibClientServices.initialize(registry, (snapshot, context) -> submitted.add(snapshot), runtime);
            BlendLibItemAnimations.clear();
            runtime.onPlayInit();
            var binding = binding(item);
            BlendLibItemAnimations.register(binding, IDLE, (stack, event) -> {
                var delivery = new Delivery(stack, event);
                deliveries.add(delivery);
                onDelivery.accept(delivery);
            });
            renderer = new BlendLibItemSpecialRenderer(binding);
            publish(1, ModelProfile.SKINNED_V1, 1, MARKERS, true, 1);
        }
        void at(double seconds) { nanos.set(Math.round(seconds * 1_000_000_000)); }
        void advance(double seconds) { nanos.addAndGet(Math.round(seconds * 1_000_000_000)); }
        ItemAnimationPlayback control(ItemStack stack) throws Exception { return controlledPlayback(stack, nanos); }
        void extract(ItemStack stack) { assertNotNull(renderer.extractArgument(stack).animatedSnapshot()); }
        void clearDeliveries() { deliveries.clear(); onDelivery = ignored -> { }; }
        List<String> keys() { return deliveries.stream().map(value -> value.event().event().eventKey().path()).toList(); }
        void publish(long generation, ModelProfile profile, float duration, List<AnimationEventDefinition> events,
                boolean descriptorLoop, double descriptorSpeed) {
            publish(fixture(generation, profile, duration, events, descriptorLoop, descriptorSpeed, IDLE));
        }
        void publish(LoadedModelHandle handle) {
            registry.publish(new ModelRegistryGeneration(handle.generationId(), Map.of(MODEL, handle), Map.of(), List.of()));
        }
        @Override public void close() {
            try {
                BlendLibItemAnimations.clear(); runtime.onWorldDisconnect(); registry.close();
            } finally {
                deliveries.clear(); onDelivery = ignored -> { };
                activeServices.set(previousServices);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> activeServices() throws Exception {
        var field = BlendLibClientServices.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        return (AtomicReference<Object>) field.get(null);
    }

    private static BlendLibItemBinding binding(Item item) {
        var id = BuiltInRegistries.ITEM.getKey(item);
        return new BlendLibItemBinding(id, MODEL, Identifier.withDefaultNamespace("item/" + id.getPath()));
    }

    private static ItemStack stack(Item item) {
        return new ItemStack(Holder.direct(item, DataComponents.COMMON_ITEM_COMPONENTS));
    }

    private static AnimationEventDefinition marker(double time, String key) {
        return new AnimationEventDefinition(time, BlendResourceId.parse("item_visual_event_test:" + key));
    }

    /** Only replace the unsampled test control; public facade, identity storage and runtime run unchanged. */
    @SuppressWarnings("unchecked")
    private static ItemAnimationPlayback controlledPlayback(ItemStack stack, AtomicLong nanos) throws Exception {
        var original = BlendLibItemAnimations.playback(stack);
        var field = BlendLibItemAnimations.class.getDeclaredField("INSTANCES");
        field.setAccessible(true);
        var instances = (ItemAnimationInstances) field.get(null);
        var entriesField = ItemAnimationInstances.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        var entries = (Map<?, ItemAnimationInstances.Entry>) entriesField.get(instances);
        for (var entry : entries.entrySet()) {
            if (entry.getValue().playback() == original) {
                var replacement = new ItemAnimationPlayback(IDLE, nanos::get);
                entry.setValue(new ItemAnimationInstances.Entry(entry.getValue().key(), replacement));
                return replacement;
            }
        }
        throw new AssertionError("Retained stack identity was not found");
    }

    private static com.liy.blendlib.core.model.MorphBindingTable morphBindings() {
        var binding = new com.liy.blendlib.core.model.MorphBindingTable.Binding(0, List.of("Preview"), 0,
                new float[] {0}, new float[] {0}, new float[] {1});
        return new com.liy.blendlib.core.model.MorphBindingTable(List.of(binding), Map.of(
                BlendResourceId.parse("test:preview"), new com.liy.blendlib.core.model.MorphBindingTable.Control(0, 0, 0, 0, 1)));
    }

    private static LoadedModelHandle fixture(long generation, ModelProfile profile, float duration,
            List<AnimationEventDefinition> events, boolean descriptorLoop, double descriptorSpeed,
            BlendAnimationKey declaredState) {
        boolean skinned = profile.skinned();
        var mesh = new MeshPrimitive("Surface", new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1}, new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2},
                skinned ? new int[12] : null, skinned ? new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0} : null);
        var clip = new AnimationClip("idle", List.of(new AnimationChannel(skinned ? 1 : 0, AnimationPath.TRANSLATION,
                Interpolation.LINEAR, duration == 0 ? new float[] {0} : new float[] {0, duration},
                duration == 0 ? new float[] {0, 0, 0} : new float[] {0, 0, 0, 1, 0, 0})));
        var animation = declaredState == null ? null : new AnimationDefinition(declaredState.resourceId(), Map.of(
                declaredState.resourceId(), new AnimationStateDefinition("idle", descriptorLoop, descriptorSpeed, 0,
                        NEXT.resourceId(), events),
                NEXT.resourceId(), new AnimationStateDefinition("idle", true, 1, 0, null,
                        List.of(marker(duration == 0 ? 0 : duration / 2, "wrong_next_state")))));
        var material = new MaterialDefinition(BlendResourceId.parse("item_visual_event_test:textures/item.png"),
                MaterialDefinition.Mode.OPAQUE, false, false, null);
        var asset = new ModelAsset(MODEL.resourceId(), MODEL.descriptorResourceId(), generation,
                profile, 1, Map.of("Surface", material), animation,
                List.of(new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, skinned ? 0 : -1, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0), List.of(new ModelPrimitive(0, 0, 0, mesh)),
                skinned ? new Skeleton(List.of(new Skin("ItemSkin", 1, List.of(1),
                        new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}))) : null,
                List.of(clip), new SocketTable(Map.of()), Bounds.fromPositions(mesh.positions()), List.of(),
                profile == ModelProfile.SKINNED_MORPH_CPU_V1 ? morphBindings()
                        : com.liy.blendlib.core.model.MorphBindingTable.empty(),
                profile == ModelProfile.SKINNED_MORPH_CPU_V1 ? Map.of(mesh,
                        new com.liy.blendlib.core.model.MorphTargetSet(List.of("Preview"), 3,
                                new float[][] {new float[9]}, new float[][] {new float[9]})) : Map.of());
        return new LoadedModelHandle(MODEL, asset, skinned ? SkinnedRenderHandle.prepare(MODEL, asset)
                : StaticRigidRenderHandle.prepare(MODEL, asset));
    }

    /** Metadata remains valid, while this concrete handle intentionally has no supported extractor. */
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
}
