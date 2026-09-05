package com.liy.blendlib.fabric.client.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Clip;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerState;
import com.liy.blendlib.core.animation.v2.AnimationV2DiagnosticCode;
import com.liy.blendlib.core.animation.v2.AnimationV2InstancePlan;
import com.liy.blendlib.core.animation.v2.AnimationV2Keyframe;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import com.liy.blendlib.core.animation.v2.AnimationV2PlaybackMode;
import com.liy.blendlib.core.animation.v2.AnimationV2Pose;
import com.liy.blendlib.core.animation.v2.BoneMask;
import com.liy.blendlib.core.animation.v2.BoneSchema;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntent;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentMode;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentReconciliationOutcome;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Client owner regression probes for X2 frame, ingress, and generation-retention findings. */
class ClientAnimationV2ReviewerRegressionTest {
    private static final BlendResourceId CONTROLLER = id("controller");
    private static final BlendAnimationKey IDLE = key("idle");
    private static final BlendAnimationKey WALK = key("walk");
    private static final BlendAnimationKey RUN = key("run");

    @Test
    void tickPartialLateFutureWrapAndLargeDeltaAllUseOneFrameBoundary() {
        AnimationIntentScope scope = scope("session-a", 1L);

        ClientAnimationV2Runtime tick = installed(scope, plan(1.0D, 600.0D));
        tick.advance(frame(10L, 0.0F));
        tick.receiveIntent(intent(scope, CONTROLLER, WALK, 10L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertTime(0.5D, tick.advance(frame(20L, 0.0F)));

        ClientAnimationV2Runtime partial = installed(scope, plan(1.0D, 600.0D));
        partial.advance(frame(10L, 0.25F));
        partial.receiveIntent(intent(scope, CONTROLLER, WALK, 10L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertTime(0.5375D, partial.advance(frame(20L, 0.75F)));

        ClientAnimationV2Runtime late = installed(scope, plan(1.0D, 600.0D));
        late.advance(frame(10L, 0.0F));
        late.receiveIntent(intent(scope, CONTROLLER, WALK, 0L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertTime(1.0D, late.advance(frame(20L, 0.0F)));

        ClientAnimationV2Runtime future = installed(scope, plan(1.0D, 600.0D));
        future.advance(frame(10L, 0.0F));
        future.receiveIntent(intent(scope, CONTROLLER, WALK, 20L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertEquals(IDLE, future.advance(frame(15L, 0.0F)).evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertTime(0.0D, future.advance(frame(20L, 0.0F)));

        ClientAnimationV2Runtime wrapped = installed(scope, plan(1.0D, 600.0D));
        wrapped.advance(frame(Long.MAX_VALUE, 0.0F));
        wrapped.receiveIntent(intent(scope, CONTROLLER, WALK, Long.MAX_VALUE, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertTime(0.05D, wrapped.advance(frame(Long.MIN_VALUE, 0.0F)));

        ClientAnimationV2Runtime large = installed(scope, plan(1.0D, 600.0D));
        large.advance(frame(0L, 0.0F));
        large.receiveIntent(intent(scope, CONTROLLER, WALK, 10_000L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        ClientAnimationV2Snapshot largeSnapshot = large.advance(frame(12_001L, 0.0F));
        assertTime(100.05D, largeSnapshot);
        assertTrue(largeSnapshot.evaluation().orElseThrow().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == AnimationV2DiagnosticCode.ADVANCE_DELTA_CLAMPED));
    }

    @Test
    void callbackArrivalOrderCannotChooseAWinnerForAConflictingSequence() throws InterruptedException {
        AnimationIntentScope scope = scope("session-a", 1L);
        ClientAnimationV2Runtime runtime = installed(scope, plan(1.0D, 10.0D));
        runtime.advance(frame(0L, 0.0F));
        AtomicReference<ClientAnimationV2IngressOutcome> walkOutcome = new AtomicReference<>();
        AtomicReference<ClientAnimationV2IngressOutcome> runOutcome = new AtomicReference<>();
        Thread walk = new Thread(() -> walkOutcome.set(runtime.receiveIntent(
                intent(scope, CONTROLLER, WALK, 0L, 4L, 1.0F, AnimationIntentMode.ONE_SHOT))));
        Thread run = new Thread(() -> runOutcome.set(runtime.receiveIntent(
                intent(scope, CONTROLLER, RUN, 0L, 4L, 1.0F, AnimationIntentMode.ONE_SHOT))));
        walk.start();
        run.start();
        walk.join();
        run.join();

        ClientAnimationV2Snapshot snapshot = runtime.advance(frame(0L, 0.0F));
        assertEquals(ClientAnimationV2IngressOutcome.QUEUED, walkOutcome.get());
        assertEquals(ClientAnimationV2IngressOutcome.QUEUED, runOutcome.get());
        assertEquals(List.of(
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED,
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED), outcomes(snapshot));
        assertEquals(IDLE, snapshot.evaluation().orElseThrow().playheads().get(CONTROLLER).state());

        ClientAnimationV2Runtime splitDrain = installed(scope, plan(1.0D, 10.0D));
        splitDrain.advance(frame(0L, 0.0F));
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE; index++) {
            splitDrain.receiveIntent(intent(scope, CONTROLLER, WALK, 0L, 5L, 1.0F, AnimationIntentMode.ONE_SHOT));
        }
        splitDrain.receiveIntent(intent(scope, CONTROLLER, RUN, 0L, 5L, 1.0F, AnimationIntentMode.ONE_SHOT));
        ClientAnimationV2Snapshot split = splitDrain.advance(frame(0L, 0.0F));
        assertTrue(outcomes(split).contains(AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED));
        assertEquals(IDLE, split.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertEquals(IDLE, splitDrain.advance(frame(0L, 0.0F)).evaluation().orElseThrow().playheads().get(CONTROLLER).state());
    }

    @Test
    void nonAdjacentIngressMembersAreReconciledAsOneGroupBeforePublication() {
        AnimationIntentScope scope = scope("session-a", 1L);
        ClientAnimationV2Runtime runtime = installed(scope, plan(1.0D, 10.0D));
        runtime.advance(frame(0L, 0.0F));
        runtime.receiveIntent(intent(scope, CONTROLLER, WALK, 0L, 5L, 1.0F, AnimationIntentMode.ONE_SHOT));
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE - 1; index++) {
            runtime.receiveIntent(intent(scope, id("a-filler-" + index), WALK, 0L, 1L,
                    1.0F, AnimationIntentMode.ONE_SHOT));
        }
        runtime.receiveIntent(intent(scope, CONTROLLER, RUN, 0L, 5L, 1.0F, AnimationIntentMode.ONE_SHOT));

        ClientAnimationV2Snapshot first = runtime.advance(frame(0L, 0.0F));
        assertEquals(IDLE, first.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertTrue(outcomes(first).contains(AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED));
        assertEquals(IDLE, runtime.advance(frame(0L, 0.0F)).evaluation().orElseThrow().playheads().get(CONTROLLER).state());
    }

    @Test
    void laterSameSequenceConflictRevokesClientAppliedAndPersistentState() {
        AnimationIntentScope scope = scope("session-a", 1L);
        ClientAnimationV2Runtime runtime = installed(scope, plan(1.0D, 10.0D));
        runtime.advance(frame(0L, 0.0F));
        runtime.receiveIntent(intent(scope, CONTROLLER, WALK, 0L, 10L, 1.0F, AnimationIntentMode.PERSISTENT));
        assertEquals(WALK, runtime.advance(frame(0L, 0.0F)).evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertEquals(1, runtime.persistentReplay().size());

        runtime.receiveIntent(intent(scope, CONTROLLER, RUN, 0L, 10L, 1.0F, AnimationIntentMode.PERSISTENT));
        ClientAnimationV2Snapshot rejected = runtime.advance(frame(0L, 0.0F));
        assertTrue(outcomes(rejected).contains(AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED));
        assertEquals(IDLE, rejected.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertTrue(runtime.persistentReplay().isEmpty());
    }

    @Test
    void mixedScopeSameSequenceCannotRejectActiveClientStateOrReplay() {
        AnimationIntentScope active = scope("session-m", "world-m", 7, 2L);
        List<ScopeFenceCase> foreignCases = List.of(
                new ScopeFenceCase(scope("session-a", "world-m", 7, 2L), true),
                new ScopeFenceCase(scope("session-z", "world-m", 7, 2L), false),
                new ScopeFenceCase(scope("session-m", "world-a", 7, 2L), true),
                new ScopeFenceCase(scope("session-m", "world-z", 7, 2L), false),
                new ScopeFenceCase(scope("session-m", "world-m", 6, 2L), true),
                new ScopeFenceCase(scope("session-m", "world-m", 8, 2L), false),
                new ScopeFenceCase(scope("session-m", "world-m", 7, 1L), true),
                new ScopeFenceCase(scope("session-m", "world-m", 7, 3L), false));

        for (ScopeFenceCase foreignCase : foreignCases) {
            ClientAnimationV2Runtime runtime = installed(active, plan(1.0D, 10.0D));
            runtime.advance(frame(0L, 0.0F));
            AnimationIntent persistent = intent(active, CONTROLLER, WALK, 0L, 10L, 1.0F,
                    AnimationIntentMode.PERSISTENT);
            runtime.receiveIntent(persistent);
            assertEquals(WALK, runtime.advance(frame(0L, 0.0F)).evaluation().orElseThrow()
                    .playheads().get(CONTROLLER).state());

            AnimationIntent foreign = intent(foreignCase.scope(), CONTROLLER, RUN, 0L, 10L, 1.0F,
                    AnimationIntentMode.PERSISTENT);
            assertEquals(foreignCase.sortsBeforeActive(),
                    AnimationIntent.CANONICAL_ORDER.compare(foreign, persistent) < 0);
            runtime.receiveIntent(foreign);
            runtime.receiveIntent(persistent);
            ClientAnimationV2Snapshot mixed = runtime.advance(frame(0L, 0.0F));

            assertEquals(AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED, outcomeFor(mixed, persistent));
            assertEquals(AnimationIntentReconciliationOutcome.SCOPE_REJECTED, outcomeFor(mixed, foreign));
            assertFalse(outcomes(mixed).contains(AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED));
            assertEquals(WALK, mixed.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
            assertEquals(List.of(persistent), runtime.persistentReplay());
        }
    }

    @Test
    void completeOverBudgetGroupsDeferWithoutLossAndLaterProgress() {
        AnimationIntentScope scope = scope("session-a", 1L);
        ClientAnimationV2Runtime runtime = installed(scope, plan(1.0D, 10.0D));
        runtime.advance(frame(0L, 0.0F));
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE; index++) {
            runtime.receiveIntent(intent(scope, id("a-frame-" + index), WALK, 0L, 1L,
                    1.0F, AnimationIntentMode.ONE_SHOT));
        }
        runtime.receiveIntent(intent(scope, CONTROLLER, WALK, 0L, 77L, 1.0F, AnimationIntentMode.ONE_SHOT));
        runtime.receiveIntent(intent(scope, CONTROLLER, WALK, 0L, 77L, 1.0F, AnimationIntentMode.ONE_SHOT));

        ClientAnimationV2Snapshot first = runtime.advance(frame(0L, 0.0F));
        assertEquals(IDLE, first.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertTrue(outcomes(first).contains(AnimationIntentReconciliationOutcome.DRAIN_BUDGET_EXHAUSTED));

        ClientAnimationV2Snapshot second = runtime.advance(frame(0L, 0.0F));
        assertEquals(WALK, second.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertTrue(outcomes(second).contains(AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED));
    }

    @Test
    void boundedIngressReportsOverflowAndDrainDeferralWithoutGrowingRetention() {
        AnimationIntentScope scope = scope("session-a", 1L);
        ClientAnimationV2Runtime runtime = installed(scope, plan(1.0D, 10.0D));
        runtime.advance(frame(0L, 0.0F));
        for (int sequence = 0; sequence < AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE; sequence++) {
            assertEquals(ClientAnimationV2IngressOutcome.QUEUED, runtime.receiveIntent(
                    intent(scope, CONTROLLER, WALK, 0L, sequence, 1.0F, AnimationIntentMode.ONE_SHOT)));
        }
        assertEquals(ClientAnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.receiveIntent(
                intent(scope, CONTROLLER, WALK, 0L, AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE,
                        1.0F, AnimationIntentMode.ONE_SHOT)));

        ClientAnimationV2Snapshot first = runtime.advance(frame(0L, 0.0F));
        assertTrue(outcomes(first).contains(AnimationIntentReconciliationOutcome.INGRESS_QUEUE_OVERFLOW));
        assertTrue(outcomes(first).contains(AnimationIntentReconciliationOutcome.DRAIN_BUDGET_EXHAUSTED));
        assertEquals(1, runtime.retainedControllerCount());
        ClientAnimationV2Snapshot second = runtime.advance(frame(0L, 0.0F));
        assertFalse(outcomes(second).contains(AnimationIntentReconciliationOutcome.DRAIN_BUDGET_EXHAUSTED));
        assertEquals(1, runtime.retainedControllerCount());

        AnimationIntent unknown = intent(scope, id("unknown"), WALK, 0L, 999L, 1.0F, AnimationIntentMode.PERSISTENT);
        runtime.receiveIntent(unknown);
        assertEquals(AnimationIntentReconciliationOutcome.CONTROLLER_REJECTED,
                runtime.advance(frame(0L, 0.0F)).reconciliationResults().getFirst().outcome());
        assertEquals(1, runtime.retainedControllerCount());
    }

    @Test
    void generationRebindPreservesOnlyValidIntentAndQueuedStaleCallbacksNeverInherit() {
        AnimationIntentScope generationOne = scope("session-a", 1L);
        AnimationIntentScope generationTwo = scope("session-a", 2L);
        ClientAnimationV2Runtime runtime = installed(generationOne, plan(1.0D, 10.0D));
        runtime.advance(frame(0L, 0.0F));
        AnimationIntent persistent = intent(generationOne, CONTROLLER, WALK, 0L, 1L, 1.0F, AnimationIntentMode.PERSISTENT);
        runtime.receiveIntent(persistent);
        runtime.advance(frame(0L, 0.0F));

        runtime.install(plan(1.0D, 10.0D), generationTwo);
        assertEquals(generationTwo, runtime.persistentReplay().getFirst().scope());
        assertEquals(WALK, runtime.advance(frame(0L, 0.0F)).evaluation().orElseThrow().playheads().get(CONTROLLER).state());

        runtime.receiveIntent(intent(generationOne, CONTROLLER, RUN, 0L, 2L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertEquals(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                runtime.advance(frame(0L, 0.0F)).reconciliationResults().getFirst().outcome());

        runtime.install(plan(1.0D, 10.0D), scope("session-b", 3L));
        assertTrue(runtime.persistentReplay().isEmpty());
    }

    @Test
    void replacementPlanAndEffectiveRateFailuresArePlanRejectedAndNeverReplay() {
        AnimationIntentScope first = scope("session-a", 1L);
        AnimationIntentScope second = scope("session-a", 2L);
        ClientAnimationV2Runtime reload = installed(first, plan(1.0D, 10.0D));
        reload.advance(frame(0L, 0.0F));
        reload.receiveIntent(intent(first, CONTROLLER, WALK, 0L, 1L, 2.0F, AnimationIntentMode.PERSISTENT));
        reload.advance(frame(0L, 0.0F));
        reload.install(plan(64.0D, 10.0D), second);
        assertEquals(AnimationIntentReconciliationOutcome.PLAN_REJECTED,
                reload.latestSnapshot().reconciliationResults().getFirst().outcome());
        assertTrue(reload.persistentReplay().isEmpty());

        ClientAnimationV2Runtime lowerBound = installed(first, plan(AnimationV2Limits.MIN_PLAYBACK_SPEED, 10.0D));
        lowerBound.advance(frame(0L, 0.0F));
        lowerBound.receiveIntent(intent(first, CONTROLLER, WALK, 0L, 1L,
                (float) AnimationV2Limits.MIN_PLAYBACK_SPEED, AnimationIntentMode.PERSISTENT));
        assertEquals(AnimationIntentReconciliationOutcome.PLAN_REJECTED,
                lowerBound.advance(frame(0L, 0.0F)).reconciliationResults().getFirst().outcome());
        assertTrue(lowerBound.persistentReplay().isEmpty());
    }

    private static ClientAnimationV2Runtime installed(AnimationIntentScope scope, AnimationV2InstancePlan plan) {
        ClientAnimationV2Runtime runtime = new ClientAnimationV2Runtime();
        runtime.install(plan, scope);
        return runtime;
    }

    private static AnimationV2InstancePlan plan(double walkSpeed, double durationSeconds) {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
        BlendResourceId layerId = id("layer");
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                layerId, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerState idle = state(IDLE, 1.0D, layerId, clip(durationSeconds, 0.0F));
        AnimationV2ControllerState walk = state(WALK, walkSpeed, layerId, clip(durationSeconds, 0.0F, 10.0F));
        AnimationV2ControllerState run = state(RUN, 1.0D, layerId, clip(durationSeconds, 0.0F, 20.0F));
        AnimationV2ControllerDefinition controller = new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(layer), IDLE, Map.of(IDLE, idle, WALK, walk, RUN, run));
        return new AnimationV2InstancePlan(schema, List.of(controller));
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key, double speed, BlendResourceId layer, AnimationV2Clip clip) {
        return new AnimationV2ControllerState(key, AnimationV2PlaybackMode.LOOP, speed, 0.0D, null, Map.of(layer, clip));
    }

    private static AnimationV2Clip clip(double durationSeconds, float... values) {
        List<AnimationV2Keyframe> keyframes = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            double time = values.length == 1 ? 0.0D : durationSeconds * index / (values.length - 1);
            Transform transform = new Transform(new Vec3(values[index], 0.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE);
            keyframes.add(new AnimationV2Keyframe(time, new AnimationV2Pose(List.of(transform))));
        }
        return new AnimationV2Clip(keyframes);
    }

    private static AnimationIntentScope scope(String session, long generation) {
        return scope(session, "world", 7, generation);
    }

    private static AnimationIntentScope scope(String session, String world, int entityId, long generation) {
        return new AnimationIntentScope(session, id(world), new BlendInstanceKey.Entity(session, entityId), generation);
    }

    private static AnimationIntent intent(
            AnimationIntentScope scope,
            BlendResourceId controller,
            BlendAnimationKey animation,
            long startTick,
            long sequence,
            float speed,
            AnimationIntentMode mode) {
        return new AnimationIntent(scope, controller, animation, startTick, sequence, speed, mode);
    }

    private static ClientAnimationV2FrameTime frame(long tick, float partial) {
        return new ClientAnimationV2FrameTime(tick, partial);
    }

    private static List<AnimationIntentReconciliationOutcome> outcomes(ClientAnimationV2Snapshot snapshot) {
        return snapshot.reconciliationResults().stream().map(result -> result.outcome()).toList();
    }

    private static AnimationIntentReconciliationOutcome outcomeFor(
            ClientAnimationV2Snapshot snapshot, AnimationIntent intent) {
        return snapshot.reconciliationResults().stream().filter(result -> result.intent().equals(intent))
                .findFirst().orElseThrow().outcome();
    }

    private static void assertTime(double expected, ClientAnimationV2Snapshot snapshot) {
        assertEquals(WALK, snapshot.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
        assertEquals(expected, snapshot.evaluation().orElseThrow().playheads().get(CONTROLLER).timeSeconds(), 0.00001D);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.of("x2clientreview", path);
    }

    private static BlendAnimationKey key(String path) {
        return BlendAnimationKey.of("x2clientreview", path);
    }

    private record ScopeFenceCase(AnimationIntentScope scope, boolean sortsBeforeActive) {
    }
}
