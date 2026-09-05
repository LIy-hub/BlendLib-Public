package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientGenerationFinalCloseBatchTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("shutdown_test:final_batch");

    @Test
    void sealedBatchIsImmutableAndNeverEntersTheNormalFenceQueue() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(false);
        ClientModelRegistry registry = registry(renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration generation = generation(1L);
        registry.publish(cpu(generation, List.of(closeCount::incrementAndGet)));

        ClientGenerationResourceOwner.ShutdownBatchArmResult armed =
                registry.generationResourceOwner().armShutdownFinalBatch();

        assertTrue(armed.armed());
        assertNotNull(armed.batch());
        assertTrue(armed.batch().attemptId() > 0L);
        assertEquals(1, armed.batch().resourceRecordCount());
        assertEquals(List.of(1L), armed.batch().generationIds());
        assertThrows(UnsupportedOperationException.class, () -> armed.batch().generationIds().add(2L));
        assertEquals(0, renderOwner.fenceCount());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_FINAL_FENCE_ARMED,
                diagnostics(registry, 1L).state());

        ClientGenerationResourceOwner.ShutdownBatchCompletion beforePresentProof = renderOwner.callOnRenderThread(
                () -> registry.generationResourceOwner().completeShutdownFinalBatch(armed.batch()));
        assertFalse(beforePresentProof.accepted());
        assertEquals(0, closeCount.get());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_FINAL_FENCE_ARMED,
                diagnostics(registry, 1L).state());

        assertTrue(registry.generationResourceOwner().markShutdownFinalFenceWait(armed.batch()));
        ClientGenerationResourceOwner.ShutdownBatchCompletion completion = renderOwner.callOnRenderThread(
                () -> registry.generationResourceOwner().completeShutdownFinalBatch(armed.batch()));

        assertTrue(completion.accepted());
        assertEquals(1, completion.closedRecordCount());
        assertEquals(0, completion.failedRecordCount());
        assertEquals(1, closeCount.get());
        assertEquals(0, renderOwner.fenceCount());
        assertNull(findDiagnostics(registry, 1L));
    }

    @Test
    void alreadyQueuedNormalFenceCannotAlsoEnterTheFinalBatchAndItsLateCallbackNoops() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(false);
        ClientModelRegistry registry = registry(renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = generation(1L);
        registry.publish(cpu(first, List.of(closeCount::incrementAndGet)));
        registry.publish(generation(2L));
        assertEquals(1, renderOwner.fenceCount());

        ClientGenerationResourceOwner.ShutdownBatchArmResult result =
                registry.generationResourceOwner().armShutdownFinalBatch();

        assertFalse(result.armed());
        assertEquals(ClientGenerationResourceOwner.ShutdownFailure.EXTERNAL_QUEUE_PENDING, result.failure());
        assertEquals(1, result.externalQueueCount());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_EXTERNAL_QUEUE,
                diagnostics(registry, 1L).state());
        assertEquals(0L, diagnostics(registry, 1L).activeCloseAttemptId());
        assertTrue(diagnostics(registry, 1L).retryForbidden());

        renderOwner.runNextFence();
        assertEquals(0, closeCount.get());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_EXTERNAL_QUEUE,
                diagnostics(registry, 1L).state());
    }

    @Test
    void finalReleaseBeforeCutoffMayFinishItsNormalFenceBeforeTheFinalBatchSeals() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(false);
        ClientModelRegistry registry = registry(renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = generation(1L);
        registry.publish(cpu(first, List.of(closeCount::incrementAndGet)));
        GenerationRenderResourceLease lease = first.acquireRenderResourceLease(handle(first));
        registry.publish(generation(2L));

        lease.close();
        assertEquals(1, renderOwner.fenceCount());
        renderOwner.runNextFence();
        assertEquals(1, closeCount.get());

        ClientGenerationResourceOwner.ShutdownBatchArmResult result =
                registry.generationResourceOwner().armShutdownFinalBatch();
        assertTrue(result.armed());
        assertTrue(result.batch().isEmpty());
        assertNull(findDiagnostics(registry, 1L));
    }

    @Test
    void finalReleaseAtCutoffWithAnUndeliveredHandoffFailsWithoutQueuingLateFence() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(true);
        ClientModelRegistry registry = registry(renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration first = generation(1L);
        registry.publish(cpu(first, List.of(closeCount::incrementAndGet)));
        GenerationRenderResourceLease lease = first.acquireRenderResourceLease(handle(first));
        registry.publish(generation(2L));

        lease.close();
        assertEquals(1, renderOwner.handoffCount());
        ClientGenerationResourceOwner.ShutdownBatchArmResult result =
                registry.generationResourceOwner().armShutdownFinalBatch();

        assertEquals(ClientGenerationResourceOwner.ShutdownFailure.HANDOFF_NOT_RUN, result.failure());
        assertEquals(1, result.handoffNotRunCount());
        renderOwner.runNextHandoff();
        assertEquals(0, renderOwner.fenceCount());
        assertEquals(0, closeCount.get());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_HANDOFF_NOT_RUN,
                diagnostics(registry, 1L).state());
        assertEquals(0L, diagnostics(registry, 1L).activeCloseAttemptId());
        assertTrue(diagnostics(registry, 1L).retryForbidden());
    }

    @Test
    void inFlightPublicationAtCutoffFailsTypedAndRetainsItsCallbackWithoutAnyNormalFence()
            throws InterruptedException {
        FakeRenderOwner renderOwner = new FakeRenderOwner(false);
        ClientModelRegistry registry = registry(renderOwner);
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch allowOwnerTransfer = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration candidate = generation(1L);
        PendingGenerationTransaction transaction = cpu(candidate, List.of(closeCount::incrementAndGet));
        AtomicReference<ModelRegistryGeneration> publication = new AtomicReference<>();
        AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
        Thread publisher = Thread.ofPlatform().start(() -> {
            try {
                publication.set(registry.publish(
                        transaction,
                        () -> {
                            claimReached.countDown();
                            await(allowOwnerTransfer);
                        },
                        ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER));
            } catch (Throwable failure) {
                publicationFailure.set(failure);
            }
        });
        try {
            assertTrue(claimReached.await(5, TimeUnit.SECONDS));
            assertEquals(1, registry.resourceLifecycleDiagnostics().outstandingPublicationTransactionCount());

            ClientGenerationResourceOwner.ShutdownBatchArmResult result =
                    registry.generationResourceOwner().armShutdownFinalBatch();

            assertFalse(result.armed());
            assertNull(result.batch());
            assertEquals(ClientGenerationResourceOwner.ShutdownFailure.OUTSTANDING_TRANSACTIONS, result.failure());
            assertEquals(1, result.outstandingTransactionCount());
            assertEquals(0, renderOwner.handoffCount());
            assertEquals(0, renderOwner.fenceCount());
            assertEquals(0, closeCount.get());

            allowOwnerTransfer.countDown();
            publisher.join(5_000L);
            assertFalse(publisher.isAlive());
            assertNull(publicationFailure.get());
            assertSame(registry.current(), publication.get());
            assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
            assertTrue(candidate.isRetired());
            assertEquals(0, registry.resourceLifecycleDiagnostics().outstandingPublicationTransactionCount());
            assertEquals(1, registry.resourceLifecycleDiagnostics().publicationTransactionCountAtCutoff());
            assertEquals(0, renderOwner.handoffCount());
            assertEquals(0, renderOwner.fenceCount());
            assertEquals(0, closeCount.get());
            ClientGenerationResourceOwner.GenerationDiagnostics retained = diagnostics(registry, 1L);
            assertEquals(ClientGenerationResourceOwner.State.SHUTDOWN_OUTSTANDING_TRANSACTIONS, retained.state());
            assertEquals(ClientGenerationResourceOwner.ShutdownFailure.OUTSTANDING_TRANSACTIONS, retained.shutdownFailure());
            assertTrue(retained.resourceReady(), "the claimed exact set remains D1 cleanup-ready after cutoff");
            assertFalse(retained.publicationReady(), "a terminal cleanup record is never CAS authority");
            assertEquals(0L, retained.activeCloseAttemptId());
            assertTrue(retained.retryForbidden());

            PendingGenerationTransaction afterCutoff = cpu(
                    generation(2L), List.of(closeCount::incrementAndGet));
            assertThrows(IllegalStateException.class, () -> registry.publish(afterCutoff));
            assertEquals(PendingGenerationTransaction.State.PREPARED_CALLER_OWNED, afterCutoff.state());
            assertEquals(0, closeCount.get());
        } finally {
            allowOwnerTransfer.countDown();
            publisher.join(5_000L);
        }
    }

    @Test
    void finalReleaseAfterCutoffCannotQueueOrCloseAnOutstandingLeaseRecord() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(false);
        ClientModelRegistry registry = registry(renderOwner);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration generation = generation(1L);
        registry.publish(cpu(generation, List.of(closeCount::incrementAndGet)));
        GenerationRenderResourceLease lease = generation.acquireRenderResourceLease(handle(generation));

        ClientGenerationResourceOwner.ShutdownBatchArmResult result =
                registry.generationResourceOwner().armShutdownFinalBatch();

        assertEquals(ClientGenerationResourceOwner.ShutdownFailure.OUTSTANDING_LEASES, result.failure());
        assertEquals(1, result.outstandingLeaseCount());
        lease.close();
        assertEquals(0, renderOwner.handoffCount());
        assertEquals(0, renderOwner.fenceCount());
        assertEquals(0, closeCount.get());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_OUTSTANDING_LEASES,
                diagnostics(registry, 1L).state());
    }

    @Test
    void signaledFinalFenceRetainsOnlyTheFailedPhysicalCallbackAndForbidsShutdownRetry() {
        FakeRenderOwner renderOwner = new FakeRenderOwner(false);
        ClientModelRegistry registry = registry(renderOwner);
        AtomicInteger vertexClose = new AtomicInteger();
        AtomicInteger indexClose = new AtomicInteger();
        ModelRegistryGeneration generation = generation(1L);
        registry.publish(cpu(generation, List.of(
                vertexClose::incrementAndGet,
                () -> {
                    indexClose.incrementAndGet();
                    throw new IllegalStateException("index close failed");
                })));
        ClientGenerationResourceOwner.ShutdownBatchArmResult armed =
                registry.generationResourceOwner().armShutdownFinalBatch();
        assertTrue(registry.generationResourceOwner().markShutdownFinalFenceWait(armed.batch()));

        ClientGenerationResourceOwner.ShutdownBatchCompletion completion = renderOwner.callOnRenderThread(
                () -> registry.generationResourceOwner().completeShutdownFinalBatch(armed.batch()));

        assertTrue(completion.accepted());
        assertEquals(0, completion.closedRecordCount());
        assertEquals(1, completion.failedRecordCount());
        assertEquals(1, vertexClose.get());
        assertEquals(1, indexClose.get());
        ClientGenerationResourceOwner.GenerationDiagnostics failed = diagnostics(registry, 1L);
        assertEquals(ClientGenerationResourceOwner.State.CLOSE_FAILED, failed.state());
        assertEquals(ClientGenerationResourceOwner.ShutdownFailure.PHYSICAL_CLOSE_FAILED, failed.shutdownFailure());
        assertTrue(failed.retryForbidden());
        assertFalse(generation.retryFailedRenderResourceClose());

        assertFalse(registry.generationResourceOwner()
                .completeShutdownFinalBatch(armed.batch())
                .accepted());
        assertFalse(registry.generationResourceOwner().failShutdownFinalBatch(
                armed.batch(), ClientGenerationResourceOwner.ShutdownFailure.FENCE_TIMEOUT, null));
        assertEquals(1, vertexClose.get());
        assertEquals(1, indexClose.get());
    }

    private static ClientModelRegistry registry(FakeRenderOwner renderOwner) {
        return new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
    }

    /** Converts the retained callback fixture into one exact aggregate before the D1 publication attempt. */
    private static PendingGenerationTransaction cpu(ModelRegistryGeneration generation, List<Runnable> closeCallbacks) {
        if (closeCallbacks.isEmpty()) {
            return PendingGenerationTransaction.cpuOnly(generation);
        }
        List<CompletedGenerationResourceSet.ResourceLeaf> leaves = new ArrayList<>(closeCallbacks.size());
        for (int index = 0; index < closeCallbacks.size(); index++) {
            Runnable callback = closeCallbacks.get(index);
            int leafIndex = index;
            leaves.add(CompletedGenerationResourceSet.leaf(
                    new X7GpuGenerationKey(
                            generation.generationId(),
                            "final-batch-" + leafIndex,
                            "callback-" + leafIndex,
                            "solid/final-batch",
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

    private static ClientGenerationResourceOwner.GenerationDiagnostics diagnostics(
            ClientModelRegistry registry, long generationId) {
        return registry.resourceLifecycleDiagnostics().generations().stream()
                .filter(candidate -> candidate.generationId() == generationId)
                .findFirst()
                .orElseThrow();
    }

    private static ClientGenerationResourceOwner.GenerationDiagnostics findDiagnostics(
            ClientModelRegistry registry, long generationId) {
        return registry.resourceLifecycleDiagnostics().generations().stream()
                .filter(candidate -> candidate.generationId() == generationId)
                .findFirst()
                .orElse(null);
    }

    private static ModelRegistryGeneration generation(long generationId) {
        BlendDiagnostic diagnostic = BlendDiagnostic.error(
                BlendDiagnosticCodes.DESC_002,
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                "/",
                "shutdown final-batch fixture");
        return new ModelRegistryGeneration(
                generationId,
                Map.of(KEY, MissingModelHandle.failed(KEY, generationId, diagnostic)),
                Map.of(KEY, diagnostic),
                List.of());
    }

    private static ModelHandle handle(ModelRegistryGeneration generation) {
        return generation.find(KEY).orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for publication race latch");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publication race latch was interrupted", failure);
        }
    }

    private static final class FakeRenderOwner implements ClientGenerationResourceOwner.RenderOwnerCallbacks {
        private final boolean delayHandoffs;
        private final ArrayDeque<Runnable> handoffs = new ArrayDeque<>();
        private final ArrayDeque<Runnable> fences = new ArrayDeque<>();
        private boolean renderThread;

        private FakeRenderOwner(boolean delayHandoffs) {
            this.delayHandoffs = delayHandoffs;
        }

        @Override
        public void handoffToRenderThread(Runnable handoff) {
            if (delayHandoffs) {
                handoffs.addLast(handoff);
            } else {
                runOnRenderThread(handoff);
            }
        }

        @Override
        public void assertOnRenderThread() {
            if (!renderThread) {
                throw new IllegalStateException("not the fake render thread");
            }
        }

        @Override
        public void queueFencedTask(Runnable callback) {
            assertOnRenderThread();
            fences.addLast(callback);
        }

        private int handoffCount() {
            return handoffs.size();
        }

        private int fenceCount() {
            return fences.size();
        }

        private void runNextHandoff() {
            runOnRenderThread(handoffs.removeFirst());
        }

        private void runNextFence() {
            runOnRenderThread(fences.removeFirst());
        }

        private void runOnRenderThread(Runnable action) {
            callOnRenderThread(() -> {
                action.run();
                return null;
            });
        }

        private <T> T callOnRenderThread(java.util.function.Supplier<T> action) {
            boolean previous = renderThread;
            renderThread = true;
            try {
                return action.get();
            } finally {
                renderThread = previous;
            }
        }
    }
}
