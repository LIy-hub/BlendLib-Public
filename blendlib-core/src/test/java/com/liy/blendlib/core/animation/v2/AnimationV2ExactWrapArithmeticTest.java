package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Regression contract for exact LOOP identities beyond binary64's integer range. */
class AnimationV2ExactWrapArithmeticTest {
    private static final BlendResourceId CONTROLLER = BlendResourceId.of("x2exact", "loop-controller");
    private static final BlendResourceId LAYER = BlendResourceId.of("x2exact", "loop-layer");
    private static final BlendResourceId SECOND_CONTROLLER = BlendResourceId.of("x2exact", "second-loop-controller");
    private static final BlendResourceId SECOND_LAYER = BlendResourceId.of("x2exact", "second-loop-layer");
    private static final BlendAnimationKey LOOP = BlendAnimationKey.of("x2exact", "loop");
    private static final BlendAnimationKey PRIOR = BlendAnimationKey.of("x2exact", "prior");
    private static final BlendAnimationKey IGNORED = BlendAnimationKey.of("x2exact", "ignored");
    private static final BlendResourceId UNKNOWN_CONTROLLER = BlendResourceId.of("x2exact", "unknown-controller");
    private static final BlendResourceId SECOND_UNKNOWN_CONTROLLER = BlendResourceId.of("x2exact", "second-unknown-controller");

    @Test
    void oddFiniteLoopIdentityAboveBinary64IntegerPrecisionRemainsExact() {
        double duration = Math.nextDown(Math.scalb(1.0D, -53));
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);

        AnimationV2EvaluationSnapshot snapshot = runtime.advance(1.0D);
        AnimationV2ObserverTraversal.ControllerTraversal traversal = snapshot.observerTraversal().controller(CONTROLLER);

        assertSame(snapshot, runtime.latestSnapshot(), "the published owner snapshot must be the exact latest object");
        assertExactDouble(Math.scalb(1.0D, -106), snapshot.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(9_007_199_254_740_993L, traversal.continuity().terminalAnchor().loopEpoch());
        assertEquals(9_007_199_254_740_993L, traversal.continuity().terminalAnchor().occurrence());
        assertTrue(traversal.truncated(), "the bounded trace may truncate without changing its exact identity");
        assertTrue(traversal.segments().isEmpty(), "a truncated trace must not expose a partial path");
        assertTrue(snapshot.diagnostics().stream().anyMatch(value -> value.code()
                == AnimationV2DiagnosticCode.OBSERVER_TRAVERSAL_TRUNCATED));
    }

    @Test
    void unrepresentableTwoToTheSixtyThreeLoopIdentityFailsClosedBeforePublicationOrTransitionMutation() {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime direct = loopRuntime(duration, 1.0D);
        AnimationV2EvaluationSnapshot directBefore = direct.latestSnapshot();

        IllegalStateException directFailure = assertThrows(IllegalStateException.class, () -> direct.advance(1.0D));
        assertEquals("v2 controller loop count overflow", directFailure.getMessage());
        assertSame(directBefore, direct.latestSnapshot(), "overflow must not publish a saturated owner snapshot");
        AnimationV2EvaluationSnapshot directRetry = direct.advance(duration / 2.0D);
        assertExactDouble(duration / 2.0D, directRetry.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(0L, directRetry.observerTraversal().controller(CONTROLLER).continuity().terminalAnchor().loopEpoch());

        AnimationV2InstanceRuntime transitioning = transitioningOverflowRuntime(duration);
        AnimationV2EvaluationSnapshot transition = transitioning.advanceAtFrame(0.0D,
                List.of(new AnimationV2Command(CONTROLLER, LOOP, 1L, 0.0D, 1.0D)));
        assertEquals(PRIOR, transition.playheads().get(CONTROLLER).previousState());
        assertEquals(0.0D, transition.playheads().get(CONTROLLER).transitionProgress(), 0.0D);

        assertThrows(IllegalStateException.class, () -> transitioning.advance(1.0D));
        assertSame(transition, transitioning.latestSnapshot(),
                "overflow must not advance an active transition before it rejects the frame");
        AnimationV2EvaluationSnapshot transitionRetry = transitioning.advance(0.0D);
        assertEquals(PRIOR, transitionRetry.playheads().get(CONTROLLER).previousState());
        assertEquals(0.0D, transitionRetry.playheads().get(CONTROLLER).transitionProgress(), 0.0D);
    }

    @Test
    void cumulativeLoopCounterOverflowFailsClosedAndRemainsRetryable() {
        double duration = Math.nextUp(Math.scalb(1.0D, -63));
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);

        AnimationV2EvaluationSnapshot nearMaximum = runtime.advance(1.0D);
        AnimationV2ObserverTraversal.Anchor nearMaximumAnchor = nearMaximum.observerTraversal()
                .controller(CONTROLLER).continuity().terminalAnchor();
        assertEquals(Long.MAX_VALUE - 2_047L, nearMaximumAnchor.loopEpoch());
        assertEquals(Long.MAX_VALUE - 2_047L, nearMaximumAnchor.occurrence());
        assertExactDouble(Math.scalb(1.0D, -104), nearMaximum.playheads().get(CONTROLLER).timeSeconds());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.advance(duration * 2_048.0D));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(nearMaximum, runtime.latestSnapshot(),
                "a cumulative counter overflow must not publish a partially advanced snapshot");

        AnimationV2EvaluationSnapshot retry = runtime.advance(duration / 2.0D);
        assertEquals(Long.MAX_VALUE - 2_047L,
                retry.observerTraversal().controller(CONTROLLER).continuity().terminalAnchor().loopEpoch());
    }

    @Test
    void longMaximumObserverOccurrenceRejectsFrameCommandBeforeQueueOrControllerMutation() {
        LoopCounterFixture fixture = loopCountersAtLongMaximum();
        AnimationV2EvaluationSnapshot before = fixture.runtime().latestSnapshot();
        AnimationV2Command command = new AnimationV2Command(
                CONTROLLER, LOOP, 1L, fixture.duration() / 2.0D, 1.0D);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.runtime().advanceAtFrame(0.0D, List.of(command)));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(before, fixture.runtime().latestSnapshot());

        AnimationV2EvaluationSnapshot retry = fixture.runtime().advance(0.0D);
        assertExactDouble(0.0D, retry.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(-1L, retry.playheads().get(CONTROLLER).acceptedSequence());
    }

    @Test
    void frameCommandPreflightIncludesTheOldLoopAdvanceBeforeItCanMutate() {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);
        runtime.advance(Math.nextDown(1.0D));
        AnimationV2EvaluationSnapshot before = runtime.advance(duration * 1_022.0D);
        assertEquals(Long.MAX_VALUE - 1L, before.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().occurrence());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.advanceAtFrame(duration,
                        List.of(new AnimationV2Command(CONTROLLER, LOOP, 1L, duration / 2.0D, 1.0D))));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(before, runtime.latestSnapshot());

        AnimationV2EvaluationSnapshot retry = runtime.advance(0.0D);
        assertEquals(Long.MAX_VALUE - 1L, retry.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().occurrence());
        assertExactDouble(0.0D, retry.playheads().get(CONTROLLER).timeSeconds());
    }

    @Test
    void queuedCommandGroupsPreflightTogetherBeforeEitherCanMutateTheOwner() throws ReflectiveOperationException {
        LoopCounterFixture firstOnlyFixture = loopCountersAtPenultimateOccurrence();
        AnimationV2Command first = new AnimationV2Command(
                CONTROLLER, LOOP, 1L, firstOnlyFixture.duration() / 2.0D, 1.0D);
        assertEquals(AnimationV2IngressOutcome.QUEUED, firstOnlyFixture.runtime().enqueue(first));
        AnimationV2EvaluationSnapshot firstOnlyCommit = firstOnlyFixture.runtime().advance(0.0D);
        assertEquals(1L, firstOnlyCommit.playheads().get(CONTROLLER).acceptedSequence(),
                "the first queued group must be independently valid before the second group is introduced");
        assertExactDouble(firstOnlyFixture.duration() / 2.0D,
                firstOnlyCommit.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(Long.MAX_VALUE, firstOnlyCommit.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().occurrence());
        assertFixedStageAndIngressReferencesCleared(firstOnlyFixture.runtime(), 1);

        LoopCounterFixture fixture = loopCountersAtPenultimateOccurrence();
        AnimationV2InstanceRuntime runtime = fixture.runtime();
        AnimationV2EvaluationSnapshot before = runtime.latestSnapshot();
        AnimationV2Command second = new AnimationV2Command(
                CONTROLLER, LOOP, 2L, fixture.duration() / 4.0D, 1.0D);
        List<AnimationV2Command> queuedGroups = List.of(first, second);
        assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(first));
        assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(second));
        assertEquals(queuedGroups, incomingCommands(runtime));
        OwnerFingerprint ownerBefore = ownerFingerprint(runtime);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runtime.advance(0.0D));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(before, runtime.latestSnapshot());
        assertQueuedGroupsRemainUntouched(runtime, ownerBefore, before, queuedGroups, Long.MAX_VALUE - 1L);
        assertFixedStageAndIngressReferencesCleared(runtime, 2);

        IllegalStateException retryFailure = assertThrows(IllegalStateException.class, () -> runtime.advance(0.0D));
        assertEquals("v2 controller loop count overflow", retryFailure.getMessage());
        assertSame(before, runtime.latestSnapshot(),
                "a queued failing prefix may be retried, but it must never leak one group into owner state");
        assertQueuedGroupsRemainUntouched(runtime, ownerBefore, before, queuedGroups, Long.MAX_VALUE - 1L);
        assertFixedStageAndIngressReferencesCleared(runtime, 2);
    }

    @Test
    void distinctCompleteQueuedGroupsRespectThe128GroupDrainBoundary() throws ReflectiveOperationException {
        AnimationV2InstanceRuntime exactLimit = loopRuntime(1.0D, 1.0D);
        List<AnimationV2Command> oneHundredTwentyEight = distinctQueuedGroups(128);
        enqueueAll(exactLimit, oneHundredTwentyEight);

        AnimationV2EvaluationSnapshot drained = exactLimit.advance(0.0D);
        assertEquals(128L, drained.playheads().get(CONTROLLER).acceptedSequence());
        assertExactDouble(oneHundredTwentyEight.get(127).requestedPlayheadSeconds(),
                drained.playheads().get(CONTROLLER).timeSeconds());
        assertTrue(drained.diagnostics().stream().noneMatch(value -> value.code()
                == AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED));
        assertTrue(incomingCommands(exactLimit).isEmpty());
        assertTrue(((List<?>) fieldValue(exactLimit, "commandBacklog")).isEmpty());
        assertTrue(((List<?>) fieldValue(exactLimit, "frameCommandBacklog")).isEmpty());
        assertFixedStageAndIngressReferencesCleared(exactLimit, 128);

        AnimationV2InstanceRuntime oneOverLimit = loopRuntime(1.0D, 1.0D);
        List<AnimationV2Command> oneHundredTwentyNine = distinctQueuedGroups(129);
        enqueueAll(oneOverLimit, oneHundredTwentyNine);

        AnimationV2EvaluationSnapshot deferred = oneOverLimit.advance(0.0D);
        assertEquals(128L, deferred.playheads().get(CONTROLLER).acceptedSequence());
        assertExactDouble(oneHundredTwentyNine.get(127).requestedPlayheadSeconds(),
                deferred.playheads().get(CONTROLLER).timeSeconds());
        assertTrue(deferred.diagnostics().stream().anyMatch(value -> value.code()
                == AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED));
        assertTrue(incomingCommands(oneOverLimit).isEmpty());
        assertEquals(List.of(oneHundredTwentyNine.get(128)),
                copyListField(oneOverLimit, "commandBacklog"),
                "the 129th complete controller/sequence group must remain intact for the next owner frame");
        assertTrue(((List<?>) fieldValue(oneOverLimit, "frameCommandBacklog")).isEmpty());
        assertFixedStageAndIngressReferencesCleared(oneOverLimit, 129);

        AnimationV2EvaluationSnapshot drainedDeferred = oneOverLimit.advance(0.0D);
        assertEquals(129L, drainedDeferred.playheads().get(CONTROLLER).acceptedSequence());
        assertExactDouble(oneHundredTwentyNine.get(128).requestedPlayheadSeconds(),
                drainedDeferred.playheads().get(CONTROLLER).timeSeconds());
        assertTrue(drainedDeferred.diagnostics().stream().noneMatch(value -> value.code()
                == AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED));
        assertTrue(incomingCommands(oneOverLimit).isEmpty());
        assertTrue(((List<?>) fieldValue(oneOverLimit, "commandBacklog")).isEmpty());
        assertFixedStageAndIngressReferencesCleared(oneOverLimit, 129);
    }

    @Test
    void captured255CommandPrefixLeavesControlledConcurrentSuffixForTheNextOwnerFrame() throws Exception {
        AnimationV2InstanceRuntime runtime = loopRuntime(1.0D, 1.0D);
        CaptureBarrierQueue incoming = installCaptureBarrierQueue(runtime);
        AnimationV2Command prefix = new AnimationV2Command(CONTROLLER, LOOP, 1L, 0.5D, 1.0D);
        AnimationV2Command suffix = new AnimationV2Command(CONTROLLER, LOOP, 2L, 0.25D, 1.0D);
        enqueueCopies(runtime, prefix, 255);

        ExecutorService owner = Executors.newSingleThreadExecutor();
        try {
            Future<AnimationV2EvaluationSnapshot> capturedFrame = owner.submit(() -> runtime.advance(0.0D));
            incoming.awaitFirstCapture();
            assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(suffix),
                    "the suffix arrives after the 255-command capture and must fit the final ingress slot");
            incoming.releaseFirstCapture();

            AnimationV2EvaluationSnapshot first = awaitFrame(capturedFrame);
            assertEquals(1L, first.playheads().get(CONTROLLER).acceptedSequence());
            assertExactDouble(0.5D, first.playheads().get(CONTROLLER).timeSeconds());
            assertEquals(List.of(suffix), incomingCommands(runtime),
                    "the concurrent suffix must not join the already captured prefix");
            assertTrue(((List<?>) fieldValue(runtime, "commandBacklog")).isEmpty());
            assertTrue(((List<?>) fieldValue(runtime, "frameCommandBacklog")).isEmpty());
            assertFixedStageAndIngressReferencesCleared(runtime, 255);

            AnimationV2EvaluationSnapshot next = awaitFrame(owner.submit(() -> runtime.advance(0.0D)));
            assertEquals(2L, next.playheads().get(CONTROLLER).acceptedSequence());
            assertExactDouble(0.25D, next.playheads().get(CONTROLLER).timeSeconds());
            assertTrue(incomingCommands(runtime).isEmpty());
            assertTrue(((List<?>) fieldValue(runtime, "commandBacklog")).isEmpty());
            assertFixedStageAndIngressReferencesCleared(runtime, 1);
        } finally {
            incoming.releaseFirstCapture();
            shutdownOwner(owner);
        }
    }

    @Test
    void failed255CommandCaptureRetainsControlledSuffixAndQueueAcrossOwnerRetries() throws Exception {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CaptureBarrierQueue incoming = null;
        try {
            AnimationV2EvaluationSnapshot before = awaitFrame(owner.submit(() -> {
                runtime.advance(Math.nextDown(1.0D));
                return runtime.advance(duration * 1_023.0D);
            }));
            assertEquals(Long.MAX_VALUE, before.observerTraversal().controller(CONTROLLER)
                    .continuity().terminalAnchor().occurrence());

            incoming = installCaptureBarrierQueue(runtime);
            AnimationV2Command prefix = new AnimationV2Command(CONTROLLER, LOOP, 1L, duration / 2.0D, 1.0D);
            AnimationV2Command suffix = new AnimationV2Command(CONTROLLER, LOOP, 2L, duration / 4.0D, 1.0D);
            enqueueCopies(runtime, prefix, 255);
            Future<AnimationV2EvaluationSnapshot> capturedFrame = owner.submit(() -> runtime.advance(0.0D));
            incoming.awaitFirstCapture();
            assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(suffix),
                    "the controlled suffix must occupy the one slot left after the captured 255-command prefix");
            List<AnimationV2Command> expectedQueue = repeatedCommands(prefix, 255);
            expectedQueue = new ArrayList<>(expectedQueue);
            expectedQueue.add(suffix);
            expectedQueue = List.copyOf(expectedQueue);
            OwnerFingerprint ownerBeforeFailure = ownerFingerprint(runtime);
            incoming.releaseFirstCapture();

            assertLoopCountOverflow(capturedFrame);
            assertSame(before, runtime.latestSnapshot());
            assertEquals(ownerBeforeFailure, ownerFingerprint(runtime));
            assertEquals(expectedQueue, incomingCommands(runtime));
            assertTrue(((List<?>) fieldValue(runtime, "commandBacklog")).isEmpty());
            assertTrue(((List<?>) fieldValue(runtime, "frameCommandBacklog")).isEmpty());
            assertFixedStageAndIngressReferencesCleared(runtime, 255);

            assertLoopCountOverflow(owner.submit(() -> runtime.advance(0.0D)));
            assertSame(before, runtime.latestSnapshot(),
                    "retrying a failed captured prefix must not publish or drain either prefix or suffix");
            assertEquals(ownerBeforeFailure, ownerFingerprint(runtime));
            assertEquals(expectedQueue, incomingCommands(runtime));
            assertTrue(((List<?>) fieldValue(runtime, "commandBacklog")).isEmpty());
            assertTrue(((List<?>) fieldValue(runtime, "frameCommandBacklog")).isEmpty());
            assertFixedStageAndIngressReferencesCleared(runtime, 256);
        } finally {
            if (incoming != null) {
                incoming.releaseFirstCapture();
            }
            shutdownOwner(owner);
        }
    }

    @Test
    void capturedIngressOverflowBeforeCommitLeavesConcurrentTicketPendingThroughRetry() throws Exception {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CommitBarrierQueue incoming = null;
        try {
            AnimationV2EvaluationSnapshot maximum = awaitFrame(owner.submit(() -> {
                runtime.advance(Math.nextDown(1.0D));
                return runtime.advance(duration * 1_023.0D);
            }));
            assertEquals(Long.MAX_VALUE, maximum.observerTraversal().controller(CONTROLLER)
                    .continuity().terminalAnchor().occurrence());

            incoming = installCommitBarrierQueue(runtime);
            AnimationV2Command ignored = new AnimationV2Command(UNKNOWN_CONTROLLER, IGNORED, 0L, 0.0D, 1.0D);
            enqueueCopies(runtime, ignored, AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
            AnimationV2Command wrappedRejected = new AnimationV2Command(UNKNOWN_CONTROLLER, IGNORED, 1L, 0.0D, 1.0D);
            AnimationV2Command concurrentRejected = new AnimationV2Command(
                    SECOND_UNKNOWN_CONTROLLER, IGNORED, 2L, 0.0D, 1.0D);
            AnimationV2Command failing = new AnimationV2Command(CONTROLLER, LOOP, 1L, duration / 2.0D, 1.0D);

            assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.enqueue(wrappedRejected));
            assertSame(wrappedRejected, currentOverflowCommand(runtime));

            Future<AnimationV2EvaluationSnapshot> capturedFrame = owner.submit(() -> runtime.advance(0.0D));
            incoming.awaitFirstCommitPoll();
            assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.enqueue(concurrentRejected));
            assertSame(concurrentRejected, currentOverflowCommand(runtime));
            incoming.releaseFirstCommitPoll();

            AnimationV2EvaluationSnapshot first = awaitFrame(capturedFrame);
            assertEquals(1L, first.diagnostics().stream().filter(value -> value.code()
                    == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW).count());
            AnimationV2Diagnostic firstOverflow = first.diagnostics().stream().filter(value -> value.code()
                    == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW).findFirst().orElseThrow();
            assertEquals(UNKNOWN_CONTROLLER, firstOverflow.controllerId());
            assertTrue(incomingCommands(runtime).isEmpty());
            assertSame(first, runtime.latestSnapshot());
            assertSame(concurrentRejected, currentOverflowCommand(runtime),
                    "the producer suffix belongs to the next owner frame");
            assertFixedStageAndIngressReferencesCleared(runtime, AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);

            OwnerFingerprint beforeRetryFailure = ownerFingerprint(runtime);
            assertLoopCountOverflow(owner.submit(() -> runtime.advanceAtFrame(0.0D, List.of(failing))));
            assertEquals(beforeRetryFailure, ownerFingerprint(runtime));
            assertSame(first, runtime.latestSnapshot());
            assertFixedStageAndIngressReferencesCleared(runtime, 0);

            AnimationV2EvaluationSnapshot retry = awaitFrame(owner.submit(() -> runtime.advance(0.0D)));
            assertEquals(1L, retry.diagnostics().stream().filter(value -> value.code()
                    == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW).count());
            AnimationV2Diagnostic retryOverflow = retry.diagnostics().stream().filter(value -> value.code()
                    == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW).findFirst().orElseThrow();
            assertEquals(SECOND_UNKNOWN_CONTROLLER, retryOverflow.controllerId());
            assertTrue(incomingCommands(runtime).isEmpty());
            assertSame(retry, runtime.latestSnapshot());
            assertNull(currentOverflowCommand(runtime));

            AnimationV2EvaluationSnapshot following = awaitFrame(owner.submit(() -> runtime.advance(0.0D)));
            assertEquals(0L, following.diagnostics().stream().filter(value -> value.code()
                    == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW).count());
            assertSame(following, runtime.latestSnapshot());
        } finally {
            if (incoming != null) {
                incoming.releaseFirstCommitPoll();
            }
            shutdownOwner(owner);
        }
    }

    @Test
    void exactTwoToTheSixtyFourOverflowAbaKeepsThePostCaptureTicketAcrossSameReferenceAndDistinctSuffixRetry() throws Exception {
        LoopCounterFixture fixture = loopCountersAtLongMaximum();
        AnimationV2InstanceRuntime runtime = fixture.runtime();
        AnimationV2Command queued = new AnimationV2Command(UNKNOWN_CONTROLLER, IGNORED, 0L, 0.0D, 1.0D);
        enqueueCopies(runtime, queued, AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);

        AnimationV2Command capturedPrefix = new AnimationV2Command(UNKNOWN_CONTROLLER, IGNORED, 1L, 0.0D, 1.0D);
        AnimationV2Command repeatedSuffix = new AnimationV2Command(UNKNOWN_CONTROLLER, IGNORED, 2L, 0.0D, 1.0D);
        AnimationV2Command distinctSuffix = new AnimationV2Command(
                SECOND_UNKNOWN_CONTROLLER, IGNORED, 3L, 0.0D, 1.0D);
        assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.enqueue(capturedPrefix));

        Object captured = invokeCaptureIngressOverflow(runtime);
        assertTrue((boolean) recordAccessor(captured, "pending"));
        accelerateOnlyTheFirstTwoToTheSixtyFourMinusThreeRejections(runtime, capturedPrefix, captured);

        // These three offers are real public rejections. The final distinct suffix completes the exact modular cycle
        // in the legacy generation implementation; the two repeated offers prove command reference equality is not
        // a safe acknowledgement identity.
        assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.enqueue(repeatedSuffix));
        Object firstRepeatedTicket = currentOverflowTicketOrNull(runtime);
        assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.enqueue(repeatedSuffix));
        Object secondRepeatedTicket = currentOverflowTicketOrNull(runtime);
        if (firstRepeatedTicket != null) {
            assertNotSame(firstRepeatedTicket, secondRepeatedTicket,
                    "the same rejected command reference must receive a fresh private ticket");
        }
        assertEquals(AnimationV2IngressOutcome.QUEUE_OVERFLOW, runtime.enqueue(distinctSuffix));
        Object distinctSuffixTicket = currentOverflowTicketOrNull(runtime);
        if (secondRepeatedTicket != null) {
            assertNotSame(secondRepeatedTicket, distinctSuffixTicket,
                    "a distinct rejected suffix must replace the pending ticket by reference");
        }
        assertSame(distinctSuffix, currentOverflowCommand(runtime));

        invokeAcknowledgeIngressOverflow(runtime, captured);
        assertSame(distinctSuffix, currentOverflowCommand(runtime),
                "acknowledging a captured prefix must not clear the post-capture ticket");
        if (distinctSuffixTicket != null) {
            assertSame(distinctSuffixTicket, currentOverflowTicketOrNull(runtime),
                    "captured ticket acknowledgement must preserve the exact suffix ticket reference");
        }

        OwnerFingerprint beforeFailedRetry = ownerFingerprint(runtime);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runtime.advance(fixture.duration()));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertEquals(beforeFailedRetry, ownerFingerprint(runtime),
                "a failed owner frame must retain the pending overflow ticket exactly for retry");
        if (distinctSuffixTicket != null) {
            assertSame(distinctSuffixTicket, currentOverflowTicketOrNull(runtime),
                    "a failed owner frame must retain the exact suffix ticket reference for retry");
        }

        AnimationV2EvaluationSnapshot retry = runtime.advance(0.0D);
        assertEquals(1L, overflowDiagnosticCount(retry));
        AnimationV2Diagnostic diagnostic = retry.diagnostics().stream()
                .filter(value -> value.code() == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW)
                .findFirst().orElseThrow();
        assertEquals(SECOND_UNKNOWN_CONTROLLER, diagnostic.controllerId());
        assertNull(currentOverflowCommand(runtime), "the successfully committed ticket must be acknowledged once");

        AnimationV2EvaluationSnapshot following = runtime.advance(0.0D);
        assertEquals(0L, overflowDiagnosticCount(following), "continuous owner frames must not duplicate an acknowledgement");
    }

    @Test
    void deferredBacklogStillPreflightsItsNextGroupBeforeAConcurrentDirectFrameCommand()
            throws ReflectiveOperationException {
        LoopCounterFixture fixture = loopCountersAtLongMaximum();
        AnimationV2InstanceRuntime runtime = fixture.runtime();
        for (int index = 0; index < AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE; index++) {
            BlendResourceId unknownController = BlendResourceId.of("a", "deferred-" + index);
            assertEquals(AnimationV2IngressOutcome.QUEUED,
                    runtime.enqueue(new AnimationV2Command(unknownController,
                            BlendAnimationKey.of("a", "ignored"), 0L, 0.0D, 1.0D)));
        }
        AnimationV2Command backlogCommand = new AnimationV2Command(CONTROLLER, LOOP, 1L,
                fixture.duration() / 2.0D, 1.0D);
        assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(backlogCommand));
        AnimationV2EvaluationSnapshot deferred = runtime.advance(0.0D);
        assertEquals(Long.MAX_VALUE, deferred.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().occurrence());
        assertEquals(1, ((List<?>) fieldValue(runtime, "commandBacklog")).size());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.advanceAtFrame(0.0D,
                        List.of(new AnimationV2Command(CONTROLLER, LOOP, 2L, fixture.duration() / 4.0D, 1.0D))));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(deferred, runtime.latestSnapshot());
        assertExactDouble(0.0D, (double) fieldValue(controllers(runtime)[0], "currentTime"));
        assertEquals(-1L, fieldValue(controllers(runtime)[0], "sequenceWatermark"));
        assertEquals(1, ((List<?>) fieldValue(runtime, "commandBacklog")).size());
        assertEquals(0, ((List<?>) fieldValue(runtime, "frameCommandBacklog")).size());
    }

    @Test
    void longMaximumObserverOccurrenceRejectsFrameRevocationBeforeControllerMutation() {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);
        runtime.advanceAtFrame(0.0D, List.of(new AnimationV2Command(CONTROLLER, LOOP, 0L, 0.0D, 1.0D)));
        runtime.advance(Math.nextDown(1.0D));
        AnimationV2EvaluationSnapshot before = runtime.advance(duration * 1_022.0D);
        assertEquals(Long.MAX_VALUE, before.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().occurrence());
        assertEquals(0L, before.playheads().get(CONTROLLER).acceptedSequence());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.advanceAtFrame(0.0D, List.of(),
                        List.of(new AnimationV2SequenceRejection(CONTROLLER, 1L))));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(before, runtime.latestSnapshot());

        AnimationV2EvaluationSnapshot retry = runtime.advance(0.0D);
        assertExactDouble(0.0D, retry.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(0L, retry.playheads().get(CONTROLLER).acceptedSequence());
    }

    @Test
    void minimumAndNextSubnormalDurationsKeepExactLoopIdentity() {
        AnimationV2InstanceRuntime minimum = loopRuntime(Double.MIN_VALUE, 1.0D);
        AnimationV2EvaluationSnapshot minimumSnapshot = minimum.advance(Double.MIN_VALUE);
        assertExactDouble(0.0D, minimumSnapshot.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(1L, minimumSnapshot.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().loopEpoch());

        double nextSubnormal = Math.nextUp(Double.MIN_VALUE);
        AnimationV2InstanceRuntime next = loopRuntime(nextSubnormal, 1.0D);
        AnimationV2EvaluationSnapshot first = next.advance(Double.MIN_VALUE);
        assertExactDouble(Double.MIN_VALUE, first.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(0L, first.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().loopEpoch());
        AnimationV2EvaluationSnapshot second = next.advance(Double.MIN_VALUE);
        assertExactDouble(0.0D, second.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(1L, second.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().loopEpoch());
    }

    @Test
    void automaticNextIntoAnOverflowingTinyLoopFailsBeforeTransitionMutation() {
        AnimationV2InstanceRuntime runtime = automaticNextOverflowRuntime(Math.scalb(1.0D, -63));
        AnimationV2EvaluationSnapshot before = runtime.advance(0.0D);
        assertEquals(PRIOR, before.playheads().get(CONTROLLER).state());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runtime.advance(1.25D));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(before, runtime.latestSnapshot());

        AnimationV2EvaluationSnapshot retry = runtime.advance(0.0D);
        assertEquals(PRIOR, retry.playheads().get(CONTROLLER).state());
        assertEquals(null, retry.playheads().get(CONTROLLER).previousState());
        assertExactDouble(0.0D, retry.playheads().get(CONTROLLER).timeSeconds());
    }

    @Test
    void laterControllerOverflowDoesNotAdvanceAnEarlierController() {
        AnimationV2InstanceRuntime runtime = twoControllerRuntime();
        AnimationV2EvaluationSnapshot before = runtime.advance(0.0D);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runtime.advance(1.0D));
        assertEquals("v2 controller loop count overflow", failure.getMessage());
        assertSame(before, runtime.latestSnapshot());

        AnimationV2EvaluationSnapshot retry = runtime.advance(0.125D);
        assertExactDouble(0.125D, retry.playheads().get(CONTROLLER).timeSeconds());
        assertEquals(0L, retry.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor().loopEpoch());
        assertEquals(1L << 60, retry.observerTraversal().controller(SECOND_CONTROLLER)
                .continuity().terminalAnchor().loopEpoch());
    }

    @Test
    void boundedExactRationalSweepHasNoFiniteLongMismatchOrOverflowAcceptance() {
        List<Double> durations = List.of(
                0.25D,
                1.0D / 3.0D,
                Math.nextDown(Math.scalb(1.0D, -53)),
                Math.nextUp(Math.scalb(1.0D, -53)),
                Math.scalb(1.0D, -63),
                Math.nextUp(Math.scalb(1.0D, -63)));
        List<Double> deltas = List.of(
                0.0D,
                Math.nextDown(1.0D),
                1.0D,
                Math.nextUp(1.0D),
                AnimationV2Limits.MAX_ADVANCE_SECONDS);
        List<Double> speeds = List.of(
                AnimationV2Limits.MIN_PLAYBACK_SPEED,
                1.0D,
                AnimationV2Limits.MAX_PLAYBACK_SPEED);

        int cases = 0;
        int finiteLongMismatches = 0;
        int overflowAcceptances = 0;
        for (double duration : durations) {
            for (double start : List.of(0.0D, duration / 2.0D, Math.nextDown(duration))) {
                for (double speed : speeds) {
                    for (double delta : deltas) {
                        cases++;
                        double terminalInput = start + delta * speed;
                        BigInteger expected = exactFloorQuotient(terminalInput, duration);
                        AnimationV2InstanceRuntime runtime = loopRuntime(duration, speed);
                        if (start > 0.0D) {
                            AnimationV2EvaluationSnapshot started = runtime.advance(start / speed);
                            assertExactDouble(start, started.playheads().get(CONTROLLER).timeSeconds());
                        }
                        if (expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                            try {
                                AnimationV2EvaluationSnapshot snapshot = runtime.advance(delta);
                                AnimationV2ObserverTraversal.Anchor terminal = snapshot.observerTraversal()
                                        .controller(CONTROLLER).continuity().terminalAnchor();
                                long exact = expected.longValueExact();
                                if (terminal.loopEpoch() != exact || terminal.occurrence() != exact
                                        || Double.doubleToRawLongBits(snapshot.playheads().get(CONTROLLER).timeSeconds())
                                        != Double.doubleToRawLongBits(terminalInput % duration)) {
                                    finiteLongMismatches++;
                                }
                            } catch (IllegalStateException unexpected) {
                                finiteLongMismatches++;
                            }
                        } else {
                            try {
                                runtime.advance(delta);
                                overflowAcceptances++;
                            } catch (IllegalStateException expectedFailure) {
                                assertEquals("v2 controller loop count overflow", expectedFailure.getMessage());
                            }
                        }
                    }
                }
            }
        }

        System.out.println("EXACT_WRAP_SWEEP cases=" + cases
                + " finiteLongMismatches=" + finiteLongMismatches
                + " overflowAcceptances=" + overflowAcceptances);
        assertEquals(270, cases, "the exact-rational sweep is deliberately bounded");
        assertEquals(0, finiteLongMismatches, "every exact count representable by long must publish exactly");
        assertEquals(0, overflowAcceptances, "an unrepresentable exact count must fail before publication");
    }

    @Test
    void ordinaryAndHugeLoopAdvancesRemainBoundedWithTheSegmentCap() {
        AnimationV2EvaluationSnapshot ordinary = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> loopRuntime(0.25D, 1.0D).advance(0.125D));
        assertExactDouble(0.125D, ordinary.playheads().get(CONTROLLER).timeSeconds());

        AnimationV2EvaluationSnapshot huge = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> loopRuntime(Math.scalb(1.0D, -20), 1.0D)
                        .advance(AnimationV2Limits.MAX_ADVANCE_SECONDS));
        AnimationV2ObserverTraversal.ControllerTraversal traversal = huge.observerTraversal().controller(CONTROLLER);
        assertTrue(traversal.truncated());
        assertTrue(traversal.segments().isEmpty());
        assertEquals(629_145_600L, traversal.continuity().terminalAnchor().loopEpoch());
    }

    private static AnimationV2InstanceRuntime loopRuntime(double duration, double speed) {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerState loop = state(LOOP, AnimationV2PlaybackMode.LOOP, speed, 0.0D, layer, duration);
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(layer), LOOP, Map.of(LOOP, loop)))));
    }

    private static AnimationV2InstanceRuntime transitioningOverflowRuntime(double duration) {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerState prior = state(PRIOR, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D, layer, 1.0D);
        AnimationV2ControllerState loop = state(LOOP, AnimationV2PlaybackMode.LOOP, 1.0D, 10.0D, layer, duration);
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(layer), PRIOR,
                        Map.of(PRIOR, prior, LOOP, loop)))));
    }

    private static AnimationV2InstanceRuntime automaticNextOverflowRuntime(double duration) {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerState prior = state(PRIOR, AnimationV2PlaybackMode.ONCE, 1.0D, 0.0D,
                LOOP, layer, 0.25D);
        AnimationV2ControllerState loop = state(LOOP, AnimationV2PlaybackMode.LOOP, 1.0D, 10.0D,
                null, layer, duration);
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(layer), PRIOR,
                        Map.of(PRIOR, prior, LOOP, loop)))));
    }

    private static AnimationV2InstanceRuntime twoControllerRuntime() {
        BoneSchema schema = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
        AnimationV2LayerDefinition firstLayer = new AnimationV2LayerDefinition(
                LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2LayerDefinition secondLayer = new AnimationV2LayerDefinition(
                SECOND_LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerState firstLoop = state(LOOP, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D,
                firstLayer, 0.25D);
        AnimationV2ControllerState secondLoop = state(LOOP, AnimationV2PlaybackMode.LOOP, 1.0D, 0.0D,
                secondLayer, Math.scalb(1.0D, -63));
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(
                new AnimationV2ControllerDefinition(CONTROLLER, 0, List.of(firstLayer), LOOP,
                        Map.of(LOOP, firstLoop)),
                new AnimationV2ControllerDefinition(SECOND_CONTROLLER, 1, List.of(secondLayer), LOOP,
                        Map.of(LOOP, secondLoop)))));
    }

    private static LoopCounterFixture loopCountersAtLongMaximum() {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);
        runtime.advance(Math.nextDown(1.0D));
        AnimationV2EvaluationSnapshot maximum = runtime.advance(duration * 1_023.0D);
        AnimationV2ObserverTraversal.Anchor terminal = maximum.observerTraversal().controller(CONTROLLER)
                .continuity().terminalAnchor();
        assertEquals(Long.MAX_VALUE, terminal.loopEpoch());
        assertEquals(Long.MAX_VALUE, terminal.occurrence());
        return new LoopCounterFixture(runtime, duration);
    }

    private static LoopCounterFixture loopCountersAtPenultimateOccurrence() {
        double duration = Math.scalb(1.0D, -63);
        AnimationV2InstanceRuntime runtime = loopRuntime(duration, 1.0D);
        runtime.advance(Math.nextDown(1.0D));
        AnimationV2EvaluationSnapshot penultimate = runtime.advance(duration * 1_022.0D);
        AnimationV2ObserverTraversal.Anchor terminal = penultimate.observerTraversal()
                .controller(CONTROLLER).continuity().terminalAnchor();
        assertEquals(Long.MAX_VALUE - 1L, terminal.loopEpoch());
        assertEquals(Long.MAX_VALUE - 1L, terminal.occurrence());
        assertExactDouble(0.0D, penultimate.playheads().get(CONTROLLER).timeSeconds());
        return new LoopCounterFixture(runtime, duration);
    }

    private static List<AnimationV2Command> distinctQueuedGroups(int groupCount) {
        ArrayList<AnimationV2Command> commands = new ArrayList<>(groupCount);
        for (int sequence = 1; sequence <= groupCount; sequence++) {
            commands.add(new AnimationV2Command(
                    CONTROLLER, LOOP, sequence, sequence / 512.0D, 1.0D));
        }
        return List.copyOf(commands);
    }

    private static List<AnimationV2Command> repeatedCommands(AnimationV2Command command, int count) {
        ArrayList<AnimationV2Command> commands = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            commands.add(command);
        }
        return List.copyOf(commands);
    }

    private static void enqueueAll(AnimationV2InstanceRuntime runtime, List<AnimationV2Command> commands) {
        for (AnimationV2Command command : commands) {
            assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(command));
        }
    }

    private static void enqueueCopies(AnimationV2InstanceRuntime runtime, AnimationV2Command command, int count) {
        for (int index = 0; index < count; index++) {
            assertEquals(AnimationV2IngressOutcome.QUEUED, runtime.enqueue(command));
        }
    }

    private static CaptureBarrierQueue installCaptureBarrierQueue(AnimationV2InstanceRuntime runtime)
            throws ReflectiveOperationException {
        Field incoming = AnimationV2InstanceRuntime.class.getDeclaredField("incoming");
        incoming.setAccessible(true);
        assertTrue(((Collection<?>) incoming.get(runtime)).isEmpty(),
                "the controlled ingress replacement requires an empty callback queue");
        CaptureBarrierQueue replacement = new CaptureBarrierQueue(AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
        incoming.set(runtime, replacement);
        assertSame(replacement, fieldValue(runtime, "incoming"));
        return replacement;
    }

    private static CommitBarrierQueue installCommitBarrierQueue(AnimationV2InstanceRuntime runtime)
            throws ReflectiveOperationException {
        Field incoming = AnimationV2InstanceRuntime.class.getDeclaredField("incoming");
        incoming.setAccessible(true);
        assertTrue(((Collection<?>) incoming.get(runtime)).isEmpty(),
                "the controlled ingress replacement requires an empty callback queue");
        CommitBarrierQueue replacement = new CommitBarrierQueue(AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
        incoming.set(runtime, replacement);
        assertSame(replacement, fieldValue(runtime, "incoming"));
        return replacement;
    }

    private static Object invokeCaptureIngressOverflow(AnimationV2InstanceRuntime runtime) throws Exception {
        Method capture = AnimationV2InstanceRuntime.class.getDeclaredMethod("captureIngressOverflow");
        capture.setAccessible(true);
        return capture.invoke(runtime);
    }

    private static void invokeAcknowledgeIngressOverflow(AnimationV2InstanceRuntime runtime, Object captured)
            throws Exception {
        Method acknowledge = AnimationV2InstanceRuntime.class.getDeclaredMethod(
                "acknowledgeIngressOverflow", captured.getClass());
        acknowledge.setAccessible(true);
        acknowledge.invoke(runtime, captured);
    }

    private static void accelerateOnlyTheFirstTwoToTheSixtyFourMinusThreeRejections(
            AnimationV2InstanceRuntime runtime, AnimationV2Command capturedPrefix, Object captured) throws Exception {
        try {
            AtomicLong generation = (AtomicLong) fieldValue(runtime, "ingressOverflowGeneration");
            long capturedGeneration = (long) recordAccessor(captured, "generation");
            // The old counter is modulo 2^64. Reflection accelerates only the first 2^64 - 3 rejected offers;
            // the three suffix offers above remain ordinary public enqueue calls.
            generation.set(capturedGeneration - 3L);
            ((AtomicReference<AnimationV2Command>) fieldValue(runtime, "lastIngressOverflow")).set(capturedPrefix);
            ((AtomicBoolean) fieldValue(runtime, "ingressOverflowed")).set(true);
        } catch (NoSuchFieldException ignored) {
            // The repaired bounded handshake intentionally has no finite generation counter to accelerate.
        }
    }

    private static Object currentOverflowCommand(AnimationV2InstanceRuntime runtime) throws Exception {
        try {
            return ((AtomicReference<?>) fieldValue(runtime, "lastIngressOverflow")).get();
        } catch (NoSuchFieldException ignored) {
            Object ticket = ((AtomicReference<?>) fieldValue(runtime, "ingressOverflowTicket")).get();
            return ticket == null ? null : fieldValue(ticket, "command");
        }
    }

    private static Object currentOverflowTicketOrNull(AnimationV2InstanceRuntime runtime) throws Exception {
        try {
            return ((AtomicReference<?>) fieldValue(runtime, "ingressOverflowTicket")).get();
        } catch (NoSuchFieldException ignored) {
            // The r10 replay deliberately has no ticket field; its valid RED remains the public missing diagnostic.
            return null;
        }
    }

    private static long overflowDiagnosticCount(AnimationV2EvaluationSnapshot snapshot) {
        return snapshot.diagnostics().stream().filter(value -> value.code()
                == AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW).count();
    }

    private static Object recordAccessor(Object value, String name) throws Exception {
        Method accessor = value.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(value);
    }

    private static AnimationV2EvaluationSnapshot awaitFrame(Future<AnimationV2EvaluationSnapshot> frame)
            throws Exception {
        return frame.get(5L, TimeUnit.SECONDS);
    }

    private static void assertLoopCountOverflow(Future<?> frame) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> frame.get(5L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("v2 controller loop count overflow", failure.getCause().getMessage());
    }

    private static void shutdownOwner(ExecutorService owner) throws InterruptedException {
        owner.shutdownNow();
        assertTrue(owner.awaitTermination(5L, TimeUnit.SECONDS), "the controlled owner thread did not terminate");
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double speed,
            double transitionSeconds,
            AnimationV2LayerDefinition layer,
            double duration) {
        return state(key, mode, speed, transitionSeconds, null, layer, duration);
    }

    private static AnimationV2ControllerState state(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double speed,
            double transitionSeconds,
            BlendAnimationKey next,
            AnimationV2LayerDefinition layer,
            double duration) {
        AnimationV2Clip clip = new AnimationV2Clip(List.of(
                new AnimationV2Keyframe(0.0D, new AnimationV2Pose(List.of(Transform.IDENTITY))),
                new AnimationV2Keyframe(duration, new AnimationV2Pose(List.of(Transform.IDENTITY)))));
        return new AnimationV2ControllerState(key, mode, speed, transitionSeconds, next, Map.of(layer.id(), clip));
    }

    private static BigInteger exactFloorQuotient(double numeratorValue, double denominatorValue) {
        BinaryInteger numerator = binaryInteger(numeratorValue);
        BinaryInteger denominator = binaryInteger(denominatorValue);
        int shift = numerator.exponent() - denominator.exponent();
        BigInteger scaledNumerator = numerator.significand();
        BigInteger scaledDenominator = denominator.significand();
        if (shift >= 0) {
            scaledNumerator = scaledNumerator.shiftLeft(shift);
        } else {
            scaledDenominator = scaledDenominator.shiftLeft(-shift);
        }
        return scaledNumerator.divide(scaledDenominator);
    }

    private static BinaryInteger binaryInteger(double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException("test oracle accepts only finite non-negative doubles");
        }
        long bits = Double.doubleToRawLongBits(value);
        int rawExponent = (int) ((bits >>> 52) & 0x7ffL);
        long fraction = bits & ((1L << 52) - 1L);
        if (rawExponent == 0) {
            return new BinaryInteger(BigInteger.valueOf(fraction), -1074);
        }
        return new BinaryInteger(BigInteger.valueOf((1L << 52) | fraction), rawExponent - 1023 - 52);
    }

    private static void assertExactDouble(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                () -> "expected " + Double.toHexString(expected) + " but was " + Double.toHexString(actual));
    }

    private static void assertQueuedGroupsRemainUntouched(
            AnimationV2InstanceRuntime runtime,
            OwnerFingerprint expectedOwner,
            AnimationV2EvaluationSnapshot expectedLatest,
            List<AnimationV2Command> expectedQueuedGroups,
            long expectedOccurrence) throws ReflectiveOperationException {
        assertEquals(expectedOwner, ownerFingerprint(runtime),
                "the second queued group must not leak the first group's staged controller or queue mutations");
        assertSame(expectedLatest, runtime.latestSnapshot(), "a failed staged frame must not publish a new snapshot");
        Object[] controllers = controllers(runtime);
        Object controller = controllers[0];
        assertExactDouble(0.0D, (double) fieldValue(controller, "currentTime"));
        assertEquals(-1L, fieldValue(controller, "sequenceWatermark"));
        assertEquals(expectedOccurrence, fieldValue(controller, "observerOccurrence"));
        assertEquals(expectedQueuedGroups, incomingCommands(runtime));
        assertTrue(((List<?>) fieldValue(runtime, "commandBacklog")).isEmpty());
        assertTrue(((List<?>) fieldValue(runtime, "frameCommandBacklog")).isEmpty());
    }

    private static Object[] controllers(AnimationV2InstanceRuntime runtime) throws ReflectiveOperationException {
        return (Object[]) fieldValue(runtime, "controllersInEvaluationOrder");
    }

    private static void assertFixedStageAndIngressReferencesCleared(
            AnimationV2InstanceRuntime runtime, int usedIngressSlots) throws ReflectiveOperationException {
        Object[] ingressPreflightSnapshot = (Object[]) fieldValue(runtime, "ingressPreflightSnapshot");
        Object ingressCapture = fieldValue(runtime, "ingressCapture");
        assertSame(ingressPreflightSnapshot, fieldValue(ingressCapture, "target"),
                "the fixed ingress consumer must write directly into the fixed preflight snapshot");
        assertEquals(usedIngressSlots, fieldValue(ingressCapture, "count"),
                "the reusable ingress consumer must have captured the tested queue prefix");
        assertFalse((boolean) fieldValue(ingressCapture, "overflowed"));
        for (int index = 0; index < usedIngressSlots; index++) {
            int slot = index;
            assertNull(ingressPreflightSnapshot[index],
                    () -> "ingressPreflightSnapshot retained command slot " + slot);
        }

        Object stage = fieldValue(runtime, "frameStage");
        assertEquals("FrameStage", stage.getClass().getSimpleName(),
                "the runtime must retain the fixed owner-only FrameStage rather than an obsolete preflight scratch");
        assertTrue(((List<?>) fieldValue(stage, "commandBacklog")).isEmpty(),
                "FrameStage command backlog retained a command reference");
        assertTrue(((List<?>) fieldValue(stage, "frameCommandBacklog")).isEmpty(),
                "FrameStage frame-command backlog retained a command reference");
        for (Object stagedController : (Object[]) fieldValue(stage, "controllersInEvaluationOrder")) {
            assertNull(fieldValue(stagedController, "acceptedCommand"),
                    "FrameStage controller retained a command reference");
            assertNull(fieldValue(stagedController, "previous"),
                    "FrameStage controller retained a transition-state reference");
            assertNull(fieldValue(stagedController, "previousClips"),
                    "FrameStage controller retained transition clips");
            assertNull(fieldValue(stagedController, "frozenPreviousPoses"),
                    "FrameStage controller retained frozen presentation");
            assertNull(fieldValue(stagedController, "transitionSourceState"),
                    "FrameStage controller retained a transition source");
            assertTrue(((List<?>) fieldValue(stagedController, "observerSegments")).isEmpty(),
                    "FrameStage controller retained observer segments");
            assertTrue(((List<?>) fieldValue(stagedController, "observerContinuityPublications")).isEmpty(),
                    "FrameStage controller retained observer publications");
            assertNull(fieldValue(stagedController, "observerContinuityInitial"),
                    "FrameStage controller retained an observer anchor");
            assertNull(fieldValue(stagedController, "observerContinuityTerminal"),
                    "FrameStage controller retained an observer anchor");
            for (Object next : (Object[]) fieldValue(stagedController, "nextVisited")) {
                assertNull(next, "FrameStage controller retained a next-chain key");
            }
        }
    }

    private static OwnerFingerprint ownerFingerprint(AnimationV2InstanceRuntime runtime)
            throws ReflectiveOperationException {
        Object controller = controllers(runtime)[0];
        return new OwnerFingerprint(
                (long) fieldValue(runtime, "revision"),
                runtime.latestSnapshot(),
                incomingCommands(runtime),
                copyListField(runtime, "commandBacklog"),
                copyListField(runtime, "frameCommandBacklog"),
                overflowStateFingerprint(runtime),
                new ControllerFingerprint(
                        fieldValue(controller, "current"),
                        fieldValue(controller, "currentClips"),
                        fieldValue(controller, "previous"),
                        fieldValue(controller, "previousClips"),
                        fieldValue(controller, "frozenPreviousPoses"),
                        fieldValue(controller, "transitionSourceState"),
                        fieldValue(controller, "currentTime"),
                        fieldValue(controller, "previousTime"),
                        fieldValue(controller, "playbackSpeed"),
                        fieldValue(controller, "previousPlaybackSpeed"),
                        fieldValue(controller, "transitionElapsed"),
                        fieldValue(controller, "transitionDuration"),
                        fieldValue(controller, "sequenceWatermark"),
                        fieldValue(controller, "acceptedCommand"),
                        fieldValue(controller, "observerLoopEpoch"),
                        fieldValue(controller, "observerOccurrence"),
                        copyListField(controller, "observerSegments"),
                        copyListField(controller, "observerContinuityPublications"),
                        fieldValue(controller, "observerContinuityInitial"),
                        fieldValue(controller, "observerContinuityTerminal"),
                        fieldValue(controller, "observerDiscontinuity"),
                        fieldValue(controller, "observerTraversalTruncated")));
    }

    @SuppressWarnings("unchecked")
    private static List<AnimationV2Command> incomingCommands(AnimationV2InstanceRuntime runtime)
            throws ReflectiveOperationException {
        return List.copyOf((Collection<AnimationV2Command>) fieldValue(runtime, "incoming"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> copyListField(Object instance, String name) throws ReflectiveOperationException {
        return List.copyOf((List<Object>) fieldValue(instance, name));
    }

    private static Object fieldValue(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object overflowStateFingerprint(AnimationV2InstanceRuntime runtime)
            throws ReflectiveOperationException {
        try {
            return new TicketOverflowFingerprint(((AtomicReference<?>) fieldValue(runtime, "ingressOverflowTicket")).get());
        } catch (NoSuchFieldException ignored) {
            // The exact-2^64 test is intentionally replayable against the r10 generation implementation. Its owner
            // fingerprint must observe the old state rather than failing before the expected missing diagnostic.
            return new LegacyOverflowFingerprint(
                    ((AtomicBoolean) fieldValue(runtime, "ingressOverflowed")).get(),
                    ((AtomicReference<?>) fieldValue(runtime, "lastIngressOverflow")).get(),
                    ((AtomicLong) fieldValue(runtime, "ingressOverflowGeneration")).get(),
                    (long) fieldValue(runtime, "consumedIngressOverflowGeneration"));
        }
    }

    /** Test-only queue barrier: the suffix is admitted only after {@link ArrayBlockingQueue#forEach} captured its prefix. */
    private static final class CaptureBarrierQueue extends ArrayBlockingQueue<AnimationV2Command> {
        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch firstCaptureFinished = new CountDownLatch(1);
        private final transient CountDownLatch releaseFirstCapture = new CountDownLatch(1);
        private boolean pauseAfterFirstCapture = true;

        private CaptureBarrierQueue(int capacity) {
            super(capacity);
        }

        @Override
        public void forEach(Consumer<? super AnimationV2Command> action) {
            super.forEach(action);
            if (!pauseAfterFirstCapture) {
                return;
            }
            pauseAfterFirstCapture = false;
            firstCaptureFinished.countDown();
            try {
                if (!releaseFirstCapture.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release the controlled captured ingress prefix");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("controlled captured ingress prefix was interrupted", exception);
            }
        }

        private void awaitFirstCapture() throws InterruptedException {
            if (!firstCaptureFinished.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("the owner did not capture the controlled ingress prefix");
            }
        }

        private void releaseFirstCapture() {
            releaseFirstCapture.countDown();
        }
    }

    /** Test-only queue barrier: pauses immediately before the owner removes its first already-captured ingress item. */
    private static final class CommitBarrierQueue extends ArrayBlockingQueue<AnimationV2Command> {
        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch firstCommitPoll = new CountDownLatch(1);
        private final transient CountDownLatch releaseFirstCommitPoll = new CountDownLatch(1);
        private boolean pauseBeforeFirstPoll = true;

        private CommitBarrierQueue(int capacity) {
            super(capacity);
        }

        @Override
        public AnimationV2Command poll() {
            if (pauseBeforeFirstPoll) {
                pauseBeforeFirstPoll = false;
                firstCommitPoll.countDown();
                try {
                    if (!releaseFirstCommitPoll.await(5L, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release the controlled ingress commit");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("controlled ingress commit was interrupted", exception);
                }
            }
            return super.poll();
        }

        private void awaitFirstCommitPoll() throws InterruptedException {
            if (!firstCommitPoll.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("the owner did not reach the controlled ingress commit");
            }
        }

        private void releaseFirstCommitPoll() {
            releaseFirstCommitPoll.countDown();
        }
    }

    private record OwnerFingerprint(
            long revision,
            AnimationV2EvaluationSnapshot latest,
            List<AnimationV2Command> incoming,
            List<Object> commandBacklog,
            List<Object> frameCommandBacklog,
            Object ingressOverflowState,
            ControllerFingerprint controller) {
    }

    private static final class TicketOverflowFingerprint {
        private final Object ticket;

        private TicketOverflowFingerprint(Object ticket) {
            this.ticket = ticket;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TicketOverflowFingerprint value && ticket == value.ticket;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ticket);
        }
    }

    private record LegacyOverflowFingerprint(
            boolean overflowed, Object command, long generation, long consumedGeneration) {
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

    private record BinaryInteger(BigInteger significand, int exponent) {
    }

    private record LoopCounterFixture(AnimationV2InstanceRuntime runtime, double duration) {
    }
}
