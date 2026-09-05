package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** r5 probes for exact-once managed terminal ownership and close-over-retire coordination. */
class X4ManagedHostAdapterFifthRepairContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_r5:models/managed");

    @Test
    void delegateCloseReentryFailsFastInsteadOfWaitingForItsOwnTerminalTransaction() throws Exception {
        TerminalProbeAdapter raw = new TerminalProbeAdapter(identity("same-thread-reentry"));
        X4ManagedHostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(raw);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        raw.onClose = () -> callbackFailure.set(assertThrows(IllegalStateException.class, managed::close));
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        CountDownLatch ownerReturned = new CountDownLatch(1);
        Thread owner = new Thread(() -> capture(managed::close, ownerFailure, ownerReturned), "x4-r5-managed-reentry-owner");
        try {
            owner.start();
            await(raw.firstCloseEntered);
            assertTrue(ownerReturned.await(2, TimeUnit.SECONDS),
                    "same-thread delegate callback must fail fast instead of waiting for its own terminal latch");
            join(owner);
            assertNull(ownerFailure.get());
            assertTrue(callbackFailure.get() instanceof IllegalStateException);
            assertTrue(callbackFailure.get().getMessage().contains("terminal owner"));
            assertEquals(1, raw.closeCalls.get());
        } finally {
            raw.releaseFirstTerminal.countDown();
            if (owner.isAlive()) {
                owner.interrupt();
            }
            joinIfAlive(owner);
        }
    }

    @Test
    void concurrentManagedCloseSharesOneTerminalFailureWithEveryObserverAndNeverRetriesTheDelegate() throws Exception {
        int failureIndex = 0;
        for (Throwable expected : new Throwable[] {
                new IllegalStateException("ordinary-close"),
                new OutOfMemoryError("fatal-close"),
                new ThreadDeath()
        }) {
            String failureName = "failure-" + failureIndex++;
            TerminalProbeAdapter raw = new TerminalProbeAdapter(identity("shared-" + failureName));
            raw.blockAndFailFirstClose(expected);
            X4ManagedHostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(raw);
            X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
            AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
            AtomicReference<Throwable> firstObserverFailure = new AtomicReference<>();
            AtomicReference<Throwable> secondObserverFailure = new AtomicReference<>();
            CountDownLatch observersStarted = new CountDownLatch(2);
            Thread owner = new Thread(() -> capture(managed::close, ownerFailure, null),
                    "x4-r5-managed-failure-owner-" + failureName);
            Thread firstObserver = new Thread(() -> {
                observersStarted.countDown();
                capture(managed::close, firstObserverFailure, null);
            }, "x4-r5-managed-failure-observer-a-" + failureName);
            Thread secondObserver = new Thread(() -> {
                observersStarted.countDown();
                capture(managed::close, secondObserverFailure, null);
            }, "x4-r5-managed-failure-observer-b-" + failureName);
            try {
                registry.register(managed);
                owner.start();
                await(raw.firstCloseEntered);
                firstObserver.start();
                secondObserver.start();
                await(observersStarted);
                awaitThreadWaiting(firstObserver);
                awaitThreadWaiting(secondObserver);
                raw.releaseFirstTerminal.countDown();
                join(owner);
                join(firstObserver);
                join(secondObserver);

                assertSame(expected, ownerFailure.get());
                assertSame(expected, firstObserverFailure.get());
                assertSame(expected, secondObserverFailure.get());
                assertEquals(1, raw.closeCalls.get(), "all observers must share one raw close callback");
                assertEquals(0, raw.retireCalls.get());
                assertTrue(registry.registrations().isEmpty(),
                        "membership must be revoked before the delegate failure is published");
                assertSame(expected, assertThrows(Throwable.class, managed::close));
                assertSame(expected, assertThrows(Throwable.class, managed::retire));
                assertEquals(1, raw.closeCalls.get(), "post-completion observers must not retry the delegate");
            } finally {
                raw.releaseFirstTerminal.countDown();
                if (owner.isAlive()) {
                    owner.interrupt();
                }
                if (firstObserver.isAlive()) {
                    firstObserver.interrupt();
                }
                if (secondObserver.isAlive()) {
                    secondObserver.interrupt();
                }
                joinIfAlive(owner);
                joinIfAlive(firstObserver);
                joinIfAlive(secondObserver);
                registry.close();
            }
        }
    }

    @Test
    void concurrentManagedClosePublishesOneCanonicalWrapperForACheckedDelegateFailure() throws Exception {
        IOException expectedCause = new IOException("checked-close");
        TerminalProbeAdapter raw = new TerminalProbeAdapter(identity("shared-checked-failure"));
        raw.blockAndFailFirstClose(expectedCause);
        X4ManagedHostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(raw);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> capture(managed::close, ownerFailure, null),
                "x4-r5-managed-checked-failure-owner");
        Thread observer = new Thread(() -> capture(managed::close, observerFailure, null),
                "x4-r5-managed-checked-failure-observer");
        try {
            owner.start();
            await(raw.firstCloseEntered);
            observer.start();
            awaitThreadWaiting(observer);
            raw.releaseFirstTerminal.countDown();
            join(owner);
            join(observer);

            assertTrue(ownerFailure.get() instanceof IllegalStateException);
            assertSame(ownerFailure.get(), observerFailure.get(),
                    "checked delegate failures must also publish one observable wrapper");
            assertSame(expectedCause, ownerFailure.get().getCause());
            assertSame(ownerFailure.get(), assertThrows(Throwable.class, managed::retire));
            assertEquals(1, raw.closeCalls.get());
        } finally {
            raw.releaseFirstTerminal.countDown();
            if (owner.isAlive()) {
                owner.interrupt();
            }
            if (observer.isAlive()) {
                observer.interrupt();
            }
            joinIfAlive(owner);
            joinIfAlive(observer);
        }
    }

    @Test
    void concurrentCloseUpgradesAStillUndispatchedRetireWithoutASecondDelegateCallback() throws Exception {
        TerminalProbeAdapter raw = new TerminalProbeAdapter(identity("retire-close-upgrade"));
        X4ManagedHostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(raw);
        X4HostRegistrationReservation<X4HostFrames.WorldObject> reservation = managed.freezeAndReserveRegistration();
        BlockingMembership membership = new BlockingMembership();
        reservation.commit(membership);
        AtomicReference<Throwable> retireFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread retiring = new Thread(() -> capture(managed::retire, retireFailure, null), "x4-r5-managed-upgrade-retire");
        Thread closing = new Thread(() -> capture(managed::close, closeFailure, null), "x4-r5-managed-upgrade-close");
        try {
            retiring.start();
            await(membership.revokeEntered);
            closing.start();
            awaitThreadWaiting(closing);
            membership.releaseRevoke.countDown();
            join(retiring);
            join(closing);

            assertNull(retireFailure.get());
            assertNull(closeFailure.get());
            assertEquals(1, membership.revokeCalls.get());
            assertEquals(1, raw.closeCalls.get(), "a close that joins before raw dispatch must dominate retire");
            assertEquals(0, raw.retireCalls.get());
            assertEquals(X4HostLifecycleState.CLOSED, raw.state());
        } finally {
            membership.releaseRevoke.countDown();
            if (retiring.isAlive()) {
                retiring.interrupt();
            }
            if (closing.isAlive()) {
                closing.interrupt();
            }
            joinIfAlive(retiring);
            joinIfAlive(closing);
        }
    }

    @Test
    void concurrentCloseAfterRetireDispatchSealsCoalescesWithoutASecondDelegateCallback() throws Exception {
        TerminalProbeAdapter raw = new TerminalProbeAdapter(identity("retire-close-coalesce"));
        raw.blockFirstRetire();
        X4ManagedHostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(raw);
        AtomicReference<Throwable> retireFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread retiring = new Thread(() -> capture(managed::retire, retireFailure, null),
                "x4-r5-managed-coalesce-retire");
        Thread closing = new Thread(() -> capture(managed::close, closeFailure, null),
                "x4-r5-managed-coalesce-close");
        try {
            retiring.start();
            await(raw.firstRetireEntered);
            closing.start();
            awaitThreadWaiting(closing);
            raw.releaseFirstRetire.countDown();
            join(retiring);
            join(closing);

            assertNull(retireFailure.get());
            assertNull(closeFailure.get());
            assertEquals(1, raw.retireCalls.get());
            assertEquals(0, raw.closeCalls.get(),
                    "a close that joins after retire dispatch must coalesce with that terminal transaction");
            assertEquals(X4HostLifecycleState.RETIRED, raw.state());
        } finally {
            raw.releaseFirstRetire.countDown();
            if (retiring.isAlive()) {
                retiring.interrupt();
            }
            if (closing.isAlive()) {
                closing.interrupt();
            }
            joinIfAlive(retiring);
            joinIfAlive(closing);
        }
    }

    @Test
    void closeAfterASuccessfullyCompletedRetireStartsTheNextTerminalTransaction() {
        TerminalProbeAdapter raw = new TerminalProbeAdapter(identity("retire-then-close"));
        X4ManagedHostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(raw);

        managed.retire();
        assertEquals(1, raw.retireCalls.get());
        assertEquals(0, raw.closeCalls.get());
        assertEquals(X4HostLifecycleState.RETIRED, raw.state());

        managed.close();
        assertEquals(1, raw.retireCalls.get());
        assertEquals(1, raw.closeCalls.get());
        assertEquals(X4HostLifecycleState.CLOSED, raw.state());
    }

    private static void capture(Runnable operation, AtomicReference<Throwable> failure, CountDownLatch returned) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        } finally {
            if (returned != null) {
                returned.countDown();
            }
        }
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_r5:scope"), BlendResourceId.parse("x4_r5:host/" + local));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for X4 r5 barrier");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitThreadWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(1L);
        }
        throw new AssertionError("managed terminal observer never waited for the shared completion");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(Duration.ofSeconds(5).toMillis());
        assertFalse(thread.isAlive(), "managed terminal operation did not finish");
    }

    private static void joinIfAlive(Thread thread) throws InterruptedException {
        if (thread.isAlive()) {
            join(thread);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private static final class BlockingMembership implements X4HostRegistrationMembership {
        private final CountDownLatch revokeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRevoke = new CountDownLatch(1);
        private final AtomicInteger revokeCalls = new AtomicInteger();

        @Override
        public long revision() {
            return 5L;
        }

        @Override
        public void revoke() {
            revokeCalls.incrementAndGet();
            revokeEntered.countDown();
            await(releaseRevoke);
        }
    }

    private static final class TerminalProbeAdapter implements X4HostAdapter<X4HostFrames.WorldObject> {
        private final X4HostSpec<X4HostFrames.WorldObject> specification;
        private final CompletableFuture<X4HostLifecycleState> completion = new CompletableFuture<>();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final CountDownLatch firstCloseEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstTerminal = new CountDownLatch(1);
        private final CountDownLatch firstRetireEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstRetire = new CountDownLatch(1);
        private volatile X4HostLifecycleState state = X4HostLifecycleState.CONFIGURING;
        private volatile Runnable onClose = () -> { };
        private volatile Throwable firstCloseFailure;
        private volatile boolean blockFirstClose;
        private volatile boolean blockFirstRetire;

        private TerminalProbeAdapter(X4HostIdentity identity) {
            specification = new X4HostSpec<>(
                    X4HostKind.WORLD_OBJECT,
                    KEY,
                    identity,
                    new X4HostConfigurations.WorldObject(
                            identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));
        }

        private void blockAndFailFirstClose(Throwable failure) {
            firstCloseFailure = failure;
            blockFirstClose = true;
        }

        private void blockFirstRetire() {
            blockFirstRetire = true;
        }

        @Override
        public X4HostSpec<X4HostFrames.WorldObject> configure() {
            return specification;
        }

        @Override
        public X4HostLifecycleState state() {
            return state;
        }

        @Override
        public X4HostLeaseDiagnostics leaseDiagnostics() {
            return new X4HostLeaseDiagnostics(state, 0L, 0, 0, false, state == X4HostLifecycleState.CLOSED, "");
        }

        @Override
        public CompletableFuture<X4HostLifecycleState> drainCompletion() {
            return completion;
        }

        @Override
        public void freeze() {
            if (state != X4HostLifecycleState.CONFIGURING) {
                throw new IllegalStateException("terminal probe can freeze only from configuring");
            }
            state = X4HostLifecycleState.FROZEN;
        }

        @Override
        public X4PreparedSnapshot prepare(X4HostFrames.WorldObject frame) {
            throw new UnsupportedOperationException("terminal probe does not render");
        }

        @Override
        public void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context) {
            throw new UnsupportedOperationException("terminal probe does not render");
        }

        @Override
        public void retire() {
            int call = retireCalls.incrementAndGet();
            if (call == 1) {
                firstRetireEntered.countDown();
                if (blockFirstRetire) {
                    await(releaseFirstRetire);
                }
            }
            state = X4HostLifecycleState.RETIRED;
            completion.complete(state);
        }

        @Override
        public void close() {
            int call = closeCalls.incrementAndGet();
            if (call == 1) {
                firstCloseEntered.countDown();
                if (blockFirstClose) {
                    await(releaseFirstTerminal);
                }
                onClose.run();
                if (firstCloseFailure != null) {
                    state = X4HostLifecycleState.CLOSED;
                    completion.completeExceptionally(firstCloseFailure);
                    throwUnchecked(firstCloseFailure);
                }
            }
            state = X4HostLifecycleState.CLOSED;
            completion.complete(state);
        }
    }
}
