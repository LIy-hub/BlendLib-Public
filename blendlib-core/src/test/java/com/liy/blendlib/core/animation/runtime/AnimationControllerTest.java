package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnimationControllerTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("fixture:idle");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("fixture:attack");
    private static final List<ModelNode> ONE_NODE = List.of(new ModelNode(0, "root", Transform.IDENTITY, List.of(), -1, -1, false));

    @Test
    void supportsInitialTriggerLoopNonLoopNextAndSpeedPerInstance() {
        AnimationState idle = state(IDLE, translationClip("idle", 0.0f, 1.0f), true, 2.0, 0.0, null, List.of());
        AnimationState attack = state(ATTACK, translationClip("attack", 4.0f, 8.0f), false, 1.0, 0.0, IDLE, List.of());
        AnimationControllerDefinition definition = definition(idle, attack);
        AnimationController controller = new AnimationController(BlendInstanceKey.entity("session", 7), definition);

        assertEquals(IDLE, controller.currentState());
        controller.advance(0.75);
        assertEquals(0.5, controller.currentTimeSeconds(), 1.0e-6);

        controller.trigger(ATTACK);
        assertEquals(ATTACK, controller.currentState());
        assertEquals(0.0, controller.currentTimeSeconds(), 1.0e-6);
        controller.advance(1.0);
        assertEquals(IDLE, controller.currentState());
        assertEquals(0.0, controller.currentTimeSeconds(), 1.0e-6);
        controller.advance(0.25);
        assertEquals(0.5, controller.currentTimeSeconds(), 1.0e-6);

        AnimationController other = new AnimationController(BlendInstanceKey.entity("session", 8), definition);
        other.advance(0.1);
        assertEquals(0.2, other.currentTimeSeconds(), 1.0e-6);
        assertEquals(0.5, controller.currentTimeSeconds(), 1.0e-6);
    }

    @Test
    void usesSmoothstepCrossFadeAtZeroHalfAndFullProgress() {
        AnimationState idle = state(IDLE, translationClip("idle", 0.0f, 0.0f), true, 1.0, 0.4, null, List.of());
        AnimationState attack = state(ATTACK, translationClip("attack", 10.0f, 10.0f), true, 1.0, 0.4, null, List.of());
        AnimationController controller = new AnimationController(BlendInstanceKey.entity("session", 1), definition(idle, attack));
        PoseSampler sampler = new PoseSampler(ONE_NODE);

        controller.trigger(ATTACK);
        assertEquals(0.0f, controller.sample(sampler).transform(0).translation().x(), 1.0e-5f);
        controller.advance(0.2);
        assertEquals(5.0f, controller.sample(sampler).transform(0).translation().x(), 1.0e-5f);
        controller.advance(0.2);
        assertEquals(10.0f, controller.sample(sampler).transform(0).translation().x(), 1.0e-5f);
        assertNull(controller.previousState());
        assertEquals(0.0, AnimationController.smoothstep(0.0), 1.0e-9);
        assertEquals(0.5, AnimationController.smoothstep(0.5), 1.0e-9);
        assertEquals(1.0, AnimationController.smoothstep(1.0), 1.0e-9);
    }

    @Test
    void dropsStaleCorrectionsAndSnapsOnlySeriousSameStateDrift() {
        AnimationState idle = state(IDLE, translationClip("idle", 0.0f, 1.0f), true, 1.0, 0.2, null, List.of());
        AnimationState attack = state(ATTACK, translationClip("attack", 2.0f, 3.0f), true, 1.0, 0.2, null, List.of());
        AnimationController controller = new AnimationController(BlendInstanceKey.entity("session", 2), definition(idle, attack));
        controller.advance(0.1);

        assertEquals(AnimationCorrectionResult.APPLIED_BLEND,
                controller.applyCorrection(new AnimationCorrection(IDLE, 0.3, 3, 0.5)));
        assertEquals(0.3, controller.currentTimeSeconds(), 1.0e-6);
        assertEquals(IDLE, controller.previousState());
        assertEquals(3L, controller.lastSequence());
        assertEquals(AnimationCorrectionResult.STALE_DROPPED,
                controller.applyCorrection(new AnimationCorrection(ATTACK, 0.8, 3, 0.1)));
        assertEquals(IDLE, controller.currentState());

        assertEquals(AnimationCorrectionResult.APPLIED_SNAP,
                controller.applyCorrection(new AnimationCorrection(IDLE, 0.9, 4, 0.2)));
        assertEquals(0.9, controller.currentTimeSeconds(), 1.0e-6);
        assertNull(controller.previousState());

        assertEquals(AnimationCorrectionResult.APPLIED_BLEND,
                controller.applyCorrection(new AnimationCorrection(ATTACK, 0.25, 5, 0.1)));
        assertEquals(ATTACK, controller.currentState());
        assertEquals(0.25, controller.currentTimeSeconds(), 1.0e-6);
        assertTrue(controller.lastSequence() == 5L);
    }

    @Test
    void timelineCorrectionComposesControllerAndDescriptorSpeeds() {
        for (double descriptorSpeed : new double[] {0.5, 1.0, 2.0}) {
            AnimationState idle = state(
                    IDLE, translationClip("idle", 0.0f, 1.0f), true, descriptorSpeed, 0.0, null, List.of());
            AnimationController controller = new AnimationController(
                    BlendInstanceKey.entity("session", (int) (descriptorSpeed * 10.0)), definition(idle));

            assertEquals(AnimationCorrectionResult.APPLIED_SNAP, controller.applyTimelineCorrection(
                    new AnimationCorrection(IDLE, 0.25, 1L, 0.0)));
            assertEquals(0.25 * descriptorSpeed, controller.currentTimeSeconds(), 1.0e-9);
        }
    }

    @Test
    void timelineCorrectionMatchesContinuousAdvanceAcrossNonLoopNext() {
        AnimationState idle = state(
                IDLE, translationClip("idle", 0.0f, 1.0f), true, 0.5, 0.0, null, List.of());
        AnimationState attack = state(
                ATTACK, translationClip("attack", 2.0f, 3.0f), false, 2.0, 0.0, IDLE, List.of());
        AnimationControllerDefinition definition = definition(idle, attack);
        AnimationController continuous = new AnimationController(
                BlendInstanceKey.entity("session", 20), definition);
        continuous.trigger(ATTACK);
        continuous.advance(1.5);

        AnimationController corrected = new AnimationController(
                BlendInstanceKey.entity("session", 21), definition);
        corrected.applyTimelineCorrection(new AnimationCorrection(ATTACK, 1.5, 1L, 0.0));

        assertEquals(IDLE, continuous.currentState());
        assertEquals(continuous.currentState(), corrected.currentState());
        assertEquals(0.5, continuous.currentTimeSeconds(), 1.0e-9);
        assertEquals(continuous.currentTimeSeconds(), corrected.currentTimeSeconds(), 1.0e-9);
    }

    @Test
    void timelineCorrectionMatchesSpeedScaledNextAndLoopBoundaries() {
        int instanceId = 100;
        for (double speed : new double[] {0.5, 1.0, 2.0, BlendAssetLimits.MAX_ANIMATION_SPEED}) {
            AnimationState next = state(
                    ATTACK, translationClip("next_" + speed, 2.0f, 3.0f), true, 1.25, 0.0, null, List.of());
            AnimationState origin = state(
                    IDLE, translationClip("origin_" + speed, 0.0f, 1.0f), false, speed, 0.0, ATTACK, List.of());
            AnimationControllerDefinition nextDefinition = definition(origin, next);
            double boundary = 1.0 / speed;
            double outsideBothTolerances = Math.max(4.0e-8, 4.0e-8 / speed);
            double insideBothTolerances = Math.min(2.5e-9, 2.5e-9 / speed);

            AnimationController before = assertTimelineMatchesAdvance(
                    nextDefinition, IDLE, boundary - outsideBothTolerances, instanceId++);
            assertEquals(IDLE, before.currentState());
            assertTimelineMatchesAdvance(nextDefinition, IDLE, boundary, instanceId++);
            assertTimelineMatchesAdvance(nextDefinition, IDLE, boundary + outsideBothTolerances, instanceId++);
            AnimationController toleranceBoundary = assertTimelineMatchesAdvance(
                    nextDefinition, IDLE, boundary - insideBothTolerances, instanceId++);
            assertEquals(ATTACK, toleranceBoundary.currentState());

            AnimationState looping = state(
                    IDLE, translationClip("loop_" + speed, 0.0f, 1.0f), true, speed, 0.0, null, List.of());
            AnimationControllerDefinition loopDefinition = definition(looping);
            assertTimelineMatchesAdvance(loopDefinition, IDLE, boundary - outsideBothTolerances, instanceId++);
            assertTimelineMatchesAdvance(loopDefinition, IDLE, boundary, instanceId++);
            assertTimelineMatchesAdvance(loopDefinition, IDLE, boundary + outsideBothTolerances, instanceId++);
        }

        AnimationState maxSpeedNext = state(
                ATTACK, translationClip("max_next", 2.0f, 3.0f), true, 1.0, 0.0, null, List.of());
        AnimationState maxSpeedOrigin = state(
                IDLE, translationClip("max_origin", 0.0f, 1.0f), false,
                BlendAssetLimits.MAX_ANIMATION_SPEED, 0.0, ATTACK, List.of());
        AnimationController reviewerReproduction = assertTimelineMatchesAdvance(
                definition(maxSpeedOrigin, maxSpeedNext),
                IDLE,
                1.0 / BlendAssetLimits.MAX_ANIMATION_SPEED - 5.0e-9,
                instanceId);
        assertEquals(IDLE, reviewerReproduction.currentState());
        assertEquals(0.99999968, reviewerReproduction.currentTimeSeconds(), 1.0e-12);
    }

    @Test
    void timelineCorrectionSkipsLongPositiveNextCyclesButRejectsZeroDurationCycles() {
        AnimationState idle = state(
                IDLE, translationClip("idle", 0.0f, 1.0f), false, 1.0, 0.0, ATTACK, List.of());
        AnimationState attack = state(
                ATTACK, translationClip("attack", 2.0f, 3.0f), false, 2.0, 0.0, IDLE, List.of());
        AnimationController positiveCycle = new AnimationController(
                BlendInstanceKey.entity("session", 22), definition(idle, attack));

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> positiveCycle.applyTimelineCorrection(
                new AnimationCorrection(IDLE, 1_500_000.25, 1L, 0.0)));
        assertEquals(IDLE, positiveCycle.currentState());
        assertEquals(0.25, positiveCycle.currentTimeSeconds(), 1.0e-9);

        AnimationState zeroIdle = state(
                IDLE, zeroDurationClip("zero_idle"), false, 1.0, 0.0, ATTACK, List.of());
        AnimationState zeroAttack = state(
                ATTACK, zeroDurationClip("zero_attack"), false, 1.0, 0.0, IDLE, List.of());
        AnimationController zeroCycle = new AnimationController(
                BlendInstanceKey.entity("session", 23), definition(zeroIdle, zeroAttack));
        IllegalStateException failure = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                IllegalStateException.class,
                () -> zeroCycle.applyTimelineCorrection(new AnimationCorrection(IDLE, 1.0, 1L, 0.0))));
        assertTrue(failure.getMessage().contains("no positive duration"));
    }

    @Test
    void timelineCorrectionPreservesClosedFormPositiveCycleModuloBoundaries() {
        AnimationState idle = state(
                IDLE, translationClip("cycle_idle", 0.0f, 1.0f), false, 1.0, 0.0, ATTACK, List.of());
        AnimationState attack = state(
                ATTACK, translationClip("cycle_attack", 2.0f, 3.0f), false, 2.0, 0.0, IDLE, List.of());
        AnimationControllerDefinition definition = definition(idle, attack);
        double cycleSeconds = 1.5;
        double largeCycleBase = 1_000_000.0 * cycleSeconds;
        int instanceId = 150;

        for (double remainder : new double[] {
                0.0,
                1.0 - 4.0e-8,
                1.0,
                1.0 + 4.0e-8,
                cycleSeconds - 4.0e-8}) {
            AnimationController expected = new AnimationController(
                    BlendInstanceKey.entity("session", instanceId++), definition);
            expected.applyTimelineCorrection(new AnimationCorrection(IDLE, remainder, 1L, 0.0));
            AnimationController closedForm = new AnimationController(
                    BlendInstanceKey.entity("session", instanceId++), definition);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> closedForm.applyTimelineCorrection(
                    new AnimationCorrection(IDLE, largeCycleBase + remainder, 1L, 0.0)));

            assertEquals(expected.currentState(), closedForm.currentState());
            assertEquals(expected.currentTimeSeconds(), closedForm.currentTimeSeconds(), 5.0e-8);
        }
    }

    @Test
    void timelineCorrectionDoesNotReplayHistoricalPresentationEvents() {
        AnimationVisualEvent idleEntry = event(0.0, "idle_entry");
        AnimationVisualEvent attackEntry = event(0.0, "attack_entry");
        AnimationState idle = state(
                IDLE, translationClip("idle", 0.0f, 1.0f), true, 1.0, 0.0, null, List.of(idleEntry));
        AnimationState attack = state(
                ATTACK, translationClip("attack", 2.0f, 3.0f), true, 1.0, 0.0, null, List.of(attackEntry));
        AnimationController controller = new AnimationController(
                BlendInstanceKey.entity("session", 24), definition(idle, attack));

        controller.applyTimelineCorrection(new AnimationCorrection(ATTACK, 0.5, 1L, 0.0));

        assertTrue(controller.advance(0.0).visualEvents().isEmpty());
    }

    @Test
    void emitsVisualEventsOnceAndExposesNoGameplayAction() {
        BlendResourceId enter = BlendResourceId.parse("fixture:visual_enter");
        BlendResourceId pulse = BlendResourceId.parse("fixture:visual_pulse");
        AnimationState idle = state(
                IDLE,
                translationClip("idle", 0.0f, 0.0f),
                true,
                1.0,
                0.0,
                null,
                List.of(new AnimationVisualEvent(0.0, enter), new AnimationVisualEvent(0.5, pulse)));
        AnimationController controller = new AnimationController(BlendInstanceKey.entity("session", 3), definition(idle));

        AnimationAdvance initial = controller.advance(0.0);
        assertEquals(List.of(new AnimationVisualEvent(0.0, enter)), initial.visualEvents());
        assertTrue(controller.advance(0.0).visualEvents().isEmpty());
        assertEquals(List.of(new AnimationVisualEvent(0.5, pulse)), controller.advance(0.5).visualEvents());
        assertEquals(List.of(new AnimationVisualEvent(0.0, enter)), controller.advance(0.5).visualEvents());
        assertFalse(initial.visualEvents().isEmpty());
    }

    @Test
    void enforcesTheUnifiedAnimationSpeedBoundaryForProgrammaticStates() {
        AnimationClip clip = translationClip("speed", 0.0f, 1.0f);
        assertEquals(BlendAssetLimits.MAX_ANIMATION_SPEED,
                state(IDLE, clip, true, BlendAssetLimits.MAX_ANIMATION_SPEED, 0.0, null, List.of()).speed());

        for (double invalid : new double[] {
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                -1.0,
                0.0,
                Math.nextUp(BlendAssetLimits.MAX_ANIMATION_SPEED)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> state(IDLE, clip, true, invalid, 0.0, null, List.of()));
        }
    }

    @Test
    void emitsEveryLegalEventAcrossTheMaximumLoopBudgetInStableOrder() {
        List<AnimationVisualEvent> visualEvents = List.of(
                event(0.1, "event_a"),
                event(0.2, "event_b"),
                event(0.3, "event_c"),
                event(0.4, "event_d"));
        AnimationController controller = new AnimationController(
                BlendInstanceKey.entity("session", 30),
                definition(state(IDLE, translationClip("loop", 0.0f, 1.0f), true, 1.0, 0.0, null, visualEvents)));

        AnimationAdvance advance = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> controller.advance(BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE));

        assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE, advance.visualEvents().size());
        assertEquals(visualEvents, advance.visualEvents().subList(0, visualEvents.size()));
        assertEquals(visualEvents, advance.visualEvents().subList(
                advance.visualEvents().size() - visualEvents.size(), advance.visualEvents().size()));
        assertEquals(0.0, controller.currentTimeSeconds(), 1.0e-6);
    }

    @Test
    void rejectsHugeLoopCycleEventAndOverflowAdvancesBeforeMutation() {
        AnimationController cycleLimited = new AnimationController(
                BlendInstanceKey.entity("session", 31),
                definition(state(IDLE, translationClip("loop", 0.0f, 1.0f), true, 1.0, 0.0, null, List.of())));
        IllegalStateException cycleFailure = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                IllegalStateException.class,
                () -> cycleLimited.advance(BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE + 1.0)));
        assertTrue(cycleFailure.getMessage().contains("loop-cycle budget"));
        assertEquals(0.0, cycleLimited.currentTimeSeconds(), 0.0);

        List<AnimationVisualEvent> fiveEvents = List.of(
                event(0.0, "event_entry"),
                event(0.1, "event_a"),
                event(0.2, "event_b"),
                event(0.3, "event_c"),
                event(0.4, "event_d"));
        AnimationController eventLimited = new AnimationController(
                BlendInstanceKey.entity("session", 32),
                definition(state(IDLE, translationClip("loop", 0.0f, 1.0f), true, 1.0, 0.0, null, fiveEvents)));
        IllegalStateException eventFailure = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                IllegalStateException.class,
                () -> eventLimited.advance(BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE)));
        assertTrue(eventFailure.getMessage().contains("visual-event budget"));
        assertEquals(0.0, eventLimited.currentTimeSeconds(), 0.0);
        assertEquals(List.of(fiveEvents.getFirst()), eventLimited.advance(0.0).visualEvents());

        AnimationController overflowLimited = new AnimationController(
                BlendInstanceKey.entity("session", 33),
                definition(state(IDLE, translationClip("loop", 0.0f, 1.0f), true,
                        BlendAssetLimits.MAX_ANIMATION_SPEED, 0.0, null, List.of())));
        IllegalStateException overflowFailure = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                IllegalStateException.class, () -> overflowLimited.advance(Double.MAX_VALUE)));
        assertTrue(overflowFailure.getMessage().contains("not finite"));
        assertEquals(0.0, overflowLimited.currentTimeSeconds(), 0.0);
    }

    @Test
    void boundsAutomaticPositiveDurationNextChainsWithoutPartialAdvance() {
        AnimationState repeating = state(
                IDLE, translationClip("repeat", 0.0f, 1.0f), false, 1.0, 0.0, IDLE, List.of());
        AnimationController controller = new AnimationController(
                BlendInstanceKey.entity("session", 34), definition(repeating));

        IllegalStateException failure = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                IllegalStateException.class,
                () -> controller.advance(BlendAssetLimits.MAX_STATE_TRANSITIONS_PER_ADVANCE + 1.0)));

        assertTrue(failure.getMessage().contains("state-transition budget"));
        assertEquals(IDLE, controller.currentState());
        assertEquals(0.0, controller.currentTimeSeconds(), 0.0);
    }

    @Test
    void samplesShortestPathNormalizedFiniteQuaternion() {
        AnimationChannel rotation = new AnimationChannel(
                0,
                AnimationPath.ROTATION,
                Interpolation.LINEAR,
                new float[] {0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, -1.0f, 0.0f, 0.0f});
        AnimationState state = state(IDLE, new AnimationClip("turn", List.of(rotation)), false, 1.0, 0.0, null, List.of());
        Quaternion halfway = new PoseSampler(ONE_NODE).sample(state, 0.5).transform(0).rotation();
        float norm = (float) Math.sqrt(halfway.x() * halfway.x() + halfway.y() * halfway.y()
                + halfway.z() * halfway.z() + halfway.w() * halfway.w());

        assertEquals(1.0f, norm, 1.0e-5f);
        assertTrue(Float.isFinite(halfway.x()));
        assertTrue(Float.isFinite(halfway.y()));
        assertTrue(Float.isFinite(halfway.z()));
        assertTrue(Float.isFinite(halfway.w()));
        assertEquals((float) Math.sqrt(0.5), Math.abs(halfway.y()), 1.0e-5f);
        assertEquals((float) Math.sqrt(0.5), Math.abs(halfway.w()), 1.0e-5f);
    }

    private static AnimationControllerDefinition definition(AnimationState... states) {
        return new AnimationControllerDefinition(IDLE, java.util.Arrays.stream(states)
                .collect(java.util.stream.Collectors.toMap(AnimationState::key, state -> state, (left, right) -> left, java.util.LinkedHashMap::new)));
    }

    private static AnimationController assertTimelineMatchesAdvance(
            AnimationControllerDefinition definition,
            BlendAnimationKey origin,
            double controllerTimeSeconds,
            int instanceId) {
        AnimationController continuous = new AnimationController(
                BlendInstanceKey.entity("continuous", instanceId), definition);
        continuous.advance(controllerTimeSeconds);
        AnimationController corrected = new AnimationController(
                BlendInstanceKey.entity("corrected", instanceId), definition);
        corrected.applyTimelineCorrection(new AnimationCorrection(origin, controllerTimeSeconds, 1L, 0.0));
        assertEquals(continuous.currentState(), corrected.currentState());
        assertEquals(continuous.currentTimeSeconds(), corrected.currentTimeSeconds(), 1.0e-12);
        return corrected;
    }

    private static AnimationState state(
            BlendAnimationKey key,
            AnimationClip clip,
            boolean loop,
            double speed,
            double blendSeconds,
            BlendAnimationKey next,
            List<AnimationVisualEvent> events) {
        return new AnimationState(key, clip, loop, speed, blendSeconds, next, events);
    }

    private static AnimationClip translationClip(String name, float start, float end) {
        return new AnimationClip(name, List.of(new AnimationChannel(
                0,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0f, 1.0f},
                new float[] {start, 0.0f, 0.0f, end, 0.0f, 0.0f})));
    }

    private static AnimationClip zeroDurationClip(String name) {
        return new AnimationClip(name, List.of(new AnimationChannel(
                0,
                AnimationPath.TRANSLATION,
                Interpolation.STEP,
                new float[] {0.0f},
                new float[] {0.0f, 0.0f, 0.0f})));
    }

    private static AnimationVisualEvent event(double timeSeconds, String path) {
        return new AnimationVisualEvent(timeSeconds, BlendResourceId.parse("fixture:" + path));
    }
}
