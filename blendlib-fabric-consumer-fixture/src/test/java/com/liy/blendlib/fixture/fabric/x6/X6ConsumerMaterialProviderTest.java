package com.liy.blendlib.fixture.fabric.x6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.CapabilityErrorCode;
import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityNegotiationException;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityPlan;
import com.liy.blendlib.spi.experimental.CapabilityRegistry;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilitySelectionOutcome;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import com.liy.blendlib.spi.experimental.ProviderLifecycleStage;
import com.liy.blendlib.spi.experimental.ProviderLifecycleState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** Verifies an external X6 material provider remains only public X1 metadata/lifecycle code. */
class X6ConsumerMaterialProviderTest {
    private static final int CLOSE_RACE_ITERATIONS = 128;
    private static final long CLOSE_RACE_TIMEOUT_SECONDS = 5L;
    private static final long CLOSE_RACE_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(CLOSE_RACE_TIMEOUT_SECONDS);
    private static final long CLOSE_RACE_CLEANUP_TIMEOUT_SECONDS = 5L;
    private static final long CLOSE_RACE_CLEANUP_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(CLOSE_RACE_CLEANUP_TIMEOUT_SECONDS);
    private static final CapabilityVersionRange VERSION_RANGE = CapabilityVersion.CURRENT_PROTOCOL_RANGE;

    @Test
    void providerNegotiatesThroughThePublicX1LifecycleWithoutClientImplementationTypes() {
        X6ConsumerMaterialProvider provider = new X6ConsumerMaterialProvider();
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(
                X6ConsumerMaterialProvider.STANDARD_MATERIAL_CAPABILITY, VERSION_RANGE)));
        CapabilityPlan plan = registry.freeze(6L);
        assertTrue(plan.isPublishable());
        assertEquals(X6ConsumerMaterialProvider.PROVIDER_ID,
                plan.selectionFor(X6ConsumerMaterialProvider.STANDARD_MATERIAL_CAPABILITY)
                        .orElseThrow().selectedOffer().orElseThrow().providerId());

        ProviderLifecycleSession session = new ProviderLifecycleSession(plan, List.of(provider));
        assertTrue(session.prepare().successful());
        assertTrue(session.apply().successful());
        session.publish();
        ProviderLease lease = session.pin();
        session.retire();
        assertEquals(List.of(ProviderLifecycleStage.PREPARE, ProviderLifecycleStage.APPLY), provider.lifecycleStages());
        lease.close();
        assertEquals(
                List.of(
                        ProviderLifecycleStage.PREPARE,
                        ProviderLifecycleStage.APPLY,
                        ProviderLifecycleStage.RETIRE,
                        ProviderLifecycleStage.CLOSE),
                provider.lifecycleStages());
    }

    @Test
    void externalOptionalFallbackAndExceptionRemainPublicLifecycleOutcomes() {
        CapabilityRegistry fallbackRegistry = new CapabilityRegistry();
        fallbackRegistry.discover(List.of(CapabilityRequest.optional(
                X6ConsumerMaterialProvider.STANDARD_MATERIAL_CAPABILITY,
                VERSION_RANGE,
                new CapabilityFallback(id("safe_vanilla"), "external caller retains a declared standard route"))));
        CapabilityPlan fallback = fallbackRegistry.freeze(7L);
        assertTrue(fallback.isPublishable());
        assertEquals(CapabilitySelectionOutcome.FALLBACK,
                fallback.selectionFor(X6ConsumerMaterialProvider.STANDARD_MATERIAL_CAPABILITY).orElseThrow().outcome());
        ProviderLifecycleSession fallbackSession = new ProviderLifecycleSession(fallback, List.of());
        assertTrue(fallbackSession.prepare().successful());
        assertTrue(fallbackSession.apply().successful());
        fallbackSession.publish();
        fallbackSession.close();

        FailingExternalProvider broken = new FailingExternalProvider();
        CapabilityRegistry brokenRegistry = new CapabilityRegistry();
        brokenRegistry.register(broken);
        brokenRegistry.discover(List.of(CapabilityRequest.required(broken.capability(), VERSION_RANGE)));
        ProviderLifecycleSession brokenSession = new ProviderLifecycleSession(brokenRegistry.freeze(8L), List.of(broken));
        assertFalse(brokenSession.prepare().successful());
        assertEquals(1, broken.prepareCalls.get());
        brokenSession.close();
        assertEquals(1, broken.closeCalls.get());
    }

    @Test
    void externalProviderCloseRaceCannotIssueANewPinOrDoubleClose() throws Throwable {
        for (int iteration = 0; iteration < CLOSE_RACE_ITERATIONS; iteration++) {
            for (CloseOrdering ordering : CloseOrdering.values()) {
                runExternalProviderCloseRace(iteration, ordering);
            }
        }
    }

    @Test
    void closeWorkerHarnessKeepsTheControllerFreeWhenCloseWaitsForPinRelease() throws Throwable {
        String label = "blocking-terminal-release harness";
        BlockingTerminalProvider provider = new BlockingTerminalProvider();
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(provider.capability(), VERSION_RANGE)));
        ProviderLifecycleSession session = new ProviderLifecycleSession(registry.freeze(8_999L), List.of(provider));
        long deadline = deadlineAfter(CLOSE_RACE_TIMEOUT_NANOS);
        AtomicReference<ProviderLease> blockerReference = new AtomicReference<>();
        CloseWorker retireWorker = new CloseWorker(label + " retire", session::close);
        CloseWorker releaseWorker = new CloseWorker(label + " release", () -> {
            ProviderLease lease = blockerReference.get();
            if (lease != null) {
                lease.close();
            }
        });
        CloseWorker observerWorker = new CloseWorker(label + " observer", session::close);
        List<CloseWorker> workers = List.of(retireWorker, releaseWorker, observerWorker);
        ProviderLease blocker = null;
        Throwable primaryFailure = null;
        List<Throwable> cleanupFailures = new ArrayList<>();
        try {
            workers.forEach(CloseWorker::ensureStarted);
            for (CloseWorker worker : workers) {
                awaitLatch(worker.ready, deadline, worker.label + ": worker was not scheduled");
            }
            assertTrue(session.prepare().successful(), label + ": prepare");
            assertTrue(session.apply().successful(), label + ": apply");
            session.publish();
            blocker = session.pin();
            blockerReference.set(blocker);
            triggerClose(retireWorker, deadline, label);
            awaitState(session, ProviderLifecycleState.RETIRING, deadline, label);
            triggerClose(releaseWorker, deadline, label);
            awaitLatch(provider.retireEntered, deadline, label + ": provider retire callback did not block release");
            assertTrue(releaseWorker.thread.isAlive(), "the real lease release must still be in the blocked retire callback");
            triggerClose(observerWorker, deadline, label);
            awaitThreadAwaitingRetirement(observerWorker.thread, deadline, label);
            assertTrue(observerWorker.thread.isAlive(),
                    "the duplicate close must wait for the active terminal callback off-controller");
            provider.releaseRetire.countDown();
            awaitThreadTermination(releaseWorker.thread, deadline, label);
            awaitThreadTermination(retireWorker.thread, deadline, label);
            awaitThreadTermination(observerWorker.thread, deadline, label);
            awaitState(session, ProviderLifecycleState.CLOSED, deadline, label);
        } catch (Throwable failure) {
            primaryFailure = failure;
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            provider.releaseRetire.countDown();
            finishWorkers(
                    workers,
                    deadlineAfter(CLOSE_RACE_CLEANUP_TIMEOUT_NANOS),
                    label,
                    cleanupFailures);
        }
        ProviderLease completedBlocker = blocker;
        try {
            assertAll(
                    label,
                    () -> assertTrue(completedBlocker != null && completedBlocker.isClosed(),
                            label + ": real blocker lease was not released"),
                    () -> assertTrue(workers.stream().noneMatch(worker -> worker.thread.isAlive()),
                            label + ": worker leaked"),
                    () -> assertTrue(workers.stream().allMatch(worker -> worker.failure.get() == null),
                            label + ": worker failed"),
                    () -> assertEquals(ProviderLifecycleState.CLOSED, session.state(), label + ": terminal state"),
                    () -> assertEquals(1, provider.retireCalls.get(), label + ": retire count"),
                    () -> assertEquals(1, provider.closeCalls.get(), label + ": close count"));
        } catch (Throwable verificationFailure) {
            cleanupFailures.add(verificationFailure);
        }
        rethrowWithCleanup(primaryFailure, cleanupFailures);
    }

    private static void runExternalProviderCloseRace(int iteration, CloseOrdering ordering) throws Throwable {
        String label = "close-race iteration " + iteration + " ordering " + ordering;
        ControlledConsumerProvider provider = new ControlledConsumerProvider();
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(
                X6ConsumerMaterialProvider.STANDARD_MATERIAL_CAPABILITY, VERSION_RANGE)));
        ProviderLifecycleSession session =
                new ProviderLifecycleSession(
                        registry.freeze(9_000L + (long) iteration * CloseOrdering.values().length + ordering.ordinal()),
                        List.of(provider));
        long deadline = deadlineAfter(CLOSE_RACE_TIMEOUT_NANOS);
        AtomicReference<ProviderLease> blockerReference = new AtomicReference<>();
        List<CloseWorker> sessionCloseWorkers = List.of(
                new CloseWorker(label + " initial", session::close),
                new CloseWorker(label + " duplicate", session::close),
                new CloseWorker(label + " terminal", session::close));
        CloseWorker releaseWorker = new CloseWorker(label + " release", () -> {
            ProviderLease lease = blockerReference.get();
            if (lease != null) {
                lease.close();
            }
        });
        List<CloseWorker> allWorkers = List.of(
                sessionCloseWorkers.get(0),
                sessionCloseWorkers.get(1),
                sessionCloseWorkers.get(2),
                releaseWorker);

        ProviderLease blocker = null;
        Throwable primaryFailure = null;
        List<Throwable> cleanupFailures = new ArrayList<>();
        try {
            allWorkers.forEach(CloseWorker::ensureStarted);
            for (CloseWorker worker : allWorkers) {
                awaitLatch(worker.ready, deadline, worker.label + ": worker was not scheduled");
            }
            assertTrue(session.prepare().successful(), label + ": prepare");
            assertTrue(session.apply().successful(), label + ": apply");
            session.publish();
            blocker = session.pin();
            blockerReference.set(blocker);
            triggerClose(sessionCloseWorkers.get(0), deadline, label);
            awaitState(session, ProviderLifecycleState.RETIRING, deadline, label);
            awaitThreadTermination(sessionCloseWorkers.get(0).thread, deadline, label);

            CapabilityNegotiationException pinFailure = assertThrows(
                    CapabilityNegotiationException.class,
                    session::pin,
                    label + ": retirement must reject a new pin");
            assertEquals(
                    CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                    pinFailure.diagnostic().code(),
                    label + ": pin rejection code");

            switch (ordering) {
                case RELEASE_JOIN_CLOSE -> {
                    triggerClose(releaseWorker, deadline, label);
                    awaitLatch(provider.retireEntered, deadline, label + ": release did not enter terminal retire");
                    provider.releaseRetire.countDown();
                    awaitThreadTermination(releaseWorker.thread, deadline, label);
                    triggerClose(sessionCloseWorkers.get(1), deadline, label);
                    awaitThreadTermination(sessionCloseWorkers.get(1).thread, deadline, label);
                }
                case RELEASE_CLOSE_JOIN -> {
                    triggerClose(releaseWorker, deadline, label);
                    awaitLatch(provider.retireEntered, deadline, label + ": release did not enter terminal retire");
                    triggerClose(sessionCloseWorkers.get(1), deadline, label);
                    awaitThreadAwaitingRetirement(sessionCloseWorkers.get(1).thread, deadline, label);
                    provider.releaseRetire.countDown();
                    awaitThreadTermination(sessionCloseWorkers.get(1).thread, deadline, label);
                    awaitThreadTermination(releaseWorker.thread, deadline, label);
                }
                case CLOSE_RELEASE_JOIN -> {
                    triggerClose(sessionCloseWorkers.get(1), deadline, label);
                    awaitThreadTermination(sessionCloseWorkers.get(1).thread, deadline, label);
                    triggerClose(releaseWorker, deadline, label);
                    awaitLatch(provider.retireEntered, deadline, label + ": release did not enter terminal retire");
                    provider.releaseRetire.countDown();
                    awaitThreadTermination(releaseWorker.thread, deadline, label);
                }
                case CLOSE_RELEASE_CLOSE_JOIN -> {
                    triggerClose(sessionCloseWorkers.get(1), deadline, label);
                    awaitThreadTermination(sessionCloseWorkers.get(1).thread, deadline, label);
                    triggerClose(releaseWorker, deadline, label);
                    awaitLatch(provider.retireEntered, deadline, label + ": release did not enter terminal retire");
                    triggerClose(sessionCloseWorkers.get(2), deadline, label);
                    awaitThreadAwaitingRetirement(sessionCloseWorkers.get(2).thread, deadline, label);
                    provider.releaseRetire.countDown();
                    for (CloseWorker worker : allWorkers) {
                        awaitThreadTermination(worker.thread, deadline, label);
                    }
                }
            }
            awaitState(session, ProviderLifecycleState.CLOSED, deadline, label);
        } catch (Throwable failure) {
            primaryFailure = failure;
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            provider.releaseRetire.countDown();
            finishWorkers(
                    allWorkers,
                    deadlineAfter(CLOSE_RACE_CLEANUP_TIMEOUT_NANOS),
                    label,
                    cleanupFailures);
        }

        ProviderLease completedBlocker = blocker;
        try {
            assertAll(
                    label,
                    () -> assertTrue(completedBlocker != null && completedBlocker.isClosed(),
                            label + ": original pin was not released"),
                    () -> assertTrue(allWorkers.stream().noneMatch(worker -> worker.thread.isAlive()),
                            label + ": at least one close worker leaked"),
                    () -> assertTrue(allWorkers.stream().allMatch(worker -> worker.failure.get() == null),
                            label + ": at least one close worker failed"),
                    () -> assertEquals(ProviderLifecycleState.CLOSED, session.state(), label + ": terminal state"),
                    () -> assertEquals(
                            List.of(
                                    ProviderLifecycleStage.PREPARE,
                                    ProviderLifecycleStage.APPLY,
                                    ProviderLifecycleStage.RETIRE,
                                    ProviderLifecycleStage.CLOSE),
                            provider.lifecycleStages(),
                            label + ": retire/close must each execute exactly once"));
        } catch (Throwable verificationFailure) {
            cleanupFailures.add(verificationFailure);
        }
        rethrowWithCleanup(primaryFailure, cleanupFailures);
    }

    @Test
    void sourceHasNoClientCoreOrPipelineDependency() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "java", "com", "liy", "blendlib", "fixture", "fabric", "x6",
                "X6ConsumerMaterialProvider.java"));
        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.client.",
                "RenderType",
                "RenderPipeline",
                "shader",
                "org.lwjgl",
                "minecraft.client")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("implements MaterialProvider"));
        assertTrue(source.contains("supportedMaterialCapabilities"));
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("consumer_fixture:" + path);
    }

    private static void awaitLatch(CountDownLatch latch, long deadline, String timeoutMessage)
            throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
            throw new AssertionError(timeoutMessage + " within the shared "
                    + CLOSE_RACE_TIMEOUT_SECONDS + "-second deadline");
        }
    }

    private static void awaitState(
            ProviderLifecycleSession session,
            ProviderLifecycleState expected,
            long deadline,
            String label) throws InterruptedException {
        while (session.state() != expected) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new AssertionError(label + ": expected state " + expected + " before deadline, actual "
                        + session.state());
            }
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException(label + ": interrupted while awaiting state " + expected);
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(1L)));
        }
    }

    private static void awaitThreadTermination(Thread thread, long deadline, String label)
            throws InterruptedException {
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new AssertionError(label + ": close thread did not terminate within "
                        + CLOSE_RACE_TIMEOUT_SECONDS + " seconds");
            }
            long millis = Math.max(1L, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 100L));
            thread.join(millis);
        }
    }

    private static void awaitThreadAwaitingRetirement(Thread thread, long deadline, String label)
            throws InterruptedException {
        while (thread.isAlive()) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(ProviderLifecycleSession.class.getName())
                        && frame.getMethodName().equals("awaitRetirement")) {
                    return;
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new AssertionError(label + ": close worker did not reach the active retirement wait; state="
                        + thread.getState());
            }
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException(label + ": controller interrupted while observing retirement wait");
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(1L)));
        }
        throw new AssertionError(label + ": close worker terminated before observing the active retirement wait");
    }

    private static void triggerClose(CloseWorker worker, long deadline, String label)
            throws InterruptedException {
        worker.start.countDown();
        awaitLatch(worker.invocation, deadline, label + ": close worker did not invoke its operation");
    }

    private static void finishWorkers(
            List<CloseWorker> workers,
            long cleanupDeadline,
            String label,
            List<Throwable> cleanupFailures) {
        for (CloseWorker worker : workers) {
            worker.ensureStarted();
            worker.start.countDown();
        }
        boolean restoreInterrupt = Thread.interrupted();
        try {
            for (CloseWorker worker : workers) {
                while (worker.thread.isAlive()) {
                    long remaining = cleanupDeadline - System.nanoTime();
                    if (remaining <= 0L) {
                        cleanupFailures.add(new AssertionError(label
                                + ": close-worker cleanup exceeded the shared "
                                + CLOSE_RACE_CLEANUP_TIMEOUT_SECONDS + "-second deadline"));
                        break;
                    }
                    long millis = Math.max(1L, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 100L));
                    try {
                        worker.thread.join(millis);
                    } catch (InterruptedException failure) {
                        restoreInterrupt = true;
                        cleanupFailures.add(failure);
                    }
                }
            }
            workers.stream()
                    .filter(worker -> worker.thread.isAlive())
                    .forEach(worker -> worker.thread.interrupt());
            for (CloseWorker worker : workers) {
                if (worker.thread.isAlive()) {
                    try {
                        worker.thread.join(100L);
                    } catch (InterruptedException failure) {
                        restoreInterrupt = true;
                        cleanupFailures.add(failure);
                    }
                    if (worker.thread.isAlive()) {
                        cleanupFailures.add(new AssertionError(
                                worker.label + ": close worker remained alive after interrupt"));
                    }
                }
                Throwable workerFailure = worker.failure.get();
                if (workerFailure != null) {
                    cleanupFailures.add(workerFailure);
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void rethrowWithCleanup(Throwable primaryFailure, List<Throwable> cleanupFailures)
            throws Throwable {
        if (primaryFailure != null) {
            cleanupFailures.stream()
                    .filter(failure -> failure != primaryFailure)
                    .forEach(primaryFailure::addSuppressed);
            throw primaryFailure;
        }
        if (!cleanupFailures.isEmpty()) {
            Throwable first = cleanupFailures.getFirst();
            cleanupFailures.stream()
                    .skip(1L)
                    .filter(failure -> failure != first)
                    .forEach(first::addSuppressed);
            throw first;
        }
    }

    private static long deadlineAfter(long durationNanos) {
        long now = System.nanoTime();
        long deadline = now + durationNanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private enum CloseOrdering {
        RELEASE_JOIN_CLOSE,
        RELEASE_CLOSE_JOIN,
        CLOSE_RELEASE_JOIN,
        CLOSE_RELEASE_CLOSE_JOIN
    }

    private static final class CloseWorker {
        private final String label;
        private final org.junit.jupiter.api.function.Executable operation;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch start = new CountDownLatch(1);
        private final CountDownLatch invocation = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private CloseWorker(String label, org.junit.jupiter.api.function.Executable operation) {
            this.label = label;
            this.operation = operation;
            thread = new Thread(this::run, "consumer-x6-close-worker-" + label.replace(' ', '-'));
        }

        private void ensureStarted() {
            if (thread.getState() == Thread.State.NEW) {
                thread.start();
            }
        }

        private void run() {
            ready.countDown();
            try {
                start.await();
                invocation.countDown();
                operation.execute();
            } catch (Throwable workerFailure) {
                failure.compareAndSet(null, workerFailure);
                if (workerFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final class BlockingTerminalProvider implements MaterialProvider {
        private static final BlendResourceId PROVIDER = id("blocking_terminal_provider");
        private static final BlendResourceId CAPABILITY = id("blocking_terminal_capability");
        private final CountDownLatch retireEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRetire = new CountDownLatch(1);
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private BlendResourceId capability() {
            return CAPABILITY;
        }

        @Override
        public BlendResourceId providerId() {
            return PROVIDER;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(
                    PROVIDER, CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL, 1));
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of(CAPABILITY);
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
            retireEntered.countDown();
            try {
                if (!releaseRetire.await(CLOSE_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("blocking terminal provider timed out waiting for controller release");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("blocking terminal provider was interrupted", failure);
            }
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class ControlledConsumerProvider implements MaterialProvider {
        private final X6ConsumerMaterialProvider delegate = new X6ConsumerMaterialProvider();
        private final CountDownLatch retireEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRetire = new CountDownLatch(1);

        @Override
        public BlendResourceId providerId() {
            return delegate.providerId();
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return delegate.offers();
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return delegate.supportedMaterialCapabilities();
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            delegate.prepare(context);
        }

        @Override
        public void apply(ProviderLifecycleContext context) {
            delegate.apply(context);
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            delegate.retire(context);
            retireEntered.countDown();
            try {
                if (!releaseRetire.await(CLOSE_RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("controlled consumer provider timed out waiting for terminal release");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("controlled consumer provider was interrupted", failure);
            }
        }

        @Override
        public void close() {
            delegate.close();
        }

        private List<ProviderLifecycleStage> lifecycleStages() {
            return delegate.lifecycleStages();
        }
    }

    private static final class FailingExternalProvider implements MaterialProvider {
        private static final BlendResourceId PROVIDER = id("failing_provider");
        private static final BlendResourceId CAPABILITY = id("failing_capability");
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        BlendResourceId capability() {
            return CAPABILITY;
        }

        @Override
        public BlendResourceId providerId() {
            return PROVIDER;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(PROVIDER, CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL, 1));
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of(CAPABILITY);
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            prepareCalls.incrementAndGet();
            throw new IllegalStateException("fixture external lifecycle failure");
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
