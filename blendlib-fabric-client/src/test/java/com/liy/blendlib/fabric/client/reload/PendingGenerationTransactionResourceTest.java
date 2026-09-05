package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Contract coverage for the one pre-CAS transaction payload and its exclusive cleanup claim. */
class PendingGenerationTransactionResourceTest {
    @Test
    void completeSetIsD1OwnedAndReadyBeforePublicationCas() {
        DirectRenderOwner renderOwner = new DirectRenderOwner();
        ModelRegistryGeneration initial = ModelRegistryGeneration.empty(0L);
        ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
        AtomicInteger closed = new AtomicInteger();
        CompletedGenerationResourceSet set = complete(candidate, 2, 96L, closed::incrementAndGet);
        PendingGenerationTransaction transaction = PendingGenerationTransaction.complete(candidate, set);
        ClientModelRegistry registry = new ClientModelRegistry(initial, renderOwner);
        AtomicReference<ClientGenerationResourceOwner.PublicationAdoptionProbe> observed = new AtomicReference<>();

        assertSame(candidate, registry.publish(
                transaction,
                ClientGenerationResourceOwner.NOOP_POLICY_FREEZE_BARRIER,
                probe -> {
                    observed.set(probe);
                    assertTrue(probe.recordId() > 0L);
                    assertEquals(candidate.generationId(), probe.candidateGenerationId());
                    assertEquals(System.identityHashCode(set), probe.exactSetIdentity());
                    assertEquals(PendingGenerationTransaction.PayloadMode.COMPLETE_SET, probe.payloadMode());
                    assertEquals(2, probe.physicalResourceCount());
                    assertEquals(96L, probe.physicalByteCount());
                    assertEquals(CompletedGenerationResourceSet.Ownership.D1_OWNED, probe.resourceOwnership());
                    assertEquals(initial.generationId(), probe.activeGenerationId());
                    assertTrue(probe.resourceReady());
                    assertTrue(probe.publicationReady());
                    assertTrue(probe.publicationPermitHeld());
                }));

        assertNotNull(observed.get());
        assertEquals(PendingGenerationTransaction.State.PUBLISHED, transaction.state());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_OWNED, set.ownership());
        assertEquals(0, closed.get());
        registry.close();
        assertEquals(1, closed.get());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, set.ownership());
    }

    @Test
    void cpuOnlyUsesTheSamePreCasRecordShapeWithNoPhysicalSet() {
        DirectRenderOwner renderOwner = new DirectRenderOwner();
        ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
        PendingGenerationTransaction transaction = PendingGenerationTransaction.cpuOnly(candidate);
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);

        assertSame(candidate, registry.publish(
                transaction,
                ClientGenerationResourceOwner.NOOP_POLICY_FREEZE_BARRIER,
                probe -> {
                    assertEquals(PendingGenerationTransaction.PayloadMode.CPU_ONLY, probe.payloadMode());
                    assertEquals(0, probe.exactSetIdentity());
                    assertEquals(0, probe.physicalResourceCount());
                    assertEquals(0L, probe.physicalByteCount());
                    assertNull(probe.resourceOwnership());
                    assertTrue(probe.resourceReady());
                    assertTrue(probe.publicationReady());
                    assertTrue(probe.publicationPermitHeld());
                }));
        assertEquals(PendingGenerationTransaction.State.PUBLISHED, transaction.state());
    }

    @Test
    void cutoffBeforeClaimLeavesTheExactSetCallerOwned() {
        DirectRenderOwner renderOwner = new DirectRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        registry.close();
        ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
        AtomicInteger closeCount = new AtomicInteger();
        CompletedGenerationResourceSet set = complete(candidate, 1, 32L, closeCount::incrementAndGet);
        PendingGenerationTransaction transaction = PendingGenerationTransaction.complete(candidate, set);

        assertThrows(IllegalStateException.class, () -> registry.publish(transaction));
        assertEquals(PendingGenerationTransaction.State.PREPARED_CALLER_OWNED, transaction.state());
        assertEquals(CompletedGenerationResourceSet.Ownership.CALLER_OWNED_COMPLETE, set.ownership());
        assertTrue(set.closeIfStillCallerOwned());
        assertEquals(1, closeCount.get());
    }

    @Test
    void directAbortUsesExactlyOneCleanupClaim() {
        DirectRenderOwner renderOwner = new DirectRenderOwner();
        ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
        AtomicInteger closeCount = new AtomicInteger();
        CompletedGenerationResourceSet set = complete(candidate, 1, 32L, closeCount::incrementAndGet);
        PendingGenerationTransaction transaction = PendingGenerationTransaction.complete(candidate, set);
        RuntimeException abortCause = new IllegalStateException("caller stopped reload");
        transaction.abort(abortCause);
        assertEquals(PendingGenerationTransaction.State.ABORTED_CALLER_OWNED, transaction.state());
        assertEquals(CompletedGenerationResourceSet.Ownership.CALLER_OWNED_COMPLETE, set.ownership());

        assertSame(registry.current(), registry.publish(transaction));
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertSame(abortCause, transaction.abortCause());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, set.ownership());
        assertEquals(1, closeCount.get());
        assertThrows(IllegalStateException.class, () -> registry.publish(transaction));
        assertEquals(1, closeCount.get());
    }

    @Test
    void callerCloseAndClaimHaveOneWinner() throws Exception {
        ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
        AtomicInteger closeCount = new AtomicInteger();
        CompletedGenerationResourceSet set = complete(candidate, 1, 32L, closeCount::incrementAndGet);
        PendingGenerationTransaction transaction = PendingGenerationTransaction.complete(candidate, set);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<PendingGenerationTransaction.Claim> claim = new AtomicReference<>();
        AtomicReference<Throwable> claimFailure = new AtomicReference<>();
        AtomicBoolean callerClosed = new AtomicBoolean();

        Thread claimant = Thread.ofPlatform().start(() -> {
            ready.countDown();
            await(start);
            try {
                claim.set(transaction.claimForResourceOwner());
            } catch (Throwable failure) {
                claimFailure.set(failure);
            }
        });
        Thread caller = Thread.ofPlatform().start(() -> {
            ready.countDown();
            await(start);
            callerClosed.set(set.closeIfStillCallerOwned());
        });
        assertTrue(ready.await(5L, TimeUnit.SECONDS));
        start.countDown();
        claimant.join(5_000L);
        caller.join(5_000L);
        assertFalse(claimant.isAlive());
        assertFalse(caller.isAlive());

        if (claim.get() != null) {
            assertFalse(callerClosed.get());
            assertEquals(PendingGenerationTransaction.State.CLAIMED, transaction.state());
            claim.get().abort(new IllegalStateException("test cleanup"));
            claim.get().closeClaimOwnedCompleteAfterD1InsertionFailure();
            assertEquals(CompletedGenerationResourceSet.Ownership.CLAIM_CLOSED, set.ownership());
        } else {
            assertNotNull(claimFailure.get());
            assertTrue(callerClosed.get());
            assertEquals(PendingGenerationTransaction.State.PREPARED_CALLER_OWNED, transaction.state());
            assertEquals(CompletedGenerationResourceSet.Ownership.CALLER_CLOSED, set.ownership());
        }
        assertEquals(1, closeCount.get());
    }

    @Test
    void duplicateClaimAndReplayAreRejected() {
        ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
        CompletedGenerationResourceSet set = complete(candidate, 1, 32L, () -> { });
        PendingGenerationTransaction transaction = PendingGenerationTransaction.complete(candidate, set);
        PendingGenerationTransaction.Claim claim = transaction.claimForResourceOwner();

        assertEquals(PendingGenerationTransaction.State.CLAIMED, transaction.state());
        assertThrows(IllegalStateException.class, transaction::claimForResourceOwner);
        assertThrows(IllegalStateException.class, () -> transaction.abort(new IllegalStateException("late abort")));
        claim.abort(new IllegalStateException("cleanup"));
        claim.closeClaimOwnedCompleteAfterD1InsertionFailure();
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertThrows(IllegalStateException.class, transaction::claimForResourceOwner);
    }

    @Test
    void foreignCandidateNeverMutatesWinnerAndClosesItsDetachedExactSet() {
        DirectRenderOwner foreignRenderOwner = new DirectRenderOwner();
        ClientModelRegistry foreignRegistry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), foreignRenderOwner);
        ModelRegistryGeneration foreignWinner = ModelRegistryGeneration.empty(2L);
        assertSame(foreignWinner, foreignRegistry.publish(foreignWinner));

        DirectRenderOwner targetRenderOwner = new DirectRenderOwner();
        ModelRegistryGeneration targetInitial = ModelRegistryGeneration.empty(0L);
        ClientModelRegistry targetRegistry = new ClientModelRegistry(targetInitial, targetRenderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        CompletedGenerationResourceSet set = complete(foreignWinner, 1, 32L, closeCount::incrementAndGet);
        PendingGenerationTransaction transaction = PendingGenerationTransaction.complete(foreignWinner, set);

        assertSame(targetInitial, targetRegistry.publish(transaction));
        assertSame(foreignWinner, foreignRegistry.current());
        assertFalse(foreignWinner.isRetired());
        assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, set.ownership());
        assertEquals(1, closeCount.get());
    }

    @Test
    void staleCasLossAndCasThrowCloseTheExactSetOnce() {
        ModelRegistryGeneration initial = ModelRegistryGeneration.empty(0L);
        ModelRegistryGeneration lossCandidate = ModelRegistryGeneration.empty(1L);
        AtomicInteger lossCloseCount = new AtomicInteger();
        CompletedGenerationResourceSet lostSet = complete(lossCandidate, 1, 32L, lossCloseCount::incrementAndGet);
        ClientModelRegistry lossRegistry = new ClientModelRegistry(
                initial,
                new DirectRenderOwner(),
                (active, replacement) -> new ClientModelRegistry.ResourceOwnerPublication(active.get(), null, false));

        assertSame(initial, lossRegistry.publish(PendingGenerationTransaction.complete(lossCandidate, lostSet)));
        assertTrue(lossCandidate.isRetired());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, lostSet.ownership());
        assertEquals(1, lossCloseCount.get());

        RuntimeException expected = new IllegalStateException("synthetic CAS fault");
        ModelRegistryGeneration throwCandidate = ModelRegistryGeneration.empty(1L);
        AtomicInteger throwCloseCount = new AtomicInteger();
        CompletedGenerationResourceSet thrownSet = complete(throwCandidate, 1, 32L, throwCloseCount::incrementAndGet);
        ClientModelRegistry throwRegistry = new ClientModelRegistry(
                ModelRegistryGeneration.empty(0L),
                new DirectRenderOwner(),
                (active, replacement) -> {
                    throw expected;
                });

        Throwable observed = catchThrowable(() -> throwRegistry.publish(
                PendingGenerationTransaction.complete(throwCandidate, thrownSet)));
        assertSame(expected, observed);
        assertTrue(throwCandidate.isRetired());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, thrownSet.ownership());
        assertEquals(1, throwCloseCount.get());
    }

    @Test
    void policyFreezePreservesRuntimeErrorAndOomeAsThePrimaryFailure() {
        for (Throwable expected : List.of(
                new IllegalStateException("freeze runtime"),
                new AssertionError("freeze error"),
                new OutOfMemoryError("freeze oome"))) {
            ModelRegistryGeneration candidate = ModelRegistryGeneration.empty(1L);
            AtomicInteger closeCount = new AtomicInteger();
            CompletedGenerationResourceSet set = complete(candidate, 1, 32L, closeCount::incrementAndGet);
            ClientModelRegistry registry = new ClientModelRegistry(ModelRegistryGeneration.empty(0L), new DirectRenderOwner());

            Throwable observed = catchThrowable(() -> registry.publish(
                    PendingGenerationTransaction.complete(candidate, set),
                    () -> throwUnchecked(expected),
                    ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));

            assertSame(expected, observed);
            assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, set.ownership());
            assertEquals(1, closeCount.get());
        }
    }

    private static CompletedGenerationResourceSet complete(
            ModelRegistryGeneration generation,
            int physicalResourceCount,
            long physicalByteCount,
            CompletedGenerationResourceSet.PhysicalLeafClose close) {
        return X7GenerationResourceTestSupport.complete(generation, physicalResourceCount, physicalByteCount, close);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for claim race");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Throwable catchThrowable(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("expected failure");
    }

    private static void throwUnchecked(Throwable failure) {
        PendingGenerationTransactionResourceTest.<RuntimeException>throwUnchecked0(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked0(Throwable failure) throws T {
        throw (T) failure;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class DirectRenderOwner implements ClientGenerationResourceOwner.RenderOwnerCallbacks {
        private boolean renderThread;

        @Override
        public void handoffToRenderThread(Runnable handoff) {
            runOnRenderThread(handoff);
        }

        @Override
        public void assertOnRenderThread() {
            if (!renderThread) {
                throw new IllegalStateException("not the direct test render thread");
            }
        }

        @Override
        public void queueFencedTask(Runnable callback) {
            assertOnRenderThread();
            callback.run();
        }

        private void runOnRenderThread(Runnable action) {
            boolean previous = renderThread;
            renderThread = true;
            try {
                action.run();
            } finally {
                renderThread = previous;
            }
        }
    }
}
