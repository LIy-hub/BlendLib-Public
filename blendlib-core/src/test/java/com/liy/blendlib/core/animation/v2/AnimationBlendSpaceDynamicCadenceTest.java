package com.liy.blendlib.core.animation.v2;

import static com.liy.blendlib.core.animation.v2.AnimationBlendSpace1DTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnimationBlendSpaceDynamicCadenceTest {
    private static final BlendAnimationKey OTHER = BlendAnimationKey.parse("space:other");
    private static final BlendResourceId UPPER = BlendResourceId.parse("space:upper");

    @Test void oldRateAdvancesUnequalDurationsBeforeCompleteNewVectorIsInstalled() {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 2, .5, 0, 1, 1, true),
                controller(B, 1, 2, 0, 1, 1, true),
                controller(C, .5, 1.5, 0, 1, 1, true),
                controller(UPPER, 8, 1, 1, 1, 1, true)));
        var space = space();
        var binding = space.bind(plan);
        var runtime = new AnimationV2InstanceRuntime(plan);
        var commands = binding.commands(.125, 7);
        var first = runtime.advanceBlendSpaceAtFrame(0, commands, space.weights(0), binding.rateUpdate(1));
        assertPhase(first, .125);
        var faster = runtime.advanceBlendSpaceAtFrame(.5, List.of(), space.weights(3), binding.rateUpdate(2));
        assertPhase(faster, .375); // Elapsed half second still uses cadence one.
        var slower = runtime.advanceBlendSpaceAtFrame(.25, List.of(), space.weights(0), binding.rateUpdate(.5));
        assertPhase(slower, .625); // Elapsed quarter second uses cadence two.
        var next = runtime.advanceBlendSpaceAtFrame(.5, List.of(), space.weights(1), binding.rateUpdate(.5));
        assertPhase(next, .75);
        assertEquals(1.25, next.playheads().get(UPPER).timeSeconds());
        for (var id : List.of(A, B, C)) {
            assertEquals(7, next.playheads().get(id).acceptedSequence());
            assertFalse(next.observerTraversal().controller(id).discontinuity());
        }
        assertEquals(0F, next.effectiveLayerWeights().get(new AnimationV2LayerWeights.Key(A, A)));
        assertEquals(0F, next.effectiveLayerWeights().get(new AnimationV2LayerWeights.Key(C, C)));
    }

    @Test void rateVectorsAreFactoryOnlyImmutableCompleteAndDoNotChangeBaselineCommands() {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 2, .5, 0, 1, 1, true), controller(B, 1, 2, 0, 1, 1, true)));
        var binding = two(2).bind(plan);
        var baseline = binding.commands(.25, 1);
        var update = binding.rateUpdate(2);
        assertEquals(2, update.cadenceMultiplier());
        assertEquals(Map.of(A, 4D, B, .5D), update.commandRates());
        assertThrows(UnsupportedOperationException.class, () -> update.commandRates().put(A, 1D));
        assertThrows(UnsupportedOperationException.class, () -> update.commandRates().clear());
        for (var constructor : AnimationBlendSpaceSyncGroup.RateUpdate.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        binding.rateUpdate(.5);
        assertEquals(Map.of(A, 4D, B, .5D), update.commandRates());
        assertEquals(baseline, binding.commands(.25, 1));
        assertEquals(Map.of(A, 2D, B, .25D), binding.rateUpdate(1).commandRates());
    }

    @Test void boundsIncludeCadenceCommandEffectiveAndRoundedEffectiveRates() {
        var unitPlan = new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 1, 0, 1, 1, true)));
        var unit = two(1).bind(unitPlan);
        for (double valid : new double[]{1D / 64, 1, 64}) {
            AnimationBlendSpaceSyncGroup.validateCadenceMultiplier(valid);
            assertEquals(valid, unit.rateUpdate(valid).commandRates().get(A));
        }
        for (double invalid : new double[]{0, -0D, -1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Math.nextDown(1D / 64), Math.nextUp(64D)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnimationBlendSpaceSyncGroup.validateCadenceMultiplier(invalid));
            assertThrows(IllegalArgumentException.class, () -> unit.rateUpdate(invalid));
        }
        // The last member fails; no partial RateUpdate can escape.
        var longLast = two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 2, 1, 0, 1, 1, true))));
        assertThrows(IllegalArgumentException.class, () -> longLast.rateUpdate(64));
        var commandLimited = two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 1D / 64, 0, 1, 1, true))));
        assertThrows(IllegalArgumentException.class, () -> commandLimited.rateUpdate(2));
        var slowCommandLimited = two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 64, 0, 1, 1, true))));
        assertThrows(IllegalArgumentException.class, () -> slowCommandLimited.rateUpdate(1D / 64));
        var effectiveLimited = two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1D / 64, 1D / 64, 0, 1, 1, true))));
        assertThrows(IllegalArgumentException.class, () -> effectiveLimited.rateUpdate(.5));
        var fastEffectiveLimited = two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 64, 2, 0, 1, 1, true))));
        assertThrows(IllegalArgumentException.class, () -> fastEffectiveLimited.rateUpdate(2));
        double descriptorSpeed = 0.7613321984033534D;
        assertTrue(descriptorSpeed * ((1D / 64) / descriptorSpeed) < 1D / 64);
        var rounded = two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, descriptorSpeed, 0, 1, 1, true))));
        assertThrows(IllegalArgumentException.class, () -> rounded.rateUpdate(1D / 64));
        // A potentially valid requested multiplier cannot rescue an unbindable cadence-one baseline.
        assertThrows(IllegalArgumentException.class, () -> two(1).bind(new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 128, 2, 0, 1, 1, true)))));
    }

    @Test void exactPlanIdentityRejectsStructurallyEqualReloadBeforeAnyLiveMutation() throws Exception {
        var controllers = List.of(controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 1, 0, 1, 1, true));
        var oldPlan = new AnimationV2InstancePlan(SCHEMA, controllers);
        var reloadedPlan = new AnimationV2InstancePlan(SCHEMA, controllers);
        var update = two(1).bind(oldPlan).rateUpdate(2);
        var runtime = new AnimationV2InstanceRuntime(reloadedPlan);
        var initial = runtime.latestSnapshot();
        assertThrows(IllegalArgumentException.class, () -> runtime.validateRateUpdate(update));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.advanceBlendSpaceAtFrame(1, List.of(), two(1).weights(.5), update));
        assertSame(initial, runtime.latestSnapshot());
        assertNull(((AtomicReference<?>) field(runtime, "ownerThread")).get());
        runtime.validateRateUpdate(two(1).bind(reloadedPlan).rateUpdate(2));
        assertNull(((AtomicReference<?>) field(runtime, "ownerThread")).get());
        assertThrows(NullPointerException.class, () -> runtime.validateRateUpdate(null));
        assertThrows(NullPointerException.class,
                () -> runtime.advanceBlendSpaceAtFrame(0, List.of(), AnimationV2LayerWeights.empty(), null));
    }

    @Test void incompatibleLastMemberRollsBackOldAdvanceNewRatesCommandsAndSnapshot() throws Exception {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(withOtherState(A, 0), withOtherState(B, 0),
                controller(UPPER, 8, 1, 1, 1, 1, true)));
        var space = two(8);
        var binding = space.bind(plan);
        var runtime = new AnimationV2InstanceRuntime(plan);
        runtime.advanceBlendSpaceAtFrame(0, binding.commands(.125, 1), space.weights(0), binding.rateUpdate(1));
        var before = runtime.latestSnapshot();
        var bad = new AnimationV2Command(B, OTHER, 2, 3, 1);
        assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(bad));
        var rate = binding.rateUpdate(2);
        var nonmemberCommand = new AnimationV2Command(UPPER, STATE, 3, 4, 1);
        assertThrows(IllegalArgumentException.class,
                () -> runtime.advanceBlendSpaceAtFrame(.25, List.of(nonmemberCommand), space.weights(1), rate));
        assertSame(before, runtime.latestSnapshot());
        assertEquals(1D, field(controllerRuntime(runtime, A), "playbackSpeed"));
        assertEquals(1D, field(controllerRuntime(runtime, B), "playbackSpeed"));
        // Rejected staging preserved the queued command and elapsed interval for a normal retry.
        var retried = runtime.advanceWeightedAtFrame(.25, List.of(), space.weights(0));
        assertEquals(1.25, retried.playheads().get(A).timeSeconds());
        assertEquals(3, retried.playheads().get(B).timeSeconds());
        assertEquals(OTHER, retried.playheads().get(B).state());
        assertEquals(2, retried.playheads().get(B).acceptedSequence());
        assertEquals(.25, retried.playheads().get(UPPER).timeSeconds());
        assertEquals(-1, retried.playheads().get(UPPER).acceptedSequence());
        // Immutable preflight intentionally permits a same-frame recovery command to restore the member.
        runtime.validateRateUpdate(rate);
        var recovered = runtime.advanceBlendSpaceAtFrame(0,
                List.of(new AnimationV2Command(B, STATE, 3, 1.25, 1)), space.weights(0), rate);
        assertEquals(STATE, recovered.playheads().get(B).state());
        assertEquals(2D, field(controllerRuntime(runtime, A), "playbackSpeed"));
        assertEquals(2D, field(controllerRuntime(runtime, B), "playbackSpeed"));
    }

    @Test void rateChangesPreserveAcceptedCommandWatermarkOccurrenceAndDuplicateSemantics() throws Exception {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 1, 0, 1, 1, true)));
        var space = two(1);
        var binding = space.bind(plan);
        var commands = binding.commands(.25, 9);
        var runtime = new AnimationV2InstanceRuntime(plan);
        runtime.advanceBlendSpaceAtFrame(0, commands, space.weights(0), binding.rateUpdate(1));
        var live = controllerRuntime(runtime, A);
        Object accepted = field(live, "acceptedCommand");
        Object watermark = field(live, "sequenceWatermark");
        var anchor = runtime.latestSnapshot().observerTraversal().controller(A).continuity().terminalAnchor();
        for (double multiplier : new double[]{2, .5, .5, 2}) {
            var updated = runtime.advanceBlendSpaceAtFrame(0, List.of(), space.weights(1), binding.rateUpdate(multiplier));
            assertSame(accepted, field(live, "acceptedCommand"));
            assertEquals(watermark, field(live, "sequenceWatermark"));
            var trace = updated.observerTraversal().controller(A);
            assertFalse(trace.discontinuity());
            assertTrue(trace.segments().isEmpty());
            assertEquals(anchor.occurrence(), trace.continuity().terminalAnchor().occurrence());
            assertEquals(anchor.loopEpoch(), trace.continuity().terminalAnchor().loopEpoch());
            assertEquals(.25, updated.playheads().get(A).timeSeconds());
        }
        var duplicate = runtime.advanceBlendSpaceAtFrame(.125, commands, space.weights(0), binding.rateUpdate(2));
        assertEquals(.5, duplicate.playheads().get(A).timeSeconds());
        assertEquals(9, duplicate.playheads().get(A).acceptedSequence());
        assertEquals(2, duplicate.diagnostics().stream()
                .filter(d -> d.code() == AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED).count());
        assertFalse(duplicate.observerTraversal().controller(A).discontinuity());
        assertSame(accepted, field(live, "acceptedCommand"));
    }

    @Test void traversalAtExactEndpointsAndWrapUsesOldCadenceWithoutNewOccurrenceOnRateChange() {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 1, 0, 1, 1, true)));
        var space = two(1);
        var binding = space.bind(plan);
        var runtime = new AnimationV2InstanceRuntime(plan);
        var first = runtime.advanceBlendSpaceAtFrame(0, binding.commands(.75, 0), space.weights(0), binding.rateUpdate(1));
        long initialOccurrence = first.observerTraversal().controller(A).continuity().terminalAnchor().occurrence();
        var wrap = runtime.advanceBlendSpaceAtFrame(.25, List.of(), space.weights(1), binding.rateUpdate(2));
        var trace = wrap.observerTraversal().controller(A);
        assertFalse(trace.discontinuity());
        assertFalse(trace.truncated());
        assertEquals(1, trace.segments().size());
        assertEquals(.75, trace.segments().getFirst().startExclusiveSeconds());
        assertEquals(1, trace.segments().getFirst().endInclusiveSeconds());
        assertEquals(0, trace.terminalTimeSeconds());
        assertEquals(initialOccurrence + 1, trace.continuity().terminalAnchor().occurrence());
        var next = runtime.advanceBlendSpaceAtFrame(.125, List.of(), space.weights(0), binding.rateUpdate(.5));
        var nextTrace = next.observerTraversal().controller(A);
        assertEquals(.25, nextTrace.terminalTimeSeconds());
        assertEquals(initialOccurrence + 1, nextTrace.continuity().terminalAnchor().occurrence());
        assertEquals(0, nextTrace.segments().getFirst().startExclusiveSeconds());
        assertEquals(.25, nextTrace.segments().getFirst().endInclusiveSeconds());
        assertEquals(trace.continuity().initialAnchor().firstRevision(),
                nextTrace.continuity().initialAnchor().firstRevision());
    }

    @Test void activeTransitionKeepsSourceRateAndElapsedTimeWhileOnlyCurrentRateChanges() throws Exception {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(withOtherState(A, 4), withOtherState(B, 4)));
        var space = two(8);
        var binding = space.bind(plan);
        var runtime = new AnimationV2InstanceRuntime(plan);
        runtime.advanceAtFrame(0, List.of(new AnimationV2Command(A, OTHER, 1, 1, 1)));
        runtime.advanceBlendSpaceAtFrame(0, List.of(new AnimationV2Command(A, STATE, 2, 0, .5)),
                space.weights(0), binding.rateUpdate(2));
        var live = controllerRuntime(runtime, A);
        assertEquals(1D, field(live, "previousPlaybackSpeed"));
        var first = runtime.advanceBlendSpaceAtFrame(1, List.of(), space.weights(0), binding.rateUpdate(.5));
        assertEquals(2, first.playheads().get(A).timeSeconds());
        assertEquals(.25, first.playheads().get(A).transitionProgress());
        assertEquals(16.875F, first.pose().transform(0).translation().x());
        assertEquals(1D, field(live, "previousPlaybackSpeed"));
        var second = runtime.advanceBlendSpaceAtFrame(1, List.of(), space.weights(0), binding.rateUpdate(3));
        assertEquals(2.5, second.playheads().get(A).timeSeconds());
        assertEquals(.5, second.playheads().get(A).transitionProgress());
        assertEquals(15F, second.pose().transform(0).translation().x());
        assertEquals(OTHER, second.playheads().get(A).previousState());
        assertEquals(1D, field(live, "previousPlaybackSpeed"));
        assertEquals(2, second.playheads().get(A).acceptedSequence());
    }

    @Test void explicitRejectionsKeepTheirSemanticsBeforeRateInstall() throws Exception {
        var plan = new AnimationV2InstancePlan(SCHEMA, List.of(
                controller(A, 1, 1, 0, 1, 1, true), controller(B, 1, 1, 0, 1, 1, true)));
        var space = two(1);
        var binding = space.bind(plan);
        var runtime = new AnimationV2InstanceRuntime(plan);
        runtime.advanceBlendSpaceAtFrame(0, binding.commands(.5, 1), space.weights(0), binding.rateUpdate(1));
        var rejected = runtime.advanceBlendSpaceAtFrame(.125, List.of(),
                List.of(new AnimationV2SequenceRejection(B, 2)), space.weights(0), binding.rateUpdate(2));
        assertEquals(.625, rejected.playheads().get(A).timeSeconds());
        assertEquals(0, rejected.playheads().get(B).timeSeconds());
        assertEquals(-1, rejected.playheads().get(B).acceptedSequence());
        assertTrue(rejected.observerTraversal().controller(B).discontinuity());
        assertEquals(2L, field(controllerRuntime(runtime, B), "sequenceWatermark"));
        assertEquals(2D, field(controllerRuntime(runtime, B), "playbackSpeed"));
    }

    private static AnimationBlendSpace1D two(double cycleSeconds) {
        return new AnimationBlendSpace1D(List.of(sample(0, A), sample(1, B)), cycleSeconds);
    }

    private static void assertPhase(AnimationV2EvaluationSnapshot snapshot, double expected) {
        double[] durations = {2, 1, .5};
        var ids = List.of(A, B, C);
        for (int i = 0; i < ids.size(); i++)
            assertEquals(expected, snapshot.playheads().get(ids.get(i)).timeSeconds() / durations[i], 1E-12);
    }

    private static AnimationV2ControllerDefinition withOtherState(BlendResourceId id, double transitionSeconds) {
        var base = controller(id, 8, 1, 0, 1, 1, true);
        var initial = new AnimationV2ControllerState(STATE, AnimationV2PlaybackMode.LOOP, 1,
                transitionSeconds, null, base.initialStateDefinition().clipsByLayer());
        var start = new AnimationV2Pose(List.of(Transform.IDENTITY));
        var end = new AnimationV2Pose(List.of(new Transform(new Vec3(80, 0, 0), Quaternion.IDENTITY, Vec3.ONE)));
        var moving = new AnimationV2Clip(List.of(new AnimationV2Keyframe(0, start), new AnimationV2Keyframe(8, end)));
        var other = new AnimationV2ControllerState(OTHER, AnimationV2PlaybackMode.LOOP, 1, 0, null, Map.of(id, moving));
        return new AnimationV2ControllerDefinition(id, 0, base.layers(), STATE, Map.of(STATE, initial, OTHER, other));
    }

    private static Object controllerRuntime(AnimationV2InstanceRuntime runtime, BlendResourceId id) throws Exception {
        for (Object controller : (Object[]) field(runtime, "controllersInEvaluationOrder")) {
            if (((AnimationV2ControllerDefinition) field(controller, "definition")).id().equals(id)) return controller;
        }
        throw new AssertionError("Missing controller " + id);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
