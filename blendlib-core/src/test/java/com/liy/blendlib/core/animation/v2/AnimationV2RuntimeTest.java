package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnimationV2RuntimeTest {
    private static final BlendAnimationKey IDLE = key("idle");
    private static final BlendAnimationKey WALK = key("walk");
    private static final BlendAnimationKey HOLD = key("hold");

    @Test
    void configurationRejectsInvalidMasksWeightsSpeedsAndHardControllerBounds() {
        BoneSchema schema = schema();
        assertThrows(IllegalArgumentException.class, () -> new BoneSchema(
                List.of("root", "root"), List.of(Transform.IDENTITY, Transform.IDENTITY)));
        assertThrows(IllegalArgumentException.class, () -> BoneMask.named(schema, List.of(
                new BoneMask.NamedWeight("root", 0.5F), new BoneMask.NamedWeight("root", 0.5F))));
        assertThrows(IllegalArgumentException.class, () -> new BoneMask.NamedWeight("root", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new BoneMask.IndexedWeight(0, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> BoneMask.indexed(schema, List.of(
                new BoneMask.IndexedWeight(1, 0.5F), new BoneMask.IndexedWeight(1, 0.5F))));
        assertThrows(IllegalArgumentException.class, () -> BoneMask.all(schema).weightAt(1));
        assertThrows(IllegalArgumentException.class, () -> new AnimationV2LayerDefinition(
                id("bad-layer"), 0, AnimationV2LayerMode.OVERRIDE, -0.01F, BoneMask.all(schema), false));
        assertThrows(IllegalArgumentException.class, () -> state(
                IDLE, AnimationV2PlaybackMode.LOOP, Double.NaN, 0.0D, null, layer("base", schema, 1.0F,
                        AnimationV2LayerMode.OVERRIDE, false), clip(0.0F)));

        List<AnimationV2ControllerDefinition> controllers = new ArrayList<>();
        for (int index = 0; index <= AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE; index++) {
            BoneSchema controllerSchema = schema();
            AnimationV2LayerDefinition layer = layer("layer" + index, controllerSchema, 1.0F,
                    AnimationV2LayerMode.OVERRIDE, false);
            AnimationV2ControllerState initial = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                    layer, clip(0.0F));
            controllers.add(controller("controller" + index, 0, List.of(layer), IDLE, Map.of(IDLE, initial)));
        }
        assertThrows(IllegalArgumentException.class, () -> new AnimationV2InstancePlan(schema(), controllers));
    }

    @Test
    void overrideAdditiveMaskAndCrossfadeProduceExpectedEndpoints() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition override = layer("override", schema, 0.5F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2LayerDefinition additive = layer("additive", schema, 0.5F, AnimationV2LayerMode.ADDITIVE, false);
        AnimationV2ControllerState blended = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                Map.of(override.id(), clip(4.0F), additive.id(), clip(2.0F)));
        AnimationV2InstanceRuntime blendedRuntime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("blend", 0, List.of(override, additive), IDLE, Map.of(IDLE, blended)))));
        assertTranslation(3.0F, blendedRuntime.advance(0.0D));

        BoneSchema twoBones = new BoneSchema(List.of("root", "child"), List.of(Transform.IDENTITY, Transform.IDENTITY));
        AnimationV2LayerDefinition partialMask = new AnimationV2LayerDefinition(id("partial-mask"), 0,
                AnimationV2LayerMode.OVERRIDE, 1.0F,
                BoneMask.named(twoBones, List.of(new BoneMask.NamedWeight("root", 1.0F),
                        new BoneMask.NamedWeight("child", 0.0F))), false);
        AnimationV2Clip twoBoneClip = new AnimationV2Clip(List.of(new AnimationV2Keyframe(0.0D,
                new AnimationV2Pose(List.of(transform(5.0F), transform(7.0F))))));
        AnimationV2ControllerState masked = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                partialMask, twoBoneClip);
        AnimationV2EvaluationSnapshot maskedSnapshot = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(twoBones,
                List.of(controller("partial-mask", 0, List.of(partialMask), IDLE, Map.of(IDLE, masked))))).advance(0.0D);
        assertEquals(5.0F, maskedSnapshot.pose().transform(0).translation().x(), 0.00001F);
        assertEquals(0.0F, maskedSnapshot.pose().transform(1).translation().x(), 0.00001F);

        AnimationV2LayerDefinition noOp = new AnimationV2LayerDefinition(id("no-op"), 0,
                AnimationV2LayerMode.OVERRIDE, 0.0F, BoneMask.empty(schema), false);
        AnimationV2ControllerState noOpState = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                noOp, clip(5.0F));
        AnimationV2EvaluationSnapshot noOpSnapshot = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("no-op", 0, List.of(noOp), IDLE, Map.of(IDLE, noOpState))))).advance(0.0D);
        assertTranslation(0.0F, noOpSnapshot);
        assertDiagnostic(noOpSnapshot, AnimationV2DiagnosticCode.EMPTY_MASK);
        assertDiagnostic(noOpSnapshot, AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER);

        AnimationV2LayerDefinition crossfadeLayer = layer("crossfade", schema, 1.0F,
                AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2ControllerState idle = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                crossfadeLayer, clip(0.0F, 0.0F));
        AnimationV2ControllerState walk = state(WALK, AnimationV2PlaybackMode.LOOP, 1.0D, 0.5D, null,
                crossfadeLayer, clip(10.0F, 10.0F));
        AnimationV2InstanceRuntime crossfadeRuntime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("crossfade", 0, List.of(crossfadeLayer), IDLE, Map.of(IDLE, idle, WALK, walk)))));
        crossfadeRuntime.enqueue(command("crossfade", WALK, 1L, 0.0D));
        assertTranslation(0.0F, crossfadeRuntime.advance(0.0D));
        assertTranslation(5.0F, crossfadeRuntime.advance(0.25D));
        assertTranslation(10.0F, crossfadeRuntime.advance(0.25D));
    }

    @Test
    void samePriorityOverridesNormalizeAndPriorityExclusiveConflictsAreDiagnosedDeterministically() {
        AnimationV2EvaluationSnapshot first = conflictRuntime(false).advance(0.0D);
        AnimationV2EvaluationSnapshot reverseInput = conflictRuntime(true).advance(0.0D);
        assertTranslation(4.0F, first);
        assertTranslation(4.0F, reverseInput);
        assertDiagnostic(first, AnimationV2DiagnosticCode.OVERLAPPING_OVERRIDE);
        assertDiagnostic(first, AnimationV2DiagnosticCode.COMPETING_CONTROLLER_WRITE);
        assertDiagnostic(first, AnimationV2DiagnosticCode.EXCLUSIVE_WRITE_CONFLICT);

        BoneSchema schema = schema();
        AnimationV2LayerDefinition lowLayer = layer("low", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2LayerDefinition highLayer = layer("high", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2ControllerDefinition low = fixedController("low-controller", 0, lowLayer, 2.0F);
        AnimationV2ControllerDefinition high = fixedController("high-controller", 1, highLayer, 8.0F);
        AnimationV2EvaluationSnapshot priority = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(high, low))).advance(0.0D);
        assertTranslation(8.0F, priority);
        assertDiagnostic(priority, AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED);
    }

    @Test
    void loopOnceHoldAndNextCycleAreBoundedAndObservable() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("timeline", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2ControllerState looping = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                layer, clip(0.0F, 10.0F));
        AnimationV2InstanceRuntime loopingRuntime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("looping", 0, List.of(layer), IDLE, Map.of(IDLE, looping)))));
        AnimationV2EvaluationSnapshot looped = loopingRuntime.advance(1.25D);
        assertEquals(0.25D, looped.playheads().get(id("looping")).timeSeconds(), 0.00001D);
        assertTranslation(2.5F, looped);
        assertDiagnostic(loopingRuntime.advance(AnimationV2Limits.MAX_ADVANCE_SECONDS + 1.0D),
                AnimationV2DiagnosticCode.ADVANCE_DELTA_CLAMPED);

        AnimationV2ControllerState once = state(IDLE, AnimationV2PlaybackMode.ONCE, 1.0D, 0.0D, HOLD,
                layer, clip(0.0F, 1.0F));
        AnimationV2ControllerState hold = state(HOLD, AnimationV2PlaybackMode.HOLD, 1.0D, 0.0D, null,
                layer, clip(9.0F, 9.0F));
        AnimationV2InstanceRuntime onceRuntime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("once", 0, List.of(layer), IDLE, Map.of(IDLE, once, HOLD, hold)))));
        AnimationV2EvaluationSnapshot held = onceRuntime.advance(1.5D);
        assertEquals(HOLD, held.playheads().get(id("once")).state());
        assertEquals(0.5D, held.playheads().get(id("once")).timeSeconds(), 0.00001D);
        assertTranslation(9.0F, held);

        AnimationV2ControllerState first = state(IDLE, AnimationV2PlaybackMode.ONCE, 1.0D, 0.0D, WALK,
                layer, clip(0.0F));
        AnimationV2ControllerState second = state(WALK, AnimationV2PlaybackMode.ONCE, 1.0D, 0.0D, IDLE,
                layer, clip(1.0F));
        AnimationV2InstanceRuntime cycleRuntime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("cycle", 0, List.of(layer), IDLE, Map.of(IDLE, first, WALK, second)))));
        AnimationV2EvaluationSnapshot cycle = cycleRuntime.advance(0.1D);
        assertDiagnostic(cycle, AnimationV2DiagnosticCode.NEXT_CYCLE);

        List<BlendAnimationKey> budgetKeys = new ArrayList<>();
        for (int index = 0; index <= AnimationV2Limits.MAX_NEXT_TRANSITIONS_PER_ADVANCE + 1; index++) {
            budgetKeys.add(key("budget" + index));
        }
        Map<BlendAnimationKey, AnimationV2ControllerState> budgetStates = new LinkedHashMap<>();
        for (int index = 0; index < budgetKeys.size(); index++) {
            BlendAnimationKey current = budgetKeys.get(index);
            BlendAnimationKey next = index + 1 == budgetKeys.size() ? null : budgetKeys.get(index + 1);
            budgetStates.put(current, state(current,
                    next == null ? AnimationV2PlaybackMode.HOLD : AnimationV2PlaybackMode.ONCE,
                    1.0D, 0.0D, next, layer, clip(0.0F)));
        }
        AnimationV2InstanceRuntime budgetRuntime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("budget", 0, List.of(layer), budgetKeys.getFirst(), budgetStates))));
        assertDiagnostic(budgetRuntime.advance(0.1D), AnimationV2DiagnosticCode.NEXT_TRANSITION_BUDGET);
    }

    @Test
    void exactClipDurationBoundariesPreserveRepresentableTraversalAcrossPlaybackModes() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("exact-duration", schema, 1.0F,
                AnimationV2LayerMode.OVERRIDE, false);
        double duration = 0.25D;
        double nextDown = Math.nextDown(duration);
        double exactResidual = duration - nextDown;
        double nextUp = Math.nextUp(duration);
        double afterDuration = nextUp - duration;

        BlendResourceId loopController = id("exact-duration-loop");
        AnimationV2ControllerState loop = durationState(IDLE, AnimationV2PlaybackMode.LOOP,
                1.0D, null, layer, duration);
        AnimationV2InstanceRuntime loopRuntime = exactDurationRuntime(schema, loopController, layer, IDLE,
                Map.of(IDLE, loop));
        AnimationV2EvaluationSnapshot loopBelow = loopRuntime.advance(nextDown);
        assertExactDouble(nextDown, loopBelow.playheads().get(loopController).timeSeconds());
        assertObserverSegment(loopBelow, loopController, 0, IDLE, 0L, 0L, 0.0D, nextDown);
        assertObserverTerminal(loopBelow, loopController, IDLE, nextDown, 0L, 0L);

        AnimationV2EvaluationSnapshot loopAtEnd = loopRuntime.advance(exactResidual);
        assertExactDouble(0.0D, loopAtEnd.playheads().get(loopController).timeSeconds());
        assertObserverSegment(loopAtEnd, loopController, 0, IDLE, 0L, 0L, nextDown, duration);
        assertObserverTerminal(loopAtEnd, loopController, IDLE, 0.0D, 1L, 1L);

        AnimationV2InstanceRuntime nonzeroStartRuntime = exactDurationRuntime(schema, loopController, layer, IDLE,
                Map.of(IDLE, loop));
        double nonzeroStart = 0.1D;
        nonzeroStartRuntime.advance(nonzeroStart);
        AnimationV2EvaluationSnapshot nonzeroNearEnd = nonzeroStartRuntime.advance(nextDown - nonzeroStart);
        assertExactDouble(nextDown, nonzeroNearEnd.playheads().get(loopController).timeSeconds());
        assertObserverSegment(nonzeroNearEnd, loopController, 0, IDLE, 0L, 0L, nonzeroStart, nextDown);
        AnimationV2EvaluationSnapshot nonzeroAtEnd = nonzeroStartRuntime.advance(exactResidual);
        assertExactDouble(0.0D, nonzeroAtEnd.playheads().get(loopController).timeSeconds());
        assertObserverSegment(nonzeroAtEnd, loopController, 0, IDLE, 0L, 0L, nextDown, duration);

        AnimationV2InstanceRuntime loopAboveRuntime = exactDurationRuntime(schema, loopController, layer, IDLE,
                Map.of(IDLE, loop));
        AnimationV2EvaluationSnapshot loopAbove = loopAboveRuntime.advance(nextUp);
        assertExactDouble(afterDuration, loopAbove.playheads().get(loopController).timeSeconds());
        assertObserverSegment(loopAbove, loopController, 0, IDLE, 0L, 0L, 0.0D, duration);
        assertObserverSegment(loopAbove, loopController, 1, IDLE, 1L, 1L, 0.0D, afterDuration);
        assertObserverTerminal(loopAbove, loopController, IDLE, afterDuration, 1L, 1L);

        BlendResourceId scaledController = id("exact-duration-scaled-loop");
        AnimationV2ControllerState scaledLoop = durationState(IDLE, AnimationV2PlaybackMode.LOOP,
                2.0D, null, layer, duration);
        AnimationV2InstanceRuntime scaledRuntime = exactDurationRuntime(schema, scaledController, layer, IDLE,
                Map.of(IDLE, scaledLoop));
        AnimationV2EvaluationSnapshot scaledBelow = scaledRuntime.advance(nextDown / 2.0D);
        assertExactDouble(nextDown, scaledBelow.playheads().get(scaledController).timeSeconds());
        AnimationV2EvaluationSnapshot scaledAtEnd = scaledRuntime.advance(exactResidual / 2.0D);
        assertExactDouble(0.0D, scaledAtEnd.playheads().get(scaledController).timeSeconds());
        AnimationV2InstanceRuntime scaledAboveRuntime = exactDurationRuntime(schema, scaledController, layer, IDLE,
                Map.of(IDLE, scaledLoop));
        AnimationV2EvaluationSnapshot scaledAbove = scaledAboveRuntime.advance(nextUp / 2.0D);
        assertExactDouble(afterDuration, scaledAbove.playheads().get(scaledController).timeSeconds());

        for (AnimationV2PlaybackMode mode : List.of(AnimationV2PlaybackMode.ONCE, AnimationV2PlaybackMode.HOLD)) {
            BlendResourceId controllerId = id("exact-duration-" + mode.name().toLowerCase());
            AnimationV2ControllerState terminal = durationState(IDLE, mode, 1.0D, null, layer, duration);
            AnimationV2InstanceRuntime terminalRuntime = exactDurationRuntime(schema, controllerId, layer, IDLE,
                    Map.of(IDLE, terminal));
            AnimationV2EvaluationSnapshot terminalBelow = terminalRuntime.advance(nextDown);
            assertExactDouble(nextDown, terminalBelow.playheads().get(controllerId).timeSeconds());
            assertObserverSegment(terminalBelow, controllerId, 0, IDLE, 0L, 0L, 0.0D, nextDown);
            AnimationV2EvaluationSnapshot terminalAtEnd = terminalRuntime.advance(exactResidual);
            assertExactDouble(duration, terminalAtEnd.playheads().get(controllerId).timeSeconds());
            assertObserverSegment(terminalAtEnd, controllerId, 0, IDLE, 0L, 0L, nextDown, duration);
            assertObserverTerminal(terminalAtEnd, controllerId, IDLE, duration, 0L, 0L);

            AnimationV2InstanceRuntime terminalAboveRuntime = exactDurationRuntime(schema, controllerId, layer, IDLE,
                    Map.of(IDLE, terminal));
            AnimationV2EvaluationSnapshot terminalAbove = terminalAboveRuntime.advance(nextUp);
            assertExactDouble(duration, terminalAbove.playheads().get(controllerId).timeSeconds());
            assertObserverSegment(terminalAbove, controllerId, 0, IDLE, 0L, 0L, 0.0D, duration);
        }

        BlendAnimationKey intro = key("exact-duration-intro");
        BlendAnimationKey active = key("exact-duration-active");
        BlendResourceId nextController = id("exact-duration-next");
        AnimationV2ControllerState introState = durationState(intro, AnimationV2PlaybackMode.ONCE,
                1.0D, active, layer, duration);
        AnimationV2ControllerState activeState = durationState(active, AnimationV2PlaybackMode.HOLD,
                1.0D, null, layer, duration);
        AnimationV2InstanceRuntime nextRuntime = exactDurationRuntime(schema, nextController, layer, intro,
                Map.of(intro, introState, active, activeState));
        AnimationV2EvaluationSnapshot nextBelow = nextRuntime.advance(nextDown);
        assertEquals(intro, nextBelow.playheads().get(nextController).state());
        assertExactDouble(nextDown, nextBelow.playheads().get(nextController).timeSeconds());
        AnimationV2EvaluationSnapshot nextAtEnd = nextRuntime.advance(exactResidual);
        assertEquals(active, nextAtEnd.playheads().get(nextController).state());
        assertExactDouble(0.0D, nextAtEnd.playheads().get(nextController).timeSeconds());
        assertObserverSegment(nextAtEnd, nextController, 0, intro, 0L, 0L, nextDown, duration);
        assertObserverTerminal(nextAtEnd, nextController, active, 0.0D, 0L, 1L);

        AnimationV2InstanceRuntime nextAboveRuntime = exactDurationRuntime(schema, nextController, layer, intro,
                Map.of(intro, introState, active, activeState));
        AnimationV2EvaluationSnapshot nextAbove = nextAboveRuntime.advance(nextUp);
        assertEquals(active, nextAbove.playheads().get(nextController).state());
        assertExactDouble(afterDuration, nextAbove.playheads().get(nextController).timeSeconds());
        assertObserverSegment(nextAbove, nextController, 0, intro, 0L, 0L, 0.0D, duration);
        assertObserverSegment(nextAbove, nextController, 1, active, 0L, 1L, 0.0D, afterDuration);

        AnimationV2InstanceRuntime microRuntime = exactDurationRuntime(schema, id("exact-duration-micro"), layer, IDLE,
                Map.of(IDLE, loop));
        AnimationV2EvaluationSnapshot zero = microRuntime.advance(0.0D);
        assertTrue(zero.observerTraversal().controller(id("exact-duration-micro")).segments().isEmpty());
        AnimationV2EvaluationSnapshot micro = microRuntime.advance(Math.nextUp(0.0D));
        assertExactDouble(Math.nextUp(0.0D), micro.playheads().get(id("exact-duration-micro")).timeSeconds());
        assertObserverSegment(micro, id("exact-duration-micro"), 0, IDLE, 0L, 0L,
                0.0D, Math.nextUp(0.0D));
    }

    @Test
    void observerLoopWrapCountMatchesTheExactModuloTerminalForNonBinaryDurations() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("exact-modulo", schema, 1.0F,
                AnimationV2LayerMode.OVERRIDE, false);
        double duration = 1.0D / 3.0D;
        BlendResourceId controllerId = id("exact-modulo-loop");
        AnimationV2ControllerState loop = durationState(IDLE, AnimationV2PlaybackMode.LOOP,
                1.0D, null, layer, duration);

        assertModuloConsistentLoopAdvance(schema, layer, controllerId, loop, Math.nextDown(1.0D), 2L);
        assertModuloConsistentLoopAdvance(schema, layer, controllerId, loop, 1.0D, 3L);
        assertModuloConsistentLoopAdvance(schema, layer, controllerId, loop, Math.nextUp(1.0D), 3L);
    }

    @Test
    void observerContinuityKeepsRevisionAnchorsForZeroMovementAndTrimsOnlyWholeRealSegments() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("observer-history", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2ControllerState loop = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                layer, clip(0.0F, 0.0F));
        BlendResourceId controllerId = id("observer-history");
        AnimationV2InstanceRuntime runtime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(new AnimationV2ControllerDefinition(controllerId, 0, List.of(layer), IDLE, Map.of(IDLE, loop)))));

        AnimationV2EvaluationSnapshot armed = runtime.advance(0.0D);
        AnimationV2EvaluationSnapshot firstForward = runtime.advance(0.25D);
        AnimationV2EvaluationSnapshot paused = runtime.advance(0.0D);
        AnimationV2EvaluationSnapshot secondForward = runtime.advance(0.25D);
        AnimationV2ObserverTraversal.Continuity continuity = secondForward.observerTraversal()
                .controller(controllerId).continuity();

        assertEquals(2, continuity.publications().size());
        assertEquals(firstForward.revision(), continuity.publications().getFirst().revision());
        assertEquals(secondForward.revision(), continuity.publications().getLast().revision());
        assertEquals(firstForward.revision(), continuity.publications().getLast().startAnchor().firstRevision());
        assertEquals(paused.revision(), continuity.publications().getLast().startAnchor().lastRevision(),
                "a no-movement owner publication must extend the exact anchor range rather than consume segment budget");
        assertEquals(secondForward.revision(), continuity.terminalAnchor().lastRevision());

        for (int index = 0; index <= AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE; index++) {
            runtime.advance(0.001D);
        }
        AnimationV2ObserverTraversal.Continuity trimmed = runtime.latestSnapshot().observerTraversal()
                .controller(controllerId).continuity();
        int retainedSegments = trimmed.publications().stream().mapToInt(value -> value.segments().size()).sum();
        assertEquals(AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE, retainedSegments);
        assertTrue(trimmed.initialAnchor().lastRevision() > armed.revision(),
                "an evicted committed anchor must become unrepresentable so X3 can fail closed instead of guessing");

        AnimationV2EvaluationSnapshot discontinuity = runtime.advanceAtFrame(0.0D,
                List.of(new AnimationV2Command(controllerId, IDLE, 1L, 0.75D, 1.0D)));
        AnimationV2ObserverTraversal.ControllerTraversal discontinuityTraversal = discontinuity.observerTraversal()
                .controller(controllerId);
        assertTrue(discontinuityTraversal.discontinuity());
        assertTrue(discontinuityTraversal.continuity().publications().isEmpty(),
                "command discontinuity must never retain a forgeable bridge from an older observation");
    }

    @Test
    void commandsAreCanonicallyAppliedAndOwnerThreadCannotChange() throws InterruptedException {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("command", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2ControllerState idle = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                layer, clip(0.0F));
        AnimationV2ControllerState walk = state(WALK, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                layer, clip(8.0F));
        AnimationV2InstanceRuntime runtime = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("command", 0, List.of(layer), IDLE, Map.of(IDLE, idle, WALK, walk)))));
        runtime.enqueue(command("command", WALK, 2L, 0.0D));
        runtime.enqueue(command("command", IDLE, 1L, 0.0D));
        AnimationV2EvaluationSnapshot accepted = runtime.advance(0.0D);
        assertEquals(WALK, accepted.playheads().get(id("command")).state());
        assertTranslation(8.0F, accepted);

        runtime.enqueue(command("command", IDLE, 2L, 0.0D));
        runtime.enqueue(command("command", IDLE, 1L, 0.0D));
        AnimationV2EvaluationSnapshot rejected = runtime.advance(0.0D);
        assertDiagnostic(rejected, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);
        assertDiagnostic(rejected, AnimationV2DiagnosticCode.STALE_COMMAND);

        AtomicReference<Throwable> otherThreadFailure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                runtime.advance(0.0D);
            } catch (Throwable throwable) {
                otherThreadFailure.set(throwable);
            }
        });
        other.start();
        other.join();
        assertNotNull(otherThreadFailure.get());
        assertTrue(otherThreadFailure.get() instanceof IllegalStateException);
    }

    @Test
    void goldenPoseAndPlayheadRemainStable() throws IOException {
        Properties golden = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/x2/animation-v2-golden.properties")) {
            assertNotNull(input);
            golden.load(input);
        }
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("golden-layer", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2ControllerState idle = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                layer, clip(0.0F, 10.0F));
        AnimationV2EvaluationSnapshot snapshot = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema,
                List.of(controller("golden", 0, List.of(layer), IDLE, Map.of(IDLE, idle))))).advance(0.25D);
        assertEquals(Long.parseLong(golden.getProperty("expected.revision")), snapshot.revision());
        assertEquals(golden.getProperty("expected.state"), snapshot.playheads().get(id("golden")).state().value());
        assertEquals(Double.parseDouble(golden.getProperty("expected.timeSeconds")),
                snapshot.playheads().get(id("golden")).timeSeconds(), 0.00001D);
        assertEquals(Float.parseFloat(golden.getProperty("expected.translationX")),
                snapshot.pose().transform(0).translation().x(), 0.00001F);
        assertFalse(snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code()
                == AnimationV2DiagnosticCode.NEXT_CYCLE));
    }

    private static AnimationV2InstanceRuntime conflictRuntime(boolean reverseInput) {
        BoneSchema schema = schema();
        AnimationV2ControllerDefinition alpha = fixedController("alpha", 0,
                layer("alpha-layer", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, true), 2.0F);
        AnimationV2ControllerDefinition beta = fixedController("beta", 0,
                layer("beta-layer", schema, 1.0F, AnimationV2LayerMode.OVERRIDE, true), 6.0F);
        List<AnimationV2ControllerDefinition> controllers = reverseInput ? List.of(beta, alpha) : List.of(alpha, beta);
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, controllers));
    }

    private static AnimationV2ControllerDefinition fixedController(
            String controllerId, int priority, AnimationV2LayerDefinition layer, float translation) {
        AnimationV2ControllerState state = state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null,
                layer, clip(translation));
        return controller(controllerId, priority, List.of(layer), IDLE, Map.of(IDLE, state));
    }

    private static AnimationV2ControllerDefinition controller(
            String controllerId,
            int priority,
            List<AnimationV2LayerDefinition> layers,
            BlendAnimationKey initial,
            Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        return new AnimationV2ControllerDefinition(id(controllerId), priority, layers, initial, states);
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double speed,
            double transitionSeconds,
            BlendAnimationKey next,
            AnimationV2LayerDefinition layer,
            AnimationV2Clip clip) {
        return state(key, mode, speed, transitionSeconds, next, Map.of(layer.id(), clip));
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double speed,
            double transitionSeconds,
            BlendAnimationKey next,
            Map<BlendResourceId, AnimationV2Clip> clips) {
        return new AnimationV2ControllerState(key, mode, speed, transitionSeconds, next, clips);
    }

    private static AnimationV2ControllerState durationState(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double speed,
            BlendAnimationKey next,
            AnimationV2LayerDefinition layer,
            double duration) {
        return state(key, mode, speed, 0.0D, next, layer, new AnimationV2Clip(List.of(
                new AnimationV2Keyframe(0.0D, new AnimationV2Pose(List.of(Transform.IDENTITY))),
                new AnimationV2Keyframe(duration, new AnimationV2Pose(List.of(Transform.IDENTITY))))));
    }

    private static AnimationV2InstanceRuntime exactDurationRuntime(
            BoneSchema schema,
            BlendResourceId controllerId,
            AnimationV2LayerDefinition layer,
            BlendAnimationKey initial,
            Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(
                new AnimationV2ControllerDefinition(controllerId, 0, List.of(layer), initial, states))));
    }

    private static AnimationV2LayerDefinition layer(
            String layerId, BoneSchema schema, float weight, AnimationV2LayerMode mode, boolean exclusive) {
        return new AnimationV2LayerDefinition(id(layerId), 0, mode, weight, BoneMask.all(schema), exclusive);
    }

    private static AnimationV2Clip clip(float... translations) {
        List<AnimationV2Keyframe> frames = new ArrayList<>();
        for (int index = 0; index < translations.length; index++) {
            frames.add(new AnimationV2Keyframe(index, new AnimationV2Pose(List.of(transform(translations[index])))));
        }
        return new AnimationV2Clip(frames);
    }

    private static AnimationV2Command command(String controllerId, BlendAnimationKey animation, long sequence, double time) {
        return new AnimationV2Command(id(controllerId), animation, sequence, time, 1.0D);
    }

    private static BoneSchema schema() {
        return new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
    }

    private static Transform transform(float translationX) {
        return new Transform(new Vec3(translationX, 0.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.of("x2test", path);
    }

    private static BlendAnimationKey key(String path) {
        return BlendAnimationKey.of("x2test", path);
    }

    private static void assertObserverSegment(
            AnimationV2EvaluationSnapshot snapshot,
            BlendResourceId controllerId,
            int index,
            BlendAnimationKey timeline,
            long loopEpoch,
            long occurrence,
            double start,
            double end) {
        AnimationV2ObserverTraversal.ControllerTraversal traversal = snapshot.observerTraversal().controller(controllerId);
        assertNotNull(traversal, "the exact owner snapshot must retain its observer facts");
        assertTrue(index < traversal.segments().size(),
                () -> "missing observer segment " + index + " in " + traversal.segments());
        AnimationV2ObserverTraversal.Segment segment = traversal.segments().get(index);
        assertEquals(timeline, segment.timeline());
        assertEquals(loopEpoch, segment.loopEpoch());
        assertEquals(occurrence, segment.occurrence());
        assertExactDouble(start, segment.startExclusiveSeconds());
        assertExactDouble(end, segment.endInclusiveSeconds());
    }

    private static void assertObserverTerminal(
            AnimationV2EvaluationSnapshot snapshot,
            BlendResourceId controllerId,
            BlendAnimationKey timeline,
            double time,
            long loopEpoch,
            long occurrence) {
        AnimationV2ObserverTraversal.ControllerTraversal traversal = snapshot.observerTraversal().controller(controllerId);
        assertNotNull(traversal, "the exact owner snapshot must retain terminal observer facts");
        AnimationV2ObserverTraversal.Anchor anchor = traversal.continuity().terminalAnchor();
        assertEquals(timeline, anchor.timeline());
        assertExactDouble(time, anchor.timeSeconds());
        assertEquals(loopEpoch, anchor.loopEpoch());
        assertEquals(occurrence, anchor.occurrence());
    }

    private static void assertModuloConsistentLoopAdvance(
            BoneSchema schema,
            AnimationV2LayerDefinition layer,
            BlendResourceId controllerId,
            AnimationV2ControllerState loop,
            double input,
            long expectedWraps) {
        AnimationV2InstanceRuntime runtime = exactDurationRuntime(schema, controllerId, layer, IDLE,
                Map.of(IDLE, loop));
        AnimationV2EvaluationSnapshot snapshot = runtime.advance(input);
        double expectedTerminal = input % loop.durationSeconds();
        assertExactDouble(expectedTerminal, snapshot.playheads().get(controllerId).timeSeconds());
        AnimationV2ObserverTraversal.ControllerTraversal traversal = snapshot.observerTraversal().controller(controllerId);
        assertNotNull(traversal);
        assertEquals(expectedWraps, traversal.continuity().terminalAnchor().loopEpoch(),
                "loop epoch must count completed durations according to the same modulo terminal");
        assertEquals(expectedWraps, traversal.continuity().terminalAnchor().occurrence());
        assertEquals(expectedWraps + (expectedTerminal > 0.0D ? 1L : 0L), traversal.segments().size());
        assertExactDouble(expectedTerminal, traversal.continuity().terminalAnchor().timeSeconds());
    }

    private static void assertExactDouble(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                () -> "expected exact double " + Double.toHexString(expected)
                        + " but was " + Double.toHexString(actual));
    }

    private static void assertTranslation(float expected, AnimationV2EvaluationSnapshot snapshot) {
        assertEquals(expected, snapshot.pose().transform(0).translation().x(), 0.00001F);
    }

    private static void assertDiagnostic(AnimationV2EvaluationSnapshot snapshot, AnimationV2DiagnosticCode code) {
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code),
                () -> "missing " + code + " in " + snapshot.diagnostics());
    }
}
