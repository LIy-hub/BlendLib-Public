package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientGenerationResourceOwnerTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("reload_test:fixtures/shared_lifecycle");

    @Test
    void completeCpuCandidateFreezesAndPublishesThroughTheRegistrysSingleActiveReference() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration candidate = missingGeneration(1L);
        PendingGenerationTransaction transaction = cpu(candidate);

        assertSame(candidate, registry.publish(transaction));
        assertSame(candidate, registry.current());
        assertEquals(PendingGenerationTransaction.State.PUBLISHED, transaction.state());
        assertEquals(ClientGenerationResourceOwner.State.PUBLISHED, diagnosticsFor(registry, 1L).state());
        assertFalse(candidate.isRetired());
    }

    @Test
    void replayedPublishedTransactionIsRejectedWithoutClosingItsActiveCallback() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = missingGeneration(1L);
        PendingGenerationTransaction transaction =
                cpu(first, List.of(closeCount::incrementAndGet));

        assertSame(first, registry.publish(transaction));
        assertThrows(IllegalStateException.class, () -> registry.publish(transaction));

        assertSame(first, registry.current());
        assertEquals(PendingGenerationTransaction.State.PUBLISHED, transaction.state());
        assertEquals(ClientGenerationResourceOwner.State.PUBLISHED, diagnosticsFor(registry, first.generationId()).state());
        assertEquals(1, registry.resourceLifecycleDiagnostics().trackedGenerationCount());
        assertEquals(0, renderOwner.queuedCount());
        assertEquals(0, closeCount.get());

        registry.publish(missingGeneration(2L));

        assertEquals(1, renderOwner.queuedCount());
        assertEquals(0, closeCount.get(), "the active generation callback must not close before retirement");
        renderOwner.runNextFencedTask();
        assertEquals(1, closeCount.get());
        assertNotTracked(registry, first.generationId());
    }

    @Test
    void concurrentDuplicatePublicationHasOneClaimWinnerAndOneRetirementClose() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        CountDownLatch claimAcquired = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        ModelRegistryGeneration candidate = missingGeneration(1L);
        PendingGenerationTransaction transaction = cpu(
                candidate,
                List.of(closeCount::incrementAndGet));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ModelRegistryGeneration> winner = executor.submit(() -> registry.publish(
                    transaction,
                    () -> {
                        claimAcquired.countDown();
                        awaitLatch(allowPublication);
                    },
                    ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));
            awaitLatch(claimAcquired);

            Future<ModelRegistryGeneration> duplicate = executor.submit(() -> registry.publish(transaction));
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> duplicate.get(5L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(0, closeCount.get());
            assertEquals(0, renderOwner.queuedCount());

            allowPublication.countDown();
            assertSame(candidate, awaitFuture(winner));
            assertSame(candidate, registry.current());
            assertEquals(PendingGenerationTransaction.State.PUBLISHED, transaction.state());
            assertEquals(0, closeCount.get());

            registry.publish(missingGeneration(2L));
            assertEquals(1, renderOwner.queuedCount());
            renderOwner.runNextFencedTask();
            assertEquals(1, closeCount.get());
            assertNotTracked(registry, candidate.generationId());
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    @Test
    void abortFirstStopsPublicationBeforeCasAndTransfersCleanupOnlyOnce() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        CountDownLatch publicationReady = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        ModelRegistryGeneration candidate = missingGeneration(1L);
        PendingGenerationTransaction transaction = cpu(candidate, List.of(() -> {
            closeAttempts.incrementAndGet();
            if (failOnce.getAndSet(false)) {
                throw new IllegalStateException("abort-first close fails once");
            }
            successfulClose.incrementAndGet();
        }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelRegistryGeneration> publisher = executor.submit(() -> {
                publicationReady.countDown();
                awaitLatch(allowPublication);
                return registry.publish(transaction);
            });
            awaitLatch(publicationReady);

            transaction.abort(new IllegalStateException("abort wins before publication claim"));
            allowPublication.countDown();

            assertSame(registry.current(), awaitFuture(publisher));
            assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
            assertTrue(candidate.isRetired());
            assertEquals(1, renderOwner.queuedCount());
            assertEquals(0, closeAttempts.get());
            renderOwner.runNextFencedTask();

            assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, candidate.generationId()).state());
            assertEquals(1, closeAttempts.get());
            assertEquals(0, successfulClose.get());
            assertTrue(candidate.retryFailedRenderResourceClose());
            renderOwner.runNextFencedTask();

            assertEquals(2, closeAttempts.get());
            assertEquals(1, successfulClose.get());
            assertNotTracked(registry, candidate.generationId());
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    @Test
    void publicationClaimRejectsAbortAndStillRetiresTheDisplacedGenerationAfterCas() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger displacedCloseCount = new AtomicInteger();
        AtomicInteger candidateCloseCount = new AtomicInteger();
        ModelRegistryGeneration displaced = missingGeneration(1L);
        registry.publish(cpu(displaced, List.of(displacedCloseCount::incrementAndGet)));
        CountDownLatch claimAcquired = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        ModelRegistryGeneration candidate = missingGeneration(2L);
        PendingGenerationTransaction transaction = cpu(
                candidate,
                List.of(candidateCloseCount::incrementAndGet));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelRegistryGeneration> publisher = executor.submit(() -> registry.publish(
                    transaction,
                    () -> {
                        claimAcquired.countDown();
                        awaitLatch(allowPublication);
                    },
                    ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));
            awaitLatch(claimAcquired);

            assertThrows(
                    IllegalStateException.class,
                    () -> transaction.abort(new IllegalStateException("publish claim already owns this transaction")));
            assertEquals(0, candidateCloseCount.get());
            assertEquals(0, displacedCloseCount.get());
            allowPublication.countDown();

            assertSame(candidate, awaitFuture(publisher));
            assertSame(candidate, registry.current());
            assertEquals(PendingGenerationTransaction.State.PUBLISHED, transaction.state());
            assertTrue(displaced.isRetired(), "a successful CAS must retire its displaced generation");
            assertEquals(ClientGenerationResourceOwner.State.PUBLISHED, diagnosticsFor(registry, candidate.generationId()).state());
            assertEquals(1, renderOwner.queuedCount());
            assertEquals(0, candidateCloseCount.get());
            renderOwner.runNextFencedTask();
            assertEquals(1, displacedCloseCount.get());

            registry.close();
            assertEquals(1, renderOwner.queuedCount());
            renderOwner.runNextFencedTask();
            assertEquals(1, candidateCloseCount.get());
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    @Test
    void abortedCandidateLeavesThePreviouslyPublishedGenerationActive() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration active = missingGeneration(1L);
        registry.publish(active);
        ModelRegistryGeneration invalid = missingGeneration(2L);
        assertTrue(invalid.retire());
        PendingGenerationTransaction transaction = cpu(invalid);

        assertThrows(IllegalStateException.class, () -> registry.publish(transaction));
        assertSame(active, registry.current());
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertTrue(invalid.isRetired());
        assertNotTracked(registry, invalid.generationId());
    }

    @Test
    void freezeFailureAfterClaimTransfersItsCallbackOnceAndLeavesRetryableCleanup() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger claimCount = new AtomicInteger();
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ModelRegistryGeneration invalid = missingGeneration(1L);
        assertTrue(invalid.retire());
        PendingGenerationTransaction transaction = cpu(
                invalid,
                List.of(() -> {
                    closeAttempts.incrementAndGet();
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("freeze-failure close fails once");
                    }
                    successfulClose.incrementAndGet();
                }));

        assertThrows(IllegalStateException.class, () -> registry.publish(
                transaction,
                claimCount::incrementAndGet,
                ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));
        assertEquals(1, claimCount.get());
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertTrue(invalid.isRetired());
        assertEquals(1, renderOwner.queuedCount());
        renderOwner.runNextFencedTask();

        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, invalid.generationId()).state());
        assertEquals(1, closeAttempts.get());
        assertEquals(0, successfulClose.get());
        assertTrue(invalid.retryFailedRenderResourceClose());
        renderOwner.runNextFencedTask();

        assertEquals(2, closeAttempts.get());
        assertEquals(1, successfulClose.get());
        assertNotTracked(registry, invalid.generationId());
    }

    @Test
    void staleCandidateIsAbortedWithoutASecondActiveGenerationReference() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration newer = missingGeneration(2L);
        ModelRegistryGeneration stale = missingGeneration(1L);

        registry.publish(newer);
        PendingGenerationTransaction transaction = cpu(stale);

        assertSame(newer, registry.publish(transaction));
        assertSame(newer, registry.current());
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertTrue(stale.isRetired());
        assertNotTracked(registry, stale.generationId());
    }

    @Test
    void retirementWaitsForTheExactLeaseAndQueuesFenceOnlyAfterLateRelease() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(cpu(first, List.of(closeCount::incrementAndGet)));
        GenerationRenderResourceLease lease = first.acquireRenderResourceLease(handle(first));

        registry.publish(missingGeneration(2L));

        assertEquals(ClientGenerationResourceOwner.State.RETIRING, diagnosticsFor(registry, 1L).state());
        assertEquals(0, renderOwner.queuedCount());
        assertEquals(0, closeCount.get());
        lease.close();

        ClientGenerationResourceOwner.GenerationDiagnostics queued = diagnosticsFor(registry, 1L);
        assertEquals(ClientGenerationResourceOwner.State.FENCE_QUEUED, queued.state());
        assertTrue(queued.activeCloseAttemptId() > 0L);
        assertEquals(1, renderOwner.queuedCount());
        assertEquals(0, closeCount.get());

        renderOwner.runNextFencedTask();

        assertEquals(1, closeCount.get());
        assertNotTracked(registry, first.generationId());
        lease.close();
        assertEquals(1, closeCount.get());
    }

    @Test
    void closeFailureRetriesOnlyTheFailedCallbackAndUsesNewNonzeroAttemptId() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger alreadyClosed = new AtomicInteger();
        AtomicInteger eventuallyClosed = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(cpu(first, List.of(
                alreadyClosed::incrementAndGet,
                () -> {
                    eventuallyClosed.incrementAndGet();
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("first close attempt fails");
                    }
                })));

        registry.publish(missingGeneration(2L));
        renderOwner.runNextFencedTask();

        ClientGenerationResourceOwner.GenerationDiagnostics failed = diagnosticsFor(registry, 1L);
        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, failed.state());
        assertEquals(1, alreadyClosed.get());
        assertEquals(1, eventuallyClosed.get());
        assertTrue(failed.lastCloseAttemptId() > 0L);
        assertEquals(1, registry.reloadRetentionMetrics().registryRetainedRetiredBackendHandleCount());

        assertTrue(first.retryFailedRenderResourceClose());
        assertEquals(ClientGenerationResourceOwner.State.FENCE_QUEUED, diagnosticsFor(registry, 1L).state());
        renderOwner.runNextFencedTask();

        assertNotTracked(registry, first.generationId());
        assertEquals(1, alreadyClosed.get(), "a successful callback must never run again during retry");
        assertEquals(2, eventuallyClosed.get());
        assertEquals(0, registry.reloadRetentionMetrics().registryRetainedRetiredBackendHandleCount());
        assertFalse(first.retryFailedRenderResourceClose());
    }

    @Test
    void synchronousFencedCallbackIsDeferredUntilFenceAcceptanceOnTheRenderOwner() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(true);
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(cpu(first, List.of(closeCount::incrementAndGet)));

        registry.publish(missingGeneration(2L));

        assertEquals(1, closeCount.get());
        assertEquals(0, renderOwner.queuedCount());
        assertNotTracked(registry, first.generationId());
    }

    @Test
    void staleAndCrossGenerationHandlesCannotAcquireNewLeases() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(first);
        ModelHandle firstHandle = handle(first);
        GenerationRenderResourceLease activeLease = first.acquireRenderResourceLease(firstHandle);
        activeLease.close();
        ModelRegistryGeneration second = missingGeneration(2L);
        registry.publish(second);

        assertThrows(IllegalStateException.class, () -> first.acquireRenderResourceLease(firstHandle));
        assertThrows(IllegalArgumentException.class, () -> second.acquireRenderResourceLease(firstHandle));
    }

    @Test
    void defaultOwnerPublishesCompleteCpuCandidateWithoutExecutingAnyGpuExperimentCallback() {
        ClientModelRegistry registry = new ClientModelRegistry();
        AtomicInteger forbiddenGpuExperiment = new AtomicInteger();
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(cpu(first, List.of(forbiddenGpuExperiment::incrementAndGet)));

        assertSame(first, registry.current());
        assertEquals(ClientGenerationResourceOwner.State.PUBLISHED, diagnosticsFor(registry, 1L).state());
        registry.publish(missingGeneration(2L));

        assertEquals(0, forbiddenGpuExperiment.get());
        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, 1L).state());
    }

    @Test
    void registryCloseDrainsCurrentAndRetiringGenerationsAfterTheirLeasesRelease() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(cpu(first, List.of(closeCount::incrementAndGet)));
        GenerationRenderResourceLease firstLease = first.acquireRenderResourceLease(handle(first));
        ModelRegistryGeneration second = missingGeneration(2L);
        registry.publish(cpu(second, List.of(closeCount::incrementAndGet)));
        GenerationRenderResourceLease secondLease = second.acquireRenderResourceLease(handle(second));

        registry.close();

        ClientGenerationResourceOwner.DrainDiagnostics draining = registry.resourceLifecycleDiagnostics();
        assertEquals(2, draining.outstandingLeaseCount());
        assertEquals(ClientGenerationResourceOwner.State.RETIRING, diagnosticsFor(registry, 1L).state());
        assertEquals(ClientGenerationResourceOwner.State.RETIRING, diagnosticsFor(registry, 2L).state());

        firstLease.close();
        secondLease.close();
        assertEquals(2, renderOwner.queuedCount());
        renderOwner.runNextFencedTask();
        renderOwner.runNextFencedTask();

        ClientGenerationResourceOwner.DrainDiagnostics drained = registry.resourceLifecycleDiagnostics();
        assertEquals(0, drained.outstandingLeaseCount());
        assertEquals(2, closeCount.get());
        assertNotTracked(registry, first.generationId());
        assertNotTracked(registry, second.generationId());
        assertEquals(0, drained.trackedGenerationCount());
    }

    @Test
    void normalReloadCyclesReleaseTerminalOwnerRecordsAndRejectEveryRetiredGeneration() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration previous = missingGeneration(1L);
        registry.publish(previous);

        for (long generationId = 2L; generationId <= 25L; generationId++) {
            ModelRegistryGeneration retiredGeneration = previous;
            ModelHandle previousHandle = handle(retiredGeneration);
            ModelRegistryGeneration next = missingGeneration(generationId);

            assertSame(next, registry.publish(next));
            assertTrue(retiredGeneration.isRetired());
            assertThrows(IllegalStateException.class, () -> retiredGeneration.acquireRenderResourceLease(previousHandle));

            ClientGenerationResourceOwner.DrainDiagnostics owner = registry.resourceLifecycleDiagnostics();
            ReloadRetentionMetrics metrics = registry.reloadRetentionMetrics();
            assertEquals(1, owner.trackedGenerationCount());
            assertEquals(1, owner.generations().size());
            assertEquals(next.generationId(), owner.activeGenerationId());
            assertEquals(0, metrics.registryRetainedRetiredBackendHandleCount());
            previous = next;
        }
    }

    @Test
    void staleUnpublishedCandidateClosesCallbacksAndReleasesItsRecord() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        ModelRegistryGeneration newer = missingGeneration(2L);
        registry.publish(newer);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration stale = missingGeneration(1L);
        PendingGenerationTransaction transaction =
                cpu(stale, List.of(closeCount::incrementAndGet));

        assertSame(newer, registry.publish(transaction));
        assertSame(newer, registry.current());
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertTrue(stale.isRetired());
        assertEquals(ClientGenerationResourceOwner.State.FENCE_QUEUED, diagnosticsFor(registry, stale.generationId()).state());
        assertEquals(0, closeCount.get());
        renderOwner.runNextFencedTask();

        assertEquals(1, closeCount.get());
        assertNotTracked(registry, stale.generationId());
        assertEquals(1, registry.resourceLifecycleDiagnostics().trackedGenerationCount());
    }

    @Test
    void staleFailureAfterClaimTransfersItsCallbackOnceAndLeavesRetryableCleanup() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        ModelRegistryGeneration newer = missingGeneration(2L);
        registry.publish(newer);
        AtomicInteger claimCount = new AtomicInteger();
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ModelRegistryGeneration stale = missingGeneration(1L);
        PendingGenerationTransaction transaction = cpu(
                stale,
                List.of(() -> {
                    closeAttempts.incrementAndGet();
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("stale close fails once");
                    }
                    successfulClose.incrementAndGet();
                }));

        assertSame(newer, registry.publish(
                transaction,
                claimCount::incrementAndGet,
                ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));
        assertEquals(1, claimCount.get());
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertTrue(stale.isRetired());
        assertEquals(1, renderOwner.queuedCount());
        renderOwner.runNextFencedTask();

        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, stale.generationId()).state());
        assertEquals(1, closeAttempts.get());
        assertEquals(0, successfulClose.get());
        assertTrue(stale.retryFailedRenderResourceClose());
        renderOwner.runNextFencedTask();

        assertEquals(2, closeAttempts.get());
        assertEquals(1, successfulClose.get());
        assertNotTracked(registry, stale.generationId());
    }

    @Test
    void closedRegistryRejectsBeforeClaimAndLeavesTheExactSetCallerOwned() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        ModelRegistryGeneration activeBeforeClose = registry.current();
        registry.close();
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration candidate = missingGeneration(1L);
        PendingGenerationTransaction transaction =
                cpu(candidate, List.of(closeCount::incrementAndGet));

        assertThrows(IllegalStateException.class, () -> registry.publish(transaction));
        assertSame(activeBeforeClose, registry.current());
        assertEquals(PendingGenerationTransaction.State.PREPARED_CALLER_OWNED, transaction.state());
        assertFalse(candidate.isRetired());
        assertEquals(0, renderOwner.queuedCount());
        assertEquals(CompletedGenerationResourceSet.Ownership.CALLER_OWNED_COMPLETE,
                transaction.preclaimPayload().completeSet().ownership());
        assertTrue(transaction.preclaimPayload().completeSet().closeIfStillCallerOwned());
        assertEquals(1, closeCount.get());
        assertNotTracked(registry, candidate.generationId());
        assertEquals(0, registry.resourceLifecycleDiagnostics().trackedGenerationCount());
    }

    @Test
    void shutdownAfterClaimTransfersItsCallbackOnceAndLeavesRetryableCleanup() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        CountDownLatch claimAcquired = new CountDownLatch(1);
        CountDownLatch allowShutdownCheck = new CountDownLatch(1);
        ModelRegistryGeneration candidate = missingGeneration(1L);
        PendingGenerationTransaction transaction = cpu(
                candidate,
                List.of(() -> {
                    closeAttempts.incrementAndGet();
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("shutdown close fails once");
                    }
                    successfulClose.incrementAndGet();
                }));
        ModelRegistryGeneration activeBeforeShutdown = registry.current();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelRegistryGeneration> publisher = executor.submit(() -> registry.publish(
                    transaction,
                    () -> {
                        claimAcquired.countDown();
                        awaitLatch(allowShutdownCheck);
                    },
                    ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));
            awaitLatch(claimAcquired);

            registry.close();
            allowShutdownCheck.countDown();

            assertSame(activeBeforeShutdown, awaitFuture(publisher));
            assertSame(activeBeforeShutdown, registry.current());
            assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
            assertTrue(candidate.isRetired());
            assertEquals(1, renderOwner.queuedCount());
            renderOwner.runNextFencedTask();

            assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, candidate.generationId()).state());
            assertEquals(1, closeAttempts.get());
            assertEquals(0, successfulClose.get());
            assertTrue(candidate.retryFailedRenderResourceClose());
            renderOwner.runNextFencedTask();

            assertEquals(2, closeAttempts.get());
            assertEquals(1, successfulClose.get());
            assertNotTracked(registry, candidate.generationId());
        } finally {
            allowShutdownCheck.countDown();
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    @Test
    void explicitlyAbortedCandidateRetainsOnlyFailedCleanupUntilRetrySucceeds() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        AtomicInteger alreadyClosed = new AtomicInteger();
        AtomicInteger retryableClose = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ModelRegistryGeneration candidate = missingGeneration(1L);
        PendingGenerationTransaction transaction = cpu(candidate, List.of(
                alreadyClosed::incrementAndGet,
                () -> {
                    retryableClose.incrementAndGet();
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("explicit abort close fails once");
                    }
                }));
        transaction.abort(new IllegalStateException("explicit abort"));

        assertSame(registry.current(), registry.publish(transaction));
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertTrue(candidate.isRetired());
        renderOwner.runNextFencedTask();

        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, candidate.generationId()).state());
        assertEquals(1, alreadyClosed.get());
        assertEquals(1, retryableClose.get());
        assertTrue(candidate.retryFailedRenderResourceClose());
        renderOwner.runNextFencedTask();

        assertEquals(1, alreadyClosed.get());
        assertEquals(2, retryableClose.get());
        assertNotTracked(registry, candidate.generationId());
        assertFalse(candidate.retryFailedRenderResourceClose());
    }

    @Test
    void duplicateCurrentCandidateUsesDetachedCleanupWithoutRetiringTheActiveGeneration() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        ModelRegistryGeneration active = missingGeneration(1L);
        registry.publish(active);
        AtomicInteger closeCount = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        PendingGenerationTransaction duplicate =
                cpu(active, List.of(() -> {
                    closeCount.incrementAndGet();
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("duplicate-current cleanup fails once");
                    }
                }));

        assertSame(active, registry.publish(duplicate));
        assertSame(active, registry.current());
        assertFalse(active.isRetired());
        assertEquals(PendingGenerationTransaction.State.ABORTED, duplicate.state());
        assertEquals(2, registry.resourceLifecycleDiagnostics().trackedGenerationCount());
        renderOwner.runNextFencedTask();

        assertEquals(1, closeCount.get());
        assertFalse(active.isRetired());
        assertTrue(registry.resourceLifecycleDiagnostics().generations().stream()
                .anyMatch(diagnostics -> diagnostics.state() == ClientGenerationResourceOwner.State.CLOSE_FAILED));
        assertTrue(active.retryFailedRenderResourceClose());
        renderOwner.runNextFencedTask();

        assertEquals(2, closeCount.get());
        assertEquals(1, registry.resourceLifecycleDiagnostics().trackedGenerationCount());
        assertEquals(ClientGenerationResourceOwner.State.PUBLISHED, diagnosticsFor(registry, active.generationId()).state());
    }

    @Test
    void sameThrowableIdentityTransitionsToCloseFailedAndRetriesOnlyUnclosedCallbacks() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        RuntimeException sharedFailure = new RuntimeException("shared close failure");
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        AtomicInteger firstCallback = new AtomicInteger();
        AtomicInteger secondCallback = new AtomicInteger();
        ModelRegistryGeneration first = missingGeneration(1L);
        registry.publish(cpu(first, List.of(
                () -> {
                    firstCallback.incrementAndGet();
                    if (shouldFail.get()) {
                        throw sharedFailure;
                    }
                },
                () -> {
                    secondCallback.incrementAndGet();
                    if (shouldFail.get()) {
                        throw sharedFailure;
                    }
                })));

        registry.publish(missingGeneration(2L));
        renderOwner.runNextFencedTask();

        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, first.generationId()).state());
        assertEquals(1, firstCallback.get());
        assertEquals(1, secondCallback.get());
        shouldFail.set(false);
        assertTrue(first.retryFailedRenderResourceClose());
        renderOwner.runNextFencedTask();

        assertEquals(2, firstCallback.get());
        assertEquals(2, secondCallback.get());
        assertNotTracked(registry, first.generationId());
    }

    @Test
    void unpublishedQueueFailureRemainsRetryableUntilItsCallbackCloses() {
        FakeRenderOwner renderOwner = new FakeRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        ModelRegistryGeneration newer = missingGeneration(2L);
        registry.publish(newer);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration stale = missingGeneration(1L);
        renderOwner.failNextQueuedFence(new IllegalStateException("queue rejected"));

        registry.publish(cpu(stale, List.of(closeCount::incrementAndGet)));

        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(registry, stale.generationId()).state());
        assertEquals(0, closeCount.get());
        assertTrue(stale.retryFailedRenderResourceClose());
        renderOwner.runNextFencedTask();

        assertEquals(1, closeCount.get());
        assertNotTracked(registry, stale.generationId());
    }

    @Test
    void foreignRegistryPublicationCleansOnlyLoserAndCannotRetireWinner() {
        FakeRenderOwner winnerRenderOwner = new FakeRenderOwner();
        FakeRenderOwner loserRenderOwner = new FakeRenderOwner();
        ClientModelRegistry winner = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), winnerRenderOwner);
        ClientModelRegistry loser = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), loserRenderOwner);
        AtomicInteger winnerCloseCount = new AtomicInteger();
        AtomicInteger loserCleanupCount = new AtomicInteger();
        ModelRegistryGeneration shared = missingGeneration(1L);

        assertSame(shared, winner.publish(cpu(shared, List.of(winnerCloseCount::incrementAndGet))));
        ModelRegistryGeneration loserInitial = loser.current();
        PendingGenerationTransaction foreignAttempt =
                cpu(shared, List.of(loserCleanupCount::incrementAndGet));

        assertSame(loserInitial, loser.publish(foreignAttempt));
        assertSame(loserInitial, loser.current());
        assertEquals(PendingGenerationTransaction.State.ABORTED, foreignAttempt.state());
        assertEquals(1, loserRenderOwner.queuedCount());
        loserRenderOwner.runNextFencedTask();

        assertEquals(1, loserCleanupCount.get());
        assertFalse(shared.isRetired());
        assertSame(shared, winner.current());
        loser.close();
        assertFalse(shared.isRetired(), "closing the foreign loser must not retire the winner generation");
        assertSame(shared, winner.current());

        ModelRegistryGeneration replacement = missingGeneration(2L);
        assertSame(replacement, winner.publish(replacement));
        assertTrue(shared.isRetired());
        assertEquals(1, winnerRenderOwner.queuedCount());
        winnerRenderOwner.runNextFencedTask();

        assertEquals(1, winnerCloseCount.get());
        assertNotTracked(winner, shared.generationId());
        assertEquals(ClientGenerationResourceOwner.State.PUBLISHED, diagnosticsFor(winner, replacement.generationId()).state());
    }

    @Test
    void concurrentForeignRegistryPublicationHasOneOwnerBindingAndOneWinner() {
        FakeRenderOwner firstRenderOwner = new FakeRenderOwner();
        FakeRenderOwner secondRenderOwner = new FakeRenderOwner();
        ClientModelRegistry first = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), firstRenderOwner);
        ClientModelRegistry second = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), secondRenderOwner);
        ModelRegistryGeneration firstInitial = first.current();
        ModelRegistryGeneration secondInitial = second.current();
        ModelRegistryGeneration shared = missingGeneration(1L);
        AtomicInteger firstCloseCount = new AtomicInteger();
        AtomicInteger secondCloseCount = new AtomicInteger();
        PendingGenerationTransaction firstTransaction =
                cpu(shared, List.of(firstCloseCount::incrementAndGet));
        PendingGenerationTransaction secondTransaction =
                cpu(shared, List.of(secondCloseCount::incrementAndGet));
        CountDownLatch publishersReady = new CountDownLatch(2);
        CountDownLatch startPublication = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ModelRegistryGeneration> firstResult = executor.submit(() -> {
                publishersReady.countDown();
                awaitLatch(startPublication);
                return first.publish(firstTransaction);
            });
            Future<ModelRegistryGeneration> secondResult = executor.submit(() -> {
                publishersReady.countDown();
                awaitLatch(startPublication);
                return second.publish(secondTransaction);
            });
            awaitLatch(publishersReady);
            startPublication.countDown();

            ModelRegistryGeneration firstPublished = awaitFuture(firstResult);
            ModelRegistryGeneration secondPublished = awaitFuture(secondResult);
            boolean firstWon = firstPublished == shared;
            assertTrue(firstWon != (secondPublished == shared), "exactly one registry may publish the shared generation");
            assertSame(firstWon ? shared : firstInitial, first.current());
            assertSame(firstWon ? secondInitial : shared, second.current());

            ClientModelRegistry winner = firstWon ? first : second;
            ClientModelRegistry loser = firstWon ? second : first;
            FakeRenderOwner winnerRenderOwner = firstWon ? firstRenderOwner : secondRenderOwner;
            FakeRenderOwner loserRenderOwner = firstWon ? secondRenderOwner : firstRenderOwner;
            AtomicInteger winnerCloseCount = firstWon ? firstCloseCount : secondCloseCount;
            AtomicInteger loserCloseCount = firstWon ? secondCloseCount : firstCloseCount;

            assertEquals(1, loserRenderOwner.queuedCount());
            loserRenderOwner.runNextFencedTask();
            assertEquals(1, loserCloseCount.get());
            assertEquals(0, winnerCloseCount.get());
            assertNotTracked(loser, shared.generationId());
            assertFalse(shared.isRetired());

            ModelRegistryGeneration replacement = missingGeneration(2L);
            assertSame(replacement, winner.publish(replacement));
            assertTrue(shared.isRetired());
            assertEquals(1, winnerRenderOwner.queuedCount());
            winnerRenderOwner.runNextFencedTask();
            assertEquals(1, winnerCloseCount.get());
            assertNotTracked(winner, shared.generationId());
        } finally {
            startPublication.countDown();
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    @Test
    void foreignOwnerCleanupFailureRetriesThroughLoserCloseWithoutTouchingWinner() {
        FakeRenderOwner winnerRenderOwner = new FakeRenderOwner();
        FakeRenderOwner loserRenderOwner = new FakeRenderOwner();
        ClientModelRegistry winner = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), winnerRenderOwner);
        ClientModelRegistry loser = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), loserRenderOwner);
        ModelRegistryGeneration shared = missingGeneration(1L);
        assertSame(shared, winner.publish(shared));
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);

        assertSame(loser.current(), loser.publish(cpu(shared, List.of(() -> {
            closeAttempts.incrementAndGet();
            if (failOnce.getAndSet(false)) {
                throw new IllegalStateException("foreign cleanup callback fails once");
            }
            successfulClose.incrementAndGet();
        }))));
        assertEquals(1, loserRenderOwner.queuedCount());
        loserRenderOwner.runNextFencedTask();

        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, diagnosticsFor(loser, shared.generationId()).state());
        assertEquals(1, closeAttempts.get());
        assertEquals(0, successfulClose.get());
        assertFalse(shared.isRetired());
        assertSame(shared, winner.current());

        loser.close();
        assertEquals(1, loserRenderOwner.queuedCount(), "loser close must retry its own detached cleanup");
        loserRenderOwner.runNextFencedTask();

        assertEquals(2, closeAttempts.get());
        assertEquals(1, successfulClose.get());
        assertNotTracked(loser, shared.generationId());
        assertFalse(shared.isRetired());
        assertSame(shared, winner.current());
    }

    @Test
    void foreignBoundInitialGenerationFailsBeforeSecondRegistryRecordsArePublished() {
        FakeRenderOwner firstRenderOwner = new FakeRenderOwner();
        ModelRegistryGeneration shared = missingGeneration(1L);
        ClientModelRegistry first = new ClientModelRegistry(shared, firstRenderOwner);

        assertThrows(IllegalStateException.class, () -> new ClientModelRegistry(shared, new FakeRenderOwner()));
        assertSame(shared, first.current());
        assertFalse(shared.isRetired());
        GenerationRenderResourceLease lease = shared.acquireRenderResourceLease(handle(shared));
        lease.close();

        first.close();
        assertTrue(shared.isRetired());
        assertEquals(0, first.resourceLifecycleDiagnostics().trackedGenerationCount());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5L, TimeUnit.SECONDS), "deterministic test barrier timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for deterministic test barrier", exception);
        }
    }

    private static <T> T awaitFuture(Future<T> future) {
        try {
            return future.get(5L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for publication", exception);
        } catch (ExecutionException exception) {
            throw new AssertionError("publication failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new AssertionError("publication did not finish at the deterministic barrier", exception);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS), "test executor did not terminate");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while terminating test executor", exception);
        }
    }

    /** Migrates the old per-generation close-callback fixture into one exact, nonempty complete aggregate. */
    private static PendingGenerationTransaction cpu(ModelRegistryGeneration generation) {
        return PendingGenerationTransaction.cpuOnly(generation);
    }

    private static PendingGenerationTransaction cpu(
            ModelRegistryGeneration generation, List<Runnable> closeCallbacks) {
        if (closeCallbacks.isEmpty()) {
            return cpu(generation);
        }
        List<CompletedGenerationResourceSet.ResourceLeaf> leaves = new ArrayList<>(closeCallbacks.size());
        for (int index = 0; index < closeCallbacks.size(); index++) {
            Runnable callback = closeCallbacks.get(index);
            int leafIndex = index;
            leaves.add(CompletedGenerationResourceSet.leaf(
                    new X7GpuGenerationKey(
                            generation.generationId(),
                            "owner-test-" + leafIndex,
                            "callback-" + leafIndex,
                            "solid/owner-test",
                            leafIndex,
                            X7GpuVertexFormat.POSITION_NORMAL_UV_F32,
                            X7PrimitiveMode.TRIANGLES,
                            X7GpuIndexType.UINT32_LE),
                    1,
                    0L,
                    callback::run));
        }
        return PendingGenerationTransaction.complete(generation, CompletedGenerationResourceSet.complete(generation, leaves));
    }

    private static ClientGenerationResourceOwner.GenerationDiagnostics diagnosticsFor(
            ClientModelRegistry registry, long generationId) {
        return registry.resourceLifecycleDiagnostics().generations().stream()
                .filter(diagnostics -> diagnostics.generationId() == generationId)
                .findFirst()
                .orElseThrow();
    }

    private static void assertNotTracked(ClientModelRegistry registry, long generationId) {
        assertFalse(registry.resourceLifecycleDiagnostics().generations().stream()
                .anyMatch(diagnostics -> diagnostics.generationId() == generationId));
    }

    private static ModelRegistryGeneration missingGeneration(long generationId) {
        BlendDiagnostic diagnostic = BlendDiagnostic.error(
                BlendDiagnosticCodes.DESC_002,
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                "/",
                "shared lifecycle fixture");
        return new ModelRegistryGeneration(
                generationId,
                Map.of(KEY, MissingModelHandle.failed(KEY, generationId, diagnostic)),
                Map.of(KEY, diagnostic),
                List.of());
    }

    private static ModelHandle handle(ModelRegistryGeneration generation) {
        return generation.find(KEY).orElseThrow();
    }

    private static final class FakeRenderOwner implements ClientGenerationResourceOwner.RenderOwnerCallbacks {
        private final ArrayDeque<Runnable> fencedTasks = new ArrayDeque<>();
        private final boolean invokeFencedTaskSynchronously;
        private boolean renderThread;
        private RuntimeException nextQueueFailure;

        private FakeRenderOwner() {
            this(false);
        }

        private FakeRenderOwner(boolean invokeFencedTaskSynchronously) {
            this.invokeFencedTaskSynchronously = invokeFencedTaskSynchronously;
        }

        @Override
        public void handoffToRenderThread(Runnable handoff) {
            renderThread = true;
            try {
                handoff.run();
            } finally {
                renderThread = false;
            }
        }

        @Override
        public void assertOnRenderThread() {
            if (!renderThread) {
                throw new IllegalStateException("not the fake render owner");
            }
        }

        @Override
        public void queueFencedTask(Runnable callback) {
            RuntimeException queueFailure = nextQueueFailure;
            nextQueueFailure = null;
            if (queueFailure != null) {
                throw queueFailure;
            }
            if (invokeFencedTaskSynchronously) {
                callback.run();
            } else {
                fencedTasks.addLast(callback);
            }
        }

        int queuedCount() {
            return fencedTasks.size();
        }

        void failNextQueuedFence(RuntimeException failure) {
            nextQueueFailure = failure;
        }

        void runNextFencedTask() {
            Runnable callback = fencedTasks.removeFirst();
            renderThread = true;
            try {
                callback.run();
            } finally {
                renderThread = false;
            }
        }
    }
}
