package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationEventDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkinnedAnimationRuntimeTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("runtime_test:skinned/actor");
    private static final BlendModelKey STATIC_MODEL = BlendModelKey.parse("runtime_test:static/prop");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("runtime_test:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("runtime_test:walk");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("runtime_test:attack");
    private static final BlendResourceId IDLE_ENTRY = BlendResourceId.parse("runtime_test:events/idle_entry");
    private static final BlendResourceId WALK_ENTRY = BlendResourceId.parse("runtime_test:events/walk_entry");
    private static final BlendResourceId ATTACK_ENTRY = BlendResourceId.parse("runtime_test:events/attack_entry");

    @Test
    void bindsCanonicalLoadedSkinnedAssetSamplesIdleAndCapturesItsFrame() {
        SkinnedFixture fixture = skinnedFixture(1L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());

        SkinnedAnimationRuntimeResult result = harness.runtime().extract(
                input(MODEL, 7, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle()))
                .orElseThrow();

        assertEquals(IDLE, result.advance().state());
        assertEquals(0.0d, result.advance().timeSeconds());
        assertEquals(List.of(IDLE_ENTRY), result.advance().visualEvents().stream()
                .map(event -> event.eventKey())
                .toList());
        assertSame(fixture.handle(), result.frame().renderSnapshot().handle());
        assertEquals(MODEL, result.frame().renderSnapshot().handle().modelKey());
        assertEquals(1L, result.frame().renderSnapshot().generation());
        assertEquals(1, harness.lifecycle().registry().size());
        assertEquals(1, harness.runtime().preparedAssetCount());
    }

    @Test
    void bindsControllerAndSamplerByModelGenerationAndRetiresPriorGenerationState() {
        SkinnedFixture first = skinnedFixture(1L);
        SkinnedFixture replacement = skinnedFixture(2L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), first.loaded());
        harness.runtime().extract(input(MODEL, 11, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, first.handle()))
                .orElseThrow();

        assertEquals(1, harness.runtime().preparedAssetCount());
        assertEquals(1, harness.runtime().trackedClockCount());
        publish(harness.models(), replacement.loaded());

        SkinnedAnimationRuntimeResult rebound = harness.runtime().extract(
                input(MODEL, 11, 1.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, replacement.handle()))
                .orElseThrow();

        assertEquals(2L, rebound.frame().renderSnapshot().generation());
        assertEquals(1, harness.runtime().preparedAssetCount());
        assertEquals(1, harness.runtime().trackedClockCount());
        assertEquals(1, harness.lifecycle().registry().size());
        assertEquals(2L, harness.lifecycle().registry().find(rebound.instanceKey()).orElseThrow().generation());
    }

    @Test
    void missingOrStaticActiveModelsProduceNoSkinnedRuntimeResult() {
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        StaticFixture staticFixture = staticFixture(1L);

        assertTrue(harness.runtime().extract(
                input(MODEL, 15, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, staticFixture.handle())).isEmpty());

        publish(harness.models(), staticFixture.loaded());
        assertTrue(harness.runtime().extract(
                input(STATIC_MODEL, 15, 1.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, staticFixture.handle())).isEmpty());
        assertEquals(0, harness.runtime().preparedAssetCount());
        assertEquals(0, harness.runtime().trackedClockCount());
    }

    @Test
    void givesTwoEntitiesIndependentControllerTimesForOneAsset() {
        SkinnedFixture fixture = skinnedFixture(3L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        harness.runtime().extract(input(MODEL, 21, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle()))
                .orElseThrow();
        harness.runtime().extract(input(MODEL, 22, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle()))
                .orElseThrow();

        SkinnedAnimationRuntimeResult first = harness.runtime().extract(
                input(MODEL, 21, 4.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult second = harness.runtime().extract(
                input(MODEL, 22, 1.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();

        assertEquals(0.20d, first.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.05d, second.advance().timeSeconds(), 1.0e-9d);
        assertTrue(first.advance().timeSeconds() > second.advance().timeSeconds());
        assertEquals(2, harness.lifecycle().registry().size());
    }

    @Test
    void farAndHiddenBucketsDoNotAdvanceOnEveryExtraction() {
        SkinnedFixture fixture = skinnedFixture(4L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        harness.runtime().extract(input(MODEL, 31, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_FAR, fixture.handle()))
                .orElseThrow();

        SkinnedAnimationRuntimeResult farNotDue = harness.runtime().extract(
                input(MODEL, 31, 1.0d, IDLE, AnimationUpdateBucket.VISIBLE_FAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult farDue = harness.runtime().extract(
                input(MODEL, 31, 4.0d, IDLE, AnimationUpdateBucket.VISIBLE_FAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult farSameCadenceTick = harness.runtime().extract(
                input(MODEL, 31, 4.5d, IDLE, AnimationUpdateBucket.VISIBLE_FAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult hiddenDue = harness.runtime().extract(
                input(MODEL, 31, 8.0d, IDLE, AnimationUpdateBucket.HIDDEN, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult hiddenSameCadenceTick = harness.runtime().extract(
                input(MODEL, 31, 8.5d, IDLE, AnimationUpdateBucket.HIDDEN, fixture.handle())).orElseThrow();

        assertEquals(0.0d, farNotDue.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.20d, farDue.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.20d, farSameCadenceTick.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.40d, hiddenDue.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.40d, hiddenSameCadenceTick.advance().timeSeconds(), 1.0e-9d);
    }

    @Test
    void stateTriggerRetainsEntryVisualEventsAsObservationOnlyResults() {
        SkinnedFixture fixture = skinnedFixture(5L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());

        SkinnedAnimationRuntimeResult idle = harness.runtime().extract(
                input(MODEL, 41, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult attack = harness.runtime().extract(
                input(MODEL, 41, 1.0d, ATTACK, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();

        assertEquals(List.of(IDLE_ENTRY), idle.advance().visualEvents().stream().map(event -> event.eventKey()).toList());
        assertEquals(ATTACK, attack.advance().state());
        assertEquals(0.0d, attack.advance().timeSeconds(), 1.0e-9d);
        assertEquals(List.of(ATTACK_ENTRY), attack.advance().visualEvents().stream()
                .map(event -> event.eventKey())
                .toList());
    }

    @Test
    void entityUnloadBlockEntityUnloadAndDisconnectClearLifecycleAndRuntimeState() {
        SkinnedFixture fixture = skinnedFixture(6L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        SkinnedAnimationRuntimeResult first = harness.runtime().extract(
                input(MODEL, 51, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult second = harness.runtime().extract(
                input(MODEL, 52, 0.0d, WALK, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();

        assertEquals(1, harness.runtime().onEntityUnload(51));
        assertTrue(harness.lifecycle().registry().find(first.instanceKey()).isEmpty());
        assertTrue(harness.lifecycle().registry().find(second.instanceKey()).isPresent());
        assertEquals(1, harness.runtime().trackedClockCount());

        BlendInstanceKey.BlockEntity blockKey = new BlendInstanceKey.BlockEntity(
                BlendResourceId.parse("minecraft:overworld"), 91L);
        harness.lifecycle().registry().bind(
                blockKey, MODEL, fixture.loaded().generationId(), AnimationControllerDefinition.fromModelAsset(fixture.asset()));
        assertEquals(1, harness.runtime().onBlockEntityUnload(blockKey));
        assertTrue(harness.lifecycle().registry().find(blockKey).isEmpty());

        harness.runtime().onWorldDisconnect();
        assertEquals(0, harness.lifecycle().registry().size());
        assertEquals(0, harness.runtime().trackedClockCount());
        assertEquals(0, harness.runtime().preparedAssetCount());
        assertFalse(harness.lifecycle().registry().find(second.instanceKey()).isPresent());
    }

    @Test
    void lateEntityUnloadAfterDisconnectDoesNotRecreateAnEntityKeyOrRuntimeState() {
        SkinnedFixture fixture = skinnedFixture(12L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        SkinnedAnimationRuntimeResult result = harness.runtime().extract(
                input(MODEL, 63, 0.0d, IDLE, AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();

        assertTrue(harness.runtime().activeEntityKey(63).isPresent());
        assertTrue(harness.lifecycle().registry().find(result.instanceKey()).isPresent());

        harness.runtime().onWorldDisconnect();

        assertTrue(harness.runtime().activeEntityKey(63).isEmpty());
        assertEquals(0, harness.runtime().onEntityUnload(63));
        assertEquals(0, harness.lifecycle().registry().size());
        assertEquals(0, harness.runtime().trackedClockCount());
        assertEquals(0, harness.runtime().preparedAssetCount());
    }

    @Test
    void genericRuntimeAcceptsTypedBlockEntityKeyWithoutAnEntityId() {
        SkinnedFixture fixture = skinnedFixture(7L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey.BlockEntity blockKey = new BlendInstanceKey.BlockEntity(
                BlendResourceId.parse("minecraft:overworld"), 0x1020304050607080L);

        SkinnedAnimationRuntimeResult result = harness.runtime().extract(input(
                MODEL,
                blockKey,
                80L,
                0.0F,
                IDLE,
                Optional.empty(),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();

        assertEquals(blockKey, result.instanceKey());
        assertTrue(harness.lifecycle().registry().find(blockKey).isPresent());
        assertEquals(1, harness.runtime().onBlockEntityUnload(blockKey));
        assertTrue(harness.lifecycle().registry().find(blockKey).isEmpty());
    }

    @Test
    void synchronizedStateUsesStartGameTickAndSpeedForDeterministicLocalTime() {
        SkinnedFixture fixture = skinnedFixture(8L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey.Entity key = new BlendInstanceKey.Entity("sync-test-session", 71);
        SyncedAnimationState state = synced(IDLE, 100L, 1L, 2.0F);

        SkinnedAnimationRuntimeResult initial = harness.runtime().extract(input(
                MODEL,
                key,
                105L,
                0.5F,
                WALK,
                Optional.of(state),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult later = harness.runtime().extract(input(
                MODEL,
                key,
                106L,
                0.5F,
                WALK,
                Optional.of(state),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();

        assertEquals(IDLE, initial.advance().state());
        assertEquals(0.55d, initial.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.65d, later.advance().timeSeconds(), 1.0e-9d);
    }

    @Test
    void synchronizedTimelineComposesNetworkAndDescriptorSpeedsBeforeAndAfterCorrection() {
        long generation = 80L;
        int entityId = 80;
        for (double descriptorSpeed : new double[] {0.5d, 1.0d, 2.0d}) {
            for (float networkSpeed : new float[] {0.5F, 1.0F, 2.0F}) {
                SkinnedFixture fixture = skinnedFixture(generation++, descriptorSpeed, 1.0d, 1.0d);
                RuntimeHarness harness = harness();
                harness.runtime().onPlayInit();
                publish(harness.models(), fixture.loaded());
                BlendInstanceKey.Entity key = new BlendInstanceKey.Entity("speed-composition", entityId++);
                SyncedAnimationState state = synced(IDLE, 100L, 1L, networkSpeed);

                SkinnedAnimationRuntimeResult corrected = harness.runtime().extract(input(
                        MODEL,
                        key,
                        102L,
                        0.0F,
                        WALK,
                        Optional.of(state),
                        AnimationUpdateBucket.VISIBLE_NEAR,
                        fixture.handle())).orElseThrow();
                SkinnedAnimationRuntimeResult continued = harness.runtime().extract(input(
                        MODEL,
                        key,
                        103L,
                        0.0F,
                        WALK,
                        Optional.of(state),
                        AnimationUpdateBucket.VISIBLE_NEAR,
                        fixture.handle())).orElseThrow();

                assertEquals(0.1d * networkSpeed * descriptorSpeed,
                        corrected.advance().timeSeconds(), 1.0e-8d);
                assertEquals(0.15d * networkSpeed * descriptorSpeed,
                        continued.advance().timeSeconds(), 1.0e-8d);
            }
        }
    }

    @Test
    void newerSequenceCorrectsComposedTimelineThenRepeatedPayloadContinuesIt() {
        SkinnedFixture fixture = skinnedFixture(90L, 2.0d, 1.0d, 1.0d);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey.Entity key = new BlendInstanceKey.Entity("speed-correction", 90);

        SkinnedAnimationRuntimeResult first = harness.runtime().extract(input(
                MODEL, key, 105L, 0.0F, WALK, Optional.of(synced(IDLE, 100L, 1L, 0.5F)),
                AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult corrected = harness.runtime().extract(input(
                MODEL, key, 106L, 0.0F, WALK, Optional.of(synced(IDLE, 104L, 2L, 1.5F)),
                AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult continued = harness.runtime().extract(input(
                MODEL, key, 107L, 0.0F, WALK, Optional.of(synced(IDLE, 104L, 2L, 1.5F)),
                AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();

        assertEquals(0.25d, first.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.30d, corrected.advance().timeSeconds(), 1.0e-9d);
        assertEquals(0.45d, continued.advance().timeSeconds(), 1.0e-9d);
    }

    @Test
    void latePersistentReplayResolvesNonLoopNextWithoutHistoricalEvents() {
        SkinnedFixture fixture = skinnedFixture(91L, 0.5d, 1.0d, 2.0d);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey.Entity key = new BlendInstanceKey.Entity("tracking-replay", 91);
        SyncedAnimationState replay = new SyncedAnimationState(ATTACK, 100L, 7L, 2.0F, 17L, true);

        SkinnedAnimationRuntimeResult late = harness.runtime().extract(input(
                MODEL, key, 115L, 0.0F, WALK, Optional.of(replay),
                AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult continued = harness.runtime().extract(input(
                MODEL, key, 116L, 0.0F, WALK, Optional.of(replay),
                AnimationUpdateBucket.VISIBLE_NEAR, fixture.handle())).orElseThrow();

        assertEquals(IDLE, late.advance().state());
        assertEquals(0.5d, late.advance().timeSeconds(), 1.0e-9d);
        assertTrue(late.advance().visualEvents().isEmpty());
        assertEquals(IDLE, continued.advance().state());
        assertEquals(0.55d, continued.advance().timeSeconds(), 1.0e-9d);
        assertTrue(continued.advance().visualEvents().isEmpty());
    }

    @Test
    void synchronizedStateRejectsNonPositiveAndNonFiniteNetworkSpeeds() {
        for (float invalid : new float[] {
                Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1.0F, 0.0F,
                Math.nextUp(SyncedAnimationState.MAX_SPEED)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> synced(IDLE, 0L, 0L, invalid));
        }
    }

    @Test
    void outOfOrderSynchronizedSequenceCannotRewindControllerState() {
        SkinnedFixture fixture = skinnedFixture(9L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey.Entity key = new BlendInstanceKey.Entity("sync-test-session", 72);

        SkinnedAnimationRuntimeResult accepted = harness.runtime().extract(input(
                MODEL,
                key,
                110L,
                0.0F,
                IDLE,
                Optional.of(synced(ATTACK, 100L, 4L, 1.0F)),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult stale = harness.runtime().extract(input(
                MODEL,
                key,
                111L,
                0.0F,
                IDLE,
                Optional.of(synced(IDLE, 100L, 3L, 4.0F)),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();

        assertEquals(ATTACK, accepted.advance().state());
        assertEquals(0.50d, accepted.advance().timeSeconds(), 1.0e-9d);
        assertEquals(ATTACK, stale.advance().state());
        assertEquals(0.55d, stale.advance().timeSeconds(), 1.0e-9d);
    }

    @Test
    void newerSameAnimationSequenceCanCorrectItsPlaybackOrigin() {
        SkinnedFixture fixture = skinnedFixture(10L);
        RuntimeHarness harness = harness();
        harness.runtime().onPlayInit();
        publish(harness.models(), fixture.loaded());
        BlendInstanceKey.Entity key = new BlendInstanceKey.Entity("sync-test-session", 73);

        SkinnedAnimationRuntimeResult first = harness.runtime().extract(input(
                MODEL,
                key,
                110L,
                0.0F,
                WALK,
                Optional.of(synced(IDLE, 100L, 1L, 1.0F)),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();
        SkinnedAnimationRuntimeResult restarted = harness.runtime().extract(input(
                MODEL,
                key,
                111L,
                0.0F,
                WALK,
                Optional.of(synced(IDLE, 110L, 2L, 1.0F)),
                AnimationUpdateBucket.VISIBLE_NEAR,
                fixture.handle())).orElseThrow();

        assertEquals(0.50d, first.advance().timeSeconds(), 1.0e-9d);
        assertEquals(IDLE, restarted.advance().state());
        assertEquals(0.05d, restarted.advance().timeSeconds(), 1.0e-9d);
    }

    private static RuntimeHarness harness() {
        ClientModelRegistry models = new ClientModelRegistry();
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(32);
        return new RuntimeHarness(models, lifecycle, new SkinnedAnimationRuntime(models, lifecycle));
    }

    private static void publish(ClientModelRegistry registry, LoadedModelHandle loaded) {
        registry.publish(new ModelRegistryGeneration(
                loaded.generationId(), Map.of(loaded.key(), loaded), Map.of(), List.of()));
    }

    private static SkinnedAnimationRuntimeInput input(
            BlendModelKey key,
            int entityId,
            double ageInTicks,
            BlendAnimationKey animation,
            AnimationUpdateBucket bucket,
            SkinnedRenderHandle handle) {
        return input(
                key,
                new BlendInstanceKey.Entity("runtime-test-session", entityId),
                wholeGameTick(ageInTicks),
                partialTick(ageInTicks),
                animation,
                Optional.empty(),
                bucket,
                handle);
    }

    private static SkinnedAnimationRuntimeInput input(
            BlendModelKey key,
            int entityId,
            double ageInTicks,
            BlendAnimationKey animation,
            AnimationUpdateBucket bucket,
            StaticRigidRenderHandle handle) {
        return input(
                key,
                new BlendInstanceKey.Entity("runtime-test-session", entityId),
                wholeGameTick(ageInTicks),
                partialTick(ageInTicks),
                animation,
                Optional.empty(),
                bucket,
                handle);
    }

    private static SkinnedAnimationRuntimeInput input(
            BlendModelKey key,
            BlendInstanceKey instanceKey,
            long clientGameTick,
            float partialTick,
            BlendAnimationKey fallbackAnimation,
            Optional<SyncedAnimationState> syncedAnimation,
            AnimationUpdateBucket bucket,
            SkinnedRenderHandle handle) {
        return new SkinnedAnimationRuntimeInput(
                key,
                instanceKey,
                clientGameTick,
                partialTick,
                fallbackAnimation,
                syncedAnimation,
                bucket,
                new SkinnedExtractionRequest(
                        Transform.IDENTITY,
                        0x000A000B,
                        7,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(handle.bounds(), true)));
    }

    private static SkinnedAnimationRuntimeInput input(
            BlendModelKey key,
            BlendInstanceKey instanceKey,
            long clientGameTick,
            float partialTick,
            BlendAnimationKey fallbackAnimation,
            Optional<SyncedAnimationState> syncedAnimation,
            AnimationUpdateBucket bucket,
            StaticRigidRenderHandle handle) {
        return new SkinnedAnimationRuntimeInput(
                key,
                instanceKey,
                clientGameTick,
                partialTick,
                fallbackAnimation,
                syncedAnimation,
                bucket,
                new SkinnedExtractionRequest(
                        Transform.IDENTITY,
                        0x000A000B,
                        7,
                        0xFFFFFFFF,
                        RenderVisibility.VISIBLE,
                        new CullingMetadata(handle.bounds(), true)));
    }

    private static SyncedAnimationState synced(
            BlendAnimationKey key, long startGameTick, long sequence, float speed) {
        return new SyncedAnimationState(key, startGameTick, sequence, speed, 17L, false);
    }

    private static long wholeGameTick(double gameTimeInTicks) {
        if (!Double.isFinite(gameTimeInTicks) || gameTimeInTicks < 0.0d
                || gameTimeInTicks > Long.MAX_VALUE) {
            throw new IllegalArgumentException("gameTimeInTicks must be finite and non-negative");
        }
        return (long) Math.floor(gameTimeInTicks);
    }

    private static float partialTick(double gameTimeInTicks) {
        long wholeTick = wholeGameTick(gameTimeInTicks);
        return (float) (gameTimeInTicks - wholeTick);
    }

    private static SkinnedFixture skinnedFixture(long generation) {
        return skinnedFixture(generation, 1.0d, 1.0d, 1.0d);
    }

    private static SkinnedFixture skinnedFixture(
            long generation, double idleSpeed, double walkSpeed, double attackSpeed) {
        MeshPrimitive geometry = skinnedGeometry();
        ModelAsset asset = new ModelAsset(
                MODEL.resourceId(),
                MODEL.descriptorResourceId(),
                generation,
                ModelProfile.SKINNED_V1,
                1.0d,
                Map.of("SkinSurface", material("skin")),
                animationDefinition(idleSpeed, walkSpeed, attackSpeed),
                List.of(
                        new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                        new ModelNode(1, "Bone", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                new Skeleton(List.of(new Skin("RuntimeSkin", 1, List.of(1), identityMatrix()))),
                List.of(clip("idle", 0.0f), clip("walk", 1.0f), clip("attack", 2.0f)),
                new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(MODEL, asset);
        return new SkinnedFixture(asset, handle, new LoadedModelHandle(MODEL, asset, handle));
    }

    private static StaticFixture staticFixture(long generation) {
        MeshPrimitive geometry = new MeshPrimitive(
                "StaticSurface",
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                null,
                null);
        ModelAsset asset = new ModelAsset(
                STATIC_MODEL.resourceId(),
                STATIC_MODEL.descriptorResourceId(),
                generation,
                ModelProfile.RIGID_V1,
                1.0d,
                Map.of("StaticSurface", material("static")),
                null,
                List.of(new ModelNode(0, "StaticRoot", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, geometry)),
                null,
                List.of(),
                new SocketTable(Map.of()),
                Bounds.fromPositions(geometry.positions()),
                List.of());
        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepare(STATIC_MODEL, asset);
        return new StaticFixture(handle, new LoadedModelHandle(STATIC_MODEL, asset, handle));
    }

    private static AnimationDefinition animationDefinition() {
        return animationDefinition(1.0d, 1.0d, 1.0d);
    }

    private static AnimationDefinition animationDefinition(
            double idleSpeed, double walkSpeed, double attackSpeed) {
        return new AnimationDefinition(IDLE.resourceId(), Map.of(
                IDLE.resourceId(), state("idle", true, idleSpeed, null, IDLE_ENTRY),
                WALK.resourceId(), state("walk", true, walkSpeed, null, WALK_ENTRY),
                ATTACK.resourceId(), state("attack", false, attackSpeed, IDLE.resourceId(), ATTACK_ENTRY)));
    }

    private static AnimationStateDefinition state(
            String clip, boolean loop, BlendResourceId next, BlendResourceId entryEvent) {
        return state(clip, loop, 1.0d, next, entryEvent);
    }

    private static AnimationStateDefinition state(
            String clip, boolean loop, double speed, BlendResourceId next, BlendResourceId entryEvent) {
        return new AnimationStateDefinition(
                clip,
                loop,
                speed,
                0.10d,
                next,
                List.of(new AnimationEventDefinition(0.0d, entryEvent)));
    }

    private static AnimationClip clip(String name, float endX) {
        return new AnimationClip(name, List.of(new AnimationChannel(
                1,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 0.0f, endX, 0.0f, 0.0f})));
    }

    private static MeshPrimitive skinnedGeometry() {
        return new MeshPrimitive(
                "SkinSurface",
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f});
    }

    private static MaterialDefinition material(String name) {
        return new MaterialDefinition(
                BlendResourceId.parse("runtime_test:textures/" + name + ".png"),
                MaterialDefinition.Mode.OPAQUE,
                false,
                false,
                null);
    }

    private static float[] identityMatrix() {
        return new float[] {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private record RuntimeHarness(
            ClientModelRegistry models,
            ClientAnimationLifecycleBridge lifecycle,
            SkinnedAnimationRuntime runtime) {
    }

    private record SkinnedFixture(ModelAsset asset, SkinnedRenderHandle handle, LoadedModelHandle loaded) {
    }

    private record StaticFixture(StaticRigidRenderHandle handle, LoadedModelHandle loaded) {
    }
}
