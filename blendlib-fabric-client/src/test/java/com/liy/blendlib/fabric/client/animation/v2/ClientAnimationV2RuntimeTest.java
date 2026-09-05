package com.liy.blendlib.fabric.client.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Clip;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerState;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2InstancePlan;
import com.liy.blendlib.core.animation.v2.AnimationV2Keyframe;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
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

class ClientAnimationV2RuntimeTest {
    private static final BlendResourceId CONTROLLER = id("controller");
    private static final BlendAnimationKey IDLE = key("idle");
    private static final BlendAnimationKey WALK = key("walk");

    @Test
    void lateJoinBacklogBecomesAPlayheadAndPersistentReplayWithoutPlatformWiring() {
        ClientAnimationV2Runtime runtime = new ClientAnimationV2Runtime();
        AnimationIntentScope scope = scope("session-a", 1L);
        runtime.install(plan(1.0D), scope);
        runtime.advance(frame(100L, 0.0F));
        AnimationIntent intent = intent(scope, WALK, 95L, 1L, 1.0F, AnimationIntentMode.PERSISTENT);
        runtime.receiveIntent(intent);

        ClientAnimationV2Snapshot snapshot = runtime.advance(frame(100L, 0.0F));
        AnimationV2EvaluationSnapshot evaluation = snapshot.evaluation().orElseThrow();
        assertEquals(1, snapshot.reconciliationResults().size());
        assertEquals(AnimationIntentReconciliationOutcome.ACCEPTED,
                snapshot.reconciliationResults().getFirst().outcome());
        assertEquals(WALK, evaluation.playheads().get(CONTROLLER).state());
        assertEquals(0.25D, evaluation.playheads().get(CONTROLLER).timeSeconds(), 0.00001D);
        assertEquals(2.5F, evaluation.pose().transform(0).translation().x(), 0.00001F);
        assertEquals(List.of(intent), runtime.persistentReplay());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.reconciliationResults().add(null));
    }

    @Test
    void canonicalArrivalOrderScopeRescopeAndDisconnectPreventStateLeakage() {
        ClientAnimationV2Runtime runtime = new ClientAnimationV2Runtime();
        AnimationIntentScope initial = scope("session-a", 1L);
        runtime.install(plan(1.0D), initial);
        runtime.advance(frame(0L, 0.0F));
        runtime.receiveIntent(intent(initial, WALK, 0L, 2L, 1.0F, AnimationIntentMode.ONE_SHOT));
        runtime.receiveIntent(intent(initial, IDLE, 0L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        ClientAnimationV2Snapshot ordered = runtime.advance(frame(0L, 0.0F));
        assertEquals(List.of(1L, 2L), ordered.reconciliationResults().stream()
                .map(result -> result.intent().sequence()).toList());
        assertEquals(WALK, ordered.evaluation().orElseThrow().playheads().get(CONTROLLER).state());

        runtime.receiveIntent(intent(initial, IDLE, 0L, 2L, 1.0F, AnimationIntentMode.ONE_SHOT));
        runtime.receiveIntent(intent(scope("wrong-session", 1L), IDLE, 0L, 3L, 1.0F, AnimationIntentMode.ONE_SHOT));
        ClientAnimationV2Snapshot rejected = runtime.advance(frame(0L, 0.0F));
        assertEquals(List.of(
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED,
                AnimationIntentReconciliationOutcome.SCOPE_REJECTED), rejected.reconciliationResults().stream()
                .map(result -> result.outcome()).toList());

        AnimationIntentScope replacement = scope("session-a", 2L);
        runtime.install(plan(1.0D), replacement);
        runtime.advance(frame(0L, 0.0F));
        runtime.receiveIntent(intent(initial, WALK, 0L, 4L, 1.0F, AnimationIntentMode.PERSISTENT));
        assertEquals(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                runtime.advance(frame(0L, 0.0F)).reconciliationResults().getFirst().outcome());
        runtime.disconnect();
        runtime.receiveIntent(intent(replacement, WALK, 0L, 1L, 1.0F, AnimationIntentMode.PERSISTENT));
        ClientAnimationV2Snapshot disconnected = runtime.advance(frame(1L, 0.0F));
        assertTrue(disconnected.evaluation().isEmpty());
        assertEquals(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                disconnected.reconciliationResults().getFirst().outcome());
    }

    @Test
    void wrapAwareClockAndPlanRateRejectionRemainBounded() {
        ClientAnimationV2Runtime runtime = new ClientAnimationV2Runtime();
        AnimationIntentScope scope = scope("session-a", 1L);
        runtime.install(plan(1.0D), scope);
        runtime.advance(frame(Long.MAX_VALUE, 0.75F));
        runtime.receiveIntent(intent(scope, WALK, Long.MAX_VALUE - 1L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        ClientAnimationV2Snapshot wrapped = runtime.advance(frame(Long.MIN_VALUE, 0.25F));
        double wrappedTime = wrapped.evaluation().orElseThrow().playheads().get(CONTROLLER).timeSeconds();
        assertTrue(wrappedTime > 0.0D);
        assertTrue(wrappedTime < 1.0D);

        AnimationIntentScope replacement = scope("session-a", 2L);
        runtime.install(plan(64.0D), replacement);
        runtime.advance(frame(0L, 0.0F));
        runtime.receiveIntent(intent(replacement, WALK, 0L, 1L, 2.0F, AnimationIntentMode.PERSISTENT));
        ClientAnimationV2Snapshot rejected = runtime.advance(frame(0L, 0.0F));
        assertEquals(AnimationIntentReconciliationOutcome.PLAN_REJECTED,
                rejected.reconciliationResults().getFirst().outcome());
        assertTrue(runtime.persistentReplay().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> frame(0L, Float.NaN));
    }

    @Test
    void callbackThreadsMayConcurrentlyEnqueueButOnlyOwnerAppliesInCanonicalOrder() throws InterruptedException {
        ClientAnimationV2Runtime runtime = new ClientAnimationV2Runtime();
        AnimationIntentScope scope = scope("session-a", 1L);
        runtime.install(plan(1.0D), scope);
        runtime.advance(frame(0L, 0.0F));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread second = new Thread(() -> receive(runtime,
                intent(scope, WALK, 0L, 2L, 1.0F, AnimationIntentMode.ONE_SHOT), failure));
        Thread first = new Thread(() -> receive(runtime,
                intent(scope, IDLE, 0L, 1L, 1.0F, AnimationIntentMode.ONE_SHOT), failure));
        second.start();
        first.start();
        second.join();
        first.join();
        assertEquals(null, failure.get());

        ClientAnimationV2Snapshot snapshot = runtime.advance(frame(0L, 0.0F));
        assertEquals(List.of(1L, 2L), snapshot.reconciliationResults().stream()
                .map(result -> result.intent().sequence()).toList());
        assertEquals(WALK, snapshot.evaluation().orElseThrow().playheads().get(CONTROLLER).state());
    }

    private static void receive(ClientAnimationV2Runtime runtime, AnimationIntent intent, AtomicReference<Throwable> failure) {
        try {
            runtime.receiveIntent(intent);
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static AnimationV2InstancePlan plan(double walkSpeed) {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
        BlendResourceId layerId = id("layer");
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                layerId, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerState idle = state(IDLE, 1.0D, layerId, clip(0.0F, 0.0F));
        AnimationV2ControllerState walk = state(WALK, walkSpeed, layerId, clip(0.0F, 10.0F));
        AnimationV2ControllerDefinition controller = new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(layer), IDLE, Map.of(IDLE, idle, WALK, walk));
        return new AnimationV2InstancePlan(schema, List.of(controller));
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key, double speed, BlendResourceId layer, AnimationV2Clip clip) {
        return new AnimationV2ControllerState(key, AnimationV2PlaybackMode.LOOP, speed, 0.0D, null, Map.of(layer, clip));
    }

    private static AnimationV2Clip clip(float... values) {
        List<AnimationV2Keyframe> keyframes = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            Transform transform = new Transform(new Vec3(values[index], 0.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE);
            keyframes.add(new AnimationV2Keyframe(index, new AnimationV2Pose(List.of(transform))));
        }
        return new AnimationV2Clip(keyframes);
    }

    private static AnimationIntentScope scope(String session, long generation) {
        return new AnimationIntentScope(session, id("world"), new BlendInstanceKey.Entity(session, 7), generation);
    }

    private static AnimationIntent intent(
            AnimationIntentScope scope,
            BlendAnimationKey animation,
            long startTick,
            long sequence,
            float speed,
            AnimationIntentMode mode) {
        return new AnimationIntent(scope, CONTROLLER, animation, startTick, sequence, speed, mode);
    }

    private static ClientAnimationV2FrameTime frame(long tick, float partial) {
        return new ClientAnimationV2FrameTime(tick, partial);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.of("x2client", path);
    }

    private static BlendAnimationKey key(String path) {
        return BlendAnimationKey.of("x2client", path);
    }
}
