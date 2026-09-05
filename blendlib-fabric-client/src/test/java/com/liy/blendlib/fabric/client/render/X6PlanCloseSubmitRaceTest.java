package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

/**
 * Permanent close/submit races for the X6 plan pin.
 *
 * <p>Every fixture is published through {@link X6PreparedRenderPlanFactory}; it never invokes the
 * package constructor. The factory validates the exact immutable handle and snapshot used by the
 * submitter, which makes the blocking callbacks an equivalent consumer of a publishable plan,
 * rather than the invalid empty-plan standalone fixture rejected in r11.</p>
 */
class X6PlanCloseSubmitRaceTest {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final BlendResourceId CAPABILITY = BlendResourceId.parse("x6_r12:material_capability");

    @Test
    void closeRequestReturnsPromptlyRejectsNewSubmitAndDefersPhysicalLeaseReleaseForEveryCallbackRoute()
            throws Exception {
        for (CallbackRoute route : CallbackRoute.values()) {
            PlanFixture fixture = fixture(route, null);
            Blocker blocker = new Blocker(1);
            AtomicReference<Throwable> submitFailure = new AtomicReference<>();
            Thread submit = new Thread(() -> {
                try {
                    X6PlanSubmitter.submit(
                            fixture.plan(), fixture.snapshot(), context(blockingCollector(route, blocker)), blockingBackend(route, blocker));
                } catch (Throwable failure) {
                    submitFailure.set(failure);
                }
            }, "x6-r12-" + route + "-submit");
            try {
                fixture.providers().retire();
                submit.start();
                blocker.awaitEntered();

                long started = System.nanoTime();
                fixture.plan().close();
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsedMillis < 500L, () -> route + " close request took " + elapsedMillis + " ms");
                assertTrue(fixture.plan().isClosed(), "close request must fail closed before its active submit drains");
                assertEquals(0, fixture.provider().retireCalls(),
                        "close return is a request only; physical provider release waits for the active " + route + " callback");
                assertEquals(0, fixture.provider().closeCalls());
                assertThrows(IllegalStateException.class, () -> X6PlanSubmitter.submit(
                        fixture.plan(), fixture.snapshot(), context(new NoOpSubmitNodeStorage()), (child, childContext) -> { }),
                        "no submit may linearize after the close request");

                blocker.release();
                join(submit);
                assertNull(submitFailure.get());
                assertEquals(1, fixture.lifecycleOwner().requestCount(), "last hold signals one pre-admitted owner bridge");
                assertEquals(0, fixture.provider().retireCalls(), "submit may not perform physical provider lifecycle work");
                assertEquals(0, fixture.provider().closeCalls());
                drainOnOwner(fixture, "x6-r13-owner-" + route);
                assertEquals(1, fixture.provider().retireCalls(), "owner drain releases the X1 generation once");
                assertEquals(1, fixture.provider().closeCalls());
                fixture.plan().close();
                fixture.plan().close();
                assertEquals(1, fixture.provider().retireCalls(), "repeated close remains idempotent after drain");
                assertEquals(1, fixture.provider().closeCalls());
            } finally {
                blocker.release();
                if (submit.isAlive()) {
                    join(submit);
                }
                closeIgnoringFailure(fixture);
            }
        }
    }

    /**
     * Regression contract for r13: the caller that drains the last admitted submit may only signal
     * lifecycle work.  It must not invoke provider retirement/close itself.
     *
     * <p>The permanent form uses the explicit public owner admission overload and deterministic
     * test owner. Its original six-argument assertions were first recorded as the valid r12 RED.</p>
     */
    @Test
    void finalSubmissionDrainSignalsOnlyAndNeverRunsProviderLifecycleOnAnySubmitRoute() throws Exception {
        for (CallbackRoute route : CallbackRoute.values()) {
            PlanFixture fixture = fixture(route, null);
            Blocker blocker = new Blocker(1);
            AtomicReference<Throwable> submitFailure = new AtomicReference<>();
            Thread submit = new Thread(() -> {
                try {
                    X6PlanSubmitter.submit(
                            fixture.plan(), fixture.snapshot(), context(blockingCollector(route, blocker)), blockingBackend(route, blocker));
                } catch (Throwable failure) {
                    submitFailure.set(failure);
                }
            }, "x6-r13-red-" + route + "-submit");
            try {
                fixture.providers().retire();
                submit.start();
                blocker.awaitEntered();

                fixture.plan().close();
                assertEquals(0, fixture.provider().retireCalls(), "close request itself remains signal-only");
                assertEquals(0, fixture.provider().closeCalls(), "close request itself remains signal-only");

                blocker.release();
                join(submit);
                assertNull(submitFailure.get(), "owner drain is not a submit failure channel");
                assertEquals(0, fixture.provider().retireCalls(),
                        route + " final submit drain must only signal the lifecycle owner");
                assertEquals(0, fixture.provider().closeCalls(),
                        route + " final submit drain must not close the provider");
                assertNull(fixture.provider().retireThread(),
                        route + " provider retire callback must remain absent until owner drain");
                assertNull(fixture.provider().closeThread(),
                        route + " provider close callback must remain absent until owner drain");
                assertEquals(1, fixture.lifecycleOwner().requestCount());
                assertEquals(1, fixture.lifecycleOwner().pendingCount());
                drainOnOwner(fixture, "x6-r13-owner-signal-" + route);
                assertEquals(1, fixture.provider().retireCalls());
                assertEquals(1, fixture.provider().closeCalls());
            } finally {
                blocker.release();
                if (submit.isAlive()) {
                    join(submit);
                }
                closeIgnoringFailure(fixture);
            }
        }
    }

    @Test
    void twoConcurrentSubmitsKeepTheSinglePlanLeaseOpenUntilBothHoldsDrain() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        Blocker blocker = new Blocker(2);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = submitThread("first", fixture, context(new BlockingSubmitNodeStorage(1, blocker)), (child, childContext) -> { }, firstFailure);
        Thread second = submitThread("second", fixture, context(new BlockingSubmitNodeStorage(1, blocker)), (child, childContext) -> { }, secondFailure);
        try {
            fixture.providers().retire();
            first.start();
            second.start();
            blocker.awaitEntered();

            fixture.plan().close();
            assertEquals(0, fixture.provider().retireCalls(), "both submit holds are still active");
            assertThrows(IllegalStateException.class, () -> X6PlanSubmitter.submit(
                    fixture.plan(), fixture.snapshot(), context(new NoOpSubmitNodeStorage()), (child, childContext) -> { }));

            blocker.release();
            join(first);
            join(second);
            assertNull(firstFailure.get());
            assertNull(secondFailure.get());
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            assertEquals(0, fixture.provider().retireCalls());
            assertEquals(0, fixture.provider().closeCalls());
            drainOnOwner(fixture, "x6-r13-two-submit-owner");
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            blocker.release();
            if (first.isAlive()) {
                join(first);
            }
            if (second.isAlive()) {
                join(second);
            }
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void onlyTheFinalOfTwoSeparatelyDrainingSubmitsSignalsThePreAdmittedOwner() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        Blocker firstBlocker = new Blocker(1);
        Blocker secondBlocker = new Blocker(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = submitThread("first-final", fixture, context(new BlockingSubmitNodeStorage(1, firstBlocker)),
                (child, childContext) -> { }, firstFailure);
        Thread second = submitThread("second-final", fixture, context(new BlockingSubmitNodeStorage(1, secondBlocker)),
                (child, childContext) -> { }, secondFailure);
        try {
            fixture.providers().retire();
            first.start();
            second.start();
            firstBlocker.awaitEntered();
            secondBlocker.awaitEntered();
            fixture.plan().close();

            firstBlocker.release();
            join(first);
            assertNull(firstFailure.get());
            assertEquals(0, fixture.lifecycleOwner().requestCount(), "the first of two holds cannot signal drain");
            assertEquals(0, fixture.provider().retireCalls());
            assertEquals(0, fixture.provider().closeCalls());

            secondBlocker.release();
            join(second);
            assertNull(secondFailure.get());
            assertEquals(1, fixture.lifecycleOwner().requestCount(), "only the final hold signals once");
            assertEquals(1, fixture.lifecycleOwner().pendingCount());
            assertEquals(0, fixture.provider().retireCalls());
            drainOnOwner(fixture, "x6-r13-final-hold-owner");
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            firstBlocker.release();
            secondBlocker.release();
            if (first.isAlive()) {
                join(first);
            }
            if (second.isAlive()) {
                join(second);
            }
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void zeroHoldCloseIsSignalOnlyForBothForeignAndOwnerNamedCallers() throws Exception {
        for (String callerRole : List.of("foreign", "owner")) {
            PlanFixture fixture = fixture(CallbackRoute.BASE, null);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closeCaller = new Thread(() -> {
                try {
                    fixture.plan().close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            }, "x6-r13-" + callerRole + "-close-caller");
            try {
                fixture.providers().retire();
                closeCaller.start();
                join(closeCaller);
                assertNull(closeFailure.get());
                assertTrue(fixture.plan().isClosed());
                assertEquals(1, fixture.lifecycleOwner().requestCount());
                assertEquals(1, fixture.lifecycleOwner().pendingCount());
                assertEquals(0, fixture.provider().retireCalls(), callerRole + " close caller must only signal");
                assertEquals(0, fixture.provider().closeCalls());
                drainOnOwner(fixture, "x6-r13-designated-owner-" + callerRole);
                assertEquals(1, fixture.provider().retireCalls());
                assertEquals(1, fixture.provider().closeCalls());
            } finally {
                if (closeCaller.isAlive()) {
                    join(closeCaller);
                }
                closeIgnoringFailure(fixture);
            }
        }
    }

    @Test
    void retainedRequestRejectionNeverEscapesCloseAndTheOwnerEmergencyDrainStillClosesOnce() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        IllegalStateException rejection = new IllegalStateException("owner request rejected after admission");
        try {
            fixture.providers().retire();
            fixture.lifecycleOwner().rejectNextRequestAfterRetention(rejection);
            fixture.plan().close();
            assertSame(rejection, fixture.plan().lifecycleDrain().completion().requestFailure());
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            assertEquals(1, fixture.lifecycleOwner().pendingCount(), "registered bridge remains emergency-drainable");
            assertEquals(0, fixture.provider().retireCalls());
            assertEquals(0, fixture.provider().closeCalls());
            Throwable ownerObserved = drainOnOwnerCapturingFailure(fixture, "x6-r13-rejection-emergency-owner");
            assertSame(rejection, ownerObserved, "request rejection remains exact lifecycle-owner completion evidence");
            assertSame(rejection, fixture.plan().lifecycleDrain().completion().ownerFailure());
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void retainedRequestErrorNeverEscapesSubmitAndTheOwnerSelectsItsExactIdentityAfterEmergencyDrain() throws Exception {
        AssertionError submitPrimary = new AssertionError("submit-primary-with-request-error");
        AssertionError requestError = new AssertionError("owner-request-error");
        LinkageError providerError = new LinkageError("owner-provider-error-after-request-error");
        PlanFixture fixture = fixture(CallbackRoute.BASE, providerError);
        try {
            fixture.providers().retire();
            fixture.lifecycleOwner().rejectNextRequestAfterRetention(requestError);
            Throwable submitObserved = assertThrows(Throwable.class, () -> X6PlanSubmitter.submit(
                    fixture.plan(),
                    fixture.snapshot(),
                    context(new CloseThenThrowSubmitNodeStorage(fixture.plan(), submitPrimary)),
                    (child, childContext) -> { }));

            assertSame(submitPrimary, submitObserved);
            assertFalse(List.of(submitObserved.getSuppressed()).contains(requestError),
                    "owner request Error must never enter submit suppression");
            assertFalse(List.of(submitObserved.getSuppressed()).contains(providerError),
                    "later provider Error must never enter submit suppression");
            assertSame(requestError, fixture.plan().lifecycleDrain().completion().requestFailure());
            assertEquals(0, fixture.provider().retireCalls());
            assertEquals(0, fixture.provider().closeCalls());
            assertEquals(1, fixture.lifecycleOwner().pendingCount(), "the accepted bridge remains emergency-drainable");

            Throwable ownerObserved = drainOnOwnerCapturingFailure(fixture, "x6-r13-request-error-owner");
            assertSame(requestError, ownerObserved, "owner completion selects the original request Error");
            assertSame(providerError, fixture.plan().lifecycleDrain().completion().terminalFailure());
            assertTrue(List.of(ownerObserved.getSuppressed()).contains(providerError),
                    "the later owner provider Error remains exact suppression metadata");
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void postReturnPreRequestOwnerRunFailsSubsequentSubmitClosedButEmergencyDrainStillOwnsPhysicalClose() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        try {
            fixture.lifecycleOwner().lastRegisteredForDefensiveOwnerReplay().run();
            assertTrue(fixture.plan().lifecycleDrain().completion().inlineViolation());

            IllegalStateException submitRejected = assertThrows(IllegalStateException.class, () -> X6PlanSubmitter.submit(
                    fixture.plan(), fixture.snapshot(), context(new NoOpSubmitNodeStorage()), (child, childContext) -> { }));
            assertTrue(submitRejected.getMessage().contains(X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED.code()));

            fixture.providers().retire();
            fixture.plan().close();
            assertEquals(0, fixture.provider().retireCalls(), "post-return violation may not close on the submit or close caller");
            assertEquals(0, fixture.provider().closeCalls());
            Throwable ownerObserved = drainOnOwnerCapturingFailure(fixture, "x6-r13-post-return-emergency-owner");
            assertSame(fixture.plan().lifecycleDrain().completion().ownerContractFailure(), ownerObserved,
                    "the hostile pre-request owner invocation is replayed only on the lifecycle-owner path");
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void sameThreadRepeatedInlineRequestRunsNeverAdvanceOrPhysicallyDrainBeforeRequestReturns() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        try {
            fixture.providers().retire();
            fixture.lifecycleOwner().inlineNextRequestRuns(2);
            fixture.plan().close();

            assertTrue(fixture.plan().lifecycleDrain().completion().inlineViolation(),
                    "the caller-stack invocation must remain an owner-contract violation");
            assertFalse(fixture.plan().lifecycleDrain().completion().terminal(),
                    "two same-thread inline runs must not advance the bridge to terminal before request acknowledgement");
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            assertEquals(1, fixture.lifecycleOwner().pendingCount(),
                    "the pre-admitted bridge remains available for the later lifecycle-owner emergency drain");
            assertEquals(0, fixture.provider().retireCalls(),
                    "same-thread inline runs must never close on the close/request caller");
            assertEquals(0, fixture.provider().closeCalls());

            Throwable ownerObserved = drainOnOwnerCapturingFailure(fixture, "x6-r13-inline-double-emergency-owner");
            assertSame(fixture.plan().lifecycleDrain().completion().ownerContractFailure(), ownerObserved,
                    "the malformed request remains replayed only through the later owner path");
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void distinctOwnerWorkerBeforeRequestReturnDrainsExactlyOnceWithoutBeingMarkedInline() {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        try {
            fixture.providers().retire();
            String ownerName = "x6-r13-owner-before-request-return";
            fixture.lifecycleOwner().runNextRequestOnDistinctOwnerBeforeReturn(ownerName);
            fixture.plan().close();

            assertNull(fixture.lifecycleOwner().lastDistinctOwnerFailure(),
                    "a distinct lifecycle owner may legitimately drain before request acknowledgement returns");
            assertFalse(fixture.plan().lifecycleDrain().completion().inlineViolation(),
                    "only the request caller's stack is an inline violation");
            assertTrue(fixture.plan().lifecycleDrain().completion().terminal(),
                    "the accepted owner drain must not be overwritten back to requested after acknowledgement");
            assertNull(fixture.plan().lifecycleDrain().completion().ownerFailure());
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
            assertEquals(ownerName, fixture.provider().retireThread().getName());
            assertEquals(ownerName, fixture.provider().closeThread().getName());

            fixture.lifecycleOwner().runAll();
            fixture.plan().close();
            assertEquals(1, fixture.provider().retireCalls(), "terminal owner replays must not close twice");
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void lateOrdinaryRequestFailureAfterPreReturnOwnerSuccessIsRetainedAndReplayed() {
        IllegalStateException requestFailure = new IllegalStateException("late-ordinary-request-after-owner-success");
        assertLateRequestFailureAfterPreReturnOwnerTerminal(
                null, requestFailure, null, requestFailure, null, "late-ordinary-after-success");
    }

    @Test
    void lateRequestErrorAfterPreReturnOwnerSuccessIsRetainedAndReplayed() {
        AssertionError requestFailure = new AssertionError("late-request-error-after-owner-success");
        assertLateRequestFailureAfterPreReturnOwnerTerminal(
                null, requestFailure, null, requestFailure, null, "late-error-after-success");
    }

    @Test
    void lateOrdinaryRequestFailureAfterPreReturnProviderErrorKeepsProviderErrorPrimary() {
        LinkageError providerFailure = new LinkageError("provider-error-before-late-ordinary-request");
        IllegalStateException requestFailure = new IllegalStateException("late-ordinary-request-after-provider-error");
        assertLateRequestFailureAfterPreReturnOwnerTerminal(
                providerFailure,
                requestFailure,
                providerFailure,
                providerFailure,
                requestFailure,
                "late-ordinary-after-provider-error");
    }

    @Test
    void lateRequestErrorAfterPreReturnProviderErrorKeepsRequestErrorPrimary() {
        LinkageError providerFailure = new LinkageError("provider-error-before-late-request-error");
        AssertionError requestFailure = new AssertionError("late-request-error-after-provider-error");
        assertLateRequestFailureAfterPreReturnOwnerTerminal(
                providerFailure,
                requestFailure,
                providerFailure,
                requestFailure,
                providerFailure,
                "late-error-after-provider-error");
    }

    @Test
    void repeatedCloseAndConcurrentDefensiveOwnerRunsPhysicallyCloseOnlyOnce() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        try {
            fixture.providers().retire();
            fixture.plan().close();
            fixture.plan().close();
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            Runnable bridge = fixture.lifecycleOwner().lastRegisteredForDefensiveOwnerReplay();
            Thread firstOwner = new Thread(() -> {
                try {
                    bridge.run();
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            }, "x6-r13-concurrent-owner-a");
            Thread secondOwner = new Thread(() -> {
                try {
                    bridge.run();
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                }
            }, "x6-r13-concurrent-owner-b");
            firstOwner.start();
            secondOwner.start();
            join(firstOwner);
            join(secondOwner);
            assertNull(firstFailure.get());
            assertNull(secondFailure.get());
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
            fixture.lifecycleOwner().runAll();
            fixture.plan().close();
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void sharedProviderAcrossTwoGenerationsClosesGloballyOnlyAfterTheSecondOwnerDrain() throws Exception {
        CountingMaterialProvider shared = new CountingMaterialProvider(
                BlendResourceId.parse("x6_r13:provider/shared-global"), null);
        long firstGeneration = 14_000L + SEQUENCE.incrementAndGet();
        long secondGeneration = 14_000L + SEQUENCE.incrementAndGet();
        PlanFixture first = fixture(CallbackRoute.BASE, shared, firstGeneration);
        PlanFixture second = fixture(CallbackRoute.BASE, shared, secondGeneration);
        try {
            first.providers().retire();
            second.providers().retire();
            first.plan().close();
            second.plan().close();
            assertEquals(0, shared.closeCalls(), "both admitted plan pins are still owned by their lifecycle bridges");

            assertNull(drainOnOwnerCapturingFailure(first, "x6-r13-shared-first-owner"));
            assertEquals(0, shared.closeCalls(), "the first generation drain must not globally close the shared provider");

            assertNull(drainOnOwnerCapturingFailure(second, "x6-r13-shared-second-owner"));
            assertEquals(1, shared.closeCalls(), "only the final generation owner drain closes the shared provider");
        } finally {
            closeIgnoringFailure(first);
            closeIgnoringFailure(second);
        }
    }

    @Test
    void submitFailureAndLaterOwnerProviderFailureKeepSeparateExactIdentities() throws Exception {
        AssertionError assertionPrimary = new AssertionError("assertion-submit-primary");
        LinkageError linkageCleanup = new LinkageError("linkage-owner-cleanup");
        assertSubmitAndOwnerFailure(assertionPrimary, linkageCleanup);
    }

    @Test
    void reentrantCloseFromCollectorDoesNotWaitForItselfAndReleasesOnlyAfterSubmitFinally() throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch callbackReturned = new CountDownLatch(1);
        Thread submit = new Thread(() -> {
            try {
                X6PlanSubmitter.submit(
                        fixture.plan(),
                        fixture.snapshot(),
                        context(new ReentrantCloseSubmitNodeStorage(fixture.plan(), fixture.provider(), callbackReturned)),
                        (child, childContext) -> { });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "x6-r12-reentrant-close");
        try {
            fixture.providers().retire();
            submit.start();
            assertTrue(callbackReturned.await(5, TimeUnit.SECONDS), "reentrant close callback did not return");
            join(submit);
            assertNull(failure.get());
            assertTrue(fixture.plan().isClosed());
            assertEquals(0, fixture.provider().retireCalls());
            assertEquals(0, fixture.provider().closeCalls());
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            drainOnOwner(fixture, "x6-r13-reentrant-owner");
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
            fixture.plan().close();
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            if (submit.isAlive()) {
                join(submit);
            }
            closeIgnoringFailure(fixture);
        }
    }

    @Test
    void suppressionAppenderFailuresRemainMetadataOnlyUnlessTheAppenderThrowsAnError() {
        AssertionError selected = new AssertionError("selected");
        IllegalStateException secondary = new IllegalStateException("secondary");
        AtomicInteger calls = new AtomicInteger();
        X6PreparedRenderPlanFactory.addSuppressedSafely(selected, secondary, (primary, cleanup) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("ordinary appender failure");
        });
        assertSame(selected, selected);
        assertEquals(1, calls.get());
        assertEquals(0, selected.getSuppressed().length);

        X6PreparedRenderPlanFactory.addSuppressedSafely(selected, selected, (primary, cleanup) -> {
            throw new AssertionError("duplicate primary may not reach appender");
        });
        OutOfMemoryError appenderError = new OutOfMemoryError("appender-error");
        assertSame(appenderError, assertThrows(OutOfMemoryError.class,
                () -> X6PreparedRenderPlanFactory.addSuppressedSafely(
                        selected, secondary, (primary, cleanup) -> { throw appenderError; })));
    }

    private static void assertSubmitAndOwnerFailure(Throwable submitPrimary, Error closeCleanup) throws Exception {
        PlanFixture fixture = fixture(CallbackRoute.BASE, closeCleanup);
        try {
            fixture.providers().retire();
            Throwable observed = assertThrows(Throwable.class, () -> X6PlanSubmitter.submit(
                    fixture.plan(),
                    fixture.snapshot(),
                    context(new CloseThenThrowSubmitNodeStorage(fixture.plan(), submitPrimary)),
                    (child, childContext) -> { }));
            assertSame(submitPrimary, observed);
            assertFalse(List.of(observed.getSuppressed()).contains(closeCleanup),
                    "a later lifecycle-owner provider failure must not be mixed into submit suppression");
            assertEquals(0, fixture.provider().retireCalls());
            assertEquals(0, fixture.provider().closeCalls());
            assertEquals(1, fixture.lifecycleOwner().requestCount());
            Throwable ownerObserved = drainOnOwnerCapturingFailure(fixture, "x6-r13-error-owner");
            assertSame(closeCleanup, ownerObserved);
            assertSame(closeCleanup, fixture.plan().lifecycleDrain().completion().terminalFailure());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    /**
     * The worker may finish before the host request method returns. A request failure discovered
     * afterward cannot change that already-returned worker call, but it must update completion and
     * every later defensive owner replay with the normal every-Error selection policy.
     */
    private static void assertLateRequestFailureAfterPreReturnOwnerTerminal(
            Error providerFailure,
            Throwable requestFailure,
            Throwable expectedInitialOwnerFailure,
            Throwable expectedReplay,
            Throwable expectedSuppressed,
            String label) {
        PlanFixture fixture = fixture(CallbackRoute.BASE, providerFailure);
        try {
            fixture.providers().retire();
            fixture.lifecycleOwner().runNextRequestOnDistinctOwnerBeforeReturn("x6-r13-" + label + "-owner");
            fixture.lifecycleOwner().rejectNextRequestAfterRetention(requestFailure);

            fixture.plan().close();
            assertSame(requestFailure, fixture.plan().lifecycleDrain().completion().requestFailure(),
                    "close must retain the exact late request failure without throwing it");
            assertSame(providerFailure, fixture.plan().lifecycleDrain().completion().terminalFailure());
            assertSame(expectedInitialOwnerFailure, fixture.lifecycleOwner().lastDistinctOwnerFailure(),
                    "the already-finished owner call cannot retroactively observe a later request failure");
            assertSame(expectedReplay, fixture.plan().lifecycleDrain().completion().ownerFailure(),
                    "completion must reselect after the late request outcome is known");
            if (expectedSuppressed != null) {
                assertTrue(List.of(expectedReplay.getSuppressed()).contains(expectedSuppressed),
                        "the non-selected late terminal remains exact suppression evidence");
            }

            IllegalStateException submitObserved = assertThrows(IllegalStateException.class, () -> X6PlanSubmitter.submit(
                    fixture.plan(), fixture.snapshot(), context(new NoOpSubmitNodeStorage()), (child, childContext) -> { }));
            assertTrue(submitObserved != requestFailure,
                    "a late owner request failure must never become the public submit failure");
            assertFalse(List.of(submitObserved.getSuppressed()).contains(requestFailure));

            Throwable replayObserved = assertThrows(Throwable.class, fixture.lifecycleOwner()::runAll);
            assertSame(expectedReplay, replayObserved,
                    "the defensive owner replay must expose the final every-Error selected identity");
            assertEquals(1, fixture.provider().retireCalls());
            assertEquals(1, fixture.provider().closeCalls());
        } finally {
            closeIgnoringFailure(fixture);
        }
    }

    private static Thread submitThread(
            String name,
            PlanFixture fixture,
            RenderSubmissionContext context,
            ModelRenderBackend backend,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                X6PlanSubmitter.submit(fixture.plan(), fixture.snapshot(), context, backend);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "x6-r12-two-submit-" + name);
    }

    private static SubmitNodeStorage blockingCollector(CallbackRoute route, Blocker blocker) {
        return switch (route) {
            case BASE -> new BlockingSubmitNodeStorage(1, blocker);
            case LAYER -> new BlockingSubmitNodeStorage(2, blocker);
            case ATTACHMENT, EQUIPMENT -> new NoOpSubmitNodeStorage();
        };
    }

    private static ModelRenderBackend blockingBackend(CallbackRoute route, Blocker blocker) {
        return switch (route) {
            case ATTACHMENT, EQUIPMENT -> (child, childContext) -> blocker.hit();
            case BASE, LAYER -> (child, childContext) -> {
                throw new AssertionError("base/layer fixture must not invoke attachment backend");
            };
        };
    }

    private static PlanFixture fixture(CallbackRoute route, Error retireFailure) {
        long generation = 12_000L + SEQUENCE.incrementAndGet();
        return fixture(
                route,
                new CountingMaterialProvider(
                        BlendResourceId.parse("x6_r12:provider/" + generation), retireFailure),
                generation);
    }

    private static PlanFixture fixture(CallbackRoute route, CountingMaterialProvider provider, long generation) {
        BlendModelKey key = BlendModelKey.parse("x6_r12:actors/" + generation);
        BlendResourceId part = BlendResourceId.parse("x6_r12:part/" + generation);
        PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(
                0,
                StaticGeometry.of(
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new int[] {0, 1, 2}),
                material(generation));
        X6PreparedGeometryCatalog geometry = new X6PreparedGeometryCatalog(key, generation, Map.of(part, primitive));
        X6TestRenderHandle handle = new X6TestRenderHandle(key, generation, List.of(primitive), Map.of(0, Transform.IDENTITY), false);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        X6MaterialPlan materials = X6MaterialPlan.prepare(key, generation, Map.of(
                part, new X6MaterialIntent(material(generation).textureId(), X6MaterialMode.OPAQUE, false, false, null)))
                .plan().orElseThrow();
        List<ModelRenderSnapshot> equipment = route == CallbackRoute.EQUIPMENT ? List.of(snapshot) : List.of();
        X6VariantApplicationPlan variants = new X6VariantApplicationPlan(
                key,
                generation,
                List.of(new X6DrawPrimitive(part, geometry.binding(part), materials.material(part), 0xFFFFFFFF)),
                equipment);
        List<X6LayerEntry> requestedLayers = route == CallbackRoute.LAYER
                ? List.of(new X6LayerEntry(
                        BlendResourceId.parse("x6_r12:layer/glow/" + generation),
                        X6LayerType.GLOW,
                        X6RenderPhase.POST_BASE,
                        0,
                        X6LayerTargetSelector.part(part),
                        Optional.empty(),
                        Optional.empty()))
                : route == CallbackRoute.ATTACHMENT
                        ? List.of(new X6LayerEntry(
                                BlendResourceId.parse("x6_r12:layer/attachment/" + generation),
                                X6LayerType.ATTACHMENT,
                                X6RenderPhase.ATTACHMENT,
                                0,
                                X6LayerTargetSelector.part(part),
                                Optional.empty(),
                                Optional.of(snapshot)))
                        : List.of();
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(key, generation, requestedLayers, geometry).plan().orElseThrow();
        X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(
                key,
                generation,
                List.of(provider),
                List.of(CapabilityRequest.required(CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL_RANGE)));
        assertTrue(providers.publishable());
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                variants, layers, materials, geometry, providers, snapshot, lifecycleOwner).plan().orElseThrow();
        assertEquals(1, plan.baseDraws().size(), "the public factory must publish a real non-empty immutable plan");
        return new PlanFixture(plan, snapshot, providers, provider, lifecycleOwner);
    }

    private static RenderMaterial material(long generation) {
        return new RenderMaterial(
                BlendResourceId.parse("x6_r12:textures/" + generation + ".png"),
                RenderLayer.SOLID,
                false,
                false,
                0xFFFFFFFF,
                false);
    }

    private static RenderSubmissionContext context(SubmitNodeStorage storage) {
        return new RenderSubmissionContext(new PoseStack(), storage);
    }

    private static void closeIgnoringFailure(PlanFixture fixture) {
        try {
            fixture.plan().close();
        } catch (Throwable ignored) {
            // Error-precedence fixtures deliberately make the terminal X1 release fail exactly once.
        }
        try {
            fixture.lifecycleOwner().runAll();
        } catch (Throwable ignored) {
            // Provider terminal failures must remain lifecycle-owner observations.
        }
        try {
            fixture.providers().close();
        } catch (Throwable ignored) {
            // The same terminal provider failure is deliberately observable from later X1 observers.
        }
    }

    private static void drainOnOwner(PlanFixture fixture, String ownerName) throws Exception {
        Throwable failure = drainOnOwnerCapturingFailure(fixture, ownerName);
        assertNull(failure, "owner drain must not become a test failure");
        assertEquals(ownerName, fixture.provider().retireThread().getName(),
                "provider retirement must run on the nominated lifecycle owner");
        assertEquals(ownerName, fixture.provider().closeThread().getName(),
                "provider close must run on the nominated lifecycle owner");
    }

    private static Throwable drainOnOwnerCapturingFailure(PlanFixture fixture, String ownerName) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try {
                fixture.lifecycleOwner().runNext();
            } catch (Throwable observed) {
                failure.set(observed);
            }
        }, ownerName);
        owner.start();
        join(owner);
        return failure.get();
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000L);
        assertFalse(thread.isAlive(), "X6 close/submit probe did not drain or terminate");
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

    private enum CallbackRoute {
        BASE,
        LAYER,
        ATTACHMENT,
        EQUIPMENT
    }

    private record PlanFixture(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            X6MaterialProviderGeneration providers,
            CountingMaterialProvider provider,
            X6TestLifecycleDrainDispatcher lifecycleOwner) {
    }

    private static final class Blocker {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        private Blocker(int expectedEntries) {
            entered = new CountDownLatch(expectedEntries);
        }

        private void hit() {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "test did not release blocked X6 callback");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }

        private void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "X6 submit never reached its intended blocking callback");
        }

        private void release() {
            release.countDown();
        }
    }

    private static class NoOpSubmitNodeStorage extends SubmitNodeStorage {
        @Override
        public void submitCustomGeometry(
                PoseStack poseStack,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            // The lifecycle probe needs only the public collector callback boundary, not rendering output.
        }
    }

    private static final class BlockingSubmitNodeStorage extends NoOpSubmitNodeStorage {
        private final int targetInvocation;
        private final Blocker blocker;
        private int invocations;

        private BlockingSubmitNodeStorage(int targetInvocation, Blocker blocker) {
            this.targetInvocation = targetInvocation;
            this.blocker = blocker;
        }

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            if (++invocations == targetInvocation) {
                blocker.hit();
            }
        }
    }

    private static final class CloseThenThrowSubmitNodeStorage extends NoOpSubmitNodeStorage {
        private final X6PreparedRenderPlan plan;
        private final Throwable primary;

        private CloseThenThrowSubmitNodeStorage(X6PreparedRenderPlan plan, Throwable primary) {
            this.plan = plan;
            this.primary = primary;
        }

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            plan.close();
            throwUnchecked(primary);
        }
    }

    private static final class ReentrantCloseSubmitNodeStorage extends NoOpSubmitNodeStorage {
        private final X6PreparedRenderPlan plan;
        private final CountingMaterialProvider provider;
        private final CountDownLatch callbackReturned;

        private ReentrantCloseSubmitNodeStorage(
                X6PreparedRenderPlan plan, CountingMaterialProvider provider, CountDownLatch callbackReturned) {
            this.plan = plan;
            this.provider = provider;
            this.callbackReturned = callbackReturned;
        }

        @Override
        public void submitCustomGeometry(
                PoseStack poseStack,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer) {
            plan.close();
            assertEquals(0, provider.retireCalls(),
                    "reentrant close returns as a request and cannot synchronously release its own active hold");
            callbackReturned.countDown();
        }
    }

    private static final class CountingMaterialProvider implements MaterialProvider {
        private final BlendResourceId providerId;
        private final Error retireFailure;
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Thread> retireThread = new AtomicReference<>();
        private final AtomicReference<Thread> closeThread = new AtomicReference<>();

        private CountingMaterialProvider(BlendResourceId providerId, Error retireFailure) {
            this.providerId = providerId;
            this.retireFailure = retireFailure;
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(providerId, CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL, 1));
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of(CAPABILITY);
        }

        @Override
        public void retire(com.liy.blendlib.spi.experimental.ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
            retireThread.compareAndSet(null, Thread.currentThread());
            if (retireFailure != null) {
                throw retireFailure;
            }
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeThread.compareAndSet(null, Thread.currentThread());
        }

        int retireCalls() {
            return retireCalls.get();
        }

        int closeCalls() {
            return closeCalls.get();
        }

        Thread retireThread() {
            return retireThread.get();
        }

        Thread closeThread() {
            return closeThread.get();
        }
    }
}
