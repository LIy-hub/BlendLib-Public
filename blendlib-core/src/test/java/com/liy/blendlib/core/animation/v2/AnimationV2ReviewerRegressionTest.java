package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Regression probes for the X2 adversarial runtime review findings. */
class AnimationV2ReviewerRegressionTest {
    private static final BlendAnimationKey IDLE = key("idle");
    private static final BlendAnimationKey WALK = key("walk");
    private static final BlendAnimationKey RUN = key("run");

    @Test
    void absoluteFrameCommandDoesNotConsumeItsFrameDeltaTwice() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("frame", schema, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2InstanceRuntime runtime = runtime(schema, layer, IDLE, Map.of(
                IDLE, state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer, clip(0.0F, 0.0F)),
                WALK, state(WALK, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer,
                        clip(0.0F, 10.0F, 20.0F, 30.0F))));

        AnimationV2EvaluationSnapshot snapshot = runtime.advanceAtFrame(0.5D,
                List.of(command(WALK, 1L, 1.0D, 1.0D)));

        assertEquals(WALK, snapshot.playheads().get(id("controller")).state());
        assertEquals(1.0D, snapshot.playheads().get(id("controller")).timeSeconds(), 0.00001D);
        assertEquals(10.0F, snapshot.pose().transform(0).translation().x(), 0.00001F);
    }

    @Test
    void sameControllerSequenceConflictIsGroupRejectedAndWatermarked() {
        AnimationV2InstanceRuntime firstOrder = commandRuntime();
        AnimationV2InstanceRuntime reverseOrder = commandRuntime();
        AnimationV2Command walk = command(WALK, 4L, 0.25D, 1.0D);
        AnimationV2Command run = command(RUN, 4L, 0.75D, 1.0D);

        AnimationV2EvaluationSnapshot first = firstOrder.advanceAtFrame(0.0D, List.of(walk, run));
        AnimationV2EvaluationSnapshot reverse = reverseOrder.advanceAtFrame(0.0D, List.of(run, walk));
        assertEquals(IDLE, first.playheads().get(id("controller")).state());
        assertEquals(IDLE, reverse.playheads().get(id("controller")).state());
        assertHas(first, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);
        assertHas(reverse, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);

        AnimationV2EvaluationSnapshot replay = firstOrder.advanceAtFrame(0.0D, List.of(walk));
        assertEquals(IDLE, replay.playheads().get(id("controller")).state());
        assertHas(replay, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);

        AnimationV2InstanceRuntime duplicates = commandRuntime();
        AnimationV2EvaluationSnapshot coalesced = duplicates.advanceAtFrame(0.0D, List.of(walk, walk));
        assertEquals(WALK, coalesced.playheads().get(id("controller")).state());
        assertHas(coalesced, AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED);

        AnimationV2InstanceRuntime splitDrain = commandRuntime();
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE; index++) {
            splitDrain.enqueue(walk);
        }
        splitDrain.enqueue(run);
        AnimationV2EvaluationSnapshot split = splitDrain.advance(0.0D);
        assertEquals(IDLE, split.playheads().get(id("controller")).state());
        assertHas(split, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);
        assertEquals(IDLE, splitDrain.advance(0.0D).playheads().get(id("controller")).state());
    }

    @Test
    void nonAdjacentIngressMembersAreGroupedBeforeAnyWinnerCanPublish() {
        AnimationV2InstanceRuntime runtime = commandRuntime();
        runtime.enqueue(command(WALK, 5L, 0.25D, 1.0D));
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE - 1; index++) {
            runtime.enqueue(new AnimationV2Command(id("a-filler-" + index), WALK, 1L, 0.0D, 1.0D));
        }
        runtime.enqueue(command(RUN, 5L, 0.75D, 1.0D));

        AnimationV2EvaluationSnapshot first = runtime.advance(0.0D);
        assertEquals(IDLE, first.playheads().get(id("controller")).state());
        assertHas(first, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);
        assertEquals(IDLE, runtime.advance(0.0D).playheads().get(id("controller")).state());
    }

    @Test
    void overBudgetFrameCommandsDeferWholeGroupsAndEventuallyMakeProgress() {
        AnimationV2InstanceRuntime runtime = commandRuntime();
        List<AnimationV2Command> frameCommands = new ArrayList<>();
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE; index++) {
            frameCommands.add(new AnimationV2Command(id("a-frame-" + index), WALK, 1L, 0.0D, 1.0D));
        }
        AnimationV2Command target = command(WALK, 77L, 0.0D, 1.0D);
        frameCommands.add(target);
        frameCommands.add(target);

        AnimationV2EvaluationSnapshot first = runtime.advanceAtFrame(0.0D, frameCommands);
        assertEquals(IDLE, first.playheads().get(id("controller")).state());
        assertHas(first, AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED);

        AnimationV2EvaluationSnapshot second = runtime.advanceAtFrame(0.0D, List.of());
        assertEquals(WALK, second.playheads().get(id("controller")).state());
        assertHas(second, AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED);
    }

    @Test
    void laterSameSequenceConflictRevokesTheEarlierWinner() {
        AnimationV2InstanceRuntime runtime = commandRuntime();
        assertEquals(WALK, runtime.advanceAtFrame(0.0D, List.of(command(WALK, 10L, 0.0D, 1.0D)))
                .playheads().get(id("controller")).state());

        AnimationV2EvaluationSnapshot rejected = runtime.advanceAtFrame(0.0D, List.of(command(RUN, 10L, 0.0D, 1.0D)));
        assertEquals(IDLE, rejected.playheads().get(id("controller")).state());
        assertHas(rejected, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);
    }

    @Test
    void aDeferredGroupWaitsForSameSequenceFrameMembersInsteadOfPublishingAPrefix() {
        AnimationV2InstanceRuntime runtime = commandRuntime();
        List<AnimationV2Command> backlog = new ArrayList<>();
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE; index++) {
            backlog.add(new AnimationV2Command(id("a-backlog-" + index), WALK, 1L, 0.0D, 1.0D));
        }
        backlog.add(command(WALK, 88L, 0.0D, 1.0D));
        runtime.advanceAtFrame(0.0D, backlog);

        AnimationV2EvaluationSnapshot rejected = runtime.advanceAtFrame(0.0D,
                List.of(command(RUN, 88L, 0.0D, 1.0D)));
        assertEquals(IDLE, rejected.playheads().get(id("controller")).state());
        assertHas(rejected, AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT);
    }

    @Test
    void holdAdvancesNormallyAndInterruptedManualBlendFreezesMixedPresentation() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("transition", schema, AnimationV2LayerMode.OVERRIDE, false);
        BlendAnimationKey hold = key("hold");
        AnimationV2InstanceRuntime holding = runtime(schema, layer, hold, Map.of(
                hold, state(hold, AnimationV2PlaybackMode.HOLD, 1.0D, 0.0D, null, layer, clip(0.0F, 10.0F))));
        AnimationV2EvaluationSnapshot progressingHold = holding.advance(0.25D);
        assertEquals(0.25D, progressingHold.playheads().get(id("controller")).timeSeconds(), 0.00001D);
        assertEquals(2.5F, progressingHold.pose().transform(0).translation().x(), 0.00001F);
        assertEquals(1.0D, holding.advance(10.0D).playheads().get(id("controller")).timeSeconds(), 0.00001D);

        AnimationV2InstanceRuntime runtime = runtime(schema, layer, IDLE, Map.of(
                IDLE, state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer, clip(0.0F)),
                WALK, state(WALK, AnimationV2PlaybackMode.LOOP, 1.0D, 1.0D, null, layer, clip(10.0F)),
                RUN, state(RUN, AnimationV2PlaybackMode.LOOP, 1.0D, 1.0D, null, layer, clip(20.0F))));
        runtime.advanceAtFrame(0.0D, List.of(command(WALK, 1L, 0.0D, 1.0D)));
        assertEquals(5.0F, runtime.advance(0.5D).pose().transform(0).translation().x(), 0.00001F);

        AnimationV2EvaluationSnapshot interrupted = runtime.advanceAtFrame(0.0D,
                List.of(command(RUN, 2L, 0.0D, 1.0D)));
        assertEquals(5.0F, interrupted.pose().transform(0).translation().x(), 0.00001F);
        assertEquals(12.5F, runtime.advance(0.5D).pose().transform(0).translation().x(), 0.00001F);
    }

    @Test
    void automaticNextInterruptAlsoFreezesTheAlreadyMixedPresentation() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("automatic", schema, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2InstanceRuntime runtime = runtime(schema, layer, IDLE, Map.of(
                IDLE, state(IDLE, AnimationV2PlaybackMode.ONCE, 1.0D, 0.0D, WALK, layer, clip(0.0F, 0.0F)),
                WALK, state(WALK, AnimationV2PlaybackMode.ONCE, 1.0D, 2.0D, RUN, layer, clip(10.0F, 10.0F)),
                RUN, state(RUN, AnimationV2PlaybackMode.LOOP, 1.0D, 1.0D, null, layer, clip(20.0F))));

        runtime.advance(1.0D);
        runtime.advance(0.5D);
        AnimationV2EvaluationSnapshot automaticInterruption = runtime.advance(0.5D);

        assertEquals(RUN, automaticInterruption.playheads().get(id("controller")).state());
        // A->B is already 50% smoothstep blended at B's automatic boundary; C must inherit that mixed source.
        assertEquals(5.0F, automaticInterruption.pose().transform(0).translation().x(), 0.00001F);
    }

    @Test
    void quaternionHemisphereAndDotZeroCasesAreCanonicalAndFinite() {
        Transform rest = Transform.IDENTITY;
        Transform positiveHalfTurn = transform(0.0F, new Quaternion(1.0F, 0.0F, 0.0F, 0.0F));
        Transform negativeHalfTurn = transform(0.0F, new Quaternion(-1.0F, 0.0F, 0.0F, 0.0F));

        Transform forward = AnimationV2Pose.weightedOverride(rest, List.of(
                new AnimationV2Pose.WeightedTransform(positiveHalfTurn, 0.5F),
                new AnimationV2Pose.WeightedTransform(negativeHalfTurn, 0.5F)));
        Transform reverse = AnimationV2Pose.weightedOverride(rest, List.of(
                new AnimationV2Pose.WeightedTransform(negativeHalfTurn, 0.5F),
                new AnimationV2Pose.WeightedTransform(positiveHalfTurn, 0.5F)));
        assertQuaternion(forward, 1.0F, 0.0F, 0.0F, 0.0F);
        assertQuaternion(reverse, 1.0F, 0.0F, 0.0F, 0.0F);

        Transform positiveInterpolation = AnimationV2Pose.interpolate(rest, positiveHalfTurn, 0.5F);
        Transform negativeInterpolation = AnimationV2Pose.interpolate(rest, negativeHalfTurn, 0.5F);
        float halfSqrt = (float) Math.sqrt(0.5D);
        assertQuaternion(positiveInterpolation, halfSqrt, 0.0F, 0.0F, halfSqrt);
        assertQuaternion(negativeInterpolation, halfSqrt, 0.0F, 0.0F, halfSqrt);
    }

    @Test
    void sharedQuaternionReferencePreservesShortestArcAcrossRuntimeAndPosePaths() {
        Transform plus170 = rotatedZ(170.0D);
        Transform minus170 = rotatedZ(-170.0D);
        AnimationV2EvaluationSnapshot forward = quaternionRuntime(
                Transform.IDENTITY, List.of(plus170, minus170), false, 1.0F).advance(0.0D);
        AnimationV2EvaluationSnapshot reverse = quaternionRuntime(
                Transform.IDENTITY, List.of(plus170, minus170), true, 1.0F).advance(0.0D);
        Transform poseForward = AnimationV2Pose.weightedOverride(Transform.IDENTITY, List.of(
                new AnimationV2Pose.WeightedTransform(plus170, 0.5F),
                new AnimationV2Pose.WeightedTransform(minus170, 0.5F)));
        Transform poseReverse = AnimationV2Pose.weightedOverride(Transform.IDENTITY, List.of(
                new AnimationV2Pose.WeightedTransform(minus170, 0.5F),
                new AnimationV2Pose.WeightedTransform(plus170, 0.5F)));
        assertRotatesPositiveXToNegativeX(forward.pose().transform(0));
        assertSameRotation(forward.pose().transform(0), reverse.pose().transform(0));
        assertSameRotation(forward.pose().transform(0), poseForward);
        assertSameRotation(poseForward, poseReverse);

        Transform restPlus170 = rotatedZ(170.0D);
        Transform restRuntime = quaternionRuntime(restPlus170, List.of(minus170), false, 0.5F)
                .advance(0.0D).pose().transform(0);
        Transform restPose = AnimationV2Pose.weightedOverride(restPlus170,
                List.of(new AnimationV2Pose.WeightedTransform(minus170, 0.5F)));
        assertRotatesPositiveXToNegativeX(restRuntime);
        assertSameRotation(restRuntime, restPose);

        Transform positiveHalfTurn = transform(0.0F, new Quaternion(1.0F, 0.0F, 0.0F, 0.0F));
        Transform negativeHalfTurn = transform(0.0F, new Quaternion(-1.0F, 0.0F, 0.0F, 0.0F));
        Transform halfTurn = AnimationV2Pose.weightedOverride(Transform.IDENTITY, List.of(
                new AnimationV2Pose.WeightedTransform(positiveHalfTurn, 0.5F),
                new AnimationV2Pose.WeightedTransform(negativeHalfTurn, 0.5F)));
        Transform runtimeHalfTurn = quaternionRuntime(Transform.IDENTITY,
                List.of(positiveHalfTurn, negativeHalfTurn), false, 1.0F).advance(0.0D).pose().transform(0);
        assertQuaternion(halfTurn, 1.0F, 0.0F, 0.0F, 0.0F);
        assertQuaternion(runtimeHalfTurn, 1.0F, 0.0F, 0.0F, 0.0F);
        assertSameRotation(runtimeHalfTurn, halfTurn);

        Transform exactHalfTurn = quaternionRuntime(Transform.IDENTITY,
                List.of(rotatedZ(180.0D), rotatedZ(-180.0D)), false, 1.0F).advance(0.0D).pose().transform(0);
        assertRotatesPositiveXToNegativeX(exactHalfTurn);

        Transform yHalfTurn = transform(0.0F, new Quaternion(0.0F, 1.0F, 0.0F, 0.0F));
        Transform dotZeroForward = AnimationV2Pose.weightedOverride(Transform.IDENTITY, List.of(
                new AnimationV2Pose.WeightedTransform(positiveHalfTurn, 0.5F),
                new AnimationV2Pose.WeightedTransform(yHalfTurn, 0.5F)));
        Transform dotZeroReverse = AnimationV2Pose.weightedOverride(Transform.IDENTITY, List.of(
                new AnimationV2Pose.WeightedTransform(yHalfTurn, 0.5F),
                new AnimationV2Pose.WeightedTransform(positiveHalfTurn, 0.5F)));
        Transform runtimeDotZero = quaternionRuntime(Transform.IDENTITY,
                List.of(positiveHalfTurn, yHalfTurn), false, 1.0F).advance(0.0D).pose().transform(0);
        assertFiniteRotation(dotZeroForward);
        assertSameRotation(dotZeroForward, dotZeroReverse);
        assertSameRotation(runtimeDotZero, dotZeroForward);

        Transform nearZeroResidual = AnimationV2Pose.weightedOverride(Transform.IDENTITY, List.of(
                new AnimationV2Pose.WeightedTransform(rotatedZ(179.99999D), 0.5F),
                new AnimationV2Pose.WeightedTransform(rotatedZ(-179.99999D), 0.5F)));
        Transform runtimeNearZeroResidual = quaternionRuntime(Transform.IDENTITY,
                List.of(rotatedZ(179.99999D), rotatedZ(-179.99999D)), false, 1.0F)
                .advance(0.0D).pose().transform(0);
        assertFiniteRotation(nearZeroResidual);
        assertRotatesPositiveXToNegativeX(nearZeroResidual);
        assertSameRotation(runtimeNearZeroResidual, nearZeroResidual);
    }

    @Test
    void staticDiagnosticsAreCanonicalBeforeTheBoundedSnapshotCollectorTruncates() {
        AnimationV2InstancePlan forwardPlan = noOpDiagnosticPlan(false);
        AnimationV2InstancePlan reversePlan = noOpDiagnosticPlan(true);

        assertEquals(forwardPlan.staticDiagnostics(), reversePlan.staticDiagnostics());
        assertEquals(AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE * AnimationV2Limits.MAX_LAYERS_PER_CONTROLLER * 2,
                forwardPlan.staticDiagnostics().size());
        assertEquals(AnimationV2DiagnosticCode.EMPTY_MASK, forwardPlan.staticDiagnostics().getFirst().code());
        assertEquals(AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER, forwardPlan.staticDiagnostics().get(1).code());

        AnimationV2EvaluationSnapshot forward = new AnimationV2InstanceRuntime(forwardPlan).advance(0.0D);
        AnimationV2EvaluationSnapshot reverse = new AnimationV2InstanceRuntime(reversePlan).advance(0.0D);
        assertEquals(forward.diagnostics(), reverse.diagnostics());
        assertEquals(AnimationV2Limits.MAX_DIAGNOSTICS_PER_EVALUATION, forward.diagnostics().size());
        assertEquals(AnimationV2DiagnosticCode.EMPTY_MASK, forward.diagnostics().getFirst().code());
        assertEquals(AnimationV2DiagnosticCode.ZERO_WEIGHT_LAYER, forward.diagnostics().get(1).code());
        assertEquals(AnimationV2DiagnosticCode.DIAGNOSTICS_TRUNCATED,
                forward.diagnostics().getLast().code());
    }

    @Test
    void allCoreSemanticIdentifiersUseTheFixedUtf16Bound() {
        BlendResourceId identifier256 = identifierOfLength(256);
        BlendResourceId identifier257 = identifierOfLength(257);
        BlendAnimationKey key256 = keyOfLength(256);
        BlendAnimationKey key257 = keyOfLength(257);
        String surrogate256 = "\uD83D\uDE00".repeat(128);
        String surrogate258 = "\uD83D\uDE00".repeat(129);
        assertEquals(256, surrogate256.length());
        assertEquals(258, surrogate258.length());
        assertDoesNotThrow(() -> AnimationV2Limits.requireCanonicalIdLength(surrogate256, "surrogate id"));
        assertBoundViolation(() -> AnimationV2Limits.requireCanonicalIdLength(surrogate258, "surrogate id"));

        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("id-bound", schema, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2Clip clip = clip(0.0F);
        AnimationV2ControllerState state256 = new AnimationV2ControllerState(key256, AnimationV2PlaybackMode.ONCE,
                1.0D, 0.0D, key256, Map.of(layer.id(), clip));
        assertEquals(key256, state256.key());
        assertDoesNotThrow(() -> new AnimationV2ControllerState(key("short"), AnimationV2PlaybackMode.ONCE,
                1.0D, 0.0D, key256, Map.of(layer.id(), clip)));
        assertBoundViolation(() -> new AnimationV2ControllerState(key257, AnimationV2PlaybackMode.LOOP,
                1.0D, 0.0D, null, Map.of(layer.id(), clip)));
        assertBoundViolation(() -> new AnimationV2ControllerState(key("short"), AnimationV2PlaybackMode.ONCE,
                1.0D, 0.0D, key257, Map.of(layer.id(), clip)));

        AnimationV2ControllerState shortState = new AnimationV2ControllerState(key("short"), AnimationV2PlaybackMode.LOOP,
                1.0D, 0.0D, null, Map.of(layer.id(), clip));
        assertDoesNotThrow(() -> new AnimationV2ControllerDefinition(id("id-bound-controller"), 0,
                List.of(layer), key256, Map.of(key256, state256)));
        assertBoundViolation(() -> new AnimationV2ControllerDefinition(id("id-bound-controller"), 0,
                List.of(layer), key257, Map.of(shortState.key(), shortState)));
        assertBoundViolation(() -> new AnimationV2ControllerDefinition(id("id-bound-controller"), 0,
                List.of(layer), shortState.key(), Map.of(key257, shortState)));

        assertDoesNotThrow(() -> new AnimationV2Command(identifier256, key256, 0L, 0.0D, 1.0D));
        assertBoundViolation(() -> new AnimationV2Command(identifier257, key("short"), 0L, 0.0D, 1.0D));
        assertBoundViolation(() -> new AnimationV2Command(id("short"), key257, 0L, 0.0D, 1.0D));
        assertDoesNotThrow(() -> new AnimationV2SequenceRejection(identifier256, 0L));
        assertBoundViolation(() -> new AnimationV2SequenceRejection(identifier257, 0L));

        AnimationV2ControllerPlayhead playhead256 = new AnimationV2ControllerPlayhead(identifier256, key256,
                0.0D, key256, 0.0D, -1L);
        assertEquals(identifier256, playhead256.controllerId());
        assertBoundViolation(() -> new AnimationV2ControllerPlayhead(identifier257, key("short"),
                0.0D, null, 0.0D, -1L));
        assertBoundViolation(() -> new AnimationV2ControllerPlayhead(id("short"), key257,
                0.0D, null, 0.0D, -1L));
        assertBoundViolation(() -> new AnimationV2ControllerPlayhead(id("short"), key("short"),
                0.0D, key257, 0.0D, -1L));
        assertDoesNotThrow(() -> new AnimationV2Diagnostic(AnimationV2DiagnosticCode.EMPTY_MASK,
                identifier256, identifier256, -1, "detail"));
        assertBoundViolation(() -> new AnimationV2Diagnostic(AnimationV2DiagnosticCode.EMPTY_MASK,
                identifier257, null, -1, "detail"));
        assertBoundViolation(() -> new AnimationV2Diagnostic(AnimationV2DiagnosticCode.EMPTY_MASK,
                null, identifier257, -1, "detail"));
        assertDoesNotThrow(() -> new AnimationV2EvaluationSnapshot(0L,
                new AnimationV2Pose(List.of(Transform.IDENTITY)), Map.of(identifier256, playhead256), List.of()));
        AnimationV2ControllerPlayhead shortPlayhead = new AnimationV2ControllerPlayhead(id("short"), key("short"),
                0.0D, null, 0.0D, -1L);
        assertBoundViolation(() -> new AnimationV2EvaluationSnapshot(0L,
                new AnimationV2Pose(List.of(Transform.IDENTITY)), Map.of(identifier257, shortPlayhead), List.of()));
    }

    @Test
    void boundedIngressReportsOverflowDrainDeferralAndRecovers() {
        AnimationV2InstanceRuntime runtime = commandRuntime();
        for (int sequence = 0; sequence < AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE; sequence++) {
            assertEquals(AnimationV2IngressOutcome.QUEUED,
                    runtime.enqueue(command(WALK, sequence, 0.0D, 1.0D)));
        }
        assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW,
                runtime.enqueue(command(WALK, AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE, 0.0D, 1.0D)));

        AnimationV2EvaluationSnapshot first = runtime.advance(0.0D);
        assertHas(first, AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW);
        assertHas(first, AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED);
        AnimationV2EvaluationSnapshot second = runtime.advance(0.0D);
        assertFalse(second.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code()
                == AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED));
        assertEquals(WALK, second.playheads().get(id("controller")).state());
    }

    @Test
    void effectiveRateBelowTheLowerBoundIsRejectedBeforeItCanApply() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("rate", schema, AnimationV2LayerMode.OVERRIDE, false);
        AnimationV2InstanceRuntime runtime = runtime(schema, layer, IDLE, Map.of(
                IDLE, state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer, clip(0.0F)),
                WALK, state(WALK, AnimationV2PlaybackMode.LOOP, AnimationV2Limits.MIN_PLAYBACK_SPEED, 0.0D,
                        null, layer, clip(10.0F))));

        AnimationV2EvaluationSnapshot snapshot = runtime.advanceAtFrame(0.0D, List.of(command(WALK, 1L, 0.0D,
                AnimationV2Limits.MIN_PLAYBACK_SPEED)));
        assertEquals(IDLE, snapshot.playheads().get(id("controller")).state());
        assertHas(snapshot, AnimationV2DiagnosticCode.COMMAND_RATE_REJECTED);
    }

    @Test
    void exclusiveLayerConflictsWithAnyOtherNonZeroOverrideOrAdditiveContribution() {
        AnimationV2EvaluationSnapshot override = exclusiveRuntime(AnimationV2LayerMode.OVERRIDE).advance(0.0D);
        AnimationV2EvaluationSnapshot additive = exclusiveRuntime(AnimationV2LayerMode.ADDITIVE).advance(0.0D);
        AnimationV2EvaluationSnapshot solitary = solitaryExclusiveRuntime().advance(0.0D);

        assertHas(override, AnimationV2DiagnosticCode.EXCLUSIVE_WRITE_CONFLICT);
        assertHas(additive, AnimationV2DiagnosticCode.EXCLUSIVE_WRITE_CONFLICT);
        assertFalse(solitary.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code()
                == AnimationV2DiagnosticCode.EXCLUSIVE_WRITE_CONFLICT));
    }

    @Test
    void steadyStateEvaluationIsRepeatableWithoutLayerPoseConstruction() {
        AnimationV2InstanceRuntime runtime = commandRuntime();
        runtime.advanceAtFrame(0.0D, List.of(command(WALK, 1L, 0.0D, 1.0D)));
        float expected = runtime.advance(0.125D).pose().transform(0).translation().x();
        for (int frame = 0; frame < 1_024; frame++) {
            AnimationV2EvaluationSnapshot snapshot = runtime.advance(0.125D);
            assertTrue(Float.isFinite(snapshot.pose().transform(0).translation().x()));
        }
        AnimationV2InstanceRuntime replay = commandRuntime();
        replay.advanceAtFrame(0.0D, List.of(command(WALK, 1L, 0.0D, 1.0D)));
        assertEquals(expected, replay.advance(0.125D).pose().transform(0).translation().x(), 0.00001F);
    }

    private static AnimationV2InstanceRuntime commandRuntime() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition layer = layer("command", schema, AnimationV2LayerMode.OVERRIDE, false);
        return runtime(schema, layer, IDLE, Map.of(
                IDLE, state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer, clip(0.0F)),
                WALK, state(WALK, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer, clip(10.0F)),
                RUN, state(RUN, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, layer, clip(20.0F))));
    }

    private static AnimationV2InstanceRuntime exclusiveRuntime(AnimationV2LayerMode otherMode) {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition exclusive = layer("exclusive", schema, AnimationV2LayerMode.OVERRIDE, true);
        AnimationV2LayerDefinition other = layer("other", schema, otherMode, false);
        AnimationV2ControllerState state = new AnimationV2ControllerState(IDLE, AnimationV2PlaybackMode.LOOP,
                1.0D, 0.0D, null, Map.of(exclusive.id(), clip(2.0F), other.id(), clip(4.0F)));
        AnimationV2ControllerDefinition controller = new AnimationV2ControllerDefinition(id("controller"), 0,
                List.of(exclusive, other), IDLE, Map.of(IDLE, state));
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(controller)));
    }

    private static AnimationV2InstanceRuntime solitaryExclusiveRuntime() {
        BoneSchema schema = schema();
        AnimationV2LayerDefinition exclusive = layer("exclusive", schema, AnimationV2LayerMode.OVERRIDE, true);
        return runtime(schema, exclusive, IDLE, Map.of(
                IDLE, state(IDLE, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, null, exclusive, clip(2.0F))));
    }

    private static AnimationV2InstanceRuntime runtime(
            BoneSchema schema,
            AnimationV2LayerDefinition layer,
            BlendAnimationKey initial,
            Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        AnimationV2ControllerDefinition controller = new AnimationV2ControllerDefinition(id("controller"), 0,
                List.of(layer), initial, states);
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(controller)));
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double speed,
            double transitionSeconds,
            BlendAnimationKey next,
            AnimationV2LayerDefinition layer,
            AnimationV2Clip clip) {
        return new AnimationV2ControllerState(key, mode, speed, transitionSeconds, next, Map.of(layer.id(), clip));
    }

    private static AnimationV2LayerDefinition layer(
            String path, BoneSchema schema, AnimationV2LayerMode mode, boolean exclusive) {
        return new AnimationV2LayerDefinition(id(path), 0, mode, 1.0F, BoneMask.all(schema), exclusive);
    }

    private static AnimationV2Clip clip(float... values) {
        List<AnimationV2Keyframe> frames = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            frames.add(new AnimationV2Keyframe(index, new AnimationV2Pose(List.of(transform(values[index], Quaternion.IDENTITY)))));
        }
        return new AnimationV2Clip(frames);
    }

    private static AnimationV2Clip clip(Transform transform) {
        return new AnimationV2Clip(List.of(new AnimationV2Keyframe(0.0D, new AnimationV2Pose(List.of(transform)))));
    }

    private static AnimationV2Command command(BlendAnimationKey state, long sequence, double time, double speed) {
        return new AnimationV2Command(id("controller"), state, sequence, time, speed);
    }

    private static BoneSchema schema() {
        return new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
    }

    private static Transform transform(float x, Quaternion rotation) {
        return new Transform(new Vec3(x, 0.0F, 0.0F), rotation, Vec3.ONE);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.of("x2review", path);
    }

    private static BlendAnimationKey key(String path) {
        return BlendAnimationKey.of("x2review", path);
    }

    private static BlendResourceId identifierOfLength(int length) {
        return BlendResourceId.of("x", "a".repeat(length - 2));
    }

    private static BlendAnimationKey keyOfLength(int length) {
        return BlendAnimationKey.fromResourceId(identifierOfLength(length));
    }

    private static AnimationV2InstanceRuntime quaternionRuntime(
            Transform rest, List<Transform> samples, boolean reverseInput, float layerWeight) {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(rest));
        List<AnimationV2ControllerDefinition> controllers = new ArrayList<>();
        for (int index = 0; index < samples.size(); index++) {
            String suffix = padded(index);
            AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(id("quaternion-layer-" + suffix), 0,
                    AnimationV2LayerMode.OVERRIDE, layerWeight, BoneMask.all(schema), false);
            BlendAnimationKey stateKey = key("quaternion-state-" + suffix);
            AnimationV2ControllerState state = new AnimationV2ControllerState(stateKey, AnimationV2PlaybackMode.LOOP,
                    1.0D, 0.0D, null, Map.of(layer.id(), clip(samples.get(index))));
            controllers.add(new AnimationV2ControllerDefinition(id("quaternion-controller-" + suffix), 0,
                    List.of(layer), stateKey, Map.of(stateKey, state)));
        }
        if (reverseInput) {
            Collections.reverse(controllers);
        }
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, controllers));
    }

    private static AnimationV2InstancePlan noOpDiagnosticPlan(boolean reverseInput) {
        BoneSchema schema = schema();
        List<Integer> controllerIndexes = indexes(AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE, reverseInput);
        List<AnimationV2ControllerDefinition> controllers = new ArrayList<>();
        for (int controllerIndex : controllerIndexes) {
            List<Integer> layerIndexes = indexes(AnimationV2Limits.MAX_LAYERS_PER_CONTROLLER, reverseInput);
            List<AnimationV2LayerDefinition> layers = new ArrayList<>();
            Map<BlendResourceId, AnimationV2Clip> clips = new LinkedHashMap<>();
            for (int layerIndex : layerIndexes) {
                AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                        id("diagnostic-layer-" + padded(controllerIndex) + '-' + padded(layerIndex)),
                        layerIndex & 1, AnimationV2LayerMode.OVERRIDE, 0.0F, BoneMask.empty(schema), false);
                layers.add(layer);
                clips.put(layer.id(), clip(0.0F));
            }
            BlendAnimationKey stateKey = key("diagnostic-state-" + padded(controllerIndex));
            AnimationV2ControllerState state = new AnimationV2ControllerState(stateKey, AnimationV2PlaybackMode.LOOP,
                    1.0D, 0.0D, null, clips);
            controllers.add(new AnimationV2ControllerDefinition(id("diagnostic-controller-" + padded(controllerIndex)), 0,
                    layers, stateKey, Map.of(stateKey, state)));
        }
        return new AnimationV2InstancePlan(schema, controllers);
    }

    private static List<Integer> indexes(int limit, boolean reverse) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            indexes.add(index);
        }
        if (reverse) {
            Collections.reverse(indexes);
        }
        return indexes;
    }

    private static String padded(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static Transform rotatedZ(double degrees) {
        double halfRadians = Math.toRadians(degrees) * 0.5D;
        return transform(0.0F, new Quaternion(0.0F, 0.0F,
                (float) Math.sin(halfRadians), (float) Math.cos(halfRadians)));
    }

    private static void assertHas(AnimationV2EvaluationSnapshot snapshot, AnimationV2DiagnosticCode code) {
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code),
                () -> "missing " + code + " in " + snapshot.diagnostics());
    }

    private static void assertQuaternion(Transform transform, float x, float y, float z, float w) {
        assertEquals(x, transform.rotation().x(), 0.00001F);
        assertEquals(y, transform.rotation().y(), 0.00001F);
        assertEquals(z, transform.rotation().z(), 0.00001F);
        assertEquals(w, transform.rotation().w(), 0.00001F);
    }

    private static void assertRotatesPositiveXToNegativeX(Transform transform) {
        Vec3 rotated = transform.rotation().rotate(new Vec3(1.0F, 0.0F, 0.0F));
        assertEquals(-1.0F, rotated.x(), 0.0001F);
        assertEquals(0.0F, rotated.y(), 0.0001F);
        assertEquals(0.0F, rotated.z(), 0.0001F);
    }

    private static void assertSameRotation(Transform left, Transform right) {
        assertSameRotation(left.rotation(), right.rotation(), new Vec3(1.0F, 0.0F, 0.0F));
        assertSameRotation(left.rotation(), right.rotation(), new Vec3(0.0F, 1.0F, 0.0F));
        assertSameRotation(left.rotation(), right.rotation(), new Vec3(0.0F, 0.0F, 1.0F));
    }

    private static void assertSameRotation(Quaternion left, Quaternion right, Vec3 basis) {
        Vec3 leftResult = left.rotate(basis);
        Vec3 rightResult = right.rotate(basis);
        assertEquals(leftResult.x(), rightResult.x(), 0.00001F);
        assertEquals(leftResult.y(), rightResult.y(), 0.00001F);
        assertEquals(leftResult.z(), rightResult.z(), 0.00001F);
    }

    private static void assertFiniteRotation(Transform transform) {
        assertTrue(Float.isFinite(transform.rotation().x()));
        assertTrue(Float.isFinite(transform.rotation().y()));
        assertTrue(Float.isFinite(transform.rotation().z()));
        assertTrue(Float.isFinite(transform.rotation().w()));
    }

    private static void assertBoundViolation(Executable executable) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, executable);
        assertTrue(exception.getMessage().contains("256 UTF-16"), exception::getMessage);
    }
}
