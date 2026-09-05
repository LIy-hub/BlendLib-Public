package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.spi.experimental.ProviderLifecycleState;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

/**
 * Compatibility probes for the X1 1.1 every-Error terminal rule at both X4 selector seams.
 *
 * <p>The package-private membership below is deliberately the narrow registry-owner seam. The
 * public builder, X1 generation session, prepared pin, and submit path are all real production
 * objects; only the registry's otherwise-uninjectable revoke callback is made deterministic.</p>
 */
class X4X6ErrorPolicyCompatibilityTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_x6_r12:models/host");
    private static final BlendAnimationKey ANIMATION = BlendAnimationKey.parse("x4_x6_r12:idle");
    private static final int TERMINAL_ORDER_ROUNDS = 12;
    private static final long TERMINAL_ORDER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final long TERMINAL_ORDER_OBSERVATION_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    @Test
    void everyErrorMatrixRetainsExactFirstErrorAndSuppressionEvidence() {
        for (FailureKind primaryKind : FailureKind.values()) {
            for (FailureKind cleanupKind : FailureKind.values()) {
                Throwable primary = primaryKind.create("primary-" + cleanupKind);
                Throwable cleanup = cleanupKind.create("cleanup-" + primaryKind);
                Throwable selected = X4HostFailureSelector.select(primary, cleanup);
                Throwable expected = expected(primary, cleanup);
                assertSame(expected, selected, () -> primaryKind + " + " + cleanupKind);
                assertSuppressed(selected, selected == primary ? cleanup : primary);
            }
        }

        IllegalStateException ordinary = new IllegalStateException("ordinary-primary");
        LinkageError linkage = new LinkageError("linkage-cleanup");
        assertSame(linkage, X4HostFailureSelector.select(ordinary, linkage),
                "X1 1.1 promotes every cleanup Error over an ordinary primary");

        AssertionError assertion = new AssertionError("assertion-primary");
        OutOfMemoryError outOfMemory = new OutOfMemoryError("oom-cleanup");
        assertSame(assertion, X4HostFailureSelector.select(assertion, outOfMemory),
                "when both sides are Error objects, the first object remains terminal");
    }

    @Test
    void disabledSuppressionAndDuplicateObjectCannotReplaceTheSelectedTerminal() {
        NoSuppressionError primary = new NoSuppressionError("suppression-disabled-primary");
        LinkageError cleanup = new LinkageError("cleanup");

        assertSame(primary, X4HostFailureSelector.select(primary, cleanup));
        assertEquals(0, primary.getSuppressed().length,
                "disabled suppression is metadata-only and cannot displace the protocol-selected Error");
        assertSame(primary, X4HostFailureSelector.select(primary, primary),
                "the duplicate-object guard must preserve exact identity without self-suppression");
    }

    @Test
    void platformBridgeTreatsAssertionErrorAsTerminalAndPreservesItsIdentity() {
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter();
        adapter.register(specification(HostKind.ENTITY, "existing"));
        AssertionError callbackFailure = new AssertionError("bridge-equals");

        assertSame(callbackFailure, assertThrows(AssertionError.class,
                () -> adapter.register(specification(HostKind.ENTITY, new FatalEqualsHost(callbackFailure)))));
        assertTrue(adapter.terminal());
        assertSame(callbackFailure, adapter.terminalFailureForTesting());
        assertTrue(adapter.bindings().isEmpty(), "terminal bridge ownership must remove accepted stable bindings");
    }

    @Test
    void fullPublicChainPromotesLinkageCleanupAndRetriesFailedMembershipRevocation() throws Exception {
        IllegalStateException rendererPrimary = new IllegalStateException("ordinary-renderer-primary");
        LinkageError providerRelease = new LinkageError("first-provider-release-error");
        OutOfMemoryError membershipRevoke = new OutOfMemoryError("later-membership-revoke-error");

        FullChainResult result = runFullChain(rendererPrimary, providerRelease, membershipRevoke);
        assertSame(providerRelease, result.observed(),
                "the first X1 cleanup Error must outrank ordinary renderer failure and later revoke Error");
        assertSuppressed(result.observed(), rendererPrimary);
        assertSuppressed(result.observed(), membershipRevoke);
        assertFailedThenRetried(result, providerRelease);
    }

    @Test
    void fullPublicChainKeepsAssertionPrimaryWhenFinalPinCleanupThrowsOutOfMemory() throws Exception {
        AssertionError rendererPrimary = new AssertionError("first-renderer-error");
        OutOfMemoryError providerRelease = new OutOfMemoryError("later-provider-release-error");
        LinkageError membershipRevoke = new LinkageError("later-membership-revoke-error");

        FullChainResult result = runFullChain(rendererPrimary, providerRelease, membershipRevoke);
        assertSame(rendererPrimary, result.observed(),
                "a first renderer Error remains terminal over every later X1/X4 cleanup Error");
        assertSuppressed(result.observed(), providerRelease);
        assertFailedThenRetried(result, rendererPrimary);
    }

    @Test
    void successfulMembershipRetryBeforeSubmitSelectionPublishesOneTerminalIdentity() throws Exception {
        for (int round = 0; round < TERMINAL_ORDER_ROUNDS; round++) {
        assertTerminalOrderingRound(round);
        }
        assertNormalTerminalCallsRemainIdempotent();
        assertRetiredThenClosedConvergesWithoutSecondDrainPublication();
        assertForeignCloseWaitsForNormalSubmissionReservation();
        assertForeignRevokeBeforeProviderFailureTransfersIntoTheExactHold();
        assertOwnerInterruptionDoesNotStrandTheExactSubmittingHold();
        assertDirectPreparedCloseCarriesLateProviderFailureAcrossAnActiveForeignRevoke();
        assertDirectFinalPinFailureDoesNotFenceItsOwnPendingPin();
        assertDirectFinalPinWaitsOnlyAfterReleasingItsOwnGuardAgainstForeignPendingPin();
        assertSealingFenceDefersTheSecondFailedRevokeUntilAfterImmutableF();
        assertFatalProviderFailureKeepsThePreallocatedHoldAndDrainLive();
        assertPostSealRetryAllocationFailureReplaysImmutableF();
        assertOwnerDeferredCloseOnProviderSuccessSealsWithTheSubmittingHold();
        assertOwnerDeferredRetireOnProviderSuccessPreservesRetiredState();
        assertConcurrentSubmitHoldsKeepTheirOwnRendererIdentities();
        // R15 terminal-epoch TDD matrix.  These remain inside the established sixth public test
        // entry point so the suite keeps its deliberate six-test compatibility contract.
        assertOrdinaryRendererFailureWithoutProviderFailureRemainsLocal();
        assertPhysicalOwnerAdmitsDirectContributionBeforeAdmissionSeal();
        assertIdlePreparedSnapshotFencesFailedPhysicalTransition();
        assertDirectProviderFailureCarriesAcrossSubmissionSeal();
        assertTwoReservedSubmissionsCannotReplaceOneTerminalEpoch();
        assertCallbackReentryUsesTheExistingSnapshotOwner();
        assertInterruptedContributionCommitsBeforeInterruptRestoration();
        assertPreSealAllocationFailureBecomesPhysicalDAndRetainsReceipt();
        assertPostSealRetryKeepsFImmutableAndPublishesBoundedDrain();
        assertEveryErrorRendererWithoutProviderFailureOpensTerminalEpoch();
    }

    private static void assertTerminalOrderingRound(int round) throws Exception {
        AssertionError rendererFailure = new AssertionError("terminal-order-renderer-" + round);
        LinkageError providerReleaseFailure = new LinkageError("terminal-order-provider-release-" + round);
        OutOfMemoryError firstRevokeFailure = new OutOfMemoryError("terminal-order-first-revoke-" + round);
        long generationId = 13_000L + round;
        var generation = X4LifecycleTestSupport.published(generationId);
        generation.provider().onRetire(() -> throwUnchecked(providerReleaseFailure));

        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        X4HostIdentity identity = identity("terminal-order-" + round);
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(generationId),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        CoordinatedMembership membership = new CoordinatedMembership(9_000L + round, firstRevokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        CountDownLatch drainPublished = new CountDownLatch(1);
        adapter.drainCompletion().whenComplete((ignoredState, ignoredFailure) -> drainPublished.countDown());
        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        AtomicReference<Throwable> retryObserved = new AtomicReference<>();
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch retryFinished = new CountDownLatch(1);
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(prepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitFinished.countDown();
            }
        }, "x4-terminal-order-submit-" + round);
        Thread retryThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                retryObserved.set(failure);
            } finally {
                retryFinished.countDown();
            }
        }, "x4-terminal-order-retry-" + round);
        submitThread.setDaemon(true);
        retryThread.setDaemon(true);

        long deadlineNanos = terminalOrderDeadline();
        boolean drainPublishedBeforeSubmitSelection = false;
        boolean retryReturnedBeforeSubmitSelection = false;
        boolean submitStarted = false;
        boolean retryStarted = false;
        Throwable orchestrationFailure = null;
        try {
            synchronized (rendererFailure) {
                submitThread.start();
                submitStarted = true;
                await(membership.firstRevoke(), deadlineNanos, "submit did not reach the first membership revoke");
                awaitTerminalRetryReadiness(adapter, deadlineNanos);
                retryThread.start();
                retryStarted = true;

                drainPublishedBeforeSubmitSelection = awaitObservation(drainPublished, deadlineNanos);
                retryReturnedBeforeSubmitSelection = retryFinished.getCount() == 0L;
                assertTrue(submitThread.isAlive(), "submit must remain blocked before its terminal selection commits");
            }
        } catch (Throwable failure) {
            // Leaving A's monitor first is essential: it lets the submit worker finish before the
            // bounded cleanup below reports an orchestration failure.
            orchestrationFailure = failure;
        }

        // The synchronization gate above can consume the full orchestration budget. Once it is
        // released, give each started worker a fresh bounded cleanup window so a failed assertion
        // cannot strand a non-daemon test worker or mask the actual protocol failure.
        long cleanupDeadlineNanos = terminalOrderDeadline();
        if (retryStarted) {
            await(membership.secondRevoke(), cleanupDeadlineNanos,
                    "retry did not revoke the retained receipt after the exact submit sealed F");
        }
        awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                "submit worker did not finish");
        awaitWorkerTermination(retryThread, retryFinished, retryStarted, cleanupDeadlineNanos,
                "retry worker did not finish");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("terminal-order orchestration failed", orchestrationFailure);
        }
        assertFalse(drainPublishedBeforeSubmitSelection,
                "successful retry must not publish provisional cleanup before submit seals the terminal identity");
        assertFalse(retryReturnedBeforeSubmitSelection,
                "successful retry must wait for the owning submit token instead of replaying provisional cleanup");
        assertSame(rendererFailure, submitObserved.get(), "submit must retain its renderer Error A");
        assertSame(rendererFailure, retryObserved.get(), "retry must replay the one sealed terminal Error A");
        assertSame(rendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "drain must expose the same sealed terminal Error A"));
        assertExactlySuppressed(rendererFailure, providerReleaseFailure);
        assertExactlySuppressed(providerReleaseFailure, firstRevokeFailure);
        assertEquals(0, firstRevokeFailure.getSuppressed().length);
        assertEquals(2, membership.revokeCalls(), "the retained exact receipt may revoke only once more");
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close),
                "a third close must replay immutable F without a third revoke");
        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::retire),
                "a later retire must also replay immutable F without reopening cleanup");
        assertEquals(2, membership.revokeCalls(), "third-and-later terminal calls may not revoke a cleared receipt");
        assertEquals(ProviderLifecycleState.CLOSED, generation.session().state());
        assertEquals(0, adapter.leaseDiagnostics().activeSnapshotLeases());
        assertEquals(0, adapter.leaseDiagnostics().inFlightSubmissions());
        assertEquals(1, generation.provider().retireCalls());
        assertEquals(1, generation.provider().closeCalls());
    }

    /** Covers the nullable already-terminal return path without changing this class's six-test contract. */
    private static void assertNormalTerminalCallsRemainIdempotent() throws Exception {
        var generation = X4LifecycleTestSupport.published(14_200L);
        X4HostIdentity identity = identity("normal-terminal-idempotence");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_200L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_201L);
        reservation.commit(membership);

        adapter.close();
        adapter.close();
        adapter.retire();

        assertEquals(1, membership.revokeCalls(), "idempotent normal terminal calls must not manufacture a second revoke");
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertEquals(X4HostLifecycleState.CLOSED, awaitFutureValue(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "normal terminal drain did not complete"));
    }

    /**
     * Drain completion is one-shot, but lifecycle state is not: a later close after a successful
     * retire still needs to converge to CLOSED while retaining the already published RETIRED
     * future result.
     */
    private static void assertRetiredThenClosedConvergesWithoutSecondDrainPublication() throws Exception {
        var generation = X4LifecycleTestSupport.published(14_250L);
        X4HostIdentity identity = identity("retired-then-closed-convergence");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_250L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_251L);
        reservation.commit(membership);

        adapter.retire();
        assertEquals(X4HostLifecycleState.RETIRED, adapter.state());
        assertEquals(X4HostLifecycleState.RETIRED, awaitFutureValue(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "retire drain did not complete"));

        adapter.close();
        assertEquals(1, membership.revokeCalls(),
                "a close after a fully retired membership cannot manufacture a second revoke");
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state(),
                "the lifecycle state must converge even though drain completion was already claimed");
        assertFalse(adapter.leaseDiagnostics().retirementRequested(),
                "a post-retirement close must upgrade diagnostics to close intent rather than report both intents");
        assertTrue(adapter.leaseDiagnostics().closeRequested(),
                "a post-retirement close must be visible as the sole terminal close intent");
        assertEquals(X4HostLifecycleState.RETIRED, awaitFutureValue(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "retire drain identity did not remain stable"),
                "the original one-shot retire completion remains the future result");
    }

    /** A foreign close must wake after H releases normally; it may not remain parked on a stale reservation. */
    private static void assertForeignCloseWaitsForNormalSubmissionReservation() throws Exception {
        var generation = X4LifecycleTestSupport.published(14_300L);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerEntered.countDown();
            try {
                await(releaseProvider, terminalOrderDeadline(), "normal provider release gate was not opened");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("normal provider release gate was interrupted", exception);
            }
        });

        X4HostIdentity identity = identity("normal-reservation-foreign-close");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_300L),
                new BlendRenderer((snapshot, context) -> preparedReference.get().close()),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_301L);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        AtomicReference<Throwable> closeObserved = new AtomicReference<>();
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(prepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitFinished.countDown();
            }
        }, "x4-normal-reservation-submit");
        Thread closeThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                closeObserved.set(failure);
            } finally {
                closeFinished.countDown();
            }
        }, "x4-normal-reservation-foreign-close");
        submitThread.setDaemon(true);
        closeThread.setDaemon(true);

        boolean submitStarted = false;
        boolean closeStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            submitThread.start();
            submitStarted = true;
            await(providerEntered, deadlineNanos, "submit did not reach the normal provider release gate");
            closeThread.start();
            closeStarted = true;
            assertFalse(awaitObservation(closeFinished, deadlineNanos),
                    "foreign close must wait while H is still in the successful provider-release interval");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseProvider.countDown();
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                "normal reservation submit worker did not finish");
        awaitWorkerTermination(closeThread, closeFinished, closeStarted, cleanupDeadlineNanos,
                "foreign close waiter did not finish after normal reservation release");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("normal reservation foreign-close orchestration failed", orchestrationFailure);
        }
        assertEquals(null, submitObserved.get());
        assertEquals(null, closeObserved.get());
        assertEquals(1, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertEquals(X4HostLifecycleState.CLOSED, awaitFutureValue(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "normal reservation drain did not complete"));
    }

    /**
     * A foreign close can begin C before the submitting hold reaches provider B.  C must fence on
     * H, then become B's cleanup evidence without a duplicate revoke or diagnostics-visible P.
     */
    private static void assertForeignRevokeBeforeProviderFailureTransfersIntoTheExactHold() throws Exception {
        AssertionError rendererFailure = new AssertionError("foreign-before-provider-renderer");
        LinkageError providerFailure = new LinkageError("foreign-before-provider-B");
        OutOfMemoryError firstRevokeFailure = new OutOfMemoryError("foreign-before-provider-C");
        var generation = X4LifecycleTestSupport.published(14_400L);
        CountDownLatch providerFailureEntered = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerFailureEntered.countDown();
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("foreign-before-provider");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_400L),
                new BlendRenderer((snapshot, context) -> {
                    rendererEntered.countDown();
                    try {
                        await(releaseRenderer, terminalOrderDeadline(),
                                "foreign-before-provider renderer release was not opened");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("foreign-before-provider renderer was interrupted", exception);
                    }
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_401L, firstRevokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        AtomicReference<Throwable> closeObserved = new AtomicReference<>();
        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        CountDownLatch closeFinished = new CountDownLatch(1);
        CountDownLatch submitFinished = new CountDownLatch(1);
        Thread closeThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                closeObserved.set(failure);
            } finally {
                closeFinished.countDown();
            }
        }, "x4-foreign-before-provider-close");
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(prepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitFinished.countDown();
            }
        }, "x4-foreign-before-provider-submit");
        closeThread.setDaemon(true);
        submitThread.setDaemon(true);

        boolean closeStarted = false;
        boolean submitStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            submitThread.start();
            submitStarted = true;
            await(rendererEntered, deadlineNanos, "submit did not enter the renderer before foreign close");
            closeThread.start();
            closeStarted = true;
            await(membership.firstRevoke(), deadlineNanos, "foreign close did not start its first physical revoke");
            // Complete C while no H reservation exists yet. The adapter must fence it from
            // admission (inFlightSubmissions), keep diagnostics blank, and make this original
            // close owner await the later exact F rather than replay raw C.
            membership.releaseFirstRevoke();
            assertFalse(awaitObservation(closeFinished, deadlineNanos),
                    "a completed pre-H revoke must remain fenced until the submitting hold seals F");
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "a completed pre-H C is still provisional and may not leak through diagnostics");
            // Hold A while B arrives. That pins submit in the final selector after the exact H
            // has adopted C, so this checks that the delayed physical owner cannot escape with C
            // in the narrow post-B/pre-F interval.
            synchronized (rendererFailure) {
                releaseRenderer.countDown();
                await(providerFailureEntered, deadlineNanos,
                        "submitting hold did not reach provider B after foreign revoke");
                awaitWorkerBlocked(submitThread, deadlineNanos,
                        "submitting hold did not enter its A/F selector after adopting completed C");
                assertFalse(awaitObservation(closeFinished, deadlineNanos),
                        "foreign close must remain fenced instead of returning provisional C before H seals F");
            }
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseRenderer.countDown();
            membership.releaseFirstRevoke();
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                "foreign-before-provider submit worker did not finish");
        awaitWorkerTermination(closeThread, closeFinished, closeStarted, cleanupDeadlineNanos,
                "foreign-before-provider close worker did not finish");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("foreign-before-provider orchestration failed", orchestrationFailure);
        }
        assertSame(rendererFailure, submitObserved.get());
        assertSame(rendererFailure, closeObserved.get());
        assertExactlySuppressed(rendererFailure, providerFailure);
        assertExactlySuppressed(providerFailure, firstRevokeFailure);
        assertEquals(1, membership.revokeCalls(), "B must consume the completed foreign C instead of revoking a second time");
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertEquals(rendererFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType(),
                "submit must install the exact sealed F, never the provisional B/C chain");

        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close),
                "the later exact retry must replay the already sealed F");
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "foreign-before-provider drain did not fail"));
    }

    /**
     * An interrupt delivered while the submitting owner waits behind a foreign physical C must be
     * restored only after that owner has promoted/sealed its exact H.  It may not abandon H in
     * RESERVED or PROVISIONAL and strand the foreign observer forever.
     */
    private static void assertOwnerInterruptionDoesNotStrandTheExactSubmittingHold() throws Exception {
        AssertionError rendererFailure = new AssertionError("interrupted-owner-renderer-A");
        LinkageError providerFailure = new LinkageError("interrupted-owner-provider-B");
        OutOfMemoryError firstRevokeFailure = new OutOfMemoryError("interrupted-owner-foreign-C");
        var generation = X4LifecycleTestSupport.published(14_450L);
        CountDownLatch providerFailureEntered = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerFailureEntered.countDown();
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("interrupted-owner-reservation");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_450L),
                new BlendRenderer((snapshot, context) -> {
                    rendererEntered.countDown();
                    try {
                        await(releaseRenderer, terminalOrderDeadline(),
                                "interrupted-owner renderer release was not opened");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted-owner renderer was interrupted", exception);
                    }
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_451L, firstRevokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        AtomicReference<Throwable> closeObserved = new AtomicReference<>();
        AtomicBoolean submitInterrupted = new AtomicBoolean();
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(prepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitInterrupted.set(Thread.currentThread().isInterrupted());
                submitFinished.countDown();
            }
        }, "x4-interrupted-owner-submit");
        Thread closeThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                closeObserved.set(failure);
            } finally {
                closeFinished.countDown();
            }
        }, "x4-interrupted-owner-foreign-close");
        submitThread.setDaemon(true);
        closeThread.setDaemon(true);

        boolean submitStarted = false;
        boolean closeStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            submitThread.start();
            submitStarted = true;
            await(rendererEntered, deadlineNanos, "interrupted-owner submit did not enter renderer");
            closeThread.start();
            closeStarted = true;
            await(membership.firstRevoke(), deadlineNanos, "interrupted-owner foreign close did not begin C");
            releaseRenderer.countDown();
            await(providerFailureEntered, deadlineNanos, "interrupted-owner submit did not reach B");
            awaitWorkerWaiting(submitThread, deadlineNanos,
                    "interrupted owner never waited behind the foreign physical transition");
            submitThread.interrupt();
            assertFalse(awaitObservation(submitFinished, deadlineNanos),
                    "an interrupt must not let the submitting owner return before it resolves its exact H");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseRenderer.countDown();
            membership.releaseFirstRevoke();
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                "interrupted-owner submit worker did not finish");
        awaitWorkerTermination(closeThread, closeFinished, closeStarted, cleanupDeadlineNanos,
                "interrupted-owner foreign-close worker did not finish");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("interrupted-owner orchestration failed", orchestrationFailure);
        }
        assertTrue(submitInterrupted.get(), "the critical owner wait must restore the interrupt flag after progress");
        assertSame(rendererFailure, submitObserved.get());
        assertSame(rendererFailure, closeObserved.get(),
                "the previously foreign observer must replay sealed F, not raw C");
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close));
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
    }

    /**
     * A direct prepared.close() has no H. Its B must merge into an already-running physical C
     * before C completes, transfer its exact pending-pin guard, leave its outer finally, and only
     * then wait for the common B -> C identity. This is the active-C direction of the direct-pin
     * ownership contract, not the older C-before-B fence direction.
     */
    private static void assertDirectPreparedCloseCarriesLateProviderFailureAcrossAnActiveForeignRevoke()
            throws Exception {
        LinkageError providerFailure = new LinkageError("direct-null-listener-provider-B");
        OutOfMemoryError firstRevokeFailure = new OutOfMemoryError("direct-null-listener-foreign-C");
        var generation = X4LifecycleTestSupport.published(14_460L);
        CountDownLatch providerEntered = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerEntered.countDown();
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("direct-null-listener-late-b");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_460L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_461L, firstRevokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();

        AtomicReference<Throwable> foreignObserved = new AtomicReference<>();
        AtomicReference<Throwable> directObserved = new AtomicReference<>();
        CountDownLatch foreignFinished = new CountDownLatch(1);
        CountDownLatch directFinished = new CountDownLatch(1);
        Thread foreignClose = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                foreignObserved.set(failure);
            } finally {
                foreignFinished.countDown();
            }
        }, "x4-direct-null-listener-foreign-close");
        Thread directClose = new Thread(() -> {
            try {
                prepared.close();
            } catch (Throwable failure) {
                directObserved.set(failure);
            } finally {
                directFinished.countDown();
            }
        }, "x4-direct-null-listener-prepared-close");
        foreignClose.setDaemon(true);
        directClose.setDaemon(true);

        boolean foreignStarted = false;
        boolean directStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            foreignClose.start();
            foreignStarted = true;
            await(membership.firstRevoke(), deadlineNanos,
                    "direct-null-listener foreign close did not begin physical C");
            directClose.start();
            directStarted = true;
            await(providerEntered, deadlineNanos,
                    "direct-null-listener prepared close did not obtain provider B while C was active");
            awaitWorkerWaiting(directClose, deadlineNanos,
                    "direct-null-listener B must leave its pin finally and wait only after active C admission");
            assertFalse(awaitObservation(foreignFinished, deadlineNanos),
                    "the active foreign C must remain physically blocked until this test releases it");
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "B/C must remain unpublished while active C is held after B merged");
            membership.releaseFirstRevoke();
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            membership.releaseFirstRevoke();
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(foreignClose, foreignFinished, foreignStarted, cleanupDeadlineNanos,
                "direct-null-listener foreign close worker did not finish");
        awaitWorkerTermination(directClose, directFinished, directStarted, cleanupDeadlineNanos,
                "direct-null-listener prepared-close worker did not finish");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("direct-null-listener orchestration failed", orchestrationFailure);
        }
        assertSame(providerFailure, directObserved.get());
        assertSame(providerFailure, foreignObserved.get(),
                "the foreign physical owner must observe B/F, never its earlier raw C");
        assertExactlySuppressed(providerFailure, firstRevokeFailure);
        assertEquals(1, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertEquals(providerFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType());

        assertSame(providerFailure, assertThrows(Throwable.class, adapter::close));
        assertEquals(2, membership.revokeCalls(), "only the retained direct membership receipt may retry");
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(providerFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "direct-null-listener drain did not fail"));
    }

    /**
     * The direct final-pin B owns one pending-pin guard while it installs its terminal transition.
     * That exact transition must not fence itself: the outer close finally is the only code that
     * can release the guard, so self-fencing would deadlock both this close and a drain observer.
     */
    private static void assertDirectFinalPinFailureDoesNotFenceItsOwnPendingPin() throws Exception {
        OutOfMemoryError providerFailure = new OutOfMemoryError("direct-final-pin-own-guard-B");
        var generation = X4LifecycleTestSupport.published(14_465L);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProviderFailure = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerEntered.countDown();
            try {
                await(releaseProviderFailure, terminalOrderDeadline(),
                        "direct-final-pin provider failure gate was not opened");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("direct-final-pin provider action was interrupted", exception);
            }
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("direct-final-pin-own-guard");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_465L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        adapter.freeze();
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();

        AtomicReference<Throwable> publicObserved = new AtomicReference<>();
        AtomicReference<Throwable> directObserved = new AtomicReference<>();
        AtomicReference<Throwable> drainObserved = new AtomicReference<>();
        CountDownLatch publicFinished = new CountDownLatch(1);
        CountDownLatch directFinished = new CountDownLatch(1);
        CountDownLatch drainFinished = new CountDownLatch(1);
        // The public close is a waiter while the prepared snapshot remains an active future-B
        // source. Keeping it on a bounded daemon worker prevents the fixture from making the
        // deprecated synchronous-close assumption that caused the previous self-wait hang.
        Thread publicClose = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                publicObserved.set(failure);
            } finally {
                publicFinished.countDown();
            }
        }, "x4-direct-final-pin-public-close");
        Thread directClose = new Thread(() -> {
            try {
                prepared.close();
            } catch (Throwable failure) {
                directObserved.set(failure);
            } finally {
                directFinished.countDown();
            }
        }, "x4-direct-final-pin-close");
        Thread drainObserver = new Thread(() -> {
            try {
                drainObserved.set(awaitFutureFailure(drain, terminalOrderDeadline(),
                        "direct-final-pin drain observer did not receive a terminal failure"));
            } catch (Throwable failure) {
                drainObserved.set(failure);
            } finally {
                drainFinished.countDown();
            }
        }, "x4-direct-final-pin-drain-observer");
        publicClose.setDaemon(true);
        directClose.setDaemon(true);
        drainObserver.setDaemon(true);

        boolean publicStarted = false;
        boolean directStarted = false;
        boolean observerStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            publicClose.start();
            publicStarted = true;
            awaitTerminalRetryReadiness(adapter, deadlineNanos);
            assertFalse(awaitObservation(publicFinished, deadlineNanos),
                    "public close must remain pending while the prepared snapshot is still open");
            assertEquals(X4HostLifecycleState.CLOSING, adapter.state(),
                    "public close must expose CLOSING while its active snapshot remains a future B source");
            assertFalse(drain.isDone(), "public close may not publish drain while the snapshot is still open");
            drainObserver.start();
            observerStarted = true;
            directClose.start();
            directStarted = true;
            await(providerEntered, deadlineNanos, "direct-final-pin close did not enter provider B gate");
            assertFalse(awaitObservation(publicFinished, deadlineNanos),
                    "public close may not replay before direct B leaves its provider gate");
            assertFalse(awaitObservation(drainFinished, deadlineNanos),
                    "the observer must remain pending while B still owns its pin guard");
            releaseProviderFailure.countDown();
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseProviderFailure.countDown();
            try {
                invokeBounded("x4-direct-final-pin-prepared-cleanup", prepared::close, terminalOrderDeadline(),
                        "direct-final-pin prepared cleanup did not complete");
            } catch (Throwable ignored) {
                // The bounded direct/public workers below preserve the exact terminal evidence.
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(publicClose, publicFinished, publicStarted, cleanupDeadlineNanos,
                "direct-final-pin public close did not finish after direct B sealed F");
        awaitWorkerTermination(directClose, directFinished, directStarted, cleanupDeadlineNanos,
                "direct-final-pin close self-waited on its own pending-pin guard");
        awaitWorkerTermination(drainObserver, drainFinished, observerStarted, cleanupDeadlineNanos,
                "direct-final-pin drain observer did not finish after B installed and the guard released");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("direct-final-pin orchestration failed", orchestrationFailure);
        }
        assertSame(providerFailure, publicObserved.get(),
                "the public close waiter must replay the B installed by the direct snapshot release");
        assertSame(providerFailure, directObserved.get());
        assertSame(providerFailure, drainObserved.get(),
                "the concurrent drain observer must receive the same installed B identity");
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(providerFailure, invokeBounded("x4-direct-final-pin-post-seal-close", adapter::close,
                terminalOrderDeadline(), "direct-final-pin post-seal close did not replay immutable B"));
    }

    /**
     * R1 has returned from a non-final provider lease close but is held immediately before its
     * pending-pin decrement. R2 then receives final B. R2 must move its own pin through the outer
     * finally before waiting for R1's genuinely foreign pin; otherwise both the terminal observer
     * and drain form the R2 self-cycle reported by formal review. Once R2 opens the epoch, R1's
     * already-admitted pending direct release must replay the same immutable B rather than escape
     * successfully. The observer is an {@code openedHere=false} waiter, so after F seals it owns
     * the retained-membership C2 retry and publishes the completed drain.
     */
    private static void assertDirectFinalPinWaitsOnlyAfterReleasingItsOwnGuardAgainstForeignPendingPin()
            throws Exception {
        OutOfMemoryError providerFailure = new OutOfMemoryError("direct-foreign-pin-provider-B");
        LinkageError revokeFailure = new LinkageError("direct-foreign-pin-revoke-C");
        var generation = X4LifecycleTestSupport.published(14_467L);
        CountDownLatch finalProviderEntered = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            finalProviderEntered.countDown();
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("direct-final-pin-foreign-pending");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_467L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(14_468L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot firstPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot secondPrepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();

        CountDownLatch r1BeforePinDecrement = new CountDownLatch(1);
        CountDownLatch releaseR1PinDecrement = new CountDownLatch(1);
        adapter.runBeforeDirectReleasePendingPinDecrementForTesting(() -> {
            r1BeforePinDecrement.countDown();
            try {
                await(releaseR1PinDecrement, terminalOrderDeadline(),
                        "direct-foreign-pin R1 pre-decrement gate was not opened");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("direct-foreign-pin R1 pre-decrement action interrupted", exception);
            }
        });

        AtomicReference<Throwable> r1Observed = new AtomicReference<>();
        AtomicReference<Throwable> r2Observed = new AtomicReference<>();
        AtomicReference<Throwable> terminalObserved = new AtomicReference<>();
        AtomicReference<Throwable> drainObserved = new AtomicReference<>();
        AtomicBoolean r2InterruptRestored = new AtomicBoolean();
        CountDownLatch r1Finished = new CountDownLatch(1);
        CountDownLatch r2Finished = new CountDownLatch(1);
        CountDownLatch terminalFinished = new CountDownLatch(1);
        CountDownLatch drainFinished = new CountDownLatch(1);
        Thread r1Close = new Thread(() -> {
            try {
                firstPrepared.close();
            } catch (Throwable failure) {
                r1Observed.set(failure);
            } finally {
                r1Finished.countDown();
            }
        }, "x4-direct-foreign-pin-r1");
        Thread r2Close = new Thread(() -> {
            try {
                secondPrepared.close();
            } catch (Throwable failure) {
                r2Observed.set(failure);
            } finally {
                r2InterruptRestored.set(Thread.currentThread().isInterrupted());
                r2Finished.countDown();
            }
        }, "x4-direct-foreign-pin-r2");
        Thread terminalObserver = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                terminalObserved.set(failure);
            } finally {
                terminalFinished.countDown();
            }
        }, "x4-direct-foreign-pin-terminal-observer");
        Thread drainObserver = new Thread(() -> {
            try {
                drainObserved.set(awaitFutureFailure(drain, terminalOrderDeadline(),
                        "direct-foreign-pin drain observer did not receive a terminal failure"));
            } catch (Throwable failure) {
                drainObserved.set(failure);
            } finally {
                drainFinished.countDown();
            }
        }, "x4-direct-foreign-pin-drain-observer");
        r1Close.setDaemon(true);
        r2Close.setDaemon(true);
        terminalObserver.setDaemon(true);
        drainObserver.setDaemon(true);

        boolean r1Started = false;
        boolean r2Started = false;
        boolean terminalStarted = false;
        boolean drainStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            r1Close.start();
            r1Started = true;
            await(r1BeforePinDecrement, deadlineNanos,
                    "direct-foreign-pin R1 did not return from its non-final provider lease close");

            r2Close.start();
            r2Started = true;
            await(finalProviderEntered, deadlineNanos,
                    "direct-foreign-pin R2 did not enter the final provider B release");
            awaitWorkerWaiting(r2Close, deadlineNanos,
                    "direct-foreign-pin R2 did not leave its outer pin finally before fence waiting");
            assertEquals(1, membership.revokeCalls(), "R2 must make exactly one physical C before retry");
            assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "B/C must remain fenced while the R1 foreign pin still exists");

            terminalObserver.start();
            terminalStarted = true;
            drainObserver.start();
            drainStarted = true;
            assertFalse(awaitObservation(terminalFinished, deadlineNanos),
                    "the terminal observer must wait for R1's foreign pending pin");
            awaitWorkerWaiting(terminalObserver, deadlineNanos,
                    "direct-foreign-pin terminal observer did not join the open epoch before R1 release");
            assertFalse(drain.isDone(),
                    "drain must remain unpublished while R1's foreign pin fences B/C");
            assertFalse(awaitObservation(drainFinished, deadlineNanos),
                    "drain must remain unpublished while R1's foreign pin fences B/C");

            r2Close.interrupt();
            assertFalse(awaitObservation(r2Finished, deadlineNanos),
                    "R2 interrupt must be restored only after its post-final uninterruptible wait completes");
            releaseR1PinDecrement.countDown();
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseR1PinDecrement.countDown();
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(r1Close, r1Finished, r1Started, cleanupDeadlineNanos,
                "direct-foreign-pin R1 worker did not finish after its pin decrement was released");
        awaitWorkerTermination(r2Close, r2Finished, r2Started, cleanupDeadlineNanos,
                "direct-foreign-pin R2 self-waited before reaching its outer finally");
        awaitWorkerTermination(terminalObserver, terminalFinished, terminalStarted, cleanupDeadlineNanos,
                "direct-foreign-pin terminal observer did not drive the retained-membership C2 retry");
        awaitWorkerTermination(drainObserver, drainFinished, drainStarted, cleanupDeadlineNanos,
                "direct-foreign-pin drain observer did not finish after the observer drove C2");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("direct-foreign-pin orchestration failed", orchestrationFailure);
        }

        assertSame(providerFailure, r1Observed.get(),
                "the non-final direct release must replay the B installed by its shared terminal epoch");
        assertSame(providerFailure, r2Observed.get());
        assertSame(providerFailure, terminalObserved.get());
        assertTrue(r2InterruptRestored.get(),
                "the post-final direct wait must restore R2's interrupt status before it rethrows B");
        assertExactlySuppressed(providerFailure, revokeFailure);
        assertEquals(2, membership.revokeCalls(),
                "the openedHere=false observer must drive C2 after the retained C1 receipt seals");
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertEquals(providerFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType());
        assertSame(providerFailure, drainObserved.get());
        assertEquals(1, generation.provider().retireCalls());
        assertEquals(1, generation.provider().closeCalls());
    }

    /**
     * Once H begins selecting immutable F, a retry cannot launch C2 until F exists.  A later C2
     * may retain the receipt, but it neither appears in F nor causes a third revoke before the
     * caller deliberately retries after seal.
     */
    private static void assertSealingFenceDefersTheSecondFailedRevokeUntilAfterImmutableF() throws Exception {
        AssertionError rendererFailure = new AssertionError("sealing-fence-renderer-A");
        LinkageError providerFailure = new LinkageError("sealing-fence-provider-B");
        OutOfMemoryError firstRevokeFailure = new OutOfMemoryError("sealing-fence-first-C");
        LinkageError secondRevokeFailure = new LinkageError("sealing-fence-second-C2");
        var generation = X4LifecycleTestSupport.published(14_470L);
        generation.provider().onRetire(() -> throwUnchecked(providerFailure));

        X4HostIdentity identity = identity("sealing-fence-post-seal-retry");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_470L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        SequencedMembership membership = new SequencedMembership(
                14_471L, firstRevokeFailure, secondRevokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        AtomicReference<Throwable> retryObserved = new AtomicReference<>();
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch retryFinished = new CountDownLatch(1);
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(prepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitFinished.countDown();
            }
        }, "x4-sealing-fence-submit");
        Thread retryThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                retryObserved.set(failure);
            } finally {
                retryFinished.countDown();
            }
        }, "x4-sealing-fence-retry");
        submitThread.setDaemon(true);
        retryThread.setDaemon(true);

        boolean submitStarted = false;
        boolean retryStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            synchronized (rendererFailure) {
                submitThread.start();
                submitStarted = true;
                await(membership.firstRevoke(), deadlineNanos,
                        "sealing-fence submit did not complete its first revoke C");
                awaitTerminalRetryReadiness(adapter, deadlineNanos);
                awaitWorkerBlocked(submitThread, deadlineNanos,
                        "sealing-fence submit did not enter the blocked A/F selection");
                retryThread.start();
                retryStarted = true;
                assertFalse(awaitObservation(retryFinished, deadlineNanos),
                        "a retry arriving during SEALING must wait rather than return provisional P");
                assertEquals(1, membership.revokeCalls(),
                        "the retry must not launch C2 while A/F selection is still fenced");
            }
            await(membership.secondRevoke(), deadlineNanos,
                    "the post-seal retry did not perform the exact second revoke");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                "sealing-fence submit worker did not finish");
        awaitWorkerTermination(retryThread, retryFinished, retryStarted, cleanupDeadlineNanos,
                "sealing-fence retry worker did not finish");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("sealing-fence orchestration failed", orchestrationFailure);
        }
        assertSame(rendererFailure, submitObserved.get());
        assertSame(rendererFailure, retryObserved.get(),
                "a failed post-seal retry must still replay immutable F");
        assertExactlySuppressed(rendererFailure, providerFailure);
        assertExactlySuppressed(providerFailure, firstRevokeFailure);
        assertEquals(0, secondRevokeFailure.getSuppressed().length,
                "C2 must not be added to the immutable terminal failure chain");
        assertEquals(2, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());

        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close),
                "only an explicit post-seal retry may attempt the retained exact receipt again");
        assertEquals(3, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "sealing-fence drain did not fail"));
    }

    /**
     * Fatal terminal cleanup is the observable stand-in for an allocation-pressure handoff: the
     * already allocated H must still become sealable, and no post-commit result carrier may split
     * submit, retry, or drain identity while that fatal is propagated.
     */
    private static void assertFatalProviderFailureKeepsThePreallocatedHoldAndDrainLive() throws Exception {
        AssertionError rendererFailure = new AssertionError("fatal-handoff-renderer-A");
        OutOfMemoryError providerFailure = new OutOfMemoryError("fatal-handoff-provider-B");
        LinkageError revokeFailure = new LinkageError("fatal-handoff-revoke-C");
        var generation = X4LifecycleTestSupport.published(14_480L);
        generation.provider().onRetire(() -> throwUnchecked(providerFailure));
        X4HostIdentity identity = identity("fatal-handoff-no-carrier-split");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_480L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(14_481L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        assertSame(rendererFailure, assertThrows(Throwable.class, () -> adapter.submit(prepared, unusedContext())));
        assertExactlySuppressed(rendererFailure, providerFailure);
        assertExactlySuppressed(providerFailure, revokeFailure);
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertEquals(rendererFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType());

        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close));
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "fatal-handoff drain did not fail"));
    }

    /**
     * This arms the package-private allocation seam immediately before the actual post-seal
     * retained-membership retry transition. The injected OOME is not a provider stand-in: the
     * caller must replay immutable F, leave the receipt untouched, and allow the next retry to
     * allocate normally and clear that same receipt.
     */
    private static void assertPostSealRetryAllocationFailureReplaysImmutableF() throws Exception {
        AssertionError rendererFailure = new AssertionError("post-seal-allocation-renderer-A");
        LinkageError providerFailure = new LinkageError("post-seal-allocation-provider-B");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("post-seal-allocation-first-C");
        OutOfMemoryError injectedAllocationFailure = new OutOfMemoryError("post-seal-transition-allocation");
        var generation = X4LifecycleTestSupport.published(14_490L);
        generation.provider().onRetire(() -> throwUnchecked(providerFailure));

        X4HostIdentity identity = identity("post-seal-transition-allocation");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_490L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(14_491L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        assertSame(rendererFailure, assertThrows(Throwable.class, () -> adapter.submit(prepared, unusedContext())));
        assertExactSuppressionChain(rendererFailure, providerFailure, revokeFailure);
        assertSuppressionGraphExcludes(rendererFailure, injectedAllocationFailure);
        assertEquals(1, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertEquals(rendererFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType());

        adapter.failNextPostSealTerminalTransitionAllocationForTesting(injectedAllocationFailure);
        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close),
                "a failed post-seal retry allocation must replay F instead of escaping a new OOME");
        assertEquals(1, membership.revokeCalls(),
                "the injected allocation failure must preserve the exact retained membership receipt");
        assertEquals(0, injectedAllocationFailure.getSuppressed().length,
                "the allocation failure must not mutate the immutable terminal error chain");
        assertExactSuppressionChain(rendererFailure, providerFailure, revokeFailure);
        assertSuppressionGraphExcludes(rendererFailure, injectedAllocationFailure);
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertEquals(rendererFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType());

        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close),
                "the next retry must reach the real allocation/revoke path and still replay F");
        assertEquals(2, membership.revokeCalls(),
                "the one-shot seam must have been consumed at the actual post-seal allocation point");
        assertTrue(membership.revoked());
        assertExactSuppressionChain(rendererFailure, providerFailure, revokeFailure);
        assertSuppressionGraphExcludes(rendererFailure, injectedAllocationFailure);
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "post-seal allocation drain did not fail"));
    }

    /** Provider-success callback reentry must retain H until its failed revoke can become F with renderer A. */
    private static void assertOwnerDeferredCloseOnProviderSuccessSealsWithTheSubmittingHold() throws Exception {
        AssertionError rendererFailure = new AssertionError("deferred-owner-renderer-A");
        LinkageError revokeFailure = new LinkageError("deferred-owner-revoke-C");
        var generation = X4LifecycleTestSupport.published(14_500L);
        X4HostIdentity identity = identity("deferred-owner-success");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        AtomicReference<X4HostAdapter<X4HostFrames.WorldObject>> adapterReference = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_500L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        adapterReference.set(adapter);
        generation.provider().onRetire(() -> adapterReference.get().close());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(14_501L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        assertSame(rendererFailure, assertThrows(Throwable.class, () -> adapter.submit(prepared, unusedContext())));
        assertExactlySuppressed(rendererFailure, revokeFailure);
        assertEquals(1, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());
        assertEquals(rendererFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType());

        assertSame(rendererFailure, assertThrows(Throwable.class, adapter::close));
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "deferred-close drain did not fail"));
    }

    /** A provider-success callback asking to retire must retain RETIRING/RETIRED intent, not escalate to close. */
    private static void assertOwnerDeferredRetireOnProviderSuccessPreservesRetiredState() throws Exception {
        var generation = X4LifecycleTestSupport.published(14_550L);
        X4HostIdentity identity = identity("deferred-owner-retire-success");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        AtomicReference<X4HostAdapter<X4HostFrames.WorldObject>> adapterReference = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_550L),
                new BlendRenderer((snapshot, context) -> preparedReference.get().close()),
                identity,
                generation.session());
        adapterReference.set(adapter);
        generation.provider().onRetire(() -> adapterReference.get().retire());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_551L);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        adapter.submit(prepared, unusedContext());
        assertEquals(1, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.RETIRED, adapter.state(),
                "a deferred provider-success retire must preserve RETIRED instead of forcing CLOSED");
        assertEquals(X4HostLifecycleState.RETIRED, awaitFutureValue(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "deferred-retire drain did not complete"));
    }

    /**
     * Two submit holds can be live at once. H1 may return its exact renderer Error locally while
     * H2 is still admitted, but every renderer Error has already opened/joined the one epoch:
     * the first linearized A1 remains F's primary, with A2 and the later B/C graph retained.
     */
    private static void assertConcurrentSubmitHoldsKeepTheirOwnRendererIdentities() throws Exception {
        AssertionError firstRendererFailure = new AssertionError("multi-hold-first-renderer-A1");
        AssertionError finalRendererFailure = new AssertionError("multi-hold-final-renderer-A2");
        LinkageError providerFailure = new LinkageError("multi-hold-final-provider-B");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("multi-hold-final-revoke-C");
        var generation = X4LifecycleTestSupport.published(14_560L);
        generation.provider().onRetire(() -> throwUnchecked(providerFailure));

        X4HostIdentity identity = identity("concurrent-submit-holds");
        AtomicReference<X4PreparedSnapshot> firstPreparedReference = new AtomicReference<>();
        AtomicReference<X4PreparedSnapshot> secondPreparedReference = new AtomicReference<>();
        AtomicInteger rendererCalls = new AtomicInteger();
        CountDownLatch firstRendererEntered = new CountDownLatch(1);
        CountDownLatch secondRendererEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRenderer = new CountDownLatch(1);
        CountDownLatch releaseSecondRenderer = new CountDownLatch(1);
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(14_560L),
                new BlendRenderer((snapshot, context) -> {
                    int call = rendererCalls.incrementAndGet();
                    try {
                        if (call == 1) {
                            firstRendererEntered.countDown();
                            await(releaseFirstRenderer, terminalOrderDeadline(),
                                    "multi-hold first renderer release was not opened");
                            firstPreparedReference.get().close();
                            throwUnchecked(firstRendererFailure);
                        }
                        if (call == 2) {
                            secondRendererEntered.countDown();
                            await(releaseSecondRenderer, terminalOrderDeadline(),
                                    "multi-hold second renderer release was not opened");
                            secondPreparedReference.get().close();
                            throwUnchecked(finalRendererFailure);
                        }
                        throw new AssertionError("unexpected multi-hold renderer invocation " + call);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("multi-hold renderer was interrupted", exception);
                    }
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(14_561L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot firstPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot secondPrepared = adapter.prepare(worldFrame(identity));
        firstPreparedReference.set(firstPrepared);
        secondPreparedReference.set(secondPrepared);
        generation.session().retire();

        AtomicReference<Throwable> firstObserved = new AtomicReference<>();
        AtomicReference<Throwable> secondObserved = new AtomicReference<>();
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        Thread firstSubmit = new Thread(() -> {
            try {
                adapter.submit(firstPrepared, unusedContext());
            } catch (Throwable failure) {
                firstObserved.set(failure);
            } finally {
                firstFinished.countDown();
            }
        }, "x4-multi-hold-first-submit");
        Thread secondSubmit = new Thread(() -> {
            try {
                adapter.submit(secondPrepared, unusedContext());
            } catch (Throwable failure) {
                secondObserved.set(failure);
            } finally {
                secondFinished.countDown();
            }
        }, "x4-multi-hold-final-submit");
        firstSubmit.setDaemon(true);
        secondSubmit.setDaemon(true);

        boolean firstStarted = false;
        boolean secondStarted = false;
        Throwable orchestrationFailure = null;
        try {
            long deadlineNanos = terminalOrderDeadline();
            firstSubmit.start();
            firstStarted = true;
            await(firstRendererEntered, deadlineNanos, "multi-hold first submit did not enter renderer");
            secondSubmit.start();
            secondStarted = true;
            await(secondRendererEntered, deadlineNanos, "multi-hold second submit did not enter renderer");

            // H1 returns first while H2 remains admitted/open. Its exact public return remains
            // local, but its Error has already joined Ae*; H2's later B/C must therefore fold
            // into the same eventual F rather than replacing A1 with a second H-local owner.
            releaseFirstRenderer.countDown();
            awaitWorkerTermination(firstSubmit, firstFinished, true, deadlineNanos,
                    "multi-hold non-final submit did not finish");
            assertSame(firstRendererFailure, firstObserved.get(),
                    "a non-final H renderer failure remains local and may not publish adapter F");
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "H1 must not install a global terminal identity before H2 reaches the physical final release");

            releaseSecondRenderer.countDown();
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseFirstRenderer.countDown();
            releaseSecondRenderer.countDown();
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(firstSubmit, firstFinished, firstStarted, cleanupDeadlineNanos,
                "multi-hold first submit worker did not finish");
        awaitWorkerTermination(secondSubmit, secondFinished, secondStarted, cleanupDeadlineNanos,
                "multi-hold final submit worker did not finish");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("multi-hold orchestration failed", orchestrationFailure);
        }
        assertSame(firstRendererFailure, firstObserved.get());
        assertSame(firstRendererFailure, secondObserved.get(),
                "every Error H joins the epoch, so H2 must replay the first linearized A1/F");
        assertEquals(2, firstRendererFailure.getSuppressed().length,
                "A1 must retain both later Ae* evidence and the B/C cleanup branch");
        assertSame(finalRendererFailure, firstRendererFailure.getSuppressed()[0],
                "the second admitted renderer Error must remain the first Ae* suppressed edge");
        assertSame(providerFailure, firstRendererFailure.getSuppressed()[1],
                "the later exact provider B must remain directly retained by A1/F");
        assertExactlySuppressed(providerFailure, revokeFailure);
        assertEquals(firstRendererFailure.getClass().getName(), adapter.leaseDiagnostics().terminalFailureType(),
                "the first linearized renderer Error must be the globally published F identity");
        assertEquals(1, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSING, adapter.state());

        assertSame(firstRendererFailure, assertThrows(Throwable.class, adapter::close));
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(firstRendererFailure, awaitFutureFailure(adapter.drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "multi-hold drain did not fail"));
        assertEquals(1, generation.provider().retireCalls());
        assertEquals(1, generation.provider().closeCalls());
    }

    /**
     * R5 ordinary-A control: an ordinary renderer exception with a successful provider close is
     * still local to that submit.  It must not manufacture an epoch, revoke a membership, or
     * publish a terminal drain outcome.
     */
    private static void assertOrdinaryRendererFailureWithoutProviderFailureRemainsLocal() throws Exception {
        IllegalStateException ordinaryRendererFailure = new IllegalStateException("r15-ordinary-A-local");
        var generation = X4LifecycleTestSupport.published(14_600L);
        X4HostIdentity identity = identity("r15-ordinary-a-local");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_600L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(ordinaryRendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_601L);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        long deadlineNanos = terminalOrderDeadline();
        assertSame(ordinaryRendererFailure, invokeBounded("x4-r15-ordinary-A-submit",
                () -> adapter.submit(prepared, unusedContext()), deadlineNanos,
                "ordinary renderer-A submit did not complete"));
        assertEquals(0, membership.revokeCalls(),
                "ordinary renderer A with B == null must not start a terminal epoch");
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
        assertFalse(adapter.drainCompletion().toCompletableFuture().isDone(),
                "a local ordinary A must not publish drain completion");

        assertSame(null, invokeBounded("x4-r15-ordinary-A-close", adapter::close, deadlineNanos,
                "ordinary renderer-A terminal close did not complete"));
        assertEquals(1, membership.revokeCalls());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertEquals(X4HostLifecycleState.CLOSED, awaitFutureValue(
                adapter.drainCompletion().toCompletableFuture(), deadlineNanos,
                "ordinary-A cleanup drain did not complete"));
    }

    /**
     * X1 every-Error rule at the renderer seam: A itself opens E even when the final provider
     * release succeeds and therefore contributes no B.  This is deliberately separate from the
     * ordinary-A control above.
     */
    private static void assertEveryErrorRendererWithoutProviderFailureOpensTerminalEpoch() throws Exception {
        AssertionError rendererFailure = new AssertionError("r15-error-A-null-B-opens-E");
        var generation = X4LifecycleTestSupport.published(14_610L);
        X4HostIdentity identity = identity("r15-error-a-null-b-opens-e");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_610L),
                new BlendRenderer((snapshot, context) -> throwUnchecked(rendererFailure)),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_611L);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();

        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        AtomicReference<Throwable> publicCloseObserved = new AtomicReference<>();
        AtomicReference<Throwable> directCloseObserved = new AtomicReference<>();
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch publicCloseFinished = new CountDownLatch(1);
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(prepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitFinished.countDown();
            }
        }, "x4-r15-error-A-null-B-submit");
        Thread publicCloseThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                publicCloseObserved.set(failure);
            } finally {
                publicCloseFinished.countDown();
            }
        }, "x4-r15-error-A-null-B-public-close");
        submitThread.setDaemon(true);
        publicCloseThread.setDaemon(true);

        boolean submitStarted = false;
        boolean publicCloseStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            submitThread.start();
            submitStarted = true;
            awaitWorkerTermination(submitThread, submitFinished, true, deadlineNanos,
                    "renderer Error submit self-waited on its still-open prepared snapshot");
            assertSame(rendererFailure, submitObserved.get(),
                    "the renderer Error must return through its owning submit without self-waiting");

            publicCloseThread.start();
            publicCloseStarted = true;
            assertFalse(awaitObservation(publicCloseFinished, deadlineNanos),
                    "an Error-open epoch may not publish a public close while its snapshot is still open");
            directCloseObserved.set(invokeBounded("x4-r15-error-A-null-B-direct", prepared::close, deadlineNanos,
                    "Error-A/null-B direct snapshot close did not complete"));
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            // The direct close is idempotent and is the only release that can unblock the public
            // waiter if an earlier assertion fails.
            if (directCloseObserved.get() == null) {
                try {
                    directCloseObserved.compareAndSet(null, invokeBounded(
                            "x4-r15-error-A-null-B-direct-cleanup", prepared::close, terminalOrderDeadline(),
                            "Error-A/null-B cleanup snapshot close did not complete"));
                } catch (Throwable failure) {
                    directCloseObserved.compareAndSet(null, failure);
                }
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                "renderer Error submit worker did not terminate after the snapshot cleanup release");
        awaitWorkerTermination(publicCloseThread, publicCloseFinished, publicCloseStarted, cleanupDeadlineNanos,
                "Error-A/null-B public close worker did not terminate after the exact snapshot release");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("Error-A/null-B epoch orchestration failed", orchestrationFailure);
        }
        assertSame(rendererFailure, directCloseObserved.get(),
                "the releasing direct snapshot must replay the same sealed Error F");
        assertSame(rendererFailure, publicCloseObserved.get(),
                "the public close must observe the same Error F after the snapshot gate drains");
        assertEquals(1, membership.revokeCalls(),
                "every renderer Error must open one terminal epoch even when B is null");
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(rendererFailure, awaitFutureFailure(
                adapter.drainCompletion().toCompletableFuture(), terminalOrderDeadline(),
                "Error-A/null-B epoch did not publish its sealed drain failure"));

        // A self-closing Error H is also locally stable when an unrelated prepared snapshot is
        // still the future B gate. This is distinct from the open-own-snapshot lane above: H has
        // released itself, so an implementation that requires a second in-flight H self-waits.
        AssertionError idleRendererFailure = new AssertionError("r15-error-a-self-close-idle");
        var idleGeneration = X4LifecycleTestSupport.published(14_612L);
        X4HostIdentity idleIdentity = identity("r15-error-a-self-close-idle-snapshot");
        AtomicReference<X4PreparedSnapshot> selfClosingPrepared = new AtomicReference<>();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> idleAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_612L),
                new BlendRenderer((snapshot, context) -> {
                    selfClosingPrepared.get().close();
                    throwUnchecked(idleRendererFailure);
                }),
                idleIdentity,
                idleGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> idleReservation = idleAdapter.freezeAndReserveRegistration();
        CountingMembership idleMembership = new CountingMembership(14_613L);
        idleReservation.commit(idleMembership);
        X4PreparedSnapshot selfClosingSnapshot = idleAdapter.prepare(worldFrame(idleIdentity));
        X4PreparedSnapshot idleSnapshot = idleAdapter.prepare(worldFrame(idleIdentity));
        selfClosingPrepared.set(selfClosingSnapshot);
        idleGeneration.session().retire();
        var idleDrain = idleAdapter.drainCompletion().toCompletableFuture();
        AtomicReference<Throwable> idlePublicObserved = new AtomicReference<>();
        CountDownLatch idlePublicFinished = new CountDownLatch(1);
        Thread idlePublicClose = new Thread(() -> {
            try {
                idleAdapter.close();
            } catch (Throwable failure) {
                idlePublicObserved.set(failure);
            } finally {
                idlePublicFinished.countDown();
            }
        }, "x4-r15-error-a-self-close-idle-public");
        idlePublicClose.setDaemon(true);
        boolean idlePublicStarted = false;
        Throwable idleDirectObserved = null;
        long idleDeadlineNanos = terminalOrderDeadline();
        try {
            assertSame(idleRendererFailure, invokeBounded("x4-r15-error-A-self-close-idle-submit",
                    () -> idleAdapter.submit(selfClosingSnapshot, unusedContext()), idleDeadlineNanos,
                    "self-closing Error H waited on its idle snapshot gate instead of returning A"));
            assertEquals(0, idleMembership.revokeCalls(),
                    "self-closing Error A may open E but must not drive C while idle S2 remains open");
            assertFalse(idleAdapter.state() == X4HostLifecycleState.CLOSED
                            || idleAdapter.state() == X4HostLifecycleState.RETIRED,
                    "E remains unpublished while S2 is the future direct-B source");
            assertEquals("", idleAdapter.leaseDiagnostics().terminalFailureType(),
                    "local Error A must not leak provisional F through diagnostics while S2 remains open");
            assertFalse(idleDrain.isDone(), "local Error A must not publish the drain while S2 remains open");

            idlePublicClose.start();
            idlePublicStarted = true;
            assertFalse(awaitObservation(idlePublicFinished, idleDeadlineNanos),
                    "a public close may not return before idle S2 closes and E seals");
            idleDirectObserved = invokeBounded("x4-r15-error-A-self-close-idle-S2-close", idleSnapshot::close,
                    idleDeadlineNanos, "idle S2 close did not complete the self-closing Error epoch");
        } finally {
            if (idleDirectObserved == null) {
                try {
                    idleDirectObserved = invokeBounded("x4-r15-error-A-self-close-idle-S2-cleanup", idleSnapshot::close,
                            terminalOrderDeadline(), "idle S2 cleanup did not complete the Error epoch");
                } catch (Throwable failure) {
                    idleDirectObserved = failure;
                }
            }
        }
        awaitWorkerTermination(idlePublicClose, idlePublicFinished, idlePublicStarted, terminalOrderDeadline(),
                "self-closing Error idle public close worker did not terminate after S2 release");
        assertSame(idleRendererFailure, idleDirectObserved,
                "idle S2 direct close must replay the local self-closing Error as immutable F");
        assertSame(idleRendererFailure, idlePublicObserved.get(),
                "public close must replay the self-closing Error only after S2 seals E");
        assertEquals(1, idleMembership.revokeCalls(), "the self-closing Error epoch must use exactly one C receipt");
        assertSame(idleRendererFailure, awaitFutureFailure(idleDrain, terminalOrderDeadline(),
                "self-closing Error idle-S2 epoch did not publish the exact drain failure"));
    }

    /**
     * A failed initial physical-owner allocation is D, not an escaping side path.  It seals F
     * while retaining the exact membership receipt for a later post-seal physical retry.
     */
    private static void assertPreSealAllocationFailureBecomesPhysicalDAndRetainsReceipt() throws Exception {
        OutOfMemoryError allocationFailure = new OutOfMemoryError("r15-preseal-T-allocation-D");
        var generation = X4LifecycleTestSupport.published(14_620L);
        X4HostIdentity identity = identity("r15-preseal-t-allocation-d");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_620L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_621L);
        reservation.commit(membership);

        long deadlineNanos = terminalOrderDeadline();
        var drain = adapter.drainCompletion().toCompletableFuture();
        Throwable retryObserved = null;
        try {
            adapter.failNextPreSealTerminalEpochAllocationForTesting(allocationFailure);
            assertSame(allocationFailure, invokeBounded("x4-r15-preseal-D-first-close", adapter::close, deadlineNanos,
                    "pre-seal T allocation close did not complete"));
            assertEquals(0, membership.revokeCalls(),
                    "D occurs before a physical revoke and must retain the original membership receipt");
            assertEquals(X4HostLifecycleState.CLOSING, adapter.state(),
                    "D seals the terminal epoch but leaves its receipt pending retry");
            assertFalse(drain.isDone(),
                    "drain cannot publish while D's retained receipt has not been physically retried");
        } finally {
            retryObserved = invokeBounded("x4-r15-preseal-D-retry", adapter::close, terminalOrderDeadline(),
                    "pre-seal D retained-receipt retry did not complete");
        }
        assertSame(allocationFailure, retryObserved,
                "post-seal receipt retry must replay immutable F/D");
        assertEquals(1, membership.revokeCalls(), "only the retained receipt may be physically retried");
        assertTrue(membership.revoked());
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        assertSame(allocationFailure, awaitFutureFailure(drain, deadlineNanos,
                "pre-seal D drain did not publish after the retained receipt cleared"));

        // D must become an exact pending source before it can take the contribution claim. Hold
        // that monitor-out interval: its own close/retire reentry must coalesce, while a foreign
        // close cannot install a second C or seal past D before the original owner commits it.
        OutOfMemoryError pendingAllocationFailure = new OutOfMemoryError("r15-preseal-D-pending-owner");
        var pendingGeneration = X4LifecycleTestSupport.published(14_622L);
        X4HostIdentity pendingIdentity = identity("r15-preseal-d-pending-owner");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> pendingAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_622L), new BlendRenderer((snapshot, context) -> { }), pendingIdentity,
                pendingGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> pendingReservation =
                pendingAdapter.freezeAndReserveRegistration();
        CountingMembership pendingMembership = new CountingMembership(14_623L);
        pendingReservation.commit(pendingMembership);
        pendingGeneration.session().retire();
        var pendingDrain = pendingAdapter.drainCompletion().toCompletableFuture();
        CountDownLatch pendingAllocationEntered = new CountDownLatch(1);
        CountDownLatch pendingOwnerReentryReturned = new CountDownLatch(1);
        CountDownLatch releasePendingAllocation = new CountDownLatch(1);
        AtomicReference<Throwable> pendingOwnerReentryFailure = new AtomicReference<>();
        pendingAdapter.runAfterInitialPhysicalAllocationFailurePendingForTesting(() -> {
            pendingAllocationEntered.countDown();
            try {
                pendingAdapter.close();
                pendingAdapter.retire();
            } catch (Throwable failure) {
                pendingOwnerReentryFailure.set(failure);
            } finally {
                pendingOwnerReentryReturned.countDown();
            }
            awaitUninterruptibly(releasePendingAllocation, terminalOrderDeadline(),
                    "pending D owner was not released after its exact reentry check");
        });
        pendingAdapter.failNextPreSealTerminalEpochAllocationForTesting(pendingAllocationFailure);
        AtomicReference<Throwable> pendingOwnerObserved = new AtomicReference<>();
        AtomicReference<Throwable> pendingForeignObserved = new AtomicReference<>();
        CountDownLatch pendingOwnerFinished = new CountDownLatch(1);
        CountDownLatch pendingForeignFinished = new CountDownLatch(1);
        Thread pendingOwnerClose = new Thread(() -> {
            try {
                pendingAdapter.close();
            } catch (Throwable failure) {
                pendingOwnerObserved.set(failure);
            } finally {
                pendingOwnerFinished.countDown();
            }
        }, "x4-r15-preseal-D-pending-owner");
        Thread pendingForeignClose = new Thread(() -> {
            try {
                pendingAdapter.close();
            } catch (Throwable failure) {
                pendingForeignObserved.set(failure);
            } finally {
                pendingForeignFinished.countDown();
            }
        }, "x4-r15-preseal-D-pending-foreign");
        pendingOwnerClose.setDaemon(true);
        pendingForeignClose.setDaemon(true);
        boolean pendingOwnerStarted = false;
        boolean pendingForeignStarted = false;
        Throwable pendingOrchestrationFailure = null;
        long pendingDeadlineNanos = terminalOrderDeadline();
        try {
            pendingOwnerClose.start();
            pendingOwnerStarted = true;
            await(pendingAllocationEntered, pendingDeadlineNanos,
                    "pre-seal allocation failure did not enter its exact pending-D seam");
            await(pendingOwnerReentryReturned, pendingDeadlineNanos,
                    "pending D owner self-waited instead of coalescing close/retire to its outer source");
            assertEquals(null, pendingOwnerReentryFailure.get(),
                    "pending D owner reentry must not observe provisional F or throw");
            assertEquals(0, pendingMembership.revokeCalls(),
                    "no physical C may start while D is exact-pending before contribution commit");
            assertFalse(pendingDrain.isDone(), "pending D may not publish the drain before it commits");

            pendingForeignClose.start();
            pendingForeignStarted = true;
            assertFalse(awaitObservation(pendingForeignFinished, pendingDeadlineNanos),
                    "foreign close must wait behind exact pending D rather than start a second physical owner");
            assertEquals(0, pendingMembership.revokeCalls(),
                    "foreign close may not install C while the allocation D carrier is pending");
            releasePendingAllocation.countDown();
        } catch (Throwable failure) {
            pendingOrchestrationFailure = failure;
        } finally {
            releasePendingAllocation.countDown();
            if (!pendingForeignStarted) {
                try {
                    pendingForeignObserved.compareAndSet(null, invokeBounded(
                            "x4-r15-preseal-D-pending-cleanup-retry", pendingAdapter::close,
                            terminalOrderDeadline(), "pending D cleanup retained-receipt retry did not complete"));
                } catch (Throwable failure) {
                    pendingForeignObserved.compareAndSet(null, failure);
                }
            }
        }
        long pendingCleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(pendingOwnerClose, pendingOwnerFinished, pendingOwnerStarted, pendingCleanupDeadlineNanos,
                "pending D owner close worker did not terminate after its contribution release");
        awaitWorkerTermination(pendingForeignClose, pendingForeignFinished, pendingForeignStarted, pendingCleanupDeadlineNanos,
                "pending D foreign close worker did not terminate after D sealed and retried C");
        if (pendingOrchestrationFailure != null) {
            if (pendingOrchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("pre-seal pending-D orchestration failed", pendingOrchestrationFailure);
        }
        assertSame(pendingAllocationFailure, pendingOwnerObserved.get(),
                "the original pending allocation owner must observe exact D/F");
        assertSame(pendingAllocationFailure, pendingForeignObserved.get(),
                "the foreign closer must retry the retained receipt and replay immutable D/F");
        assertEquals(1, pendingMembership.revokeCalls(),
                "only the post-seal retained-receipt retry may invoke membership after D commits");
        assertTrue(pendingMembership.revoked());
        assertSame(pendingAllocationFailure, awaitFutureFailure(pendingDrain, pendingCleanupDeadlineNanos,
                "pending D did not publish its immutable drain failure after the one retry"));
    }

    /**
     * A post-seal allocation failure is intentionally invisible to F.  The future assertion is
     * timed, so this also supplies the P1 stable bounded-drain observation rather than a join().
     */
    private static void assertPostSealRetryKeepsFImmutableAndPublishesBoundedDrain() throws Exception {
        AssertionError rendererFailure = new AssertionError("r15-postseal-immutable-A");
        LinkageError providerFailure = new LinkageError("r15-postseal-immutable-B");
        OutOfMemoryError firstRevokeFailure = new OutOfMemoryError("r15-postseal-immutable-C");
        OutOfMemoryError retryAllocationFailure = new OutOfMemoryError("r15-postseal-immutable-allocation");
        var generation = X4LifecycleTestSupport.published(14_630L);
        generation.provider().onRetire(() -> throwUnchecked(providerFailure));
        X4HostIdentity identity = identity("r15-postseal-immutable");
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_630L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(14_631L, firstRevokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        var drain = adapter.drainCompletion().toCompletableFuture();
        long deadlineNanos = terminalOrderDeadline();
        Throwable realRetryObserved = null;
        try {
            assertSame(rendererFailure, invokeBounded("x4-r15-postseal-submit",
                    () -> adapter.submit(prepared, unusedContext()), deadlineNanos,
                    "post-seal immutable-F submit did not complete"));
            assertExactSuppressionChain(rendererFailure, providerFailure, firstRevokeFailure);
            assertSame(drain, adapter.drainCompletion().toCompletableFuture(),
                    "the terminal epoch must retain one stable drain future identity");
            assertFalse(drain.isDone(),
                    "F is sealed before its failed receipt retry, not before drain can physically converge");
            adapter.failNextPostSealTerminalTransitionAllocationForTesting(retryAllocationFailure);
            assertSame(rendererFailure, invokeBounded("x4-r15-postseal-injected-retry", adapter::close, deadlineNanos,
                    "post-seal injected allocation retry did not complete"));
            assertExactSuppressionChain(rendererFailure, providerFailure, firstRevokeFailure);
            assertSuppressionGraphExcludes(rendererFailure, retryAllocationFailure);
            assertFalse(drain.isDone(),
                    "a post-seal allocation failure must not publish a different drain result");
        } finally {
            // This is the only real physical retry. It must happen even when an assertion above
            // fails, so the test never leaves a retained receipt or an incomplete future behind.
            realRetryObserved = invokeBounded("x4-r15-postseal-real-retry", adapter::close,
                    terminalOrderDeadline(), "post-seal real retained-receipt retry did not complete");
        }

        assertSame(rendererFailure, realRetryObserved);
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertSame(rendererFailure, awaitFutureFailure(drain, deadlineNanos,
                "post-seal immutable-F drain did not publish within its bounded deadline"));
    }

    /**
     * The physical owner is deliberately stopped after installation and before its contribution
     * gate closes.  The accepted-action latch proves that a direct B entered that exact owner
     * before revoke; neither observer may escape a raw successful physical T outcome.
     */
    private static void assertPhysicalOwnerAdmitsDirectContributionBeforeAdmissionSeal() throws Exception {
        LinkageError providerFailure = new LinkageError("r15-open-gate-direct-B");
        var generation = X4LifecycleTestSupport.published(14_640L);
        CountDownLatch providerFailureEntered = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerFailureEntered.countDown();
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("r15-open-gate-direct-b");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_640L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_641L);
        reservation.commit(membership);
        X4PreparedSnapshot directPrepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();

        CountDownLatch physicalOwnerInstalled = new CountDownLatch(1);
        CountDownLatch releasePhysicalOwner = new CountDownLatch(1);
        CountDownLatch contributionAccepted = new CountDownLatch(1);
        adapter.runBeforeTerminalTransitionMembershipRevokeForTesting(() -> {
            physicalOwnerInstalled.countDown();
            try {
                await(releasePhysicalOwner, terminalOrderDeadline(),
                        "physical owner was not released after direct-B admission");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("physical-owner TDD barrier was interrupted", exception);
            }
        });
        adapter.runAfterTerminalContributionAcceptedForTesting(contributionAccepted::countDown);

        AtomicReference<Throwable> physicalObserved = new AtomicReference<>();
        AtomicReference<Throwable> directObserved = new AtomicReference<>();
        CountDownLatch physicalFinished = new CountDownLatch(1);
        CountDownLatch directFinished = new CountDownLatch(1);
        Thread physicalThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                physicalObserved.set(failure);
            } finally {
                physicalFinished.countDown();
            }
        }, "x4-r15-open-gate-physical");
        Thread directThread = new Thread(() -> {
            try {
                directPrepared.close();
            } catch (Throwable failure) {
                directObserved.set(failure);
            } finally {
                directFinished.countDown();
            }
        }, "x4-r15-open-gate-direct-B");
        physicalThread.setDaemon(true);
        directThread.setDaemon(true);

        boolean physicalStarted = false;
        boolean directStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            physicalThread.start();
            physicalStarted = true;
            await(physicalOwnerInstalled, deadlineNanos,
                    "physical terminal owner was not installed before opening direct B");
            directThread.start();
            directStarted = true;
            await(providerFailureEntered, deadlineNanos, "direct prepared close did not reach provider B");
            await(contributionAccepted, deadlineNanos,
                    "direct B was not accepted by the installed physical owner before its gate sealed");
            assertFalse(awaitObservation(physicalFinished, deadlineNanos),
                    "physical caller may not return a raw successful T after accepted direct B");
            assertFalse(awaitObservation(directFinished, deadlineNanos),
                    "direct B observer must await the same physical epoch while its owner is paused");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releasePhysicalOwner.countDown();
            if (directObserved.get() == null) {
                try {
                    directObserved.compareAndSet(null, invokeBounded(
                            "x4-r15-open-gate-direct-cleanup", directPrepared::close, terminalOrderDeadline(),
                            "open-gate direct-B cleanup did not complete"));
                } catch (Throwable failure) {
                    directObserved.compareAndSet(null, failure);
                }
            }
        }

        awaitWorkerTermination(physicalThread, physicalFinished, physicalStarted, deadlineNanos,
                "open-gate physical worker did not terminate after its barrier released");
        awaitWorkerTermination(directThread, directFinished, directStarted, deadlineNanos,
                "open-gate direct-B worker did not terminate after its barrier released");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("open-gate contribution orchestration failed", orchestrationFailure);
        }
        assertSame(providerFailure, physicalObserved.get());
        assertSame(providerFailure, directObserved.get());
        assertEquals(1, membership.revokeCalls());
        assertSame(providerFailure, awaitFutureFailure(
                adapter.drainCompletion().toCompletableFuture(), deadlineNanos,
                "accepted direct B did not publish one terminal drain failure"));
        assertPhysicalSuccessCannotReturnBeforeLateDirectBIsAccepted();
    }

    /**
     * Independent H4 schedule: C has already returned successfully from the real membership
     * revoke, but publication is paused. A late direct B must enter that same epoch before the
     * physical caller is released; it may not become a post-success readmission or let the
     * foreign caller return normally.
     */
    private static void assertPhysicalSuccessCannotReturnBeforeLateDirectBIsAccepted() throws Exception {
        LinkageError providerFailure = new LinkageError("r15-successful-C-late-direct-B");
        var generation = X4LifecycleTestSupport.published(14_642L);
        CountDownLatch providerFailureEntered = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            providerFailureEntered.countDown();
            throwUnchecked(providerFailure);
        });

        X4HostIdentity identity = identity("r15-successful-c-late-direct-b");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_642L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        CountingMembership membership = new CountingMembership(14_643L);
        reservation.commit(membership);
        X4PreparedSnapshot directPrepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();

        CountDownLatch physicalSuccessBeforePublication = new CountDownLatch(1);
        CountDownLatch releasePhysicalPublication = new CountDownLatch(1);
        CountDownLatch contributionAccepted = new CountDownLatch(1);
        adapter.runAfterTerminalPhysicalOutcomeBeforePublicationForTesting(() -> {
            physicalSuccessBeforePublication.countDown();
            try {
                await(releasePhysicalPublication, terminalOrderDeadline(),
                        "successful C publication was not released after late-B admission");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("successful-C publication barrier was interrupted", exception);
            }
        });
        adapter.runAfterTerminalContributionAcceptedForTesting(contributionAccepted::countDown);

        AtomicReference<Throwable> physicalObserved = new AtomicReference<>();
        AtomicReference<Throwable> directObserved = new AtomicReference<>();
        CountDownLatch physicalFinished = new CountDownLatch(1);
        CountDownLatch directFinished = new CountDownLatch(1);
        Thread physicalThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                physicalObserved.set(failure);
            } finally {
                physicalFinished.countDown();
            }
        }, "x4-r15-successful-C-physical");
        Thread directThread = new Thread(() -> {
            try {
                directPrepared.close();
            } catch (Throwable failure) {
                directObserved.set(failure);
            } finally {
                directFinished.countDown();
            }
        }, "x4-r15-successful-C-late-B");
        physicalThread.setDaemon(true);
        directThread.setDaemon(true);

        boolean physicalStarted = false;
        boolean directStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            physicalThread.start();
            physicalStarted = true;
            await(physicalSuccessBeforePublication, deadlineNanos,
                    "membership revoke did not complete successfully before the late-B schedule");
            assertEquals(1, membership.revokeCalls(), "the physical C must already have returned successfully");
            directThread.start();
            directStarted = true;
            await(providerFailureEntered, deadlineNanos, "late direct prepared close did not reach provider B");
            await(contributionAccepted, deadlineNanos,
                    "late direct B was not accepted before successful C publication was released");
            assertFalse(awaitObservation(physicalFinished, deadlineNanos),
                    "foreign physical caller must remain pending until late B belongs to its epoch");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releasePhysicalPublication.countDown();
            if (directObserved.get() == null) {
                try {
                    directObserved.compareAndSet(null, invokeBounded(
                            "x4-r15-successful-C-late-B-cleanup", directPrepared::close, terminalOrderDeadline(),
                            "successful-C late-B cleanup did not complete"));
                } catch (Throwable failure) {
                    directObserved.compareAndSet(null, failure);
                }
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        awaitWorkerTermination(physicalThread, physicalFinished, physicalStarted, cleanupDeadlineNanos,
                "successful-C physical worker did not terminate after late-B publication release");
        awaitWorkerTermination(directThread, directFinished, directStarted, cleanupDeadlineNanos,
                "successful-C late-B worker did not terminate after publication release");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("successful-C late-B orchestration failed", orchestrationFailure);
        }
        assertSame(providerFailure, physicalObserved.get(),
                "the foreign physical caller may not return raw success before late B is carried");
        assertSame(providerFailure, directObserved.get());
        assertSame(providerFailure, awaitFutureFailure(
                adapter.drainCompletion().toCompletableFuture(), cleanupDeadlineNanos,
                "successful-C late-B path did not publish a shared terminal drain failure"));
    }

    /**
     * H3 publication fence: a completed failed C cannot escape merely because no submit is
     * currently running. The already-published idle snapshot is still an exact B source and
     * keeps every public observer behind the epoch until it drains.
     */
    private static void assertIdlePreparedSnapshotFencesFailedPhysicalTransition() throws Exception {
        LinkageError providerFailure = new LinkageError("r15-idle-snapshot-B");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("r15-idle-snapshot-C");
        var generation = X4LifecycleTestSupport.published(14_650L);
        generation.provider().onRetire(() -> throwUnchecked(providerFailure));
        X4HostIdentity identity = identity("r15-idle-snapshot-c-fence");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_650L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_651L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot idlePrepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();

        AtomicReference<Throwable> publicCloseObserved = new AtomicReference<>();
        AtomicReference<Throwable> directCloseObserved = new AtomicReference<>();
        CountDownLatch publicCloseFinished = new CountDownLatch(1);
        Thread publicCloseThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                publicCloseObserved.set(failure);
            } finally {
                publicCloseFinished.countDown();
            }
        }, "x4-r15-idle-snapshot-public-C");
        publicCloseThread.setDaemon(true);

        boolean publicCloseStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            publicCloseThread.start();
            publicCloseStarted = true;
            await(membership.firstRevoke(), deadlineNanos,
                    "failed physical C did not enter its real membership revoke");
            membership.releaseFirstRevoke();
            assertFalse(awaitObservation(publicCloseFinished, deadlineNanos),
                    "failed C must remain fenced while the idle prepared snapshot can still supply B");
            assertFalse(adapter.state() == X4HostLifecycleState.CLOSED
                            || adapter.state() == X4HostLifecycleState.RETIRED,
                    "C may not expose a terminal lifecycle state while the idle B source remains open");
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "C is provisional evidence and may not leak through diagnostics before E seals");
            assertFalse(drain.isDone(), "the stable drain future may not publish provisional C");
            directCloseObserved.set(invokeBounded("x4-r15-idle-snapshot-direct-B", idlePrepared::close, deadlineNanos,
                    "idle-snapshot direct B close did not complete"));
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            membership.releaseFirstRevoke();
            if (directCloseObserved.get() == null) {
                try {
                    directCloseObserved.compareAndSet(null, invokeBounded(
                            "x4-r15-idle-snapshot-direct-cleanup", idlePrepared::close, terminalOrderDeadline(),
                            "idle-snapshot cleanup B close did not complete"));
                } catch (Throwable failure) {
                    directCloseObserved.compareAndSet(null, failure);
                }
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        Throwable retryObserved = null;
        try {
            awaitWorkerTermination(publicCloseThread, publicCloseFinished, publicCloseStarted, cleanupDeadlineNanos,
                    "idle-snapshot public C worker did not terminate after the B source released");
        } finally {
            retryObserved = invokeBounded("x4-r15-idle-snapshot-retry", adapter::close, terminalOrderDeadline(),
                    "idle-snapshot retained-receipt retry did not complete");
        }
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("idle-snapshot C fence orchestration failed", orchestrationFailure);
        }
        assertSame(providerFailure, directCloseObserved.get());
        assertSame(providerFailure, publicCloseObserved.get(),
                "the original public C observer must replay F, never raw C");
        assertSame(providerFailure, retryObserved,
                "the retained receipt retry must replay F after physically clearing C's membership");
        assertExactSuppressionChain(providerFailure, revokeFailure);
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertSame(providerFailure, awaitFutureFailure(drain, cleanupDeadlineNanos,
                "idle-snapshot C fence did not publish its bounded shared drain failure"));
    }

    /**
     * H1/direct-B schedule: H1 commits A/B1 into an OPEN epoch while a second direct pin remains
     * an active pre-seal source gate. The B2 contribution must be admitted before F seals; a later
     * readmission may not merely replay the old A/C result.
     */
    private static void assertDirectProviderFailureCarriesAcrossSubmissionSeal() throws Exception {
        AssertionError rendererFailure = new AssertionError("r15-open-gate-A");
        LinkageError firstProviderFailure = new LinkageError("r15-open-gate-H-B1");
        LinkageError lateDirectProviderFailure = new LinkageError("r15-open-gate-direct-B2");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("r15-open-gate-C");
        var generation = X4LifecycleTestSupport.published(14_660L);
        CountDownLatch firstProviderFailureEntered = new CountDownLatch(1);
        CountDownLatch lateDirectProviderFailureEntered = new CountDownLatch(1);

        X4HostIdentity identity = identity("r15-open-gate-late-b");
        AtomicReference<X4PreparedSnapshot> submittingPreparedReference = new AtomicReference<>();
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_660L),
                new BlendRenderer((snapshot, context) -> {
                    rendererEntered.countDown();
                    try {
                        await(releaseRenderer, terminalOrderDeadline(),
                                "open-gate A renderer was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("open-gate A renderer was interrupted", exception);
                    }
                    submittingPreparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_661L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot submittingPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot directPrepared = adapter.prepare(worldFrame(identity));
        submittingPreparedReference.set(submittingPrepared);
        AtomicInteger providerLeaseCloseCalls = new AtomicInteger();
        adapter.runAfterProviderLeaseCloseForTesting(() -> {
            int release = providerLeaseCloseCalls.incrementAndGet();
            if (release == 1) {
                firstProviderFailureEntered.countDown();
                throwUnchecked(firstProviderFailure);
            }
            if (release == 2) {
                lateDirectProviderFailureEntered.countDown();
                throwUnchecked(lateDirectProviderFailure);
            }
            throw new AssertionError("unexpected provider-lease close count " + release);
        });
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();
        AtomicInteger contributionAcceptedCount = new AtomicInteger();
        CountDownLatch submissionContributionAccepted = new CountDownLatch(1);
        CountDownLatch directContributionAccepted = new CountDownLatch(1);
        CountDownLatch releaseDirectContributionAccepted = new CountDownLatch(1);
        adapter.runAfterTerminalContributionAcceptedForTesting(() -> {
            int contribution = contributionAcceptedCount.incrementAndGet();
            if (contribution == 1) {
                submissionContributionAccepted.countDown();
                return;
            }
            if (contribution == 2) {
                directContributionAccepted.countDown();
                try {
                    await(releaseDirectContributionAccepted, terminalOrderDeadline(),
                            "open-gate direct B2 contribution was not released before seal");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("open-gate direct B2 contribution barrier was interrupted", exception);
                }
                return;
            }
            throw new AssertionError("unexpected open-gate contribution " + contribution);
        });

        AtomicReference<Throwable> submitObserved = new AtomicReference<>();
        AtomicReference<Throwable> physicalObserved = new AtomicReference<>();
        AtomicReference<Throwable> directObserved = new AtomicReference<>();
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch physicalFinished = new CountDownLatch(1);
        CountDownLatch directFinished = new CountDownLatch(1);
        Thread submitThread = new Thread(() -> {
            try {
                adapter.submit(submittingPrepared, unusedContext());
            } catch (Throwable failure) {
                submitObserved.set(failure);
            } finally {
                submitFinished.countDown();
            }
        }, "x4-r15-open-gate-submit");
        Thread physicalThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                physicalObserved.set(failure);
            } finally {
                physicalFinished.countDown();
            }
        }, "x4-r15-open-gate-physical-C");
        Thread directThread = new Thread(() -> {
            try {
                directPrepared.close();
            } catch (Throwable failure) {
                directObserved.set(failure);
            } finally {
                directFinished.countDown();
            }
        }, "x4-r15-open-gate-direct-B");
        submitThread.setDaemon(true);
        physicalThread.setDaemon(true);
        directThread.setDaemon(true);

        boolean submitStarted = false;
        boolean physicalStarted = false;
        boolean directStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            submitThread.start();
            submitStarted = true;
            await(rendererEntered, deadlineNanos, "submitting H did not enter its renderer");
            physicalThread.start();
            physicalStarted = true;
            await(membership.firstRevoke(), deadlineNanos, "physical C did not begin before H release");
            membership.releaseFirstRevoke();
            releaseRenderer.countDown();
            await(firstProviderFailureEntered, deadlineNanos,
                    "submitting H did not produce its exact first provider B1");
            await(submissionContributionAccepted, deadlineNanos,
                    "submitting H did not commit its A/B1 contribution into the OPEN epoch");
            assertEquals(1, contributionAcceptedCount.get(),
                    "only H1 may have contributed while the direct source still gates seal");
            assertFalse(awaitObservation(submitFinished, deadlineNanos),
                    "H1 may not escape while the active direct source still gates seal");
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "A/B1/C are provisional until the active direct B2 source contributes");
            assertFalse(drain.isDone(), "drain may not publish before the direct B2 source contributes");

            directThread.start();
            directStarted = true;
            await(lateDirectProviderFailureEntered, deadlineNanos,
                    "late direct pin did not produce its distinct B2 before seal");
            await(directContributionAccepted, deadlineNanos,
                    "late direct B2 did not commit into the OPEN epoch before seal");
            assertEquals(2, contributionAcceptedCount.get(),
                    "both H1 and direct B2 must commit before the sole seal claim");
            assertFalse(awaitObservation(submitFinished, deadlineNanos),
                    "H1 may not escape while the accepted direct B2 barrier still prevents seal");
            assertFalse(awaitObservation(directFinished, deadlineNanos),
                    "accepted direct B2 may not escape before the sole seal claim");
            assertEquals("", adapter.leaseDiagnostics().terminalFailureType(),
                    "F may not publish while the accepted direct B2 barrier holds seal");
            assertFalse(drain.isDone(), "drain may not publish while the accepted direct B2 barrier holds seal");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseRenderer.countDown();
            membership.releaseFirstRevoke();
            releaseDirectContributionAccepted.countDown();
            if (directObserved.get() == null) {
                try {
                    directObserved.compareAndSet(null, invokeBounded(
                            "x4-r15-open-gate-direct-cleanup", directPrepared::close, terminalOrderDeadline(),
                            "open-gate late-B cleanup close did not complete"));
                } catch (Throwable failure) {
                    directObserved.compareAndSet(null, failure);
                }
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        Throwable retryObserved = null;
        try {
            awaitWorkerTermination(submitThread, submitFinished, submitStarted, cleanupDeadlineNanos,
                    "open-gate submit worker did not terminate after B2 contribution release");
            awaitWorkerTermination(physicalThread, physicalFinished, physicalStarted, cleanupDeadlineNanos,
                    "open-gate physical-C worker did not terminate after B2 contribution release");
            awaitWorkerTermination(directThread, directFinished, directStarted, cleanupDeadlineNanos,
                    "open-gate late-B worker did not terminate after B2 contribution release");
        } finally {
            retryObserved = invokeBounded("x4-r15-open-gate-retry", adapter::close, terminalOrderDeadline(),
                    "open-gate late-B retained-receipt retry did not complete");
        }
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("open-gate late-B orchestration failed", orchestrationFailure);
        }
        assertSame(rendererFailure, submitObserved.get());
        assertSame(rendererFailure, physicalObserved.get());
        assertSame(rendererFailure, directObserved.get());
        assertSame(rendererFailure, retryObserved);
        assertEquals(2, providerLeaseCloseCalls.get(),
                "the H B1 and direct B2 fixtures must be distinct real lease-close boundaries");
        assertSuppressionGraphContains(rendererFailure, firstProviderFailure);
        assertSuppressionGraphContains(rendererFailure, lateDirectProviderFailure);
        assertSuppressionGraphContains(rendererFailure, revokeFailure);
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertSame(rendererFailure, awaitFutureFailure(drain, cleanupDeadlineNanos,
                "open-gate late-B race did not publish its bounded immutable F"));
    }

    /**
     * H2 contribution race: public C has already opened E, so both renderer Error identities are
     * Ae evidence. H1 owns the selector/claim while paused; H2 must not commit until H1 releases
     * it. Both then fold into one epoch and one deterministic A1-rooted F graph.
     */
    private static void assertTwoReservedSubmissionsCannotReplaceOneTerminalEpoch() throws Exception {
        AssertionError firstRendererFailure = new AssertionError("r15-two-H-A1");
        AssertionError finalRendererFailure = new AssertionError("r15-two-H-A2");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("r15-two-H-C");
        var generation = X4LifecycleTestSupport.published(14_670L);

        X4HostIdentity identity = identity("r15-two-h-overwrite");
        AtomicReference<X4PreparedSnapshot> firstPreparedReference = new AtomicReference<>();
        AtomicReference<X4PreparedSnapshot> secondPreparedReference = new AtomicReference<>();
        AtomicInteger rendererCalls = new AtomicInteger();
        CountDownLatch firstRendererEntered = new CountDownLatch(1);
        CountDownLatch secondRendererEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRenderer = new CountDownLatch(1);
        CountDownLatch releaseSecondRenderer = new CountDownLatch(1);
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_670L),
                new BlendRenderer((snapshot, context) -> {
                    int call = rendererCalls.incrementAndGet();
                    try {
                        if (call == 1) {
                            firstRendererEntered.countDown();
                            await(releaseFirstRenderer, terminalOrderDeadline(),
                                    "two-H first renderer was not released");
                            firstPreparedReference.get().close();
                            throwUnchecked(firstRendererFailure);
                        }
                        if (call == 2) {
                            secondRendererEntered.countDown();
                            await(releaseSecondRenderer, terminalOrderDeadline(),
                                    "two-H second renderer was not released");
                            secondPreparedReference.get().close();
                            throwUnchecked(finalRendererFailure);
                        }
                        throw new AssertionError("unexpected two-H renderer invocation " + call);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("two-H renderer was interrupted", exception);
                    }
                }),
                identity,
                generation.session());
        AtomicInteger providerLeaseCloseCalls = new AtomicInteger();
        CountDownLatch firstProviderLeaseCloseEntered = new CountDownLatch(1);
        CountDownLatch secondProviderLeaseCloseEntered = new CountDownLatch(1);
        adapter.runAfterProviderLeaseCloseForTesting(() -> {
            int release = providerLeaseCloseCalls.incrementAndGet();
            if (release == 1) {
                firstProviderLeaseCloseEntered.countDown();
                return;
            }
            if (release == 2) {
                secondProviderLeaseCloseEntered.countDown();
                return;
            }
            throw new AssertionError("unexpected two-H provider lease-close count " + release);
        });
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_671L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot firstPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot secondPrepared = adapter.prepare(worldFrame(identity));
        firstPreparedReference.set(firstPrepared);
        secondPreparedReference.set(secondPrepared);
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();

        CountDownLatch firstContributionPaused = new CountDownLatch(1);
        CountDownLatch releaseFirstContribution = new CountDownLatch(1);
        adapter.runBeforeTerminalContributionCommitForTesting(() -> {
            firstContributionPaused.countDown();
            try {
                await(releaseFirstContribution, terminalOrderDeadline(),
                        "two-H first contribution was not released after H2 attempted the same claim");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("two-H contribution barrier was interrupted", exception);
            }
        });
        AtomicInteger contributionCommitOrder = new AtomicInteger();
        CountDownLatch firstContributionCommitted = new CountDownLatch(1);
        CountDownLatch secondContributionCommitted = new CountDownLatch(1);
        adapter.runAfterTerminalContributionAcceptedForTesting(() -> {
            int contribution = contributionCommitOrder.incrementAndGet();
            if (contribution == 1) {
                firstContributionCommitted.countDown();
            } else if (contribution == 2) {
                secondContributionCommitted.countDown();
            } else {
                throw new AssertionError("unexpected two-H contribution commit " + contribution);
            }
        });

        AtomicReference<Throwable> firstObserved = new AtomicReference<>();
        AtomicReference<Throwable> secondObserved = new AtomicReference<>();
        AtomicReference<Throwable> physicalObserved = new AtomicReference<>();
        AtomicBoolean secondInterruptedAfter = new AtomicBoolean();
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        CountDownLatch physicalFinished = new CountDownLatch(1);
        Thread firstSubmit = new Thread(() -> {
            try {
                adapter.submit(firstPrepared, unusedContext());
            } catch (Throwable failure) {
                firstObserved.set(failure);
            } finally {
                firstFinished.countDown();
            }
        }, "x4-r15-two-H-first");
        Thread secondSubmit = new Thread(() -> {
            try {
                adapter.submit(secondPrepared, unusedContext());
            } catch (Throwable failure) {
                secondObserved.set(failure);
            } finally {
                secondInterruptedAfter.set(Thread.currentThread().isInterrupted());
                secondFinished.countDown();
            }
        }, "x4-r15-two-H-second");
        Thread physicalThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                physicalObserved.set(failure);
            } finally {
                physicalFinished.countDown();
            }
        }, "x4-r15-two-H-physical-C");
        firstSubmit.setDaemon(true);
        secondSubmit.setDaemon(true);
        physicalThread.setDaemon(true);

        boolean firstStarted = false;
        boolean secondStarted = false;
        boolean physicalStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            firstSubmit.start();
            firstStarted = true;
            await(firstRendererEntered, deadlineNanos, "two-H first submit did not enter renderer");
            secondSubmit.start();
            secondStarted = true;
            await(secondRendererEntered, deadlineNanos, "two-H second submit did not enter renderer");
            physicalThread.start();
            physicalStarted = true;
            await(membership.firstRevoke(), deadlineNanos, "two-H physical C did not start");
            membership.releaseFirstRevoke();

            releaseFirstRenderer.countDown();
            await(firstProviderLeaseCloseEntered, deadlineNanos,
                    "H1 did not finish its exact first provider lease close");
            await(firstContributionPaused, deadlineNanos,
                    "H1 did not stop after selecting shared C and before its contribution commit");
            releaseSecondRenderer.countDown();
            await(secondProviderLeaseCloseEntered, deadlineNanos,
                    "H2 did not reach its distinct second provider lease close before the contribution gate");
            awaitWorkerGatePending(secondSubmit, deadlineNanos,
                    "H2 did not wait at the contribution gate while H1 held the exact claim");
            secondSubmit.interrupt();
            assertFalse(awaitObservation(secondFinished, deadlineNanos),
                    "interrupt may not let an admitted H2 contributor abandon its exact epoch evidence");
            assertFalse(awaitObservation(secondContributionCommitted, deadlineNanos),
                    "H2 may not commit while H1 still owns the epoch contribution claim");
            assertFalse(awaitObservation(firstContributionCommitted, deadlineNanos),
                    "H1 cannot report a commit while its own selector barrier remains held");
            releaseFirstContribution.countDown();
            await(firstContributionCommitted, deadlineNanos,
                    "H1 did not commit first after its contribution claim released");
            await(secondContributionCommitted, deadlineNanos,
                    "H2 did not commit after H1 released the shared contribution claim");
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseFirstRenderer.countDown();
            releaseSecondRenderer.countDown();
            releaseFirstContribution.countDown();
            membership.releaseFirstRevoke();
            try {
                invokeBounded("x4-r15-two-H-first-cleanup", firstPrepared::close, terminalOrderDeadline(),
                        "two-H first prepared cleanup did not complete");
            } catch (Throwable ignored) {
                // The workers' exact outcomes are asserted after they terminate.
            }
            try {
                invokeBounded("x4-r15-two-H-second-cleanup", secondPrepared::close, terminalOrderDeadline(),
                        "two-H second prepared cleanup did not complete");
            } catch (Throwable ignored) {
                // The workers' exact outcomes are asserted after they terminate.
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        Throwable retryObserved = null;
        try {
            awaitWorkerTermination(firstSubmit, firstFinished, firstStarted, cleanupDeadlineNanos,
                    "two-H first worker did not terminate after barriers released");
            awaitWorkerTermination(secondSubmit, secondFinished, secondStarted, cleanupDeadlineNanos,
                    "two-H second worker did not terminate after barriers released");
            awaitWorkerTermination(physicalThread, physicalFinished, physicalStarted, cleanupDeadlineNanos,
                    "two-H physical-C worker did not terminate after barriers released");
        } finally {
            // The retained C receipt is always retried even when a preceding worker join fails.
            retryObserved = invokeBounded("x4-r15-two-H-retry", adapter::close, terminalOrderDeadline(),
                    "two-H retained-receipt retry did not complete");
        }
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("two-H contribution overwrite orchestration failed", orchestrationFailure);
        }
        assertEquals(2, contributionCommitOrder.get(), "exactly H1 and H2 may commit epoch evidence");
        assertSame(firstRendererFailure, firstObserved.get());
        assertSame(firstRendererFailure, secondObserved.get());
        assertSame(firstRendererFailure, physicalObserved.get());
        assertSame(firstRendererFailure, retryObserved);
        assertTrue(secondInterruptedAfter.get(),
                "the interrupted H2 contributor must restore its interrupt flag only after F is available");
        assertEquals(2, providerLeaseCloseCalls.get(),
                "the schedule must observe both exact H provider lease closes before sealing");
        assertEquals(2, firstRendererFailure.getSuppressed().length,
                "A* must retain A2 and the physical C semantic group as two ordered A1 edges");
        assertSame(finalRendererFailure, firstRendererFailure.getSuppressed()[0]);
        assertSame(revokeFailure, firstRendererFailure.getSuppressed()[1]);
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertSame(firstRendererFailure, awaitFutureFailure(drain, cleanupDeadlineNanos,
                "two-H epoch did not publish a bounded ordered A1/A2 identity"));
    }

    /**
     * Two direct release callbacks overlap. Only callback #1 is the exact owner allowed to
     * re-enter close/retire and return to its outer release; callback #2 and an independently
     * started foreign close must wait. The existing snapshot objects—not a scalar Thread owner—
     * are therefore the required callback-owner chain.
     */
    private static void assertCallbackReentryUsesTheExistingSnapshotOwner() throws Exception {
        LinkageError firstProviderFailure = new LinkageError("r15-callback-owner-B1");
        LinkageError secondProviderFailure = new LinkageError("r15-callback-owner-B2");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("r15-callback-owner-C");
        var generation = X4LifecycleTestSupport.published(14_680L);
        X4HostIdentity identity = identity("r15-callback-owner");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_680L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_681L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot firstPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot secondPrepared = adapter.prepare(worldFrame(identity));
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();

        CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        CountDownLatch secondCallbackEntered = new CountDownLatch(1);
        CountDownLatch firstCallbackReentryReturned = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch releaseSecondCallback = new CountDownLatch(1);
        CountDownLatch firstBContributionAccepted = new CountDownLatch(1);
        CountDownLatch secondBContributionAccepted = new CountDownLatch(1);
        AtomicInteger callbackOrder = new AtomicInteger();
        AtomicInteger contributionOrder = new AtomicInteger();
        AtomicReference<Throwable> firstCallbackReentryFailure = new AtomicReference<>();
        AtomicBoolean firstCallbackInterruptCleared = new AtomicBoolean();
        AtomicBoolean firstCallbackInterruptRestored = new AtomicBoolean();
        adapter.runAfterTerminalContributionAcceptedForTesting(() -> {
            int contribution = contributionOrder.incrementAndGet();
            if (contribution == 1) {
                firstBContributionAccepted.countDown();
            } else if (contribution == 2) {
                secondBContributionAccepted.countDown();
            } else {
                throw new AssertionError("unexpected callback-owner contribution " + contribution);
            }
        });
        adapter.runAfterProviderLeaseCloseForTesting(() -> {
            int callback = callbackOrder.incrementAndGet();
            if (callback == 1) {
                firstCallbackEntered.countDown();
                try {
                    Thread.currentThread().interrupt();
                    adapter.close();
                    firstCallbackInterruptCleared.set(!Thread.currentThread().isInterrupted());
                    adapter.retire();
                } catch (Throwable failure) {
                    firstCallbackReentryFailure.set(failure);
                } finally {
                    firstCallbackReentryReturned.countDown();
                }
                try {
                    await(releaseFirstCallback, terminalOrderDeadline(),
                            "first callback was not released after its exact owner reentry");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("first callback owner barrier was interrupted", exception);
                }
                throwUnchecked(firstProviderFailure);
            }
            if (callback == 2) {
                secondCallbackEntered.countDown();
                try {
                    await(releaseSecondCallback, terminalOrderDeadline(),
                            "second callback was not released after foreign wait observation");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("second callback owner barrier was interrupted", exception);
                }
                throwUnchecked(secondProviderFailure);
            }
            throw new AssertionError("unexpected callback-owner lease-close count " + callback);
        });

        AtomicReference<Throwable> firstObserved = new AtomicReference<>();
        AtomicReference<Throwable> secondObserved = new AtomicReference<>();
        AtomicReference<Throwable> foreignObserved = new AtomicReference<>();
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        CountDownLatch foreignFinished = new CountDownLatch(1);
        Thread firstCloseThread = new Thread(() -> {
            try {
                firstPrepared.close();
            } catch (Throwable failure) {
                firstObserved.set(failure);
            } finally {
                firstCallbackInterruptRestored.set(Thread.currentThread().isInterrupted());
                firstFinished.countDown();
            }
        }, "x4-r15-callback-owner-first");
        Thread secondCloseThread = new Thread(() -> {
            try {
                secondPrepared.close();
            } catch (Throwable failure) {
                secondObserved.set(failure);
            } finally {
                secondFinished.countDown();
            }
        }, "x4-r15-callback-owner-second");
        Thread foreignCloseThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                foreignObserved.set(failure);
            } finally {
                foreignFinished.countDown();
            }
        }, "x4-r15-callback-owner-foreign");
        firstCloseThread.setDaemon(true);
        secondCloseThread.setDaemon(true);
        foreignCloseThread.setDaemon(true);

        boolean firstStarted = false;
        boolean secondStarted = false;
        boolean foreignStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            firstCloseThread.start();
            firstStarted = true;
            await(firstCallbackEntered, deadlineNanos, "first direct callback did not enter");
            await(firstCallbackReentryReturned, deadlineNanos,
                    "the exact first callback owner self-waited instead of coalescing to its outer release");
            assertEquals(null, firstCallbackReentryFailure.get(),
                    "the exact callback owner must not receive provisional C/F before its outer release returns");
            assertEquals(0, membership.revokeCalls(),
                    "callback reentry may only open/upgrade E before its direct B carrier commits");
            secondCloseThread.start();
            secondStarted = true;
            await(secondCallbackEntered, deadlineNanos,
                    "second direct callback did not overlap the first callback-owner transition");
            foreignCloseThread.start();
            foreignStarted = true;
            await(membership.firstRevoke(), deadlineNanos,
                    "the foreign caller did not begin private physical C while callback carriers stayed open");
            assertFalse(awaitObservation(foreignFinished, deadlineNanos),
                    "a foreign closer may not impersonate an active callback owner");

            releaseFirstCallback.countDown();
            await(firstBContributionAccepted, deadlineNanos,
                    "the first callback B1 did not commit before the second callback was released");
            releaseSecondCallback.countDown();
            await(secondBContributionAccepted, deadlineNanos,
                    "the second callback B2 did not commit after the first exact carrier");
            membership.releaseFirstRevoke();
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            membership.releaseFirstRevoke();
            releaseFirstCallback.countDown();
            releaseSecondCallback.countDown();
            try {
                invokeBounded("x4-r15-callback-owner-first-cleanup", firstPrepared::close, terminalOrderDeadline(),
                        "first callback-owner prepared cleanup did not complete");
            } catch (Throwable ignored) {
                // Exact terminal outcomes are observed from the bounded worker joins below.
            }
            try {
                invokeBounded("x4-r15-callback-owner-second-cleanup", secondPrepared::close, terminalOrderDeadline(),
                        "second callback-owner prepared cleanup did not complete");
            } catch (Throwable ignored) {
                // Exact terminal outcomes are observed from the bounded worker joins below.
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        Throwable retryObserved = null;
        try {
            awaitWorkerTermination(firstCloseThread, firstFinished, firstStarted, cleanupDeadlineNanos,
                    "first callback-owner worker did not terminate after callback release");
            awaitWorkerTermination(secondCloseThread, secondFinished, secondStarted, cleanupDeadlineNanos,
                    "second callback worker did not terminate after callback release");
            awaitWorkerTermination(foreignCloseThread, foreignFinished, foreignStarted, cleanupDeadlineNanos,
                    "foreign callback waiter did not terminate after callback release");
        } finally {
            retryObserved = invokeBounded("x4-r15-callback-owner-retry", adapter::close, terminalOrderDeadline(),
                    "callback-owner retained-receipt retry did not complete");
        }
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("callback-owner overlap orchestration failed", orchestrationFailure);
        }
        assertEquals(2, callbackOrder.get(), "the fixture must run exactly two overlapping callbacks");
        assertEquals(2, contributionOrder.get(), "B1 then B2 must commit through the exact callback carriers");
        assertTrue(firstCallbackInterruptCleared.get(),
                "nested callback close must transfer and clear its interrupt before outer provider work resumes");
        assertTrue(firstCallbackInterruptRestored.get(),
                "the outer provider source must restore nested interrupt only after its final F replay");
        assertSame(firstProviderFailure, firstObserved.get());
        assertSame(firstProviderFailure, secondObserved.get());
        assertSame(firstProviderFailure, foreignObserved.get());
        assertSame(firstProviderFailure, retryObserved);
        assertSuppressionGraphContains(firstProviderFailure, secondProviderFailure);
        assertSuppressionGraphContains(firstProviderFailure, revokeFailure);
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertSame(firstProviderFailure, awaitFutureFailure(drain, cleanupDeadlineNanos,
                "callback-owner overlap did not publish one bounded exact F"));

        // This is the epoch==NONE counterpart of the callback-owner case above. S1's provider
        // callback closes S2 successfully (B2 == null) before S1 itself turns into B1. The nested
        // early return must transfer—not restore—S1's interrupt receipt while no epoch yet exists.
        LinkageError localNestedOuterProviderFailure = new LinkageError("r15-callback-local-s1-B1");
        AssertionError localNestedInterruptSentinel = new AssertionError("r15-callback-local-s2-interrupt-sentinel");
        var localNestedGeneration = X4LifecycleTestSupport.published(14_683L);
        X4HostIdentity localNestedIdentity = identity("r15-callback-local-s1-s2");
        CountingMembership localNestedMembership = new CountingMembership(14_684L);
        AtomicReference<X4PreparedSnapshot> localNestedPreparedReference = new AtomicReference<>();
        AtomicReference<Throwable> localNestedCloseFailure = new AtomicReference<>();
        AtomicInteger localNestedProviderCallbacks = new AtomicInteger();
        AtomicBoolean localNestedB2Succeeded = new AtomicBoolean();
        AtomicBoolean localNestedClearedBeforeOuterFailure = new AtomicBoolean();
        AtomicBoolean localNestedObservedNoPhysicalBeforeOuterFailure = new AtomicBoolean();
        AtomicBoolean localNestedOuterInterruptRestored = new AtomicBoolean();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> localNestedAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_683L), new BlendRenderer((snapshot, context) -> { }), localNestedIdentity,
                localNestedGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> localNestedReservation =
                localNestedAdapter.freezeAndReserveRegistration();
        localNestedReservation.commit(localNestedMembership);
        X4PreparedSnapshot localNestedOuterPrepared = localNestedAdapter.prepare(worldFrame(localNestedIdentity));
        X4PreparedSnapshot localNestedPrepared = localNestedAdapter.prepare(worldFrame(localNestedIdentity));
        localNestedPreparedReference.set(localNestedPrepared);
        localNestedAdapter.runAfterProviderLeaseCloseForTesting(() -> {
            int callback = localNestedProviderCallbacks.incrementAndGet();
            if (callback == 1) {
                Thread.currentThread().interrupt();
                try {
                    localNestedPreparedReference.get().close();
                } catch (Throwable failure) {
                    localNestedCloseFailure.set(failure);
                }
                localNestedClearedBeforeOuterFailure.set(!Thread.currentThread().isInterrupted());
                localNestedObservedNoPhysicalBeforeOuterFailure.set(localNestedMembership.revokeCalls() == 0);
                if (Thread.currentThread().isInterrupted()) throwUnchecked(localNestedInterruptSentinel);
                throwUnchecked(localNestedOuterProviderFailure);
            }
            if (callback == 2) {
                localNestedB2Succeeded.set(true);
                return;
            }
            throw new AssertionError("unexpected local nested provider callback " + callback);
        });
        localNestedGeneration.session().retire();
        var localNestedDrain = localNestedAdapter.drainCompletion().toCompletableFuture();
        Throwable localNestedOuterObserved = null;
        try {
            localNestedOuterObserved = invokeBounded("x4-r15-callback-local-s1-close", () -> {
                try {
                    localNestedOuterPrepared.close();
                } finally {
                    localNestedOuterInterruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }, terminalOrderDeadline(), "local S1 provider callback did not complete its exact F replay");
        } finally {
            try {
                invokeBounded("x4-r15-callback-local-s1-cleanup", localNestedOuterPrepared::close,
                        terminalOrderDeadline(), "local S1 cleanup did not complete");
            } catch (Throwable ignored) {
                // The bounded outer close above owns the exact assertion evidence.
            }
            try {
                invokeBounded("x4-r15-callback-local-s2-cleanup", localNestedPrepared::close,
                        terminalOrderDeadline(), "local S2 cleanup did not complete");
            } catch (Throwable ignored) {
                // The bounded outer close above owns the exact assertion evidence.
            }
        }
        assertEquals(2, localNestedProviderCallbacks.get(),
                "S1 callback must synchronously observe exactly its successful B2-null S2 callback");
        assertTrue(localNestedB2Succeeded.get(), "nested S2 provider release must contribute B2 == null");
        assertEquals(null, localNestedCloseFailure.get(),
                "nested S2.close must return through S1's exact provider callback while E is still NONE");
        assertTrue(localNestedObservedNoPhysicalBeforeOuterFailure.get(),
                "the B2-null nested close must not manufacture E/C before outer S1 produces B1");
        assertTrue(localNestedClearedBeforeOuterFailure.get(),
                "nested epoch==NONE return must leave S1 callback with its interrupt receipt cleared");
        assertSame(localNestedOuterProviderFailure, localNestedOuterObserved,
                "outer S1 provider must replay its exact B1 after nested B2-null return");
        assertTrue(localNestedOuterInterruptRestored.get(),
                "outer S1 must restore the transferred interrupt only after its final F replay");
        assertEquals(1, localNestedMembership.revokeCalls(), "outer B1 must drive exactly one physical C");
        assertTrue(localNestedMembership.revoked());
        assertSuppressionGraphExcludes(localNestedOuterProviderFailure, localNestedInterruptSentinel);
        assertSame(localNestedOuterProviderFailure, awaitFutureFailure(localNestedDrain, terminalOrderDeadline(),
                "local S1/S2 callback lane did not publish immutable B1/F"));

        // The renderer H itself is an exact callback owner while it is admitted. Its close/retire
        // calls must only open/upgrade E and return before renderer completion; H then self-closes,
        // contributes A, and is the one source that drives/seals/replays it.
        AssertionError rendererReentryFailure = new AssertionError("r15-renderer-owner-reentry-A");
        var rendererGeneration = X4LifecycleTestSupport.published(14_682L);
        X4HostIdentity rendererIdentity = identity("r15-renderer-owner-reentry");
        AtomicReference<X4PreparedSnapshot> rendererPreparedReference = new AtomicReference<>();
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch rendererReentryReturned = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        AtomicReference<Throwable> rendererReentryObserved = new AtomicReference<>();
        AtomicBoolean rendererReentryInterruptCleared = new AtomicBoolean();
        AtomicBoolean rendererInterruptRestored = new AtomicBoolean();
        AtomicReference<DefaultX4HostAdapter<X4HostFrames.WorldObject>> rendererAdapterReference = new AtomicReference<>();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> rendererAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_682L),
                new BlendRenderer((snapshot, context) -> {
                    rendererEntered.countDown();
                    try {
                        DefaultX4HostAdapter<X4HostFrames.WorldObject> callbackAdapter = rendererAdapterReference.get();
                        Thread.currentThread().interrupt();
                        callbackAdapter.close();
                        rendererReentryInterruptCleared.set(!Thread.currentThread().isInterrupted());
                        callbackAdapter.retire();
                    } catch (Throwable failure) {
                        rendererReentryObserved.set(failure);
                    } finally {
                        rendererReentryReturned.countDown();
                    }
                    try {
                        await(releaseRenderer, terminalOrderDeadline(),
                                "renderer callback was not released after exact H reentry");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("renderer callback owner barrier was interrupted", exception);
                    }
                    rendererPreparedReference.get().close();
                    throwUnchecked(rendererReentryFailure);
                }),
                rendererIdentity,
                rendererGeneration.session());
        rendererAdapterReference.set(rendererAdapter);
        X4HostRegistrationReservation<X4HostFrames.WorldObject> rendererReservation =
                rendererAdapter.freezeAndReserveRegistration();
        CountingMembership rendererMembership = new CountingMembership(14_683L);
        rendererReservation.commit(rendererMembership);
        X4PreparedSnapshot rendererPrepared = rendererAdapter.prepare(worldFrame(rendererIdentity));
        rendererPreparedReference.set(rendererPrepared);
        rendererGeneration.session().retire();
        var rendererDrain = rendererAdapter.drainCompletion().toCompletableFuture();
        AtomicReference<Throwable> rendererSubmitObserved = new AtomicReference<>();
        CountDownLatch rendererSubmitFinished = new CountDownLatch(1);
        Thread rendererSubmit = new Thread(() -> {
            try {
                rendererAdapter.submit(rendererPrepared, unusedContext());
            } catch (Throwable failure) {
                rendererSubmitObserved.set(failure);
            } finally {
                rendererInterruptRestored.set(Thread.currentThread().isInterrupted());
                rendererSubmitFinished.countDown();
            }
        }, "x4-r15-renderer-owner-reentry-submit");
        rendererSubmit.setDaemon(true);
        boolean rendererSubmitStarted = false;
        Throwable rendererLaneFailure = null;
        try {
            rendererSubmit.start();
            rendererSubmitStarted = true;
            await(rendererEntered, terminalOrderDeadline(), "renderer reentry H did not enter its callback");
            await(rendererReentryReturned, terminalOrderDeadline(),
                    "renderer H callback self-waited instead of coalescing its close/retire calls");
            assertEquals(null, rendererReentryObserved.get(),
                    "renderer H reentry must not observe provisional C/F before its outer submit continues");
            assertEquals(0, rendererMembership.revokeCalls(),
                    "renderer H reentry may not start C before the outer H commits");
            assertFalse(rendererDrain.isDone(), "renderer H reentry may not publish a provisional drain");
            releaseRenderer.countDown();
        } catch (Throwable failure) {
            rendererLaneFailure = failure;
        } finally {
            releaseRenderer.countDown();
            try {
                invokeBounded("x4-r15-renderer-owner-reentry-cleanup", rendererPrepared::close,
                        terminalOrderDeadline(), "renderer owner prepared cleanup did not complete");
            } catch (Throwable ignored) {
                // The real outer submit worker below owns the exact Error outcome.
            }
        }
        awaitWorkerTermination(rendererSubmit, rendererSubmitFinished, rendererSubmitStarted, terminalOrderDeadline(),
                "renderer H owner submit did not terminate after its callback release");
        if (rendererLaneFailure != null) {
            if (rendererLaneFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("renderer H callback-owner orchestration failed", rendererLaneFailure);
        }
        assertSame(rendererReentryFailure, rendererSubmitObserved.get(),
                "the outer renderer H must drive and replay its own exact terminal A");
        assertTrue(rendererReentryInterruptCleared.get(),
                "renderer H nested close must clear/transmit its interrupt before renderer work resumes");
        assertTrue(rendererInterruptRestored.get(),
                "outer renderer H must restore nested interrupt only after its F replay");
        assertEquals(1, rendererMembership.revokeCalls(), "only the outer renderer H may drive one physical C");
        assertSame(rendererReentryFailure, awaitFutureFailure(rendererDrain, terminalOrderDeadline(),
                "renderer H callback-owner lane did not publish its exact terminal A"));

        // The physical T callback is also an exact owner. A synchronous S2.close from revoke()
        // contributes B, returns to that callback without replaying provisional F, then T alone
        // commits C and seals the immutable B/C graph.
        LinkageError physicalCallbackProviderFailure = new LinkageError("r15-physical-callback-B");
        OutOfMemoryError physicalCallbackMembershipFailure = new OutOfMemoryError("r15-physical-callback-C");
        var physicalCallbackGeneration = X4LifecycleTestSupport.published(14_684L);
        physicalCallbackGeneration.provider().onRetire(() -> throwUnchecked(physicalCallbackProviderFailure));
        X4HostIdentity physicalCallbackIdentity = identity("r15-physical-callback-nested-s2");
        AtomicReference<X4PreparedSnapshot> nestedPreparedReference = new AtomicReference<>();
        AtomicReference<Throwable> nestedPreparedCloseFailure = new AtomicReference<>();
        CountDownLatch nestedPreparedCloseReturned = new CountDownLatch(1);
        CountDownLatch releasePhysicalCallback = new CountDownLatch(1);
        AtomicInteger physicalCallbackRevokeCalls = new AtomicInteger();
        AtomicBoolean physicalCallbackRevoked = new AtomicBoolean();
        AtomicBoolean physicalCallbackNestedInterruptCleared = new AtomicBoolean();
        AtomicBoolean physicalCallbackRetrySawInterrupt = new AtomicBoolean();
        AssertionError physicalCallbackRetryInterruptSentinel =
                new AssertionError("r15-physical-callback-retry-interrupt-sentinel");
        X4HostRegistrationMembership physicalCallbackMembership = new X4HostRegistrationMembership() {
            @Override
            public long revision() {
                return 14_685L;
            }

            @Override
            public void revoke() {
                int attempt = physicalCallbackRevokeCalls.incrementAndGet();
                if (attempt == 1) {
                    Thread.currentThread().interrupt();
                    try {
                        nestedPreparedReference.get().close();
                    } catch (Throwable failure) {
                        nestedPreparedCloseFailure.set(failure);
                    } finally {
                        nestedPreparedCloseReturned.countDown();
                    }
                    physicalCallbackNestedInterruptCleared.set(!Thread.currentThread().isInterrupted());
                    awaitUninterruptibly(releasePhysicalCallback, terminalOrderDeadline(),
                            "physical callback was not released after nested S2.close returned");
                    throwUnchecked(physicalCallbackMembershipFailure);
                }
                if (attempt == 2) {
                    boolean interruptedAtCallback = Thread.currentThread().isInterrupted();
                    physicalCallbackRetrySawInterrupt.set(interruptedAtCallback);
                    if (interruptedAtCallback) throw physicalCallbackRetryInterruptSentinel;
                    physicalCallbackRevoked.set(true);
                    return;
                }
                if (attempt == 3) {
                    physicalCallbackRevoked.set(true);
                    return;
                }
                throw new AssertionError("unexpected physical callback retry " + attempt);
            }
        };
        DefaultX4HostAdapter<X4HostFrames.WorldObject> physicalCallbackAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_684L), new BlendRenderer((snapshot, context) -> { }), physicalCallbackIdentity,
                physicalCallbackGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> physicalCallbackReservation =
                physicalCallbackAdapter.freezeAndReserveRegistration();
        physicalCallbackReservation.commit(physicalCallbackMembership);
        X4PreparedSnapshot nestedPrepared = physicalCallbackAdapter.prepare(worldFrame(physicalCallbackIdentity));
        nestedPreparedReference.set(nestedPrepared);
        physicalCallbackGeneration.session().retire();
        var physicalCallbackDrain = physicalCallbackAdapter.drainCompletion().toCompletableFuture();
        AtomicReference<Throwable> physicalCallbackObserved = new AtomicReference<>();
        AtomicReference<Throwable> physicalCallbackForeignObserved = new AtomicReference<>();
        CountDownLatch physicalCallbackFinished = new CountDownLatch(1);
        CountDownLatch physicalCallbackForeignFinished = new CountDownLatch(1);
        AtomicBoolean physicalCallbackOuterInterruptRestored = new AtomicBoolean();
        AtomicBoolean physicalCallbackForeignInterruptRestored = new AtomicBoolean();
        Thread physicalCallbackClose = new Thread(() -> {
            try {
                physicalCallbackAdapter.close();
            } catch (Throwable failure) {
                physicalCallbackObserved.set(failure);
            } finally {
                physicalCallbackOuterInterruptRestored.set(Thread.currentThread().isInterrupted());
                physicalCallbackFinished.countDown();
            }
        }, "x4-r15-physical-callback-outer-close");
        Thread physicalCallbackForeignClose = new Thread(() -> {
            try {
                physicalCallbackAdapter.close();
            } catch (Throwable failure) {
                physicalCallbackForeignObserved.set(failure);
            } finally {
                physicalCallbackForeignInterruptRestored.set(Thread.currentThread().isInterrupted());
                physicalCallbackForeignFinished.countDown();
            }
        }, "x4-r15-physical-callback-interrupted-follower");
        physicalCallbackClose.setDaemon(true);
        physicalCallbackForeignClose.setDaemon(true);
        boolean physicalCallbackStarted = false;
        boolean physicalCallbackForeignStarted = false;
        Throwable physicalCallbackLaneFailure = null;
        try {
            physicalCallbackClose.start();
            physicalCallbackStarted = true;
            await(nestedPreparedCloseReturned, terminalOrderDeadline(),
                    "T.revoke callback did not return from its synchronous nested S2.close");
            assertEquals(null, nestedPreparedCloseFailure.get(),
                    "nested S2.close in the exact T callback must coalesce instead of replaying provisional F");
            assertTrue(physicalCallbackNestedInterruptCleared.get(),
                    "the nested T callback reentry must transfer and clear its interrupt before C1 resumes");
            assertFalse(awaitObservation(physicalCallbackFinished, terminalOrderDeadline()),
                    "outer physical callback close may not return before its T callback releases and seals F");
            assertFalse(physicalCallbackDrain.isDone(),
                    "nested S2.close may not publish a drain before outer T commits physical C");
            physicalCallbackForeignClose.start();
            physicalCallbackForeignStarted = true;
            awaitWorkerWaiting(physicalCallbackForeignClose, terminalOrderDeadline(),
                    "foreign close did not join the private C1 epoch before its retained-receipt interrupt");
            physicalCallbackForeignClose.interrupt();
            assertFalse(awaitObservation(physicalCallbackForeignFinished, terminalOrderDeadline()),
                    "the interrupted foreign epoch waiter may not return before it drives/observes retained C2");
            releasePhysicalCallback.countDown();
        } catch (Throwable failure) {
            physicalCallbackLaneFailure = failure;
        } finally {
            releasePhysicalCallback.countDown();
            try {
                invokeBounded("x4-r15-physical-callback-S2-cleanup", nestedPrepared::close,
                        terminalOrderDeadline(), "physical callback nested S2 cleanup did not complete");
            } catch (Throwable ignored) {
                // The outer T worker and retained-receipt retry below own the final evidence.
            }
        }
        Throwable physicalCallbackRetryObserved = null;
        try {
            awaitWorkerTermination(physicalCallbackClose, physicalCallbackFinished, physicalCallbackStarted,
                    terminalOrderDeadline(), "outer physical callback close did not terminate after callback release");
            awaitWorkerTermination(physicalCallbackForeignClose, physicalCallbackForeignFinished,
                    physicalCallbackForeignStarted, terminalOrderDeadline(),
                    "interrupted foreign callback follower did not terminate after retained C2");
            physicalCallbackRetryObserved = physicalCallbackForeignObserved.get();
        } finally {
            // C2 is normally driven by the interrupted foreign request above. If that worker
            // exposed a broken retained-receipt path, perform exactly one third-attempt cleanup
            // so this daemon-backed fixture cannot retain C or its drain indefinitely.
            if (!physicalCallbackRevoked.get()) {
                try {
                    invokeBounded("x4-r15-physical-callback-retry-cleanup", physicalCallbackAdapter::close,
                            terminalOrderDeadline(), "physical callback failed-retry cleanup did not complete");
                } catch (Throwable ignored) {
                    // The original bounded worker failure remains the useful assertion evidence.
                }
            }
        }
        if (physicalCallbackLaneFailure != null) {
            if (physicalCallbackLaneFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("physical T callback nested-S2 orchestration failed", physicalCallbackLaneFailure);
        }
        assertSame(physicalCallbackProviderFailure, physicalCallbackObserved.get(),
                "outer T must replay B after it commits the later physical C");
        assertSame(physicalCallbackProviderFailure, physicalCallbackRetryObserved,
                "the interrupted foreign request must itself retry retained C2 and preserve immutable B/F");
        assertTrue(physicalCallbackOuterInterruptRestored.get(),
                "the outer T source must restore nested interrupt only after its exact F replay");
        assertFalse(physicalCallbackRetrySawInterrupt.get(),
                "the foreign retained-receipt C2 callback must run after the waiter clears its interrupt");
        assertTrue(physicalCallbackForeignInterruptRestored.get(),
                "the foreign retained-receipt request must restore interrupt only after its exact F replay");
        assertSuppressionGraphContains(physicalCallbackProviderFailure, physicalCallbackMembershipFailure);
        assertSuppressionGraphExcludes(physicalCallbackProviderFailure, physicalCallbackRetryInterruptSentinel);
        assertEquals(2, physicalCallbackRevokeCalls.get(), "T callback lane must use one C then one retained retry");
        assertTrue(physicalCallbackRevoked.get());
        assertSame(physicalCallbackProviderFailure, awaitFutureFailure(physicalCallbackDrain, terminalOrderDeadline(),
                "physical T callback nested-S2 lane did not publish immutable B/F"));
    }

    /**
     * Both an admitted direct B and an admitted H2 contributor are interrupted while H1 owns the
     * one contribution claim. Neither may abandon its exact evidence: each waits uninterruptibly,
     * commits/replays the shared F after H1 releases, and restores its own interrupt flag only then.
     */
    private static void assertInterruptedContributionCommitsBeforeInterruptRestoration() throws Exception {
        AssertionError firstRendererFailure = new AssertionError("r15-interrupt-A1");
        AssertionError secondRendererFailure = new AssertionError("r15-interrupt-A2");
        LinkageError firstProviderFailure = new LinkageError("r15-interrupt-H1-B1");
        LinkageError secondProviderFailure = new LinkageError("r15-interrupt-H2-B2");
        LinkageError directProviderFailure = new LinkageError("r15-interrupt-direct-B3");
        OutOfMemoryError revokeFailure = new OutOfMemoryError("r15-interrupt-C");
        var generation = X4LifecycleTestSupport.published(14_690L);
        X4HostIdentity identity = identity("r15-interrupt-contribution");
        AtomicReference<X4PreparedSnapshot> firstSubmittingPreparedReference = new AtomicReference<>();
        AtomicReference<X4PreparedSnapshot> secondSubmittingPreparedReference = new AtomicReference<>();
        AtomicInteger rendererCalls = new AtomicInteger();
        CountDownLatch firstRendererEntered = new CountDownLatch(1);
        CountDownLatch secondRendererEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRenderer = new CountDownLatch(1);
        CountDownLatch releaseSecondRenderer = new CountDownLatch(1);
        DefaultX4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_690L),
                new BlendRenderer((snapshot, context) -> {
                    int call = rendererCalls.incrementAndGet();
                    try {
                        if (call == 1) {
                            firstRendererEntered.countDown();
                            await(releaseFirstRenderer, terminalOrderDeadline(),
                                    "interrupted-contribution H1 renderer was not released");
                            firstSubmittingPreparedReference.get().close();
                            throwUnchecked(firstRendererFailure);
                            return;
                        }
                        if (call == 2) {
                            secondRendererEntered.countDown();
                            await(releaseSecondRenderer, terminalOrderDeadline(),
                                    "interrupted-contribution H2 renderer was not released");
                            secondSubmittingPreparedReference.get().close();
                            throwUnchecked(secondRendererFailure);
                            return;
                        }
                        throw new AssertionError("unexpected interrupted-contribution renderer invocation " + call);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted-contribution renderer was interrupted", exception);
                    }
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = adapter.freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership(14_691L, revokeFailure);
        reservation.commit(membership);
        X4PreparedSnapshot firstSubmittingPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot secondSubmittingPrepared = adapter.prepare(worldFrame(identity));
        X4PreparedSnapshot directPrepared = adapter.prepare(worldFrame(identity));
        firstSubmittingPreparedReference.set(firstSubmittingPrepared);
        secondSubmittingPreparedReference.set(secondSubmittingPrepared);
        AtomicInteger providerLeaseCloseCalls = new AtomicInteger();
        CountDownLatch firstProviderFailureEntered = new CountDownLatch(1);
        CountDownLatch secondProviderFailureEntered = new CountDownLatch(1);
        CountDownLatch directProviderFailureEntered = new CountDownLatch(1);
        adapter.runAfterProviderLeaseCloseForTesting(() -> {
            int release = providerLeaseCloseCalls.incrementAndGet();
            if (release == 1) {
                firstProviderFailureEntered.countDown();
                throwUnchecked(firstProviderFailure);
            }
            if (release == 2) {
                secondProviderFailureEntered.countDown();
                throwUnchecked(secondProviderFailure);
            }
            if (release == 3) {
                directProviderFailureEntered.countDown();
                throwUnchecked(directProviderFailure);
            }
            throw new AssertionError("unexpected interrupted-contribution provider close " + release);
        });
        generation.session().retire();
        var drain = adapter.drainCompletion().toCompletableFuture();

        CountDownLatch firstContributionPaused = new CountDownLatch(1);
        CountDownLatch releaseFirstContribution = new CountDownLatch(1);
        CountDownLatch secondContributionPaused = new CountDownLatch(1);
        CountDownLatch releaseSecondContribution = new CountDownLatch(1);
        AtomicInteger acceptedContributionCount = new AtomicInteger();
        adapter.runBeforeTerminalContributionCommitForTesting(() -> {
            firstContributionPaused.countDown();
            try {
                await(releaseFirstContribution, terminalOrderDeadline(),
                        "interrupted-contribution H1 owner was not released");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted-contribution owner barrier was interrupted", exception);
            }
        });
        AtomicReference<Throwable> firstSubmitObserved = new AtomicReference<>();
        AtomicReference<Throwable> secondSubmitObserved = new AtomicReference<>();
        AtomicReference<Throwable> physicalObserved = new AtomicReference<>();
        AtomicReference<Throwable> directObserved = new AtomicReference<>();
        AtomicBoolean secondSubmitInterruptedAfter = new AtomicBoolean();
        AtomicBoolean directInterruptedAfter = new AtomicBoolean();
        CountDownLatch firstSubmitFinished = new CountDownLatch(1);
        CountDownLatch secondSubmitFinished = new CountDownLatch(1);
        CountDownLatch physicalFinished = new CountDownLatch(1);
        CountDownLatch directFinished = new CountDownLatch(1);
        Thread firstSubmitThread = new Thread(() -> {
            try {
                adapter.submit(firstSubmittingPrepared, unusedContext());
            } catch (Throwable failure) {
                firstSubmitObserved.set(failure);
            } finally {
                firstSubmitFinished.countDown();
            }
        }, "x4-r15-interrupt-H1");
        firstSubmitThread.setDaemon(true);
        Thread secondSubmitThread = new Thread(() -> {
            try {
                adapter.submit(secondSubmittingPrepared, unusedContext());
            } catch (Throwable failure) {
                secondSubmitObserved.set(failure);
            } finally {
                secondSubmitInterruptedAfter.set(Thread.currentThread().isInterrupted());
                secondSubmitFinished.countDown();
            }
        }, "x4-r15-interrupt-H2");
        Thread physicalThread = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                physicalObserved.set(failure);
            } finally {
                physicalFinished.countDown();
            }
        }, "x4-r15-interrupt-physical-C");
        Thread directThread = new Thread(() -> {
            try {
                directPrepared.close();
            } catch (Throwable failure) {
                directObserved.set(failure);
            } finally {
                directInterruptedAfter.set(Thread.currentThread().isInterrupted());
                directFinished.countDown();
            }
        }, "x4-r15-interrupt-direct-B");
        secondSubmitThread.setDaemon(true);
        physicalThread.setDaemon(true);
        directThread.setDaemon(true);
        AtomicReference<Thread> firstAcceptedContributor = new AtomicReference<>();
        AtomicReference<Thread> secondAcceptedContributor = new AtomicReference<>();
        AtomicReference<Thread> thirdAcceptedContributor = new AtomicReference<>();
        CountDownLatch firstContributionAccepted = new CountDownLatch(1);
        CountDownLatch secondContributionAccepted = new CountDownLatch(1);
        CountDownLatch thirdContributionAccepted = new CountDownLatch(1);
        adapter.runAfterTerminalContributionAcceptedForTesting(() -> {
            int contribution = acceptedContributionCount.incrementAndGet();
            Thread contributor = Thread.currentThread();
            if (contribution == 1) {
                firstAcceptedContributor.set(contributor);
                firstContributionAccepted.countDown();
                return;
            }
            if (contribution == 2) {
                secondAcceptedContributor.set(contributor);
                secondContributionAccepted.countDown();
                return;
            }
            if (contribution == 3) {
                thirdAcceptedContributor.set(contributor);
                thirdContributionAccepted.countDown();
                return;
            }
            throw new AssertionError("unexpected interrupted-contribution commit " + contribution);
        });

        boolean firstSubmitStarted = false;
        boolean secondSubmitStarted = false;
        boolean physicalStarted = false;
        boolean directStarted = false;
        long deadlineNanos = terminalOrderDeadline();
        Throwable orchestrationFailure = null;
        try {
            firstSubmitThread.start();
            firstSubmitStarted = true;
            await(firstRendererEntered, deadlineNanos, "interrupted-contribution H1 did not enter renderer");
            // H2 must acquire its real submit hold while the adapter is still PREPARED. Starting it
            // after C/H1 opens the terminal state would only prove the ordinary submit-state guard,
            // not an admitted H contributor waiting at the epoch contribution gate.
            secondSubmitThread.start();
            secondSubmitStarted = true;
            await(secondRendererEntered, deadlineNanos,
                    "interrupted-contribution H2 did not enter renderer before the shared epoch opened");
            physicalThread.start();
            physicalStarted = true;
            await(membership.firstRevoke(), deadlineNanos, "interrupted-contribution physical C did not start");
            membership.releaseFirstRevoke();
            releaseFirstRenderer.countDown();
            await(firstProviderFailureEntered, deadlineNanos,
                    "interrupted-contribution H1 did not reach its exact B1 source");
            await(firstContributionPaused, deadlineNanos,
                    "interrupted-contribution H1 did not hold the contribution claim");

            releaseSecondRenderer.countDown();
            await(secondProviderFailureEntered, deadlineNanos,
                    "interrupted-contribution H2 did not reach its exact B2 source");
            awaitWorkerGatePending(secondSubmitThread, deadlineNanos,
                    "interrupted-contribution H2 did not wait behind H1's contribution claim");
            assertEquals(0, acceptedContributionCount.get(),
                    "H2 must be interrupted before any terminal contribution commits while H1 holds the claim");
            secondSubmitThread.interrupt();
            assertFalse(awaitObservation(secondSubmitFinished, deadlineNanos),
                    "interrupt may not let an admitted H2 contributor abandon its exact evidence");

            // The first one-shot contribution seam has already been consumed before it signalled
            // H1's pause. Re-arm the null-default seam so the already-interrupted H2 is the sole
            // next claimant held before commit; only then may direct B enter. This fixes the exact
            // H1 -> H2 -> direct contribution order without relying on monitor waiter fairness.
            adapter.runBeforeTerminalContributionCommitForTesting(() -> {
                secondContributionPaused.countDown();
                awaitUninterruptibly(releaseSecondContribution, terminalOrderDeadline(),
                        "interrupted-contribution H2 was not released before its exact commit");
            });
            releaseFirstContribution.countDown();
            await(firstContributionAccepted, deadlineNanos,
                    "H1 did not commit first after its held contribution claim released");
            assertEquals(1, acceptedContributionCount.get(),
                    "only H1 may have committed before H2 reaches its re-armed contribution seam");
            assertSame(firstSubmitThread, firstAcceptedContributor.get(),
                    "the held H1 claim must commit before every later contribution");
            await(secondContributionPaused, deadlineNanos,
                    "H2 did not become the sole next contribution claimant after H1 committed");
            assertEquals(1, acceptedContributionCount.get(),
                    "H2 must still be uncommitted while its second contribution seam is held");

            directThread.start();
            directStarted = true;
            await(directProviderFailureEntered, deadlineNanos,
                    "interrupted direct snapshot did not reach its exact B3 source");
            awaitWorkerGatePending(directThread, deadlineNanos,
                    "direct B did not wait behind H2's already-owned contribution claim");
            assertEquals(1, acceptedContributionCount.get(),
                    "direct B must be interrupted before it commits behind the held H2 contribution claim");
            directThread.interrupt();
            assertFalse(awaitObservation(directFinished, deadlineNanos),
                    "interrupt may not let an admitted direct B abandon its exact contribution");
            releaseSecondContribution.countDown();
        } catch (Throwable failure) {
            orchestrationFailure = failure;
        } finally {
            releaseFirstRenderer.countDown();
            releaseSecondRenderer.countDown();
            releaseFirstContribution.countDown();
            releaseSecondContribution.countDown();
            membership.releaseFirstRevoke();
            try {
                directObserved.compareAndSet(null, invokeBounded(
                        "x4-r15-interrupt-direct-cleanup", directPrepared::close, terminalOrderDeadline(),
                        "interrupted-contribution direct cleanup did not complete"));
            } catch (Throwable failure) {
                directObserved.compareAndSet(null, failure);
            }
            try {
                invokeBounded("x4-r15-interrupt-H1-cleanup", firstSubmittingPrepared::close, terminalOrderDeadline(),
                        "interrupted-contribution H1 cleanup did not complete");
            } catch (Throwable ignored) {
                // Exact H1 outcome is collected from its bounded worker below.
            }
            try {
                invokeBounded("x4-r15-interrupt-H2-cleanup", secondSubmittingPrepared::close, terminalOrderDeadline(),
                        "interrupted-contribution H2 cleanup did not complete");
            } catch (Throwable ignored) {
                // Exact H2 outcome is collected from its bounded worker below.
            }
        }

        long cleanupDeadlineNanos = terminalOrderDeadline();
        Throwable retryObserved = null;
        try {
            awaitWorkerTermination(firstSubmitThread, firstSubmitFinished, firstSubmitStarted, cleanupDeadlineNanos,
                    "interrupted-contribution H1 worker did not terminate");
            awaitWorkerTermination(secondSubmitThread, secondSubmitFinished, secondSubmitStarted, cleanupDeadlineNanos,
                    "interrupted-contribution H2 worker did not terminate");
            awaitWorkerTermination(physicalThread, physicalFinished, physicalStarted, cleanupDeadlineNanos,
                    "interrupted-contribution physical worker did not terminate");
            awaitWorkerTermination(directThread, directFinished, directStarted, cleanupDeadlineNanos,
                    "interrupted-contribution direct worker did not terminate");
        } finally {
            retryObserved = invokeBounded("x4-r15-interrupt-retry", adapter::close, terminalOrderDeadline(),
                    "interrupted-contribution retained-receipt retry did not complete");
        }
        await(secondContributionAccepted, cleanupDeadlineNanos,
                "interrupted H2 did not commit after its second contribution seam released");
        await(thirdContributionAccepted, cleanupDeadlineNanos,
                "interrupted direct B did not commit after the two H contributions released");
        if (orchestrationFailure != null) {
            if (orchestrationFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError("interrupted-contribution orchestration failed", orchestrationFailure);
        }
        assertSame(firstRendererFailure, firstSubmitObserved.get());
        assertSame(firstRendererFailure, secondSubmitObserved.get());
        assertSame(firstRendererFailure, physicalObserved.get());
        assertSame(firstRendererFailure, directObserved.get());
        assertSame(firstRendererFailure, retryObserved);
        assertTrue(secondSubmitInterruptedAfter.get(),
                "the H2 contribution wait must restore interrupt status only after F is available");
        assertTrue(directInterruptedAfter.get(),
                "the direct contribution wait must restore interrupt status only after F is available");
        assertEquals(3, providerLeaseCloseCalls.get());
        assertEquals(3, acceptedContributionCount.get(),
                "H1, interrupted H2, and interrupted direct B must each commit exactly one epoch contribution");
        assertSame(firstSubmitThread, firstAcceptedContributor.get(),
                "the H1 owner must commit first after its held contribution claim releases");
        assertSame(secondSubmitThread, secondAcceptedContributor.get(),
                "H2 must commit second in the controlled epoch contribution order");
        assertSame(directThread, thirdAcceptedContributor.get(),
                "the interrupted direct B must commit last after the two H contributors");
        assertSuppressionGraphContains(firstRendererFailure, secondRendererFailure);
        assertSuppressionGraphContains(firstRendererFailure, firstProviderFailure);
        assertSuppressionGraphContains(firstRendererFailure, secondProviderFailure);
        assertSuppressionGraphContains(firstRendererFailure, directProviderFailure);
        assertSuppressionGraphContains(firstRendererFailure, revokeFailure);
        assertEquals(2, membership.revokeCalls());
        assertTrue(membership.revoked());
        assertSame(firstRendererFailure, awaitFutureFailure(drain, cleanupDeadlineNanos,
                "interrupted contribution did not publish a bounded exact F"));

        // A top-level close may enter with its interrupt bit already set. It must be captured and
        // cleared before first C, then restored only after the public close replay boundary.
        AssertionError topLevelInterruptSentinel = new AssertionError("r15-preinterrupt-top-level-sentinel");
        AtomicBoolean topLevelMembershipSawInterrupt = new AtomicBoolean();
        AtomicInteger topLevelRevokeCalls = new AtomicInteger();
        X4HostRegistrationMembership topLevelMembership = new X4HostRegistrationMembership() {
            @Override
            public long revision() {
                return 14_696L;
            }

            @Override
            public void revoke() {
                topLevelRevokeCalls.incrementAndGet();
                boolean interruptedAtCallback = Thread.currentThread().isInterrupted();
                topLevelMembershipSawInterrupt.set(interruptedAtCallback);
                if (interruptedAtCallback) throw topLevelInterruptSentinel;
            }
        };
        var topLevelGeneration = X4LifecycleTestSupport.published(14_695L);
        X4HostIdentity topLevelIdentity = identity("r15-preinterrupt-top-level-close");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> topLevelAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_695L), new BlendRenderer((snapshot, context) -> { }), topLevelIdentity,
                topLevelGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> topLevelReservation =
                topLevelAdapter.freezeAndReserveRegistration();
        topLevelReservation.commit(topLevelMembership);
        topLevelGeneration.session().retire();
        AtomicReference<Throwable> topLevelObserved = new AtomicReference<>();
        AtomicBoolean topLevelInterruptRestored = new AtomicBoolean();
        CountDownLatch topLevelFinished = new CountDownLatch(1);
        Thread topLevelClose = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                topLevelAdapter.close();
            } catch (Throwable failure) {
                topLevelObserved.set(failure);
            } finally {
                topLevelInterruptRestored.set(Thread.currentThread().isInterrupted());
                topLevelFinished.countDown();
            }
        }, "x4-r15-preinterrupt-top-level-close");
        topLevelClose.setDaemon(true);
        topLevelClose.start();
        awaitWorkerTermination(topLevelClose, topLevelFinished, true, terminalOrderDeadline(),
                "pre-interrupted top-level close did not terminate");
        assertSame(null, topLevelObserved.get(),
                "clean physical C must not select an interrupt sentinel as terminal F");
        assertFalse(topLevelMembershipSawInterrupt.get(),
                "membership C must never observe a pre-existing caller interrupt flag");
        assertTrue(topLevelInterruptRestored.get(),
                "top-level close must restore its pre-existing interrupt only after replay completes");
        assertEquals(1, topLevelRevokeCalls.get(), "pre-interrupted close must still execute exactly one physical C");
        assertEquals(X4HostLifecycleState.CLOSED, awaitFutureValue(
                topLevelAdapter.drainCompletion().toCompletableFuture(), terminalOrderDeadline(),
                "pre-interrupted top-level close did not publish normal drain completion"));

        // Direct B is materialized before this test seam fires. The source owns no contribution
        // claim yet, then becomes the first C driver; C still must observe a clear status and the
        // direct caller gets its exact B plus its interrupt flag only after replay.
        LinkageError directFirstProviderFailure = new LinkageError("r15-preinterrupt-direct-B");
        AssertionError directFirstInterruptSentinel = new AssertionError("r15-preinterrupt-direct-C-sentinel");
        AtomicBoolean directFirstMembershipSawInterrupt = new AtomicBoolean();
        var directFirstGeneration = X4LifecycleTestSupport.published(14_697L);
        directFirstGeneration.provider().onRetire(() -> throwUnchecked(directFirstProviderFailure));
        X4HostIdentity directFirstIdentity = identity("r15-preinterrupt-direct-first-driver");
        DefaultX4HostAdapter<X4HostFrames.WorldObject> directFirstAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_697L), new BlendRenderer((snapshot, context) -> { }), directFirstIdentity,
                directFirstGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> directFirstReservation =
                directFirstAdapter.freezeAndReserveRegistration();
        directFirstReservation.commit(new X4HostRegistrationMembership() {
            @Override
            public long revision() {
                return 14_698L;
            }

            @Override
            public void revoke() {
                boolean interruptedAtCallback = Thread.currentThread().isInterrupted();
                directFirstMembershipSawInterrupt.set(interruptedAtCallback);
                if (interruptedAtCallback) throw directFirstInterruptSentinel;
            }
        });
        X4PreparedSnapshot directFirstPrepared = directFirstAdapter.prepare(worldFrame(directFirstIdentity));
        directFirstGeneration.session().retire();
        directFirstAdapter.runBeforeDirectReleasePendingPinDecrementForTesting(() -> Thread.currentThread().interrupt());
        AtomicReference<Throwable> directFirstObserved = new AtomicReference<>();
        AtomicBoolean directFirstInterruptRestored = new AtomicBoolean();
        CountDownLatch directFirstFinished = new CountDownLatch(1);
        Thread directFirstClose = new Thread(() -> {
            try {
                directFirstPrepared.close();
            } catch (Throwable failure) {
                directFirstObserved.set(failure);
            } finally {
                directFirstInterruptRestored.set(Thread.currentThread().isInterrupted());
                directFirstFinished.countDown();
            }
        }, "x4-r15-preinterrupt-direct-first-driver");
        directFirstClose.setDaemon(true);
        directFirstClose.start();
        awaitWorkerTermination(directFirstClose, directFirstFinished, true, terminalOrderDeadline(),
                "pre-interrupted direct B first driver did not terminate");
        assertSame(directFirstProviderFailure, directFirstObserved.get(),
                "direct B must remain F when its first physical C callback is clean");
        assertFalse(directFirstMembershipSawInterrupt.get(),
                "direct B's first physical C must not observe its source interrupt receipt");
        assertTrue(directFirstInterruptRestored.get(),
                "direct B first driver must restore its interrupt after its exact F replay");
        assertSuppressionGraphExcludes(directFirstProviderFailure, directFirstInterruptSentinel);
        assertSame(directFirstProviderFailure, awaitFutureFailure(
                directFirstAdapter.drainCompletion().toCompletableFuture(), terminalOrderDeadline(),
                "pre-interrupted direct B lane did not publish exact F"));

        // H's B is now fully committed and the H contribution owner has released. Set the flag at
        // that owner-free point; the same H is then the first C driver and must clear it at C.
        LinkageError submissionFirstProviderFailure = new LinkageError("r15-preinterrupt-H-B");
        AssertionError submissionFirstInterruptSentinel = new AssertionError("r15-preinterrupt-H-C-sentinel");
        AtomicBoolean submissionFirstMembershipSawInterrupt = new AtomicBoolean();
        var submissionFirstGeneration = X4LifecycleTestSupport.published(14_699L);
        submissionFirstGeneration.provider().onRetire(() -> throwUnchecked(submissionFirstProviderFailure));
        X4HostIdentity submissionFirstIdentity = identity("r15-preinterrupt-h-first-driver");
        AtomicReference<X4PreparedSnapshot> submissionFirstPreparedReference = new AtomicReference<>();
        DefaultX4HostAdapter<X4HostFrames.WorldObject> submissionFirstAdapter = worldAdapterWithPostSealAllocationProbe(
                new FakeLookup(14_699L),
                new BlendRenderer((snapshot, context) -> submissionFirstPreparedReference.get().close()),
                submissionFirstIdentity,
                submissionFirstGeneration.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> submissionFirstReservation =
                submissionFirstAdapter.freezeAndReserveRegistration();
        submissionFirstReservation.commit(new X4HostRegistrationMembership() {
            @Override
            public long revision() {
                return 14_700L;
            }

            @Override
            public void revoke() {
                boolean interruptedAtCallback = Thread.currentThread().isInterrupted();
                submissionFirstMembershipSawInterrupt.set(interruptedAtCallback);
                if (interruptedAtCallback) throw submissionFirstInterruptSentinel;
            }
        });
        X4PreparedSnapshot submissionFirstPrepared = submissionFirstAdapter.prepare(worldFrame(submissionFirstIdentity));
        submissionFirstPreparedReference.set(submissionFirstPrepared);
        submissionFirstGeneration.session().retire();
        CountDownLatch submissionBCommitted = new CountDownLatch(1);
        submissionFirstAdapter.runAfterTerminalContributionAcceptedForTesting(() -> {
            submissionBCommitted.countDown();
            Thread.currentThread().interrupt();
        });
        AtomicReference<Throwable> submissionFirstObserved = new AtomicReference<>();
        AtomicBoolean submissionFirstInterruptRestored = new AtomicBoolean();
        CountDownLatch submissionFirstFinished = new CountDownLatch(1);
        Thread submissionFirstSubmit = new Thread(() -> {
            try {
                submissionFirstAdapter.submit(submissionFirstPrepared, unusedContext());
            } catch (Throwable failure) {
                submissionFirstObserved.set(failure);
            } finally {
                submissionFirstInterruptRestored.set(Thread.currentThread().isInterrupted());
                submissionFirstFinished.countDown();
            }
        }, "x4-r15-preinterrupt-H-first-driver");
        submissionFirstSubmit.setDaemon(true);
        submissionFirstSubmit.start();
        await(submissionBCommitted, terminalOrderDeadline(),
                "H B was not committed before its owner-free interrupt receipt was installed");
        awaitWorkerTermination(submissionFirstSubmit, submissionFirstFinished, true, terminalOrderDeadline(),
                "pre-interrupted H B first driver did not terminate");
        assertSame(submissionFirstProviderFailure, submissionFirstObserved.get(),
                "H B must remain F when its first physical C callback is clean");
        assertFalse(submissionFirstMembershipSawInterrupt.get(),
                "H B's first physical C must not observe its owner-free interrupt receipt");
        assertTrue(submissionFirstInterruptRestored.get(),
                "H B first driver must restore interrupt only after its exact F replay");
        assertSuppressionGraphExcludes(submissionFirstProviderFailure, submissionFirstInterruptSentinel);
        assertSame(submissionFirstProviderFailure, awaitFutureFailure(
                submissionFirstAdapter.drainCompletion().toCompletableFuture(), terminalOrderDeadline(),
                "pre-interrupted H B lane did not publish exact F"));
    }

    private static void await(CountDownLatch latch, long deadlineNanos, String failureMessage) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        assertTrue(remainingNanos > 0L && latch.await(remainingNanos, TimeUnit.NANOSECONDS), failureMessage);
    }

    /**
     * A scheduling seam must not convert the source's required uninterruptible contribution wait
     * into an interrupt escape. It records the signal, keeps waiting for its controller release,
     * and restores the flag to the source before the source resumes its own terminal protocol.
     */
    private static void awaitUninterruptibly(CountDownLatch latch, long deadlineNanos, String failureMessage) {
        boolean interrupted = false;
        try {
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new AssertionError(failureMessage + " (deadline elapsed before controller release)");
                }
                try {
                    if (latch.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                    throw new AssertionError(failureMessage);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static <T> T awaitFutureValue(
            CompletableFuture<T> future,
            long deadlineNanos,
            String failureMessage) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new AssertionError(failureMessage + " (deadline elapsed before future observation)");
        }
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError(failureMessage, exception);
        } catch (ExecutionException exception) {
            throw new AssertionError(failureMessage + " (future failed unexpectedly)", exception.getCause());
        }
    }

    private static Throwable awaitFutureFailure(
            CompletableFuture<?> future,
            long deadlineNanos,
            String failureMessage) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new AssertionError(failureMessage + " (deadline elapsed before future observation)");
        }
        try {
            Object value = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            throw new AssertionError(failureMessage + " (future completed successfully with " + value + ")");
        } catch (TimeoutException exception) {
            throw new AssertionError(failureMessage, exception);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingTestAction {
        void run() throws Throwable;
    }

    /**
     * Bounds a direct terminal API call without silently abandoning its worker. The action worker
     * is daemonized before start because the terminal protocol deliberately has uninterruptible
     * waits; the caller's own finally releases every fixture gate, then this helper interrupts and
     * bounded-joins as a last cleanup attempt. A surviving worker is reported with its live state
     * and stack rather than keeping the test JVM alive.
     */
    private static Throwable invokeBounded(
            String workerName,
            ThrowingTestAction action,
            long deadlineNanos,
            String failureMessage) throws Exception {
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                observed.set(failure);
            } finally {
                finished.countDown();
            }
        }, workerName);
        worker.setDaemon(true);
        boolean started = false;
        try {
            worker.start();
            started = true;
            await(finished, deadlineNanos, failureMessage);
        } finally {
            if (started && finished.getCount() != 0L) {
                worker.interrupt();
            }
            if (started && worker.isAlive()) {
                try {
                    awaitWorkerTermination(worker, finished, true, terminalOrderDeadline(),
                            failureMessage + " (worker did not terminate after interrupt cleanup)");
                } catch (AssertionError failure) {
                    throw new AssertionError(failure.getMessage() + "; " + workerDiagnostic(worker), failure);
                }
            }
        }
        return observed.get();
    }

    private static String workerDiagnostic(Thread worker) {
        StringBuilder diagnostic = new StringBuilder("worker=")
                .append(worker.getName())
                .append(" state=")
                .append(worker.getState())
                .append(" stack=");
        StackTraceElement[] stack = worker.getStackTrace();
        if (stack.length == 0) {
            return diagnostic.append("<empty>").toString();
        }
        for (int index = 0; index < stack.length && index < 6; index++) {
            if (index != 0) {
                diagnostic.append(" <- ");
            }
            diagnostic.append(stack[index]);
        }
        return diagnostic.toString();
    }

    private static long terminalOrderDeadline() {
        return System.nanoTime() + TERMINAL_ORDER_TIMEOUT_NANOS;
    }

    private static void awaitWorkerTermination(
            Thread worker,
            CountDownLatch finished,
            boolean started,
            long deadlineNanos,
            String failureMessage) throws InterruptedException {
        if (!started) {
            return;
        }
        await(finished, deadlineNanos, failureMessage);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos > 0L) {
            TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos);
        }
        assertFalse(worker.isAlive(), failureMessage);
    }

    private static void awaitWorkerWaiting(Thread worker, long deadlineNanos, String failureMessage)
            throws InterruptedException {
        while (System.nanoTime() < deadlineNanos) {
            Thread.State state = worker.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(1L);
        }
        throw new AssertionError(failureMessage + " (state=" + worker.getState() + ")");
    }

    /**
     * The contribution gate may be implemented with a monitor acquisition or an uninterruptible
     * condition wait. Both states are valid, but a runnable/terminated H2 after its exact source
     * has entered is not: that would not prove it is held behind H1's contribution claim.
     */
    private static void awaitWorkerGatePending(Thread worker, long deadlineNanos, String failureMessage)
            throws InterruptedException {
        while (System.nanoTime() < deadlineNanos) {
            Thread.State state = worker.getState();
            if (state == Thread.State.BLOCKED
                    || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (state == Thread.State.TERMINATED) {
                throw new AssertionError(failureMessage + " (worker terminated before the gate wait)");
            }
            TimeUnit.MILLISECONDS.sleep(1L);
        }
        throw new AssertionError(failureMessage + " (state=" + worker.getState() + ")");
    }

    private static void awaitWorkerBlocked(Thread worker, long deadlineNanos, String failureMessage)
            throws InterruptedException {
        while (System.nanoTime() < deadlineNanos) {
            if (worker.getState() == Thread.State.BLOCKED) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(1L);
        }
        throw new AssertionError(failureMessage + " (state=" + worker.getState() + ")");
    }

    private static boolean awaitObservation(CountDownLatch latch, long deadlineNanos) throws InterruptedException {
        long observationDeadline = Math.min(deadlineNanos, System.nanoTime() + TERMINAL_ORDER_OBSERVATION_NANOS);
        long remainingNanos = observationDeadline - System.nanoTime();
        return remainingNanos > 0L && latch.await(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private static void awaitTerminalRetryReadiness(X4HostAdapter<?> adapter, long deadlineNanos)
            throws InterruptedException {
        while (System.nanoTime() < deadlineNanos) {
            X4HostLeaseDiagnostics diagnostics = adapter.leaseDiagnostics();
            if (adapter.state() == X4HostLifecycleState.CLOSING
                    && diagnostics.closeRequested()) {
                assertEquals("", diagnostics.terminalFailureType(),
                        "unsealed cleanup must not leak through terminal diagnostics");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(1L);
        }
        throw new AssertionError("submit did not establish bounded CLOSING retry readiness");
    }

    private static void assertExactlySuppressed(Throwable primary, Throwable expectedSuppressed) {
        assertEquals(1, primary.getSuppressed().length,
                () -> "expected exactly one suppressed failure on " + primary);
        assertSame(expectedSuppressed, primary.getSuppressed()[0]);
    }

    private static void assertExactSuppressionChain(Throwable primary, Throwable... expectedSuppressed) {
        Throwable current = primary;
        for (Throwable expected : expectedSuppressed) {
            assertExactlySuppressed(current, expected);
            current = expected;
        }
        assertEquals(0, current.getSuppressed().length,
                "unexpected terminal suppression tail on " + current);
    }

    private static void assertSuppressionGraphExcludes(Throwable root, Throwable forbidden) {
        assertSuppressionGraphExcludes(root, forbidden, new IdentityHashMap<>());
    }

    private static void assertSuppressionGraphContains(Throwable root, Throwable expected) {
        assertTrue(suppressionGraphContains(root, expected, new IdentityHashMap<>()),
                () -> "expected exact throwable identity in terminal suppression graph: " + expected);
    }

    private static boolean suppressionGraphContains(
            Throwable current,
            Throwable expected,
            IdentityHashMap<Throwable, Boolean> visited) {
        if (current == expected) {
            return true;
        }
        if (visited.put(current, Boolean.TRUE) != null) {
            return false;
        }
        for (Throwable suppressed : current.getSuppressed()) {
            if (suppressionGraphContains(suppressed, expected, visited)) {
                return true;
            }
        }
        return false;
    }

    private static void assertSuppressionGraphExcludes(
            Throwable current,
            Throwable forbidden,
            IdentityHashMap<Throwable, Boolean> visited) {
        if (visited.put(current, Boolean.TRUE) != null) {
            return;
        }
        assertFalse(current == forbidden,
                () -> "forbidden throwable leaked into the immutable terminal suppression graph: " + forbidden);
        for (Throwable suppressed : current.getSuppressed()) {
            assertSuppressionGraphExcludes(suppressed, forbidden, visited);
        }
    }

    private static void assertFailedThenRetried(FullChainResult result, Throwable expectedTerminal) throws Exception {
        assertEquals(X4HostLifecycleState.CLOSING, result.adapter().state(),
                "failed revoke must block fake-live prepare/submit while retaining the original terminal request");
        assertTrue(result.adapter().leaseDiagnostics().closeRequested());
        assertEquals(expectedTerminal.getClass().getName(), result.adapter().leaseDiagnostics().terminalFailureType());
        assertEquals(1, result.membership().revokeCalls());
        assertFalse(result.membership().revoked(), "the injected first revoke failure leaves the exact receipt pending retry");
        assertEquals(ProviderLifecycleState.CLOSED, result.generation().session().state(),
                "the real X1 generation is terminal even while X4 retries membership cleanup");
        assertEquals(1, result.generation().provider().retireCalls());
        assertEquals(1, result.generation().provider().closeCalls(), "X1 ownership release remains exact-once");
        assertThrows(IllegalStateException.class,
                () -> result.adapter().prepare(worldFrame(result.identity())),
                "the false-live membership state may not permit a new prepared pin");

        assertSame(expectedTerminal, assertThrows(Throwable.class, result.adapter()::close),
                "retry must replay the original protocol-selected terminal identity");
        assertEquals(2, result.membership().revokeCalls(), "only the retained exact receipt is retried");
        assertTrue(result.membership().revoked());
        assertEquals(X4HostLifecycleState.CLOSED, result.adapter().state());
        assertSame(expectedTerminal, awaitFutureFailure(result.adapter().drainCompletion().toCompletableFuture(),
                terminalOrderDeadline(), "full-chain drain did not fail"));
        assertEquals(1, result.generation().provider().retireCalls());
        assertEquals(1, result.generation().provider().closeCalls());
    }

    private static FullChainResult runFullChain(
            Throwable rendererPrimary, Error providerRelease, Error membershipRevoke) {
        var generation = X4LifecycleTestSupport.published(912L);
        generation.provider().onRetire(() -> throwUnchecked(providerRelease));
        X4HostIdentity identity = identity("full-chain-" + (rendererPrimary instanceof AssertionError ? "assertion" : "ordinary"));
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(912L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererPrimary);
                }),
                identity,
                generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation =
                ((X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter).freezeAndReserveRegistration();
        FlakyMembership membership = new FlakyMembership(77L, membershipRevoke);
        reservation.commit(membership);
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();

        Throwable observed = assertThrows(Throwable.class, () -> adapter.submit(prepared, unusedContext()));
        return new FullChainResult(generation, adapter, identity, membership, observed);
    }

    private static Throwable expected(Throwable primary, Throwable cleanup) {
        if (primary instanceof Error) {
            return primary;
        }
        if (cleanup instanceof Error) {
            return cleanup;
        }
        return primary;
    }

    private static void assertSuppressed(Throwable primary, Throwable expected) {
        assertTrue(List.of(primary.getSuppressed()).contains(expected),
                () -> "expected suppressed identity " + expected + " on " + primary);
    }

    private static X4HostAdapter<X4HostFrames.WorldObject> worldAdapter(
            ClientModelLookup lookup,
            BlendRenderer renderer,
            X4HostIdentity identity,
            com.liy.blendlib.spi.experimental.ProviderLifecycleSession session) {
        return X4HostAdapters.worldObject(lookup, renderer, session)
                .model(KEY)
                .identity(identity)
                .configuration(new X4HostConfigurations.WorldObject(
                        identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();
    }

    /** Uses the package-private concrete constructor only to arm the terminal-only allocation seam. */
    private static DefaultX4HostAdapter<X4HostFrames.WorldObject> worldAdapterWithPostSealAllocationProbe(
            ClientModelLookup lookup,
            BlendRenderer renderer,
            X4HostIdentity identity,
            com.liy.blendlib.spi.experimental.ProviderLifecycleSession session) {
        X4HostConfigurations.WorldObject configuration = new X4HostConfigurations.WorldObject(
                identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT);
        X4HostSpec<X4HostFrames.WorldObject> specification = new X4HostSpec<>(
                X4HostKind.WORLD_OBJECT, KEY, identity, configuration);
        return new DefaultX4HostAdapter<>(specification, lookup, renderer, session);
    }

    private static X4HostFrames.WorldObject worldFrame(X4HostIdentity identity) {
        return new X4HostFrames.WorldObject(
                X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, 0x00F00071, 0, 0xFFFFFFFF, true),
                identity.scope(),
                0L);
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_x6_r12:scope"), BlendResourceId.parse("x4_x6_r12:host/" + local));
    }

    private static RenderSubmissionContext unusedContext() {
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                X4X6ErrorPolicyCompatibilityTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("the X4 renderer callback must not consume the collector in this lifecycle probe");
                });
        return new RenderSubmissionContext(new PoseStack(), collector);
    }

    private static <H> HostRegistrationSpec<H> specification(HostKind kind, H host) {
        return new HostRegistrationSpec<>(kind, host, KEY, ignored -> AnimationRequest.loop(ANIMATION));
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

    private enum FailureKind {
        ORDINARY {
            @Override
            Throwable create(String message) {
                return new IllegalStateException(message);
            }
        },
        ASSERTION {
            @Override
            Throwable create(String message) {
                return new AssertionError(message);
            }
        },
        LINKAGE {
            @Override
            Throwable create(String message) {
                return new LinkageError(message);
            }
        },
        VIRTUAL_MACHINE {
            @Override
            Throwable create(String message) {
                return new OutOfMemoryError(message);
            }
        },
        THREAD_DEATH {
            @Override
            Throwable create(String message) {
                return new ThreadDeath();
            }
        };

        abstract Throwable create(String message);
    }

    private static final class NoSuppressionError extends Error {
        private NoSuppressionError(String message) {
            super(message, null, false, true);
        }
    }

    private static final class FatalEqualsHost {
        private final Error failure;

        private FatalEqualsHost(Error failure) {
            this.failure = failure;
        }

        @Override
        public boolean equals(Object other) {
            throw failure;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static final class FlakyMembership implements X4HostRegistrationMembership {
        private final long revision;
        private final Error firstFailure;
        private int revokeCalls;
        private boolean revoked;

        private FlakyMembership(long revision, Error firstFailure) {
            this.revision = revision;
            this.firstFailure = firstFailure;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public void revoke() {
            revokeCalls++;
            if (revokeCalls == 1) {
                throw firstFailure;
            }
            revoked = true;
        }

        int revokeCalls() {
            return revokeCalls;
        }

        boolean revoked() {
            return revoked;
        }
    }

    private static final class CountingMembership implements X4HostRegistrationMembership {
        private final long revision;
        private final AtomicInteger revokeCalls = new AtomicInteger();
        private final AtomicBoolean revoked = new AtomicBoolean();

        private CountingMembership(long revision) {
            this.revision = revision;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public void revoke() {
            revokeCalls.incrementAndGet();
            revoked.set(true);
        }

        private int revokeCalls() {
            return revokeCalls.get();
        }

        private boolean revoked() {
            return revoked.get();
        }
    }

    private static final class BlockingMembership implements X4HostRegistrationMembership {
        private final long revision;
        private final Error firstFailure;
        private final AtomicInteger revokeCalls = new AtomicInteger();
        private final CountDownLatch firstRevoke = new CountDownLatch(1);
        private final CountDownLatch releaseFirstRevoke = new CountDownLatch(1);
        private final AtomicBoolean revoked = new AtomicBoolean();

        private BlockingMembership(long revision, Error firstFailure) {
            this.revision = revision;
            this.firstFailure = firstFailure;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public void revoke() {
            int attempt = revokeCalls.incrementAndGet();
            if (attempt == 1) {
                firstRevoke.countDown();
                try {
                    if (!releaseFirstRevoke.await(TERMINAL_ORDER_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
                        throw new AssertionError("foreign first revoke was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("foreign first revoke interrupted", interrupted);
                }
                throw firstFailure;
            }
            revoked.set(true);
        }

        private CountDownLatch firstRevoke() {
            return firstRevoke;
        }

        private void releaseFirstRevoke() {
            releaseFirstRevoke.countDown();
        }

        private int revokeCalls() {
            return revokeCalls.get();
        }

        private boolean revoked() {
            return revoked.get();
        }
    }

    private static final class CoordinatedMembership implements X4HostRegistrationMembership {
        private final long revision;
        private final Error firstFailure;
        private final AtomicInteger revokeCalls = new AtomicInteger();
        private final CountDownLatch firstRevoke = new CountDownLatch(1);
        private final CountDownLatch secondRevoke = new CountDownLatch(1);
        private final AtomicBoolean revoked = new AtomicBoolean();

        private CoordinatedMembership(long revision, Error firstFailure) {
            this.revision = revision;
            this.firstFailure = firstFailure;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public void revoke() {
            int attempt = revokeCalls.incrementAndGet();
            if (attempt == 1) {
                firstRevoke.countDown();
                throw firstFailure;
            }
            if (revoked.compareAndSet(false, true) && attempt == 2) {
                secondRevoke.countDown();
            }
        }

        private CountDownLatch firstRevoke() {
            return firstRevoke;
        }

        private CountDownLatch secondRevoke() {
            return secondRevoke;
        }

        private int revokeCalls() {
            return revokeCalls.get();
        }

        private boolean revoked() {
            return revoked.get();
        }
    }

    /** First C and post-seal C2 both fail; only an explicit third terminal attempt may clear the receipt. */
    private static final class SequencedMembership implements X4HostRegistrationMembership {
        private final long revision;
        private final Error firstFailure;
        private final Error secondFailure;
        private final AtomicInteger revokeCalls = new AtomicInteger();
        private final CountDownLatch firstRevoke = new CountDownLatch(1);
        private final CountDownLatch secondRevoke = new CountDownLatch(1);
        private final AtomicBoolean revoked = new AtomicBoolean();

        private SequencedMembership(long revision, Error firstFailure, Error secondFailure) {
            this.revision = revision;
            this.firstFailure = firstFailure;
            this.secondFailure = secondFailure;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public void revoke() {
            int attempt = revokeCalls.incrementAndGet();
            if (attempt == 1) {
                firstRevoke.countDown();
                throw firstFailure;
            }
            if (attempt == 2) {
                secondRevoke.countDown();
                throw secondFailure;
            }
            revoked.set(true);
        }

        private CountDownLatch firstRevoke() {
            return firstRevoke;
        }

        private CountDownLatch secondRevoke() {
            return secondRevoke;
        }

        private int revokeCalls() {
            return revokeCalls.get();
        }

        private boolean revoked() {
            return revoked.get();
        }
    }

    private static final class FakeLookup implements ClientModelLookup {
        private final ClientModelView view;

        private FakeLookup(long generation) {
            MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, generation);
            ClientDiagnostic diagnostic = new ClientDiagnostic(
                    ClientDiagnosticSeverity.ERROR,
                    "X4-X6-R12-MISSING",
                    KEY.resourceId(),
                    KEY.resourceId(),
                    "x4-x6-r12",
                    "fixture missing model",
                    "none");
            view = new ClientModelView(KEY, generation, false, handle, Optional.of(diagnostic));
        }

        @Override
        public ClientRegistryView snapshot() {
            return new ClientRegistryView(
                    view.generationId(), Map.of(KEY, view), List.of(view.primaryDiagnostic().orElseThrow()));
        }

        @Override
        public ClientModelView resolve(BlendModelKey key) {
            assertEquals(KEY, key);
            return view;
        }
    }

    private record FullChainResult(
            X4LifecycleTestSupport.SessionHarness generation,
            X4HostAdapter<X4HostFrames.WorldObject> adapter,
            X4HostIdentity identity,
            FlakyMembership membership,
            Throwable observed) {
    }
}
