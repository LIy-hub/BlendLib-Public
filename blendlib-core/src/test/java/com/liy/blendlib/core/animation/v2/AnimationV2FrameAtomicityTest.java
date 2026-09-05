package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Permanent ordinary-input regression coverage for the X2 owner frame commit boundary. */
class AnimationV2FrameAtomicityTest {
    private static final BlendResourceId CONTROLLER = BlendResourceId.of("x2frame", "controller");
    private static final BlendResourceId SECOND_CONTROLLER = BlendResourceId.of("x2frame", "second-controller");
    private static final BlendResourceId LAYER = BlendResourceId.of("x2frame", "layer");
    private static final BlendResourceId SECOND_LAYER = BlendResourceId.of("x2frame", "second-layer");
    private static final BlendResourceId THIRD_LAYER = BlendResourceId.of("x2frame", "third-layer");
    private static final BlendAnimationKey A = BlendAnimationKey.of("x2frame", "a");
    private static final BlendAnimationKey B = BlendAnimationKey.of("x2frame", "b");
    private static final BlendAnimationKey C = BlendAnimationKey.of("x2frame", "c");
    private static final BlendAnimationKey D = BlendAnimationKey.of("x2frame", "d");

    @Test
    void revisionUpperBoundRejectsBeforeEveryDirectQueuedRejectionAndControllerMutation() throws Exception {
        AnimationV2InstanceRuntime direct = revisionRuntime();
        direct.advance(0.0D);
        setRevisionAndLatest(direct, Long.MAX_VALUE);
        AnimationV2Command directCommand = new AnimationV2Command(CONTROLLER, B, 1L, 0.5D, 1.0D);
        assertArithmeticFailureLeavesFrameUntouched(direct,
                () -> direct.advanceAtFrame(0.125D, List.of(directCommand)));
        assertArithmeticFailureLeavesFrameUntouched(direct,
                () -> direct.advanceAtFrame(0.125D, List.of(directCommand)));
        assertTrue(incoming(direct).isEmpty(), "call-scoped direct commands must not enter ingress on a failed frame");
        assertTrue(backlog(direct, "frameCommandBacklog").isEmpty(), "failed direct input must not be deferred");

        AnimationV2InstanceRuntime queued = revisionRuntime();
        queued.advance(0.0D);
        setRevisionAndLatest(queued, Long.MAX_VALUE);
        AnimationV2Command queuedCommand = new AnimationV2Command(CONTROLLER, B, 0L, 0.25D, 1.0D);
        assertEquals(AnimationV2IngressOutcome.QUEUED, queued.enqueue(queuedCommand));
        assertArithmeticFailureLeavesFrameUntouched(queued, () -> queued.advance(0.0D));
        assertArithmeticFailureLeavesFrameUntouched(queued, () -> queued.advance(0.0D));
        assertEquals(List.of(queuedCommand), incoming(queued), "a queued prefix must survive an identical retry");

        AnimationV2InstanceRuntime noCommand = revisionRuntime();
        noCommand.advance(0.0D);
        setRevisionAndLatest(noCommand, Long.MAX_VALUE);
        assertArithmeticFailureLeavesFrameUntouched(noCommand, () -> noCommand.advance(0.125D));

        AnimationV2InstanceRuntime rejection = revisionRuntime();
        rejection.advance(0.0D);
        setRevisionAndLatest(rejection, Long.MAX_VALUE);
        assertArithmeticFailureLeavesFrameUntouched(rejection,
                () -> rejection.advanceAtFrame(0.0D, List.of(),
                        List.of(new AnimationV2SequenceRejection(CONTROLLER, 0L))));

        AnimationV2InstanceRuntime multipleControllers = twoControllerRuntime(
                clip(Transform.IDENTITY, Transform.IDENTITY), clip(Transform.IDENTITY, Transform.IDENTITY));
        multipleControllers.advance(0.0D);
        setRevisionAndLatest(multipleControllers, Long.MAX_VALUE);
        assertArithmeticFailureLeavesFrameUntouched(multipleControllers, () -> multipleControllers.advance(0.5D));
    }

    @Test
    void fixedStagingScratchReferencesClearAfterSuccessfulAndFailedFrames() throws Exception {
        AnimationV2InstanceRuntime runtime = revisionRuntime();
        runtime.advanceAtFrame(0.0D, List.of(new AnimationV2Command(CONTROLLER, B, 1L, 0.0D, 1.0D)));
        assertScratchReferencesCleared(runtime);

        setRevisionAndLatest(runtime, Long.MAX_VALUE);
        assertArithmeticFailureLeavesFrameUntouched(runtime, () -> runtime.advance(0.0D));
    }

    @Test
    void interruptedTransitionPublishesFrozenPresentationAndProspectivePoseFailureRemainsAtomic() throws Exception {
        AnimationV2InstanceRuntime runtime = frozenTransitionFailureRuntime();
        AnimationV2EvaluationSnapshot started = runtime.advanceAtFrame(0.0D,
                List.of(new AnimationV2Command(CONTROLLER, B, 0L, 0.0D, 1.0D)));
        assertEquals(B, started.playheads().get(CONTROLLER).state());
        assertEquals(A, started.playheads().get(CONTROLLER).previousState());

        AnimationV2EvaluationSnapshot middle = runtime.advance(0.5D);
        assertEquals(B, middle.playheads().get(CONTROLLER).state());
        assertEquals(A, middle.playheads().get(CONTROLLER).previousState());
        assertEquals(0.5D, middle.playheads().get(CONTROLLER).transitionProgress(), 0.0D);

        AnimationV2Command toC = new AnimationV2Command(CONTROLLER, C, 1L, 0.0D, 1.0D);
        AnimationV2EvaluationSnapshot published = runtime.advanceAtFrame(0.0D, List.of(toC));
        Object firstController = liveController(runtime, CONTROLLER);
        AnimationV2Pose[] frozenPreviousPoses = (AnimationV2Pose[]) field(firstController, "frozenPreviousPoses");
        assertTrue(frozenPreviousPoses != null && frozenPreviousPoses.length == 1,
                "interrupting an active transition must freeze a real source pose");
        assertEquals(middle.pose().transform(0), frozenPreviousPoses[0].transform(0),
                "the frozen layer pose must be the in-flight A-to-B presentation");
        assertEquals(frozenPreviousPoses[0].transform(0), published.pose().transform(0),
                "C begins from the frozen presentation instead of resampling A or B");
        assertEquals(C, ((AnimationV2ControllerState) field(firstController, "current")).key());
        assertEquals(null, field(firstController, "previous"));
        assertEquals(null, field(firstController, "previousClips"));
        assertEquals(B, field(firstController, "transitionSourceState"));
        assertEquals(0.0D, (double) field(firstController, "currentTime"), 0.0D);
        assertEquals(0.5D, (double) field(firstController, "previousTime"), 0.0D);
        assertEquals(1L, (long) field(firstController, "sequenceWatermark"));
        assertEquals(toC, field(firstController, "acceptedCommand"));

        AnimationV2ControllerPlayhead playhead = published.playheads().get(CONTROLLER);
        assertEquals(C, playhead.state());
        assertEquals(B, playhead.previousState());
        assertEquals(0.0D, playhead.timeSeconds(), 0.0D);
        assertEquals(0.0D, playhead.transitionProgress(), 0.0D);
        assertEquals(1L, playhead.acceptedSequence());
        AnimationV2ObserverTraversal.ControllerTraversal traversal = published.observerTraversal().controller(CONTROLLER);
        assertEquals(C, traversal.terminalState());
        assertEquals(0.0D, traversal.terminalTimeSeconds(), 0.0D);
        assertTrue(traversal.discontinuity());
        assertTrue(traversal.segments().isEmpty());
        assertTrue(traversal.continuity().publications().isEmpty());
        assertSame(field(firstController, "observerContinuityInitial"), traversal.continuity().initialAnchor());
        assertSame(field(firstController, "observerContinuityTerminal"), traversal.continuity().terminalAnchor());
        assertEquals((long) field(firstController, "observerLoopEpoch"),
                traversal.continuity().terminalAnchor().loopEpoch());
        assertEquals((long) field(firstController, "observerOccurrence"),
                traversal.continuity().terminalAnchor().occurrence());
        assertEquals(published.revision(), (long) field(runtime, "revision"));
        assertSame(published, runtime.latestSnapshot());
        assertScratchReferencesCleared(runtime);

        AnimationV2Command failing = new AnimationV2Command(SECOND_CONTROLLER, D, 0L, 0.0D, 1.0D);
        assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(failing));
        OwnerFingerprint beforeFailure = fingerprint(runtime);
        Object continuityTerminal = field(firstController, "observerContinuityTerminal");
        assertIllegalArgumentFailureLeavesFrameUntouched(runtime, () -> runtime.advance(0.0D));
        assertEquals(beforeFailure, fingerprint(runtime));
        assertEquals(List.of(failing), incoming(runtime), "the captured failing prefix must remain queued for retry");
        assertSame(frozenPreviousPoses, field(firstController, "frozenPreviousPoses"));
        assertSame(continuityTerminal, field(firstController, "observerContinuityTerminal"));
        assertSame(published, runtime.latestSnapshot());
        assertScratchReferencesCleared(runtime);
    }

    @Test
    void frameSchedulerBytecodeUsesFixedConsumerAndIndexedRemovalWithoutPredicate() throws Exception {
        InputStream resource = AnimationV2InstanceRuntime.class.getResourceAsStream("AnimationV2InstanceRuntime.class");
        assertTrue(resource != null, "runtime class bytes must be available to this regression test");
        String constants;
        try (resource) {
            constants = new String(resource.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        assertTrue(constants.contains("IngressCapture"));
        assertTrue(constants.contains("removeMatchingFrameCommands"));
        assertTrue(constants.contains("java/util/function/Consumer"));
        assertFalse(constants.contains("removeIf"));
        assertFalse(constants.contains("java/util/function/Predicate"));
    }

    @Test
    void representableExtremeClipTranslationAndScaleMidpointsPublishExactly() {
        AnimationV2InstanceRuntime translation = singleControllerRuntime(
                Transform.IDENTITY,
                AnimationV2LayerMode.OVERRIDE,
                A,
                Map.of(A, state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                        clip(translated(-Float.MAX_VALUE), translated(Float.MAX_VALUE)))));
        AnimationV2EvaluationSnapshot translationSnapshot = translation.advance(0.5D);
        assertFloatBits(0.0F, translationSnapshot.pose().transform(0).translation().x());
        assertSame(translationSnapshot, translation.latestSnapshot());

        AnimationV2InstanceRuntime scale = singleControllerRuntime(
                scaled(Float.MIN_VALUE),
                AnimationV2LayerMode.OVERRIDE,
                A,
                Map.of(A, state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                        clip(scaled(Float.MIN_VALUE), scaled(Float.MAX_VALUE)))));
        AnimationV2EvaluationSnapshot scaleSnapshot = scale.advance(0.5D);
        assertTrue(Float.isFinite(scaleSnapshot.pose().transform(0).scale().x()));
        assertTrue(scaleSnapshot.pose().transform(0).scale().x() > 0.0F);
        assertSame(scaleSnapshot, scale.latestSnapshot());
    }

    @Test
    void laterControllerAndActiveTransitionUseRepresentableExtremePresentationWithoutFalseOverflow() {
        AnimationV2InstanceRuntime laterController = twoControllerRuntime(
                clip(Transform.IDENTITY, Transform.IDENTITY),
                clip(translated(-Float.MAX_VALUE), translated(Float.MAX_VALUE)));
        AnimationV2EvaluationSnapshot laterSnapshot = laterController.advance(0.5D);
        assertFloatBits(0.0F, laterSnapshot.pose().transform(0).translation().x());
        assertEquals(0.5D, laterSnapshot.playheads().get(CONTROLLER).timeSeconds(), 0.0D);
        assertEquals(0.5D, laterSnapshot.playheads().get(SECOND_CONTROLLER).timeSeconds(), 0.0D);

        AnimationV2InstanceRuntime transition = transitionRuntime(
                translated(-Float.MAX_VALUE), translated(Float.MAX_VALUE), 1.0D);
        AnimationV2EvaluationSnapshot started = transition.advanceAtFrame(0.0D,
                List.of(new AnimationV2Command(CONTROLLER, B, 0L, 0.0D, 1.0D)));
        assertEquals(B, started.playheads().get(CONTROLLER).state());
        assertEquals(A, started.playheads().get(CONTROLLER).previousState());
        assertFloatBits(-Float.MAX_VALUE, started.pose().transform(0).translation().x());

        AnimationV2EvaluationSnapshot middle = transition.advance(0.5D);
        assertFloatBits(0.0F, middle.pose().transform(0).translation().x());
        assertEquals(0.5D, middle.playheads().get(CONTROLLER).transitionProgress(), 0.0D);
    }

    @Test
    void additiveMinimumScaleDoesNotUnderflowToAFalseZero() {
        BoneSchema schema = schema(scaled(Float.MAX_VALUE));
        AnimationV2LayerDefinition additive = layer(schema, LAYER, AnimationV2LayerMode.ADDITIVE);
        AnimationV2ControllerState state = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(scaled(Float.MIN_VALUE), scaled(Float.MIN_VALUE)));
        AnimationV2InstanceRuntime runtime = runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(additive), A, Map.of(A, state))));

        AnimationV2EvaluationSnapshot snapshot = runtime.advanceAtFrame(0.0D,
                List.of(new AnimationV2Command(CONTROLLER, A, 0L, 0.0D, 1.0D)));
        assertFloatBits(Float.MIN_VALUE, snapshot.pose().transform(0).scale().x());
        assertEquals(0L, snapshot.playheads().get(CONTROLLER).acceptedSequence());
    }

    @Test
    void additiveEffectiveDyadicWeightsPreserveRepresentableTranslationAndScaleAcrossTwoBoundaries() {
        assertAll(
                () -> assertAdditiveEffectiveWeight(
                        Math.scalb(1.0F, -138), Math.scalb(1.0F, -138),
                        "two equal 2^-138 factors"),
                () -> assertAdditiveEffectiveWeight(
                        Float.MIN_VALUE, 0.5F,
                        "Float.MIN_VALUE times one half"));
    }

    @Test
    void additiveEndpointsKeepSignedTranslationSubnormalsAndRetainTheOverrideBase() {
        assertAll(
                () -> assertAdditiveTranslationEndpoint(Float.MAX_VALUE, Float.MIN_VALUE),
                () -> assertAdditiveTranslationEndpoint(Float.MAX_VALUE, -Float.MIN_VALUE),
                () -> assertAdditiveTranslationEndpoint(-Float.MAX_VALUE, Float.MIN_VALUE),
                () -> assertAdditiveTranslationEndpoint(-Float.MAX_VALUE, -Float.MIN_VALUE));

        AnimationV2EvaluationSnapshot zeroWeight = additiveWeightedRuntime(
                translatedUniform(Float.MAX_VALUE), translatedUniform(Float.MIN_VALUE), 1.0F, 0.0F).advance(0.0D);
        assertAll(
                () -> assertFloatBits(Float.MAX_VALUE, zeroWeight.pose().transform(0).translation().x()),
                () -> assertFloatBits(Float.MAX_VALUE, zeroWeight.pose().transform(0).translation().y()),
                () -> assertFloatBits(Float.MAX_VALUE, zeroWeight.pose().transform(0).translation().z()));

        AnimationV2EvaluationSnapshot scaleOne = additiveWeightedRuntime(
                scaled(Float.MAX_VALUE), scaled(Float.MIN_VALUE), 1.0F, 1.0F).advance(0.0D);
        AnimationV2EvaluationSnapshot scaleZero = additiveWeightedRuntime(
                scaled(Float.MAX_VALUE), scaled(Float.MIN_VALUE), 1.0F, 0.0F).advance(0.0D);
        assertAll(
                () -> assertFloatBits(Float.MIN_VALUE, scaleOne.pose().transform(0).scale().x()),
                () -> assertFloatBits(Float.MIN_VALUE, scaleOne.pose().transform(0).scale().y()),
                () -> assertFloatBits(Float.MIN_VALUE, scaleOne.pose().transform(0).scale().z()),
                () -> assertFloatBits(Float.MAX_VALUE, scaleZero.pose().transform(0).scale().x()),
                () -> assertFloatBits(Float.MAX_VALUE, scaleZero.pose().transform(0).scale().y()),
                () -> assertFloatBits(Float.MAX_VALUE, scaleZero.pose().transform(0).scale().z()));

        AnimationV2EvaluationSnapshot overrideBase = additiveWithOverrideRuntime(
                translatedUniform(1.0F), translatedUniform(2.0F), translatedUniform(1.0F)).advance(0.0D);
        assertAll(
                () -> assertFloatBits(2.0F, overrideBase.pose().transform(0).translation().x()),
                () -> assertFloatBits(2.0F, overrideBase.pose().transform(0).translation().y()),
                () -> assertFloatBits(2.0F, overrideBase.pose().transform(0).translation().z()));
    }

    @Test
    void additiveTranslationMagnitudeOrderedSumKeepsPriorLayersAtNearZeroAndNearOneWeights() {
        float nearZero = Math.scalb(1.0F, -23);
        float nearOne = Math.nextDown(1.0F);
        for (float restValue : List.of(Float.MAX_VALUE, -Float.MAX_VALUE)) {
            for (float endpointValue : List.of(Float.MIN_VALUE, -Float.MIN_VALUE)) {
                assertTwoLayerAdditiveTranslation(restValue, -restValue, nearZero, endpointValue,
                        "near-zero first layer rest=" + restValue + " endpoint=" + endpointValue);
                assertTwoLayerAdditiveTranslation(restValue, 0.0F, nearOne, endpointValue,
                        "near-one first layer rest=" + restValue + " endpoint=" + endpointValue);
            }
        }

        AnimationV2EvaluationSnapshot layeredScale = twoAdditiveWeightedRuntime(
                scaled(2.0F), scaled(4.0F), 0.25F, scaled(1.0F), 1.0F).advance(0.0D);
        assertAll(
                () -> assertFloatBits(1.25F, layeredScale.pose().transform(0).scale().x()),
                () -> assertFloatBits(1.25F, layeredScale.pose().transform(0).scale().y()),
                () -> assertFloatBits(1.25F, layeredScale.pose().transform(0).scale().z()));
    }

    @Test
    void additiveQuaternionHamiltonProductsPreserveSubnormalComponentsAcrossPermutationsAndCanonicalSigns() {
        float halfRoot = Float.intBitsToFloat(0x3F3504F3);
        Quaternion sampleZero = new Quaternion(Float.MIN_VALUE, halfRoot, halfRoot, Float.MIN_VALUE);
        Quaternion sampleOne = new Quaternion(halfRoot, Float.MIN_VALUE, halfRoot, Float.MIN_VALUE);
        Quaternion sampleTwo = new Quaternion(halfRoot, halfRoot, Float.MIN_VALUE, Float.MIN_VALUE);
        assertAll(
                () -> assertAdditiveQuaternionBits(
                        new Quaternion(-0.5F, 0.5F, 0.5F, 0.5F), sampleZero, sampleZero),
                () -> assertAdditiveQuaternionBits(
                        new Quaternion(0.5F, -0.5F, 0.5F, 0.5F), sampleOne, sampleOne),
                () -> assertAdditiveQuaternionBits(
                        new Quaternion(0.5F, 0.5F, -0.5F, 0.5F), sampleTwo, sampleTwo),
                () -> assertAdditiveQuaternionBits(
                        new Quaternion(-0.5F, 0.5F, 0.5F, 0.5F),
                        new Quaternion(-Float.MIN_VALUE, -halfRoot, -halfRoot, -Float.MIN_VALUE), sampleZero));
    }

    @Test
    void additiveWeightOneQuaternionGroupIdentityKeepsScratchCanonicalSamplesAndPublishesTheStableTransformProjection() {
        // Formal r11 public counterexample: Scratch owns the exact canonical endpoint. The established public Transform
        // value contract then publishes Quaternion.normalized() of that raw endpoint; the test locks both layers.
        assertAdditiveQuaternionGroupIdentity(
                new int[] {0x3F366F95, 0x3EA13728, 0xBF306C0F, 0x3F333E12},
                new int[] {0xBDB80429, 0x00000001, 0xBE507A0A, 0x3DF2F7BA},
                new int[] {0xBEB66C14, 0x00000004, 0xBF4EABB7, 0x3EF0DCEA});
        // The same scratch canonical endpoint and stable public projection must hold for q/-q.
        assertAdditiveQuaternionGroupIdentity(
                new int[] {0xBF366F95, 0xBEA13728, 0x3F306C0F, 0xBF333E12},
                new int[] {0x3DB80429, 0x80000001, 0x3E507A0A, 0xBDF2F7BA},
                new int[] {0xBEB66C14, 0x00000004, 0xBF4EABB7, 0x3EF0DCEA});
        // Fixed cases 0..3 from the public 0x52_31_31_51_45_4e_44 endpoint sweep. Each moves the legal MIN_VALUE
        // component through a distinct quaternion position, so an intermediate float relative delta cannot hide in
        // one Hamilton permutation. The raw bits below are the scratch canonical samples; public assertions use their
        // stable Transform publication projection.
        assertAdditiveQuaternionGroupIdentity(
                new int[] {0x80000001, 0xBF410AB7, 0xBF7A98AF, 0x3EA5F1B5},
                new int[] {0xBF6F5042, 0x00000001, 0xBEF1154F, 0xBCBE0BF4},
                new int[] {0x3F6493FD, 0x80000001, 0x3EE644B7, 0x3CB58576});
        assertAdditiveQuaternionGroupIdentity(
                new int[] {0x3F1A2B2F, 0x00000001, 0x3EE5A733, 0x3F7367C2},
                new int[] {0x3F499A0C, 0x3F21C344, 0x00000001, 0x3E775591},
                new int[] {0x3F4230C1, 0x3F1BD0E6, 0x00000001, 0x3E6E3DE1});
        assertAdditiveQuaternionGroupIdentity(
                new int[] {0xBDA1DE8E, 0xBC7A0301, 0x80000001, 0x3F1AEA08},
                new int[] {0xBF26CD01, 0x3F7C416C, 0x3EE4E6E8, 0x00000001},
                new int[] {0x3F040F05, 0xBF47B6E8, 0xBEB539A1, 0x80000001});
        assertAdditiveQuaternionGroupIdentity(
                new int[] {0x3F4E013C, 0x3F6EC1F8, 0x3EB5310E, 0x00000001},
                new int[] {0x80000001, 0x3F09448B, 0xBE0B9111, 0x3F47CF18},
                new int[] {0x80000001, 0x3F0F7AFC, 0xBE11E225, 0x3F50DA2A});
    }

    @Test
    void additiveWeightOneQuaternionEndpointRetainsDifferentOverrideAndPriorAdditiveBases() {
        Transform halfTurnX = rotated(new Quaternion(1.0F, 0.0F, 0.0F, 0.0F));
        Transform halfTurnY = rotated(new Quaternion(0.0F, 1.0F, 0.0F, 0.0F));
        Quaternion expected = new Quaternion(0.0F, 0.0F, 1.0F, 0.0F);

        AnimationV2TransformScratch restScratch = scratch(Transform.IDENTITY);
        AnimationV2TransformScratch currentScratch = scratch(halfTurnX);
        currentScratch.applyAdditive(restScratch, scratch(halfTurnY), 1.0D);

        AnimationV2InstanceRuntime overrideRuntime = additiveWithOverrideRuntime(Transform.IDENTITY, halfTurnX, halfTurnY);
        AnimationV2EvaluationSnapshot override = overrideRuntime.advance(0.0D);
        AnimationV2InstanceRuntime priorAdditiveRuntime = twoAdditiveWeightedRuntime(
                Transform.IDENTITY, halfTurnX, 1.0F, halfTurnY, 1.0F);
        AnimationV2EvaluationSnapshot priorAdditive = priorAdditiveRuntime.advance(0.0D);
        Quaternion afterOverride = override.pose().transform(0).rotation();
        Quaternion afterPriorAdditive = priorAdditive.pose().transform(0).rotation();
        Quaternion repeatedOverride = additiveWithOverrideRuntime(Transform.IDENTITY, halfTurnX, halfTurnY)
                .advance(0.0D).pose().transform(0).rotation();
        Quaternion repeatedPriorAdditive = twoAdditiveWeightedRuntime(
                Transform.IDENTITY, halfTurnX, 1.0F, halfTurnY, 1.0F).advance(0.0D).pose().transform(0).rotation();
        assertAll(
                () -> assertScratchQuaternionBits(expected, currentScratch),
                () -> assertQuaternionBits(expected, afterOverride),
                () -> assertQuaternionBits(expected, afterPriorAdditive),
                () -> assertQuaternionBits(afterOverride, repeatedOverride),
                () -> assertQuaternionBits(afterPriorAdditive, repeatedPriorAdditive),
                () -> assertSame(override, overrideRuntime.latestSnapshot()),
                () -> assertSame(priorAdditive, priorAdditiveRuntime.latestSnapshot()),
                () -> assertNotEquals(halfTurnY.rotation(), afterOverride,
                        "a different override current pose must still compose rather than directly replace with sample"),
                () -> assertNotEquals(halfTurnY.rotation(), afterPriorAdditive,
                        "a prior additive current pose must still compose rather than directly replace with sample"));
    }

    @Test
    void samePriorityHalfMinimumTranslationAndNextUpControlPreserveExactSubnormals() {
        AnimationV2EvaluationSnapshot translation = samePriorityOverrideRuntime(
                translatedUniform(Float.MIN_VALUE), translatedUniform(Float.MIN_VALUE)).advance(0.0D);
        assertFloatBits(Float.MIN_VALUE, translation.pose().transform(0).translation().x());
        assertFloatBits(Float.MIN_VALUE, translation.pose().transform(0).translation().y());
        assertFloatBits(Float.MIN_VALUE, translation.pose().transform(0).translation().z());

        float nextMinimum = Math.nextUp(Float.MIN_VALUE);
        AnimationV2EvaluationSnapshot translationControl = samePriorityOverrideRuntime(
                translatedUniform(nextMinimum), translatedUniform(nextMinimum)).advance(0.0D);
        assertFloatBits(nextMinimum, translationControl.pose().transform(0).translation().x());
        assertFloatBits(nextMinimum, translationControl.pose().transform(0).translation().y());
        assertFloatBits(nextMinimum, translationControl.pose().transform(0).translation().z());
    }

    @Test
    void samePriorityHalfMinimumQuaternionPreservesExactSubnormals() {
        Transform minimumRotation = new Transform(Vec3.ZERO,
                new Quaternion(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE, 1.0F), Vec3.ONE);
        AnimationV2EvaluationSnapshot rotation = samePriorityOverrideRuntime(minimumRotation, minimumRotation)
                .advance(0.0D);
        assertFloatBits(Float.MIN_VALUE, rotation.pose().transform(0).rotation().x());
        assertFloatBits(Float.MIN_VALUE, rotation.pose().transform(0).rotation().y());
        assertFloatBits(Float.MIN_VALUE, rotation.pose().transform(0).rotation().z());
    }

    @Test
    void samePriorityHalfMinimumScalePublishesExactSubnormalsAfterQueuedTransitions() throws Exception {
        AnimationV2InstanceRuntime scale = samePriorityScaleTransitionRuntime();
        AnimationV2EvaluationSnapshot before = scale.advance(0.0D);
        AnimationV2Command first = new AnimationV2Command(CONTROLLER, B, 0L, 0.0D, 1.0D);
        AnimationV2Command second = new AnimationV2Command(SECOND_CONTROLLER, B, 0L, 0.0D, 1.0D);
        assertEquals(AnimationV2IngressOutcome.QUEUED, scale.enqueue(first));
        assertEquals(AnimationV2IngressOutcome.QUEUED, scale.enqueue(second));

        AnimationV2EvaluationSnapshot published = scale.advance(0.0D);
        assertFloatBits(Float.MIN_VALUE, published.pose().transform(0).scale().x());
        assertFloatBits(Float.MIN_VALUE, published.pose().transform(0).scale().y());
        assertFloatBits(Float.MIN_VALUE, published.pose().transform(0).scale().z());
        assertEquals(0L, published.playheads().get(CONTROLLER).acceptedSequence());
        assertEquals(0L, published.playheads().get(SECOND_CONTROLLER).acceptedSequence());
        assertTrue(incoming(scale).isEmpty());
        assertSame(published, scale.latestSnapshot());
        assertEquals(before.revision() + 1L, published.revision());
    }

    @Test
    void mathematicallyUnrepresentableAdditiveAndTransitionOutputsFailBeforeOwnerCommit() throws Exception {
        AnimationV2InstanceRuntime additive = impossibleAdditiveRuntime();
        AnimationV2Command command = new AnimationV2Command(CONTROLLER, B, 0L, 0.0D, 1.0D);
        assertIllegalArgumentFailureLeavesFrameUntouched(additive,
                () -> additive.advanceAtFrame(0.0D, List.of(command)));
        assertIllegalArgumentFailureLeavesFrameUntouched(additive,
                () -> additive.advanceAtFrame(0.0D, List.of(command)));

        AnimationV2InstanceRuntime queuedAdditive = impossibleAdditiveRuntime();
        assertEquals(AnimationV2IngressOutcome.QUEUED, queuedAdditive.enqueue(command));
        assertIllegalArgumentFailureLeavesFrameUntouched(queuedAdditive, () -> queuedAdditive.advance(0.0D));
        assertIllegalArgumentFailureLeavesFrameUntouched(queuedAdditive, () -> queuedAdditive.advance(0.0D));
        assertEquals(List.of(command), incoming(queuedAdditive), "a staged pose failure must retain its queued prefix");

        AnimationV2InstanceRuntime transition = impossibleTransitionRuntime();
        transition.advanceAtFrame(0.0D, List.of(new AnimationV2Command(CONTROLLER, B, 0L, 0.0D, 1.0D)));
        assertIllegalArgumentFailureLeavesFrameUntouched(transition, () -> transition.advance(0.5D));
        assertIllegalArgumentFailureLeavesFrameUntouched(transition, () -> transition.advance(0.5D));

        AnimationV2InstanceRuntime laterController = laterControllerFailureRuntime();
        assertIllegalArgumentFailureLeavesFrameUntouched(laterController, () -> laterController.advance(0.0D));
        assertIllegalArgumentFailureLeavesFrameUntouched(laterController, () -> laterController.advance(0.0D));
    }

    @Test
    void maximumUtf16AutomaticNextCyclesPublishBoundedDiagnosticsAndKeepCanonicalTermination() throws Exception {
        BlendAnimationKey ascii = BlendAnimationKey.of("z", "x".repeat(254));
        assertEquals(AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS, ascii.value().length());
        assertBoundedCyclePublishes(ascii);

        BlendAnimationKey surrogate = keyWithDisplayValue("z:"
                + "\uD83D\uDE00".repeat(126) + "aa");
        assertEquals(AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS, surrogate.value().length());
        assertBoundedCyclePublishes(surrogate);
    }

    @Test
    void cycleDiagnosticTruncationDecrementsAtAnActualUtf16SurrogateSplit() throws Exception {
        String value = "z:x" + "\uD83D\uDE00".repeat(126) + "a";
        BlendAnimationKey splitAtBoundary = keyWithDisplayValue(value);
        int unguardedCut = AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS
                - "automatic next chain revisited ".length() - "…".length();
        assertEquals(AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS, splitAtBoundary.value().length());
        assertEquals(224, unguardedCut);
        assertTrue(Character.isHighSurrogate(splitAtBoundary.value().charAt(unguardedCut - 1)));
        assertTrue(Character.isLowSurrogate(splitAtBoundary.value().charAt(unguardedCut)));

        String detail = assertBoundedCyclePublishes(splitAtBoundary);
        assertEquals("automatic next chain revisited "
                        + splitAtBoundary.value().substring(0, unguardedCut - 1) + "…",
                detail, "the runtime must decrement its cut before the high surrogate");
    }

    private static String assertBoundedCyclePublishes(BlendAnimationKey first) {
        AnimationV2InstanceRuntime runtime = cycleRuntime(first);
        AnimationV2EvaluationSnapshot snapshot = runtime.advance(3.0D);
        AnimationV2Diagnostic cycle = snapshot.diagnostics().stream()
                .filter(value -> value.code() == AnimationV2DiagnosticCode.NEXT_CYCLE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("cycle diagnostic was not published"));
        assertTrue(cycle.detail().startsWith("automatic next chain revisited "));
        assertTrue(cycle.detail().length() <= AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS);
        assertTrue(hasOnlyCompleteSurrogatePairs(cycle.detail()), "bounded detail split a UTF-16 surrogate pair");
        assertEquals(B, snapshot.playheads().get(CONTROLLER).state());
        assertEquals(1.0D, snapshot.playheads().get(CONTROLLER).timeSeconds(), 0.0D);
        assertSame(snapshot, runtime.latestSnapshot());

        AnimationV2EvaluationSnapshot repeat = cycleRuntime(first).advance(3.0D);
        AnimationV2Diagnostic repeatCycle = repeat.diagnostics().stream()
                .filter(value -> value.code() == AnimationV2DiagnosticCode.NEXT_CYCLE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("repeat cycle diagnostic was not published"));
        assertEquals(cycle.detail(), repeatCycle.detail(), "canonical cycle detail must be deterministic");
        return cycle.detail();
    }

    private static AnimationV2InstanceRuntime revisionRuntime() {
        return transitionRuntime(Transform.IDENTITY, Transform.IDENTITY, 1.0D);
    }

    private static AnimationV2InstanceRuntime transitionRuntime(
            Transform left, Transform right, double transitionSeconds) {
        BoneSchema schema = schema(Transform.IDENTITY);
        AnimationV2LayerDefinition layer = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2ControllerState a = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER, clip(left, left));
        AnimationV2ControllerState b = state(B, AnimationV2PlaybackMode.HOLD, transitionSeconds, null, LAYER,
                clip(right, right));
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(layer), A, states(a, b))));
    }

    private static AnimationV2InstanceRuntime twoControllerRuntime(
            AnimationV2Clip firstClip, AnimationV2Clip secondClip) {
        BoneSchema schema = schema(Transform.IDENTITY);
        AnimationV2LayerDefinition firstLayer = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition secondLayer = layer(schema, SECOND_LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2ControllerState first = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER, firstClip);
        AnimationV2ControllerState second = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, SECOND_LAYER, secondClip);
        return runtime(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(firstLayer), A, Map.of(A, first)),
                new AnimationV2ControllerDefinition(SECOND_CONTROLLER, 1, List.of(secondLayer), A, Map.of(A, second))));
    }

    private static AnimationV2InstanceRuntime samePriorityOverrideRuntime(Transform firstSample, Transform secondSample) {
        BoneSchema schema = schema(Transform.IDENTITY);
        AnimationV2LayerDefinition firstLayer = weightedLayer(schema, LAYER, AnimationV2LayerMode.OVERRIDE, 0.5F);
        AnimationV2LayerDefinition secondLayer = weightedLayer(schema, SECOND_LAYER, AnimationV2LayerMode.OVERRIDE, 0.5F);
        AnimationV2ControllerState first = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(firstSample, firstSample));
        AnimationV2ControllerState second = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, SECOND_LAYER,
                clip(secondSample, secondSample));
        return runtime(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(firstLayer), A, Map.of(A, first)),
                new AnimationV2ControllerDefinition(SECOND_CONTROLLER, 0, List.of(secondLayer), A, Map.of(A, second))));
    }

    private static AnimationV2InstanceRuntime samePriorityScaleTransitionRuntime() {
        BoneSchema schema = schema(Transform.IDENTITY);
        AnimationV2LayerDefinition firstLayer = weightedLayer(schema, LAYER, AnimationV2LayerMode.OVERRIDE, 0.5F);
        AnimationV2LayerDefinition secondLayer = weightedLayer(schema, SECOND_LAYER, AnimationV2LayerMode.OVERRIDE, 0.5F);
        AnimationV2ControllerState firstA = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(Transform.IDENTITY, Transform.IDENTITY));
        AnimationV2ControllerState firstB = state(B, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(scaled(Float.MIN_VALUE), scaled(Float.MIN_VALUE)));
        AnimationV2ControllerState secondA = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, SECOND_LAYER,
                clip(Transform.IDENTITY, Transform.IDENTITY));
        AnimationV2ControllerState secondB = state(B, AnimationV2PlaybackMode.HOLD, 0.0D, null, SECOND_LAYER,
                clip(scaled(Float.MIN_VALUE), scaled(Float.MIN_VALUE)));
        return runtime(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(firstLayer), A,
                        states(firstA, firstB)),
                new AnimationV2ControllerDefinition(SECOND_CONTROLLER, 0, List.of(secondLayer), A,
                        states(secondA, secondB))));
    }

    private static void assertAdditiveEffectiveWeight(float layerWeight, float maskWeight, String label) {
        double effectiveWeight = (double) layerWeight * (double) maskWeight;
        float expectedTranslation = (float) ((double) Float.MAX_VALUE * effectiveWeight);
        AnimationV2EvaluationSnapshot translation = additiveWeightedRuntime(
                Transform.IDENTITY, translatedUniform(Float.MAX_VALUE), layerWeight, maskWeight).advance(0.0D);
        Transform translationPose = translation.pose().transform(0);

        float expectedScale = (float) ((double) Float.MIN_VALUE
                * ((1.0D - effectiveWeight) + effectiveWeight * ((double) Float.MAX_VALUE / Float.MIN_VALUE)));
        AnimationV2EvaluationSnapshot scale = additiveWeightedRuntime(
                scaled(Float.MIN_VALUE), scaled(Float.MAX_VALUE), layerWeight, maskWeight).advance(0.0D);
        Transform scalePose = scale.pose().transform(0);

        assertAll(label,
                () -> assertFloatBits(expectedTranslation, translationPose.translation().x()),
                () -> assertFloatBits(expectedTranslation, translationPose.translation().y()),
                () -> assertFloatBits(expectedTranslation, translationPose.translation().z()),
                () -> assertFloatBits(expectedScale, scalePose.scale().x()),
                () -> assertFloatBits(expectedScale, scalePose.scale().y()),
                () -> assertFloatBits(expectedScale, scalePose.scale().z()));
    }

    private static void assertAdditiveTranslationEndpoint(float restValue, float sampleValue) {
        AnimationV2EvaluationSnapshot snapshot = additiveWeightedRuntime(
                translatedUniform(restValue), translatedUniform(sampleValue), 1.0F, 1.0F).advance(0.0D);
        assertAll(
                () -> assertFloatBits(sampleValue, snapshot.pose().transform(0).translation().x()),
                () -> assertFloatBits(sampleValue, snapshot.pose().transform(0).translation().y()),
                () -> assertFloatBits(sampleValue, snapshot.pose().transform(0).translation().z()));
    }

    private static void assertTwoLayerAdditiveTranslation(
            float restValue, float firstSampleValue, float firstWeight, float endpointValue, String label) {
        float firstPublished = (float) ((double) restValue
                + ((double) firstSampleValue - (double) restValue) * (double) firstWeight);
        // The independent oracle deliberately cancels the two large terms before adding the tiny endpoint.
        float expected = (float) (((double) firstPublished - (double) restValue) + (double) endpointValue);
        AnimationV2EvaluationSnapshot snapshot = twoAdditiveWeightedRuntime(
                translatedUniform(restValue), translatedUniform(firstSampleValue), firstWeight,
                translatedUniform(endpointValue), 1.0F).advance(0.0D);
        assertAll(label,
                () -> assertFloatBits(expected, snapshot.pose().transform(0).translation().x()),
                () -> assertFloatBits(expected, snapshot.pose().transform(0).translation().y()),
                () -> assertFloatBits(expected, snapshot.pose().transform(0).translation().z()));
    }

    private static void assertAdditiveQuaternionBits(Quaternion restRotation, Quaternion sampleRotation,
            Quaternion expectedCanonicalRotation) {
        Transform rest = new Transform(Vec3.ZERO, restRotation, Vec3.ONE);
        Transform sample = new Transform(Vec3.ZERO, sampleRotation, Vec3.ONE);
        Quaternion actual = additiveWeightedRuntime(rest, sample, 1.0F, 1.0F).advance(0.0D).pose().transform(0).rotation();
        assertAll(
                () -> assertFloatBits(expectedCanonicalRotation.x(), actual.x()),
                () -> assertFloatBits(expectedCanonicalRotation.y(), actual.y()),
                () -> assertFloatBits(expectedCanonicalRotation.z(), actual.z()),
                () -> assertFloatBits(expectedCanonicalRotation.w(), actual.w()));
    }

    private static void assertAdditiveQuaternionGroupIdentity(
            int[] restBits, int[] sampleBits, int[] expectedCanonicalBits) {
        Transform rest = rotated(quaternion(restBits));
        Transform sample = rotated(quaternion(sampleBits));
        Quaternion expectedScratch = canonicalQuaternion(sample.rotation());
        Quaternion expectedScratchBits = quaternion(expectedCanonicalBits);
        AnimationV2TransformScratch scratch = scratch(rest);
        scratch.applyAdditive(scratch(rest), scratch(sample), 1.0D);

        AnimationV2InstanceRuntime runtime = additiveWeightedRuntime(rest, sample, 1.0F, 1.0F);
        AnimationV2EvaluationSnapshot published = runtime.advance(0.0D);
        Quaternion actual = published.pose().transform(0).rotation();
        // The parent/stable Transform constructor unconditionally uses this exact public projection.
        Quaternion stablePublicationProjection = expectedScratch.normalized();
        assertAll(
                () -> assertQuaternionBits(expectedScratchBits, expectedScratch),
                () -> assertScratchQuaternionBits(expectedScratchBits, scratch),
                () -> assertQuaternionBits(stablePublicationProjection, actual),
                () -> assertSame(published, runtime.latestSnapshot()));
    }

    private static AnimationV2TransformScratch scratch(Transform transform) {
        AnimationV2TransformScratch scratch = new AnimationV2TransformScratch();
        scratch.set(transform);
        return scratch;
    }

    private static void assertScratchQuaternionBits(Quaternion expected, AnimationV2TransformScratch actual) {
        assertAll(
                () -> assertFloatBits(expected.x(), actual.qx),
                () -> assertFloatBits(expected.y(), actual.qy),
                () -> assertFloatBits(expected.z(), actual.qz),
                () -> assertFloatBits(expected.w(), actual.qw));
    }

    private static Quaternion quaternion(int[] values) {
        return new Quaternion(Float.intBitsToFloat(values[0]), Float.intBitsToFloat(values[1]),
                Float.intBitsToFloat(values[2]), Float.intBitsToFloat(values[3]));
    }

    private static Transform rotated(Quaternion rotation) {
        return new Transform(Vec3.ZERO, rotation, Vec3.ONE);
    }

    private static Quaternion canonicalQuaternion(Quaternion value) {
        float sign = expectedCanonicalHemisphereSign(value);
        return new Quaternion(value.x() * sign, value.y() * sign, value.z() * sign, value.w() * sign);
    }

    private static float expectedCanonicalHemisphereSign(Quaternion value) {
        if (Math.abs(value.w()) > 1.0E-6F) {
            return value.w() < 0.0F ? -1.0F : 1.0F;
        }
        if (Math.abs(value.x()) > 1.0E-6F) {
            return value.x() < 0.0F ? -1.0F : 1.0F;
        }
        if (Math.abs(value.y()) > 1.0E-6F) {
            return value.y() < 0.0F ? -1.0F : 1.0F;
        }
        return value.z() < 0.0F ? -1.0F : 1.0F;
    }

    private static void assertQuaternionBits(Quaternion expected, Quaternion actual) {
        assertAll(
                () -> assertFloatBits(expected.x(), actual.x()),
                () -> assertFloatBits(expected.y(), actual.y()),
                () -> assertFloatBits(expected.z(), actual.z()),
                () -> assertFloatBits(expected.w(), actual.w()));
    }

    private static AnimationV2InstanceRuntime additiveWeightedRuntime(
            Transform rest, Transform sample, float layerWeight, float maskWeight) {
        BoneSchema schema = schema(rest);
        BoneMask mask = BoneMask.indexed(schema, List.of(new BoneMask.IndexedWeight(0, maskWeight)));
        AnimationV2LayerDefinition additive = new AnimationV2LayerDefinition(
                LAYER, 0, AnimationV2LayerMode.ADDITIVE, layerWeight, mask, false);
        AnimationV2ControllerState state = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(sample, sample));
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(additive), A, Map.of(A, state))));
    }

    private static AnimationV2InstanceRuntime additiveWithOverrideRuntime(
            Transform rest, Transform overrideSample, Transform additiveSample) {
        BoneSchema schema = schema(rest);
        AnimationV2LayerDefinition override = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition additive = layer(schema, SECOND_LAYER, AnimationV2LayerMode.ADDITIVE);
        AnimationV2ControllerState state = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(LAYER, clip(overrideSample, overrideSample), SECOND_LAYER, clip(additiveSample, additiveSample)));
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(override, additive), A, Map.of(A, state))));
    }

    private static AnimationV2InstanceRuntime twoAdditiveWeightedRuntime(
            Transform rest, Transform firstSample, float firstWeight, Transform secondSample, float secondWeight) {
        BoneSchema schema = schema(rest);
        AnimationV2LayerDefinition first = weightedLayer(schema, LAYER, AnimationV2LayerMode.ADDITIVE, firstWeight);
        AnimationV2LayerDefinition second = weightedLayer(schema, SECOND_LAYER, AnimationV2LayerMode.ADDITIVE, secondWeight);
        AnimationV2ControllerState state = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(LAYER, clip(firstSample, firstSample), SECOND_LAYER, clip(secondSample, secondSample)));
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(first, second), A, Map.of(A, state))));
    }

    private static AnimationV2InstanceRuntime impossibleAdditiveRuntime() {
        BoneSchema schema = schema(translated(-Float.MAX_VALUE));
        AnimationV2LayerDefinition override = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition additive = layer(schema, SECOND_LAYER, AnimationV2LayerMode.ADDITIVE);
        AnimationV2ControllerState a = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(LAYER, clip(Transform.IDENTITY, Transform.IDENTITY),
                        SECOND_LAYER, clip(translated(-Float.MAX_VALUE), translated(-Float.MAX_VALUE))));
        AnimationV2ControllerState b = state(B, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE)),
                        SECOND_LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE))));
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(override, additive), A, states(a, b))));
    }

    private static AnimationV2InstanceRuntime impossibleTransitionRuntime() {
        BoneSchema schema = schema(translated(-Float.MAX_VALUE));
        AnimationV2LayerDefinition override = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition additive = layer(schema, SECOND_LAYER, AnimationV2LayerMode.ADDITIVE);
        AnimationV2ControllerState a = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(LAYER, clip(Transform.IDENTITY, Transform.IDENTITY),
                        SECOND_LAYER, clip(translated(-Float.MAX_VALUE), translated(-Float.MAX_VALUE))));
        AnimationV2ControllerState b = state(B, AnimationV2PlaybackMode.HOLD, 1.0D, null,
                Map.of(LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE)),
                        SECOND_LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE))));
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(override, additive), A, states(a, b))));
    }

    private static AnimationV2InstanceRuntime frozenTransitionFailureRuntime() {
        BoneSchema schema = schema(translated(-Float.MAX_VALUE));
        AnimationV2LayerDefinition primary = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition secondaryOverride = layer(schema, SECOND_LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition secondaryAdditive = layer(schema, THIRD_LAYER, AnimationV2LayerMode.ADDITIVE);
        AnimationV2ControllerState a = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(translated(-Float.MAX_VALUE), translated(-Float.MAX_VALUE)));
        AnimationV2ControllerState b = state(B, AnimationV2PlaybackMode.HOLD, 1.0D, null, LAYER,
                clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE)));
        AnimationV2ControllerState c = state(C, AnimationV2PlaybackMode.HOLD, 1.0D, null, LAYER,
                clip(Transform.IDENTITY, Transform.IDENTITY));
        AnimationV2ControllerState stable = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(SECOND_LAYER, clip(translated(-Float.MAX_VALUE), translated(-Float.MAX_VALUE)),
                        THIRD_LAYER, clip(translated(-Float.MAX_VALUE), translated(-Float.MAX_VALUE))));
        AnimationV2ControllerState failing = state(D, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(SECOND_LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE)),
                        THIRD_LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE))));
        return runtime(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 1, List.of(primary), A, states(a, b, c)),
                new AnimationV2ControllerDefinition(SECOND_CONTROLLER, 0,
                        List.of(secondaryOverride, secondaryAdditive), A, states(stable, failing))));
    }

    private static AnimationV2InstanceRuntime laterControllerFailureRuntime() {
        BoneSchema schema = schema(translated(-Float.MAX_VALUE));
        AnimationV2LayerDefinition firstLayer = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition secondOverride = layer(schema, SECOND_LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2LayerDefinition secondAdditive = layer(schema, THIRD_LAYER, AnimationV2LayerMode.ADDITIVE);
        AnimationV2ControllerState first = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null, LAYER,
                clip(Transform.IDENTITY, Transform.IDENTITY));
        AnimationV2ControllerState second = state(A, AnimationV2PlaybackMode.HOLD, 0.0D, null,
                Map.of(SECOND_LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE)),
                        THIRD_LAYER, clip(translated(Float.MAX_VALUE), translated(Float.MAX_VALUE))));
        return runtime(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(firstLayer), A, Map.of(A, first)),
                new AnimationV2ControllerDefinition(SECOND_CONTROLLER, 1,
                        List.of(secondOverride, secondAdditive), A, Map.of(A, second))));
    }

    private static AnimationV2InstanceRuntime cycleRuntime(BlendAnimationKey first) {
        BoneSchema schema = schema(Transform.IDENTITY);
        AnimationV2LayerDefinition layer = layer(schema, LAYER, AnimationV2LayerMode.OVERRIDE);
        AnimationV2Clip unit = clip(Transform.IDENTITY, Transform.IDENTITY);
        AnimationV2ControllerState firstState = state(first, AnimationV2PlaybackMode.ONCE, 0.0D, B, LAYER, unit);
        AnimationV2ControllerState secondState = state(B, AnimationV2PlaybackMode.ONCE, 0.0D, first, LAYER, unit);
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(layer), first, states(firstState, secondState))));
    }

    private static AnimationV2InstanceRuntime singleControllerRuntime(
            Transform rest,
            AnimationV2LayerMode mode,
            BlendAnimationKey initial,
            Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        BoneSchema schema = schema(rest);
        AnimationV2LayerDefinition layer = layer(schema, LAYER, mode);
        return runtime(schema, List.of(new AnimationV2ControllerDefinition(
                CONTROLLER, 0, List.of(layer), initial, states)));
    }

    private static AnimationV2InstanceRuntime runtime(
            BoneSchema schema, List<AnimationV2ControllerDefinition> controllers) {
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, controllers));
    }

    private static BoneSchema schema(Transform rest) {
        return new BoneSchema(List.of("root"), List.of(rest));
    }

    private static AnimationV2LayerDefinition layer(
            BoneSchema schema, BlendResourceId id, AnimationV2LayerMode mode) {
        return weightedLayer(schema, id, mode, 1.0F);
    }

    private static AnimationV2LayerDefinition weightedLayer(
            BoneSchema schema, BlendResourceId id, AnimationV2LayerMode mode, float weight) {
        return new AnimationV2LayerDefinition(id, 0, mode, weight, BoneMask.all(schema), false);
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double transitionSeconds,
            BlendAnimationKey next,
            BlendResourceId layer,
            AnimationV2Clip clip) {
        return state(key, mode, transitionSeconds, next, Map.of(layer, clip));
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double transitionSeconds,
            BlendAnimationKey next,
            Map<BlendResourceId, AnimationV2Clip> clips) {
        return new AnimationV2ControllerState(key, mode, 1.0D, transitionSeconds, next, clips);
    }

    private static Map<BlendAnimationKey, AnimationV2ControllerState> states(
            AnimationV2ControllerState... entries) {
        LinkedHashMap<BlendAnimationKey, AnimationV2ControllerState> values = new LinkedHashMap<>();
        for (AnimationV2ControllerState entry : entries) {
            values.put(entry.key(), entry);
        }
        return values;
    }

    private static AnimationV2Clip clip(Transform first, Transform second) {
        return new AnimationV2Clip(List.of(
                new AnimationV2Keyframe(0.0D, new AnimationV2Pose(List.of(first))),
                new AnimationV2Keyframe(1.0D, new AnimationV2Pose(List.of(second)))));
    }

    private static Transform translated(float value) {
        return new Transform(new Vec3(value, 0.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE);
    }

    private static Transform translatedUniform(float value) {
        return new Transform(new Vec3(value, value, value), Quaternion.IDENTITY, Vec3.ONE);
    }

    private static Transform scaled(float value) {
        return new Transform(Vec3.ZERO, Quaternion.IDENTITY, new Vec3(value, value, value));
    }

    private static void setRevisionAndLatest(AnimationV2InstanceRuntime runtime, long target) throws Exception {
        AnimationV2EvaluationSnapshot before = runtime.latestSnapshot();
        AnimationV2EvaluationSnapshot adjusted = new AnimationV2EvaluationSnapshot(
                target, before.pose(), before.playheads(), before.diagnostics(), before.observerTraversal());
        Field revision = AnimationV2InstanceRuntime.class.getDeclaredField("revision");
        revision.setAccessible(true);
        revision.setLong(runtime, target);
        @SuppressWarnings("unchecked")
        AtomicReference<AnimationV2EvaluationSnapshot> latest =
                (AtomicReference<AnimationV2EvaluationSnapshot>) field(runtime, "latest");
        latest.set(adjusted);
    }

    private static void assertArithmeticFailureLeavesFrameUntouched(
            AnimationV2InstanceRuntime runtime, ThrowingFrame call) throws Exception {
        OwnerFingerprint before = fingerprint(runtime);
        AnimationV2EvaluationSnapshot latest = runtime.latestSnapshot();
        ArithmeticException failure = assertThrows(ArithmeticException.class, call::run);
        assertEquals("long overflow", failure.getMessage());
        assertEquals(before, fingerprint(runtime));
        assertSame(latest, runtime.latestSnapshot());
        assertScratchReferencesCleared(runtime);
    }

    private static void assertIllegalArgumentFailureLeavesFrameUntouched(
            AnimationV2InstanceRuntime runtime, ThrowingFrame call) throws Exception {
        OwnerFingerprint before = fingerprint(runtime);
        AnimationV2EvaluationSnapshot latest = runtime.latestSnapshot();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, call::run);
        assertTrue(failure.getMessage().contains("non-finite") || failure.getMessage().contains("non-positive scale"));
        assertEquals(before, fingerprint(runtime));
        assertSame(latest, runtime.latestSnapshot());
        assertScratchReferencesCleared(runtime);
    }

    @SuppressWarnings("unchecked")
    private static OwnerFingerprint fingerprint(AnimationV2InstanceRuntime runtime) throws Exception {
        List<ControllerFingerprint> controllers = new ArrayList<>();
        for (Object controller : (Object[]) field(runtime, "controllersInEvaluationOrder")) {
            controllers.add(new ControllerFingerprint(
                    field(controller, "current"),
                    field(controller, "currentClips"),
                    field(controller, "previous"),
                    field(controller, "previousClips"),
                    field(controller, "frozenPreviousPoses"),
                    field(controller, "transitionSourceState"),
                    field(controller, "currentTime"),
                    field(controller, "previousTime"),
                    field(controller, "playbackSpeed"),
                    field(controller, "previousPlaybackSpeed"),
                    field(controller, "transitionElapsed"),
                    field(controller, "transitionDuration"),
                    field(controller, "sequenceWatermark"),
                    field(controller, "acceptedCommand"),
                    field(controller, "observerLoopEpoch"),
                    field(controller, "observerOccurrence"),
                    List.copyOf((List<Object>) field(controller, "observerSegments")),
                    List.copyOf((List<Object>) field(controller, "observerContinuityPublications")),
                    field(controller, "observerContinuityInitial"),
                    field(controller, "observerContinuityTerminal"),
                    field(controller, "observerDiscontinuity"),
                    field(controller, "observerTraversalTruncated")));
        }
        return new OwnerFingerprint(
                (long) field(runtime, "revision"),
                runtime.latestSnapshot(),
                List.copyOf(incoming(runtime)),
                List.copyOf(backlog(runtime, "commandBacklog")),
                List.copyOf(backlog(runtime, "frameCommandBacklog")),
                ((AtomicReference<?>) field(runtime, "ingressOverflowTicket")).get(),
                List.copyOf(controllers));
    }

    @SuppressWarnings("unchecked")
    private static List<AnimationV2Command> incoming(AnimationV2InstanceRuntime runtime) throws Exception {
        return List.copyOf((Collection<AnimationV2Command>) field(runtime, "incoming"));
    }

    @SuppressWarnings("unchecked")
    private static List<AnimationV2Command> backlog(AnimationV2InstanceRuntime runtime, String name) throws Exception {
        return List.copyOf((List<AnimationV2Command>) field(runtime, name));
    }

    private static Object liveController(AnimationV2InstanceRuntime runtime, BlendResourceId id) throws Exception {
        for (Object controller : (Object[]) field(runtime, "controllersInEvaluationOrder")) {
            AnimationV2ControllerDefinition definition =
                    (AnimationV2ControllerDefinition) field(controller, "definition");
            if (definition.id().equals(id)) {
                return controller;
            }
        }
        throw new AssertionError("missing live controller: " + id);
    }

    private static void assertScratchReferencesCleared(AnimationV2InstanceRuntime runtime) throws Exception {
        for (String name : List.of("ingressPreflightSnapshot")) {
            Object[] scratch = (Object[]) field(runtime, name);
            for (Object value : scratch) {
                assertEquals(null, value, name + " retained an old command reference");
            }
        }
        Object stage = field(runtime, "frameStage");
        for (String name : List.of("commandBacklog", "frameCommandBacklog")) {
            assertTrue(((List<?>) field(stage, name)).isEmpty(), "staging " + name + " retained an old command reference");
        }
        for (Object controller : (Object[]) field(stage, "controllersInEvaluationOrder")) {
            assertEquals(null, field(controller, "acceptedCommand"), "staging controller retained a command");
            assertEquals(null, field(controller, "previous"), "staging controller retained a previous state");
            assertEquals(null, field(controller, "previousClips"), "staging controller retained previous clips");
            assertEquals(null, field(controller, "frozenPreviousPoses"), "staging controller retained frozen presentation");
            assertEquals(null, field(controller, "transitionSourceState"), "staging controller retained a transition source");
            assertTrue(((List<?>) field(controller, "observerSegments")).isEmpty(),
                    "staging controller retained observer segments");
            assertTrue(((List<?>) field(controller, "observerContinuityPublications")).isEmpty(),
                    "staging controller retained observer continuity publications");
            assertEquals(null, field(controller, "observerContinuityInitial"),
                    "staging controller retained an observer initial anchor");
            assertEquals(null, field(controller, "observerContinuityTerminal"),
                    "staging controller retained an observer terminal anchor");
            for (Object key : (Object[]) field(controller, "nextVisited")) {
                assertEquals(null, key, "staging controller retained a next-chain key");
            }
        }
    }

    private static BlendAnimationKey keyWithDisplayValue(String value) throws Exception {
        // The public v1 resource grammar is ASCII-only. This package-local adversarial value changes only the cached
        // display form so the runtime's UTF-16-safe diagnostic truncation is covered without widening that API grammar.
        BlendAnimationKey key = BlendAnimationKey.of("z", "x".repeat(254));
        Field resource = BlendAnimationKey.class.getDeclaredField("resourceId");
        resource.setAccessible(true);
        BlendResourceId resourceId = (BlendResourceId) resource.get(key);
        Field displayValue = BlendResourceId.class.getDeclaredField("value");
        displayValue.setAccessible(true);
        displayValue.set(resourceId, value);
        return key;
    }

    private static boolean hasOnlyCompleteSurrogatePairs(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    @FunctionalInterface
    private interface ThrowingFrame {
        void run() throws Exception;
    }

    private record OwnerFingerprint(
            long revision,
            Object latest,
            List<AnimationV2Command> incoming,
            List<AnimationV2Command> commandBacklog,
            List<AnimationV2Command> frameCommandBacklog,
            Object ingressOverflowTicket,
            List<ControllerFingerprint> controllers) {
    }

    private record ControllerFingerprint(
            Object current,
            Object currentClips,
            Object previous,
            Object previousClips,
            Object frozenPreviousPoses,
            Object transitionSourceState,
            Object currentTime,
            Object previousTime,
            Object playbackSpeed,
            Object previousPlaybackSpeed,
            Object transitionElapsed,
            Object transitionDuration,
            Object sequenceWatermark,
            Object acceptedCommand,
            Object observerLoopEpoch,
            Object observerOccurrence,
            List<Object> observerSegments,
            List<Object> observerContinuityPublications,
            Object observerContinuityInitial,
            Object observerContinuityTerminal,
            Object observerDiscontinuity,
            Object observerTraversalTruncated) {
    }
}
