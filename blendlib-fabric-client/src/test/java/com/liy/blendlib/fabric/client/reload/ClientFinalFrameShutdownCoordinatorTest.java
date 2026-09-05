package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class ClientFinalFrameShutdownCoordinatorTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("shutdown_test:coordinator");

    @Test
    void ordinaryFrameRunsOriginalPresentExactlyOnceWithZeroShutdownSideEffects() {
        Fixture fixture = new Fixture();
        AtomicInteger presents = new AtomicInteger();

        fixture.runFrame(true, presents::incrementAndGet);

        assertEquals(1, presents.get());
        assertEquals(ClientFinalFrameShutdownCoordinator.State.RUNNING, fixture.diagnostics().state());
        assertTrue(fixture.diagnostics().creatorAdmissionOpen());
        assertEquals(0, fixture.adapter.createCount);
        assertEquals(0, fixture.adapter.pollCount);
        assertEquals(0, fixture.renderOwner.fenceCount());
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void finalFrameWithNoPhysicalCloseWorkDrainsWithoutCreatingADeviceFence() {
        Fixture fixture = new Fixture();
        AtomicInteger presents = new AtomicInteger();

        fixture.runFrame(false, presents::incrementAndGet);

        assertEquals(1, presents.get());
        assertEquals(ClientFinalFrameShutdownCoordinator.State.DRAINED, fixture.diagnostics().state());
        assertEquals(0, fixture.adapter.createCount);
        assertEquals(0, fixture.adapter.pollCount);
        assertEquals(0, fixture.adapter.fenceCloseCount);
        assertEquals(0, fixture.adapter.gameDeviceCloseCount);
        assertEquals(0, fixture.renderOwner.fenceCount());
    }

    @Test
    void admissionClosesBeforeDeviceAccessAndSignaledFenceClosesOnlyAfterOriginalPresent() {
        Fixture fixture = new Fixture();
        AtomicInteger vertexClose = new AtomicInteger();
        AtomicInteger indexClose = new AtomicInteger();
        CompletedGenerationResourceSet set = fixture.publishSet(2, 128L, () -> {
            fixture.events.add("physical-close");
            vertexClose.incrementAndGet();
            indexClose.incrementAndGet();
        });
        fixture.adapter.pollResults.addLast(false);
        fixture.adapter.pollResults.addLast(false);
        fixture.adapter.pollResults.addLast(true);
        fixture.adapter.beforeCreate = () ->
                assertNull(fixture.coordinator.tryAdmitCreatorBeforeDeviceAccess());
        AtomicInteger presents = new AtomicInteger();

        fixture.runFrame(false, () -> {
            presents.incrementAndGet();
            fixture.events.add("present");
        });

        assertEquals(1, presents.get());
        assertEquals(ClientFinalFrameShutdownCoordinator.State.DRAINED, fixture.diagnostics().state());
        assertFalse(fixture.diagnostics().creatorAdmissionOpen());
        assertEquals(1, fixture.adapter.createCount);
        assertEquals(3, fixture.adapter.pollCount);
        assertEquals(1, fixture.adapter.fenceCloseCount);
        assertEquals(1, vertexClose.get());
        assertEquals(1, indexClose.get());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, set.ownership());
        assertEquals(0, fixture.renderOwner.fenceCount());
        assertTrue(fixture.events.indexOf("create-fence") < fixture.events.indexOf("present"));
        assertTrue(fixture.events.indexOf("present") < fixture.events.indexOf("poll-fence"));
        assertTrue(fixture.events.indexOf("poll-fence") < fixture.events.indexOf("physical-close"));
        assertTrue(fixture.events.indexOf("physical-close") < fixture.events.indexOf("close-fence"));
    }

    @Test
    void fenceTimeoutRetainsExactSetDiagnosticsAndClosesOnlyTheOwnedFence() {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        CompletedGenerationResourceSet set = fixture.publishSet(2, 4096L, physicalClose::incrementAndGet);
        fixture.adapter.pollResults.addLast(false);
        fixture.adapter.pollResults.addLast(false);
        fixture.adapter.pollResults.addLast(false);

        fixture.runFrame(false, () -> fixture.events.add("present"));
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::afterOriginalPresent);
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::clientStoppingFallback);

        ClientFinalFrameShutdownCoordinator.Diagnostics shutdown = fixture.diagnostics();
        assertEquals(ClientFinalFrameShutdownCoordinator.State.FAILED_FENCE_TIMEOUT, shutdown.state());
        assertEquals(3, shutdown.finalFencePollCount());
        assertEquals(0, physicalClose.get());
        assertEquals(1, fixture.adapter.fenceCloseCount);
        assertEquals(0, fixture.adapter.gameDeviceCloseCount);
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_OWNED, set.ownership());
        ClientGenerationResourceOwner.GenerationDiagnostics retained = shutdown.d1().generations().getFirst();
        assertEquals(ClientGenerationResourceOwner.State.SHUTDOWN_FENCE_TIMEOUT, retained.state());
        assertEquals(ClientGenerationResourceOwner.ShutdownFailure.FENCE_TIMEOUT, retained.shutdownFailure());
        assertEquals(2, retained.physicalResourceCount());
        assertEquals(4096L, retained.physicalByteCount());
        assertTrue(retained.retryForbidden());
    }

    @Test
    void fenceWaitFailureDoesNotEscapeOrConsumeAnUnrelatedGlobalFifoCallback() {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        AtomicInteger unrelatedGlobalCallback = new AtomicInteger();
        fixture.publishSet(1, 64L, physicalClose::incrementAndGet);
        fixture.renderOwner.runOnRenderThread(
                () -> fixture.renderOwner.queueFencedTask(unrelatedGlobalCallback::incrementAndGet));
        fixture.adapter.pollFailure = new IllegalStateException("owned wait failed");

        fixture.runFrame(false, () -> fixture.events.add("present"));

        assertEquals(ClientFinalFrameShutdownCoordinator.State.FAILED_FENCE_WAIT, fixture.diagnostics().state());
        assertEquals(0, physicalClose.get());
        assertEquals(0, unrelatedGlobalCallback.get());
        assertEquals(1, fixture.renderOwner.fenceCount());
        assertEquals(1, fixture.adapter.fenceCloseCount);
        assertEquals(0, fixture.adapter.gameDeviceCloseCount);
    }

    @Test
    void admittedCreatorFinishingAfterCutoffCannotPublishACompleteTransactionAndShutdownCannotReopenDeviceAdmission() {
        Fixture fixture = new Fixture();
        ClientFinalFrameShutdownCoordinator.CreatorPermit creator =
                fixture.coordinator.tryAdmitCreatorBeforeDeviceAccess();
        assertTrue(creator != null);

        fixture.renderOwner.runOnRenderThread(
                () -> fixture.coordinator.beforeOriginalPresent(false));

        assertEquals(
                ClientFinalFrameShutdownCoordinator.State.FAILED_OUTSTANDING_CREATORS,
                fixture.diagnostics().state());
        assertEquals(1, fixture.diagnostics().creatorCountAtCutoff());
        assertEquals(0, fixture.adapter.createCount);
        creator.close();
        creator.close();
        assertEquals(0, fixture.diagnostics().inFlightCreatorCount());
        CompletedGenerationResourceSet lateSet = X7GenerationResourceTestSupport.complete(
                fixture.generation, 1, 32L, () -> { });
        PendingGenerationTransaction lateTransaction =
                PendingGenerationTransaction.complete(fixture.generation, lateSet);
        assertThrows(IllegalStateException.class, () -> fixture.registry.publish(lateTransaction));
        assertEquals(PendingGenerationTransaction.State.PREPARED_CALLER_OWNED, lateTransaction.state());
        assertEquals(CompletedGenerationResourceSet.Ownership.CALLER_OWNED_COMPLETE, lateSet.ownership());
        assertTrue(fixture.renderOwner.callOnRenderThread(lateSet::closeIfStillCallerOwned));

        AtomicInteger nonNullDeviceProbe = new AtomicInteger();
        ClientFinalFrameShutdownCoordinator.CreatorPermit rejected =
                fixture.coordinator.tryAdmitCreatorBeforeDeviceAccess();
        if (rejected != null) {
            nonNullDeviceProbe.incrementAndGet();
            rejected.close();
        }
        assertEquals(0, nonNullDeviceProbe.get(), "a non-null platform device cannot reopen logical admission");
    }

    @Test
    void inFlightPublicationMakesTheFinalFrameTypedNonDrainedWithoutCreatingAFence()
            throws InterruptedException {
        Fixture fixture = new Fixture();
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch allowOwnerTransfer = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        ModelRegistryGeneration candidate = generation(2L);
        PendingGenerationTransaction transaction = cpu(candidate, List.of(closeCount::incrementAndGet));
        AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
        Thread publisher = Thread.ofPlatform().start(() -> {
            try {
                fixture.registry.publish(
                        transaction,
                        () -> {
                            claimReached.countDown();
                            try {
                                if (!allowOwnerTransfer.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("timed out waiting for final-frame publication cutoff");
                                }
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("final-frame publication cutoff was interrupted", failure);
                            }
                        },
                        ClientGenerationResourceOwner.NOOP_PUBLICATION_ADOPTION_BARRIER);
            } catch (Throwable failure) {
                publicationFailure.set(failure);
            }
        });
        try {
            assertTrue(claimReached.await(5, TimeUnit.SECONDS));
            AtomicInteger presents = new AtomicInteger();

            fixture.runFrame(false, presents::incrementAndGet);

            assertEquals(1, presents.get());
            assertEquals(
                    ClientFinalFrameShutdownCoordinator.State.FAILED_OUTSTANDING_TRANSACTIONS,
                    fixture.diagnostics().state());
            assertEquals(1, fixture.diagnostics().d1().outstandingPublicationTransactionCount());
            assertEquals(0, fixture.adapter.createCount);
            assertEquals(0, fixture.adapter.pollCount);
            assertEquals(0, fixture.renderOwner.fenceCount());
            assertEquals(0, closeCount.get());

            allowOwnerTransfer.countDown();
            publisher.join(5_000L);
            assertFalse(publisher.isAlive());
            assertNull(publicationFailure.get());
            assertEquals(PendingGenerationTransaction.State.ABORTED, transaction.state());
            assertEquals(0, fixture.diagnostics().d1().outstandingPublicationTransactionCount());
            assertEquals(1, fixture.diagnostics().d1().publicationTransactionCountAtCutoff());
            assertEquals(0, fixture.renderOwner.fenceCount());
            assertEquals(0, closeCount.get());
            ClientGenerationResourceOwner.GenerationDiagnostics retained = fixture.diagnostics().d1().generations()
                    .stream()
                    .filter(generation -> generation.generationId() == 2L)
                    .findFirst()
                    .orElseThrow();
            assertEquals(ClientGenerationResourceOwner.State.SHUTDOWN_OUTSTANDING_TRANSACTIONS, retained.state());
            assertTrue(retained.resourceReady(), "the claimed exact set remains D1 cleanup-ready after cutoff");
            assertFalse(retained.publicationReady(), "a terminal cleanup record is never CAS authority");
            assertTrue(retained.retryForbidden());
        } finally {
            allowOwnerTransfer.countDown();
            publisher.join(5_000L);
        }
    }

    @Test
    void adapterHashMismatchFailsClosedBeforeFenceCreationOrPhysicalClose() {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        fixture.publishSet(1, 16L, physicalClose::incrementAndGet);
        fixture.adapter.available = false;

        fixture.runFrame(false, () -> fixture.events.add("present"));

        assertEquals(ClientFinalFrameShutdownCoordinator.State.FAILED_ADAPTER_MISMATCH, fixture.diagnostics().state());
        assertEquals(0, fixture.adapter.createCount);
        assertEquals(0, fixture.adapter.pollCount);
        assertEquals(0, fixture.adapter.fenceCloseCount);
        assertEquals(0, physicalClose.get());
        assertEquals(
                ClientGenerationResourceOwner.State.SHUTDOWN_ADAPTER_MISMATCH,
                fixture.diagnostics().d1().generations().getFirst().state());
    }

    @Test
    void missingAfterHookAndRepeatedStoppingFailClosedWithoutBufferClose() {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        fixture.publishSet(1, 16L, physicalClose::incrementAndGet);

        fixture.renderOwner.runOnRenderThread(
                () -> fixture.coordinator.beforeOriginalPresent(false));
        assertEquals(ClientFinalFrameShutdownCoordinator.State.FINAL_FENCE_ARMED, fixture.diagnostics().state());
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::clientStoppingFallback);
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::clientStoppingFallback);
        fixture.renderOwner.runOnRenderThread(
                () -> fixture.coordinator.beforeOriginalPresent(false));
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::afterOriginalPresent);

        assertEquals(ClientFinalFrameShutdownCoordinator.State.FAILED_NO_FINAL_PRESENT, fixture.diagnostics().state());
        assertEquals(1, fixture.adapter.createCount);
        assertEquals(0, fixture.adapter.pollCount);
        assertEquals(1, fixture.adapter.fenceCloseCount);
        assertEquals(0, physicalClose.get());
        assertEquals(0, fixture.adapter.gameDeviceCloseCount);
    }

    @Test
    void throwingOriginalPresentPreservesVanillaFailureAndStoppingRetainsTheUnclosedBatch() {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        fixture.publishSet(1, 16L, physicalClose::incrementAndGet);
        IllegalStateException presentFailure = new IllegalStateException("vanilla present failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                fixture.renderOwner.runOnRenderThread(() -> {
                    fixture.coordinator.beforeOriginalPresent(false);
                    throw presentFailure;
                }));

        assertSame(presentFailure, thrown);
        assertEquals(ClientFinalFrameShutdownCoordinator.State.FINAL_FENCE_ARMED, fixture.diagnostics().state());
        assertEquals(0, fixture.adapter.pollCount);
        assertEquals(0, physicalClose.get());
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::clientStoppingFallback);
        assertEquals(ClientFinalFrameShutdownCoordinator.State.FAILED_NO_FINAL_PRESENT, fixture.diagnostics().state());
        assertEquals(1, fixture.adapter.fenceCloseCount);
        assertEquals(0, physicalClose.get());
    }

    @Test
    void repeatedFinalHookClientStoppingAndRegistryCloseRemainIdempotentAfterDrain()
            throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        fixture.publishSet(1, 16L, physicalClose::incrementAndGet);
        fixture.adapter.pollResults.addLast(true);
        java.lang.reflect.Field coordinatorField =
                ClientModelRegistry.class.getDeclaredField("shutdownCoordinator");
        coordinatorField.setAccessible(true);
        coordinatorField.set(fixture.registry, fixture.coordinator);

        fixture.runFrame(false, () -> fixture.events.add("present"));
        fixture.renderOwner.runOnRenderThread(
                () -> fixture.coordinator.beforeOriginalPresent(false));
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::afterOriginalPresent);
        fixture.renderOwner.runOnRenderThread(fixture.coordinator::clientStoppingFallback);
        fixture.registry.close();
        fixture.registry.close();

        assertEquals(ClientFinalFrameShutdownCoordinator.State.DRAINED, fixture.diagnostics().state());
        assertEquals(1, fixture.adapter.createCount);
        assertEquals(1, fixture.adapter.pollCount);
        assertEquals(1, fixture.adapter.fenceCloseCount);
        assertEquals(1, physicalClose.get());
    }

    @Test
    void ownedFenceCloseFailureRetainsTheFenceForOneStoppingRetryWithoutReplayingPhysicalClose() {
        Fixture fixture = new Fixture();
        AtomicInteger physicalClose = new AtomicInteger();
        fixture.publishSet(1, 16L, physicalClose::incrementAndGet);
        fixture.adapter.pollResults.addLast(true);
        fixture.adapter.fenceCloseFailuresRemaining = 1;

        fixture.runFrame(false, () -> fixture.events.add("present"));

        assertEquals(ClientFinalFrameShutdownCoordinator.State.DRAINED, fixture.diagnostics().state());
        assertEquals(1, physicalClose.get());
        assertEquals(1, fixture.adapter.fenceCloseAttemptCount);
        assertEquals(0, fixture.adapter.fenceCloseCount);
        assertEquals(IllegalStateException.class.getName(), fixture.diagnostics().ownedFenceCloseFailureType());

        fixture.renderOwner.runOnRenderThread(fixture.coordinator::clientStoppingFallback);
        assertEquals(ClientFinalFrameShutdownCoordinator.State.DRAINED, fixture.diagnostics().state());
        assertEquals(1, physicalClose.get());
        assertEquals(2, fixture.adapter.fenceCloseAttemptCount);
        assertEquals(1, fixture.adapter.fenceCloseCount);
    }

    @Test
    void concurrentStoppingCannotCancelAnAlreadyAuthorizedPhysicalClose() throws InterruptedException {
        Fixture fixture = new Fixture();
        CountDownLatch physicalCloseEntered = new CountDownLatch(1);
        CountDownLatch allowPhysicalClose = new CountDownLatch(1);
        AtomicInteger physicalClose = new AtomicInteger();
        fixture.publishSet(1, 16L, () -> {
            physicalCloseEntered.countDown();
            try {
                if (!allowPhysicalClose.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to finish the fake physical close");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fake physical close was interrupted", failure);
            }
            physicalClose.incrementAndGet();
        });
        fixture.adapter.pollResults.addLast(true);
        AtomicReference<Throwable> frameFailure = new AtomicReference<>();
        Thread frameThread = Thread.ofPlatform().start(() -> {
            try {
                fixture.runFrame(false, () -> fixture.events.add("present"));
            } catch (Throwable failure) {
                frameFailure.set(failure);
            }
        });

        assertTrue(physicalCloseEntered.await(5, TimeUnit.SECONDS));
        fixture.coordinator.clientStoppingFallback();
        assertEquals(ClientFinalFrameShutdownCoordinator.State.PHYSICAL_CLOSE, fixture.diagnostics().state());
        allowPhysicalClose.countDown();
        frameThread.join(5_000L);

        assertFalse(frameThread.isAlive());
        assertNull(frameFailure.get());
        assertEquals(ClientFinalFrameShutdownCoordinator.State.DRAINED, fixture.diagnostics().state());
        assertEquals(1, physicalClose.get());
        assertEquals(1, fixture.adapter.fenceCloseCount);
    }

    /** The retained shutdown fixture now composes its exact resource set before publication. */
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
                            "coordinator-" + leafIndex,
                            "callback-" + leafIndex,
                            "solid/coordinator",
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

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final FakeRenderOwner renderOwner = new FakeRenderOwner();
        private final ClientModelRegistry registry =
                new ClientModelRegistry(ModelRegistryGeneration.empty(0L), renderOwner);
        private final ModelRegistryGeneration generation = generation(1L);
        private final StepClock clock = new StepClock();
        private final FakeFenceAdapter adapter = new FakeFenceAdapter(renderOwner, events);
        private final ClientFinalFrameShutdownCoordinator coordinator;

        private Fixture() {
            coordinator = new ClientFinalFrameShutdownCoordinator(
                    registry.generationResourceOwner(), adapter, clock, 3L, 10);
        }

        private CompletedGenerationResourceSet publishSet(
                int resourceCount, long byteCount, Runnable close) {
            CompletedGenerationResourceSet set = X7GenerationResourceTestSupport.complete(
                    generation, resourceCount, byteCount, close::run);
            assertSame(generation, registry.publish(PendingGenerationTransaction.complete(generation, set)));
            return set;
        }

        private void runFrame(boolean running, Runnable originalPresent) {
            renderOwner.runOnRenderThread(() -> {
                coordinator.beforeOriginalPresent(running);
                originalPresent.run();
                coordinator.afterOriginalPresent();
            });
        }

        private ClientFinalFrameShutdownCoordinator.Diagnostics diagnostics() {
            return coordinator.diagnostics();
        }
    }

    private static final class StepClock implements ClientFinalFrameShutdownCoordinator.NanoClock {
        private long now;

        @Override
        public long nanoTime() {
            return now++;
        }
    }

    private static final class FakeFenceAdapter implements ClientFinalFrameShutdownCoordinator.OwnedFenceAdapter {
        private final FakeRenderOwner renderOwner;
        private final List<String> events;
        private final ArrayDeque<Boolean> pollResults = new ArrayDeque<>();
        private boolean available = true;
        private RuntimeException pollFailure;
        private Runnable beforeCreate = () -> { };
        private int createCount;
        private int pollCount;
        private int fenceCloseAttemptCount;
        private int fenceCloseCount;
        private int fenceCloseFailuresRemaining;
        private int gameDeviceCloseCount;

        private FakeFenceAdapter(FakeRenderOwner renderOwner, List<String> events) {
            this.renderOwner = renderOwner;
            this.events = events;
        }

        @Override
        public boolean pinnedAdapterAvailable() {
            return available;
        }

        @Override
        public void assertOnRenderThread() {
            renderOwner.assertOnRenderThread();
        }

        @Override
        public ClientFinalFrameShutdownCoordinator.OwnedFence createOwnedFence() {
            renderOwner.assertOnRenderThread();
            beforeCreate.run();
            createCount++;
            events.add("create-fence");
            return new ClientFinalFrameShutdownCoordinator.OwnedFence() {
                private boolean closed;

                @Override
                public boolean awaitCompletionZeroTimeout() {
                    renderOwner.assertOnRenderThread();
                    pollCount++;
                    events.add("poll-fence");
                    if (pollFailure != null) {
                        throw pollFailure;
                    }
                    return !pollResults.isEmpty() && pollResults.removeFirst();
                }

                @Override
                public void closeOnRenderThread() {
                    renderOwner.assertOnRenderThread();
                    if (!closed) {
                        fenceCloseAttemptCount++;
                        if (fenceCloseFailuresRemaining > 0) {
                            fenceCloseFailuresRemaining--;
                            throw new IllegalStateException("owned fence close failed");
                        }
                        closed = true;
                        fenceCloseCount++;
                        events.add("close-fence");
                    }
                }
            };
        }
    }

    private static final class FakeRenderOwner implements ClientGenerationResourceOwner.RenderOwnerCallbacks {
        private final ArrayDeque<Runnable> fences = new ArrayDeque<>();
        private boolean renderThread;

        @Override
        public void handoffToRenderThread(Runnable handoff) {
            runOnRenderThread(handoff);
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

        private int fenceCount() {
            return fences.size();
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

    private static ModelRegistryGeneration generation(long generationId) {
        BlendDiagnostic diagnostic = BlendDiagnostic.error(
                BlendDiagnosticCodes.DESC_002,
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                "/",
                "shutdown coordinator fixture");
        return new ModelRegistryGeneration(
                generationId,
                Map.of(KEY, MissingModelHandle.failed(KEY, generationId, diagnostic)),
                Map.of(KEY, diagnostic),
                List.of());
    }
}
