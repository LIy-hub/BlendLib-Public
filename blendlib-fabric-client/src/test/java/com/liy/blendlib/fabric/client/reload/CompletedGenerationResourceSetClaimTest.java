package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CompletedGenerationResourceSetClaimTest {
    @Test
    void callerClaimThenD1AdoptionIsOneWayAndReplaySafe() {
        AtomicReference<Integer> closeCalls = new AtomicReference<>(0);
        CompletedGenerationResourceSet aggregate = aggregate(() -> closeCalls.set(closeCalls.get() + 1));

        assertEquals(CompletedGenerationResourceSet.ClaimMove.CLAIMED, aggregate.tryClaimCallerOwnedCompleteForD1());
        assertEquals(CompletedGenerationResourceSet.Ownership.CLAIM_OWNED_COMPLETE, aggregate.ownership());
        assertFalse(aggregate.closeIfStillCallerOwned());
        assertEquals(CompletedGenerationResourceSet.ClaimMove.NOT_CALLER_OWNED_COMPLETE,
                aggregate.tryClaimCallerOwnedCompleteForD1());
        assertThrows(IllegalStateException.class, aggregate::closeFromD1VerifiedCompletion);

        assertEquals(CompletedGenerationResourceSet.D1Adoption.ADOPTED, aggregate.tryAdoptClaimOwnedCompleteByD1());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_OWNED, aggregate.ownership());
        assertEquals(CompletedGenerationResourceSet.D1Adoption.NOT_CLAIM_OWNED_COMPLETE,
                aggregate.tryAdoptClaimOwnedCompleteByD1());
        assertFalse(aggregate.closeIfStillCallerOwned());

        aggregate.closeFromD1VerifiedCompletion();
        assertEquals(1, closeCalls.get());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, aggregate.ownership());
    }

    @Test
    void callerCloseAndClaimRaceHasOneDeterministicWinner() throws Exception {
        AtomicBoolean physicalClosed = new AtomicBoolean();
        CompletedGenerationResourceSet aggregate = aggregate(() -> physicalClosed.set(true));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CompletedGenerationResourceSet.ClaimMove> claim = new AtomicReference<>();
        AtomicReference<Boolean> callerClose = new AtomicReference<>();
        Thread claimant = new Thread(() -> {
            ready.countDown();
            await(start);
            claim.set(aggregate.tryClaimCallerOwnedCompleteForD1());
        }, "x7-claimant");
        Thread caller = new Thread(() -> {
            ready.countDown();
            await(start);
            callerClose.set(aggregate.closeIfStillCallerOwned());
        }, "x7-caller-close");
        claimant.start();
        caller.start();
        ready.await();
        start.countDown();
        claimant.join();
        caller.join();

        if (claim.get() == CompletedGenerationResourceSet.ClaimMove.CLAIMED) {
            assertFalse(callerClose.get());
            assertFalse(physicalClosed.get());
            assertEquals(CompletedGenerationResourceSet.Ownership.CLAIM_OWNED_COMPLETE, aggregate.ownership());
            aggregate.closeFromClaimOwnerAfterD1InsertionFailure();
        } else {
            assertEquals(CompletedGenerationResourceSet.ClaimMove.NOT_CALLER_OWNED_COMPLETE, claim.get());
            assertTrue(callerClose.get());
            assertEquals(CompletedGenerationResourceSet.Ownership.CALLER_CLOSED, aggregate.ownership());
        }
        assertTrue(physicalClosed.get());
    }

    @Test
    void claimOwnerClosesAfterPreallocationOrInsertionFailureWithoutRestoringCallerOwnership() {
        AtomicReference<Integer> closeCalls = new AtomicReference<>(0);
        CompletedGenerationResourceSet aggregate = aggregate(() -> closeCalls.set(closeCalls.get() + 1));
        RuntimeException preallocationFailure = new IllegalStateException("synthetic D1 preallocation failure");

        assertEquals(CompletedGenerationResourceSet.ClaimMove.CLAIMED, aggregate.tryClaimCallerOwnedCompleteForD1());
        RuntimeException observed = assertThrows(RuntimeException.class, () -> {
            throw preallocationFailure;
        });
        assertSame(preallocationFailure, observed);
        assertEquals(CompletedGenerationResourceSet.Ownership.CLAIM_OWNED_COMPLETE, aggregate.ownership());
        assertFalse(aggregate.closeIfStillCallerOwned());

        aggregate.closeFromClaimOwnerAfterD1InsertionFailure();
        assertEquals(1, closeCalls.get());
        assertEquals(CompletedGenerationResourceSet.Ownership.CLAIM_CLOSED, aggregate.ownership());
        assertEquals(CompletedGenerationResourceSet.D1Adoption.NOT_CLAIM_OWNED_COMPLETE,
                aggregate.tryAdoptClaimOwnedCompleteByD1());
    }

    private static CompletedGenerationResourceSet aggregate(CompletedGenerationResourceSet.PhysicalLeafClose close) {
        ModelRegistryGeneration generation = ModelRegistryGeneration.empty(17L);
        return CompletedGenerationResourceSet.complete(
                generation,
                List.of(CompletedGenerationResourceSet.leaf(
                        new X7GpuGenerationKey(
                                17L,
                                "model",
                                "geometry",
                                "solid/model",
                                0,
                                X7GpuVertexFormat.POSITION_NORMAL_UV_F32,
                                X7PrimitiveMode.TRIANGLES,
                                X7GpuIndexType.UINT32_LE),
                        1,
                        8L,
                        close)));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
