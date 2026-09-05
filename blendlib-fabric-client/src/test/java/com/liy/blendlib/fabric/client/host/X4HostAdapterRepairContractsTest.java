package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

/** Regression probes for the X4 lifetime, ownership, ABA, composite, and missing-model repairs. */
class X4HostAdapterRepairContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_repair:models/host");

    @Test
    void frozenAdapterSupportsContinuousConcurrentSnapshotsOnOneRealGenerationPinOwner() {
        var generation = X4LifecycleTestSupport.published(21L);
        FakeLookup lookup = new FakeLookup(21L);
        AtomicInteger submits = new AtomicInteger();
        List<Integer> submittedLights = new ArrayList<>();
        X4HostIdentity identity = identity("continuous");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                lookup,
                new BlendRenderer((snapshot, context) -> {
                    submits.incrementAndGet();
                    submittedLights.add(snapshot.packedLight());
                }),
                identity,
                generation.session());
        adapter.freeze();

        X4PreparedSnapshot first = adapter.prepare(worldFrame(identity, 0x00F00001));
        X4PreparedSnapshot second = adapter.prepare(worldFrame(identity, 0x00F00002));
        assertEquals(X4HostLifecycleState.PREPARED, adapter.state());
        assertEquals(2, adapter.leaseDiagnostics().activeSnapshotLeases());
        adapter.submit(first, context());
        adapter.submit(second, context());
        assertEquals(List.of(0x00F00001, 0x00F00002), submittedLights);
        first.close();
        assertEquals(X4HostLifecycleState.PREPARED, adapter.state());
        assertEquals(1, adapter.leaseDiagnostics().activeSnapshotLeases());
        second.close();
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state());

        try (X4PreparedSnapshot third = adapter.prepare(worldFrame(identity, 0x00F00003))) {
            adapter.submit(third, context());
        }
        assertEquals(3, submits.get());
        assertEquals(List.of(0x00F00001, 0x00F00002, 0x00F00003), submittedLights);
        adapter.retire();
        assertEquals(X4HostLifecycleState.RETIRED, adapter.drainCompletion().toCompletableFuture().join());
        generation.session().retire();
        assertEquals(1, generation.provider().retireCalls());
        assertEquals(1, generation.provider().closeCalls());
    }

    @Test
    void closeAndSubmitRaceRetainsTheRealPinUntilTheAlreadyAcquiredSubmitHoldDrains() throws Exception {
        var generation = X4LifecycleTestSupport.published(22L);
        FakeLookup lookup = new FakeLookup(22L);
        X4HostIdentity identity = identity("submit-race");
        CountDownLatch enteredRenderer = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        BlendRenderer renderer = new BlendRenderer((snapshot, ignored) -> {
            enteredRenderer.countDown();
            try {
                assertTrue(releaseRenderer.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(lookup, renderer, identity, generation.session());
        adapter.freeze();
        X4PreparedSnapshot lease = adapter.prepare(worldFrame(identity, 0x00F00004));
        AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch submitStarted = new CountDownLatch(1);
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        Thread submitting = daemonWorker(
                "x4-submit-race", () -> adapter.submit(lease, context()), submitFailure, submitStarted, submitFinished);
        Thread closing = daemonWorker(
                "x4-close-race", adapter::close, closeFailure, closeStarted, closeFinished);
        var drain = adapter.drainCompletion().toCompletableFuture();
        try {
            submitting.start();
            await(submitStarted, "submit-race worker did not start");
            await(enteredRenderer, "submit-race renderer was not entered");

            closing.start();
            await(closeStarted, "submit-race close worker did not start");
            awaitState(adapter, X4HostLifecycleState.CLOSING, closeFinished, closeFailure);
            assertEquals(1L, closeFinished.getCount(),
                    "public close must wait for the already-admitted submit and its snapshot");
            assertTrue(closing.isAlive(), "public close returned before the admitted submit drained");
            assertFalse(drain.isDone(), "close drain completed while the admitted submit was still rendering");
            X4HostLeaseDiagnostics closingDiagnostics = adapter.leaseDiagnostics();
            assertTrue(closingDiagnostics.closeRequested());
            assertEquals("", closingDiagnostics.terminalFailureType());

            lease.close();
            assertTrue(lease.closed());
            assertEquals(1, adapter.leaseDiagnostics().activeSnapshotLeases());
            generation.session().retire();
            releaseRenderer.countDown();

            await(submitFinished, "admitted submit did not finish after its renderer was released");
            await(closeFinished, "public close did not finish after the admitted submit drained");
            submitting.join(Duration.ofSeconds(5).toMillis());
            closing.join(Duration.ofSeconds(5).toMillis());
        } finally {
            releaseRenderer.countDown();
            try {
                lease.close();
            } finally {
                cleanupWorker(submitting);
                cleanupWorker(closing);
            }
        }
        assertFalse(submitting.isAlive(), "submit-race worker did not terminate");
        assertFalse(closing.isAlive(), "close-race worker did not terminate");
        assertNull(submitFailure.get(), "admitted submit failed");
        assertNull(closeFailure.get(), "public close failed");
        assertEquals(X4HostLifecycleState.CLOSED, drain.get(5L, TimeUnit.SECONDS));
        assertEquals(0, adapter.leaseDiagnostics().activeSnapshotLeases());
        assertEquals(0, adapter.leaseDiagnostics().inFlightSubmissions());
        assertEquals(1, generation.provider().retireCalls(), "double close must not release the X1 pin twice");
        assertEquals(1, generation.provider().closeCalls());
        lease.close();
        assertEquals(1, generation.provider().closeCalls());
    }

    @Test
    void terminalDrainDoesNotCompleteBeforeTheUnderlyingProviderLeaseActuallyReleases() throws Exception {
        var generation = X4LifecycleTestSupport.published(221L);
        CountDownLatch enteredRetire = new CountDownLatch(1);
        CountDownLatch releaseRetire = new CountDownLatch(1);
        generation.provider().onRetire(() -> {
            enteredRetire.countDown();
            try {
                assertTrue(releaseRetire.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        X4HostIdentity identity = identity("pin-drain");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(221L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
        adapter.freeze();
        X4PreparedSnapshot lease = adapter.prepare(worldFrame(identity, 0x00F00005));
        AtomicReference<Throwable> publicCloseFailure = new AtomicReference<>();
        AtomicReference<Throwable> leaseCloseFailure = new AtomicReference<>();
        CountDownLatch publicCloseStarted = new CountDownLatch(1);
        CountDownLatch publicCloseFinished = new CountDownLatch(1);
        CountDownLatch leaseCloseStarted = new CountDownLatch(1);
        CountDownLatch leaseCloseFinished = new CountDownLatch(1);
        Thread publicClose = daemonWorker(
                "x4-pin-drain-public-close", adapter::close,
                publicCloseFailure, publicCloseStarted, publicCloseFinished);
        Thread closingLease = daemonWorker(
                "x4-pin-drain", lease::close, leaseCloseFailure, leaseCloseStarted, leaseCloseFinished);
        var drain = adapter.drainCompletion().toCompletableFuture();
        try {
            publicClose.start();
            await(publicCloseStarted, "pin-drain public close worker did not start");
            awaitState(adapter, X4HostLifecycleState.CLOSING, publicCloseFinished, publicCloseFailure);
            assertEquals(1L, publicCloseFinished.getCount(),
                    "public close must wait while its exact generation snapshot remains open");
            assertTrue(publicClose.isAlive(), "public close returned before its generation snapshot released");
            assertFalse(drain.isDone(), "close drain completed before the generation snapshot released");
            X4HostLeaseDiagnostics closingDiagnostics = adapter.leaseDiagnostics();
            assertTrue(closingDiagnostics.closeRequested());
            assertEquals("", closingDiagnostics.terminalFailureType());

            generation.session().retire();
            closingLease.start();
            await(leaseCloseStarted, "pin-drain lease close worker did not start");
            await(enteredRetire, "underlying provider retire callback was not entered");
            assertEquals(1L, leaseCloseFinished.getCount(),
                    "snapshot close must remain pending while the provider callback is blocked");
            assertEquals(1L, publicCloseFinished.getCount(),
                    "public close returned before the provider callback released its exact pin");
            assertFalse(drain.isDone(), "close drain completed before the provider callback released its exact pin");
            assertEquals(1, adapter.leaseDiagnostics().activeSnapshotLeases());

            releaseRetire.countDown();
            await(leaseCloseFinished, "snapshot close did not finish after the provider callback was released");
            await(publicCloseFinished, "public close did not finish after the provider callback was released");
            closingLease.join(Duration.ofSeconds(5).toMillis());
            publicClose.join(Duration.ofSeconds(5).toMillis());
        } finally {
            releaseRetire.countDown();
            try {
                lease.close();
            } finally {
                cleanupWorker(closingLease);
                cleanupWorker(publicClose);
            }
        }
        assertFalse(closingLease.isAlive(), "pin-drain lease close worker did not terminate");
        assertFalse(publicClose.isAlive(), "pin-drain public close worker did not terminate");
        assertNull(leaseCloseFailure.get(), "generation snapshot close failed");
        assertNull(publicCloseFailure.get(), "public close failed");
        assertEquals(X4HostLifecycleState.CLOSED, drain.get(5L, TimeUnit.SECONDS));
    }

    @Test
    void missingEvidenceAndCapturedMissingSnapshotsAreCoupledToTheSameGeneration() {
        X4HostIdentity identity = identity("missing");
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 23L);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        ClientDiagnostic diagnostic = diagnostic(KEY);
        assertThrows(IllegalArgumentException.class, () -> X4SnapshotFrame.extracted(
                identity, X4Transform.IDENTITY, 0x00F000F0, 0, 0xFFFFFFFF, true, snapshot));
        assertThrows(IllegalArgumentException.class, () -> X4SnapshotFrame.extractedMissing(
                identity,
                X4Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                true,
                snapshot,
                new X4MissingModelDiagnostic(24L, diagnostic)));
        X4SnapshotFrame frame = X4SnapshotFrame.extractedMissing(
                identity,
                X4Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                true,
                snapshot,
                new X4MissingModelDiagnostic(23L, diagnostic));
        assertEquals(23L, frame.capturedMissingDiagnostic().orElseThrow().generation());
    }

    @Test
    void compositeConfigurationRejectsMixedScopeDisconnectedGraphsAndSpecificationRootMismatch() {
        X4ExperimentalAccess access = X4ExperimentalAccess.optIn(BlendResourceId.parse("x4_repair:composite-opt-in"));
        X4HostIdentity root = identity("root");
        X4HostIdentity child = identity("child");
        X4HostIdentity foreignScope = new X4HostIdentity(
                BlendResourceId.parse("x4_repair:other-session"), BlendResourceId.parse("x4_repair:host/foreign"));
        assertThrows(IllegalArgumentException.class,
                () -> new X4CompositeGraph(Map.of(root, List.of(foreignScope), foreignScope, List.of())));

        X4HostIdentity detached = identity("detached");
        X4CompositeGraph disconnected = new X4CompositeGraph(Map.of(
                root, List.of(child), child, List.of(), detached, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new X4HostConfigurations.MountComposite(
                access, disconnected, root, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));

        X4CompositeGraph connected = new X4CompositeGraph(Map.of(root, List.of(child), child, List.of()));
        var generation = X4LifecycleTestSupport.published(25L);
        assertThrows(IllegalArgumentException.class, () -> X4HostAdapters.mountComposite(
                access, new FakeLookup(25L), new BlendRenderer((snapshot, context) -> { }), generation.session())
                .model(KEY)
                .identity(child)
                .configuration(new X4HostConfigurations.MountComposite(
                        access, connected, root, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build());
    }

    @Test
    void opaqueRegistryReceiptRejectsAbaRetirementAndCloseAttemptsEveryAdapter() {
        var generation = X4LifecycleTestSupport.published(26L);
        FakeLookup lookup = new FakeLookup(26L);
        X4HostIdentity sharedIdentity = identity("registry-aba");
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        X4HostRegistrationReceipt<X4HostFrames.WorldObject> first = registry.register(worldAdapter(
                lookup, new BlendRenderer((snapshot, context) -> { }), sharedIdentity, generation.session()));
        assertTrue(registry.retire(first));
        X4HostRegistrationReceipt<X4HostFrames.WorldObject> replacement = registry.register(worldAdapter(
                lookup, new BlendRenderer((snapshot, context) -> { }), sharedIdentity, generation.session()));
        assertFalse(registry.retire(first), "a stale receipt must not retire a later same-kind/same-identity owner");
        assertEquals(X4HostLifecycleState.FROZEN, replacement.adapter().state());
        registry.close();
        assertEquals(X4HostLifecycleState.CLOSED, replacement.adapter().state());

        X4HostAdapterRegistry failures = new X4HostAdapterRegistry();
        ClosingFailureAdapter one = new ClosingFailureAdapter(identity("close-one"), new IllegalStateException("one"));
        ClosingFailureAdapter two = new ClosingFailureAdapter(identity("close-two"), new IllegalStateException("two"));
        failures.register(one);
        failures.register(two);
        IllegalStateException aggregate = assertThrows(IllegalStateException.class, failures::close);
        assertSame(one.failure, aggregate.getCause());
        assertEquals(1, one.closeCalls.get());
        assertEquals(1, two.closeCalls.get());
        assertEquals(1, aggregate.getCause().getSuppressed().length);
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

    private static X4HostFrames.WorldObject worldFrame(X4HostIdentity identity, int light) {
        return new X4HostFrames.WorldObject(X4SnapshotFrame.unresolved(
                identity, X4Transform.IDENTITY, light, 0, 0xFFFFFFFF, true), identity.scope(), 0L);
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_repair:session"), BlendResourceId.parse("x4_repair:host/" + local));
    }

    private static ClientDiagnostic diagnostic(BlendModelKey key) {
        return new ClientDiagnostic(
                ClientDiagnosticSeverity.ERROR,
                "X4-REPAIR-MISSING",
                key.resourceId(),
                key.resourceId(),
                "x4-repair",
                "fixture missing model",
                "none");
    }

    private static RenderSubmissionContext context() {
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                X4HostAdapterRepairContractsTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("X4 repair backend must not use collector method " + method.getName());
                });
        return new RenderSubmissionContext(new com.mojang.blaze3d.vertex.PoseStack(), collector);
    }

    private static Thread daemonWorker(
            String name,
            Runnable operation,
            AtomicReference<Throwable> failure,
            CountDownLatch started,
            CountDownLatch finished) {
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                operation.run();
            } catch (Throwable observed) {
                failure.set(observed);
            } finally {
                finished.countDown();
            }
        }, name);
        worker.setDaemon(true);
        return worker;
    }

    private static void await(CountDownLatch latch, String failureMessage) throws InterruptedException {
        assertTrue(latch.await(5L, TimeUnit.SECONDS), failureMessage);
    }

    private static void awaitState(
            X4HostAdapter<?> adapter,
            X4HostLifecycleState expected,
            CountDownLatch workerFinished,
            AtomicReference<Throwable> workerFailure) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (adapter.state() == expected) return;
            if (workerFinished.getCount() == 0L) break;
            Thread.onSpinWait();
        }
        Throwable failure = workerFailure.get();
        if (failure != null) {
            throw new AssertionError("terminal worker failed before reaching " + expected, failure);
        }
        throw new AssertionError("X4 adapter did not reach " + expected + " before the deadline; actual="
                + adapter.state());
    }

    private static void cleanupWorker(Thread worker) {
        if (!worker.isAlive()) return;
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeLookup implements ClientModelLookup {
        private final ClientModelView view;

        private FakeLookup(long generation) {
            MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, generation);
            view = new ClientModelView(KEY, generation, false, handle, Optional.of(diagnostic(KEY)));
        }

        @Override
        public ClientRegistryView snapshot() {
            return new ClientRegistryView(view.generationId(), Map.of(KEY, view), List.of(view.primaryDiagnostic().orElseThrow()));
        }

        @Override
        public ClientModelView resolve(BlendModelKey key) {
            assertEquals(KEY, key);
            return view;
        }
    }

    private static final class ClosingFailureAdapter
            implements X4HostAdapter<X4HostFrames.WorldObject>, X4HostRegistrationLifecycle<X4HostFrames.WorldObject> {
        private final X4HostSpec<X4HostFrames.WorldObject> specification;
        private final Throwable failure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CompletableFuture<X4HostLifecycleState> completion = new CompletableFuture<>();
        private X4HostLifecycleState state = X4HostLifecycleState.CONFIGURING;
        private boolean reservationActive;

        private ClosingFailureAdapter(X4HostIdentity identity, Throwable failure) {
            specification = new X4HostSpec<>(
                    X4HostKind.WORLD_OBJECT,
                    KEY,
                    identity,
                    new X4HostConfigurations.WorldObject(
                            identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));
            this.failure = failure;
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
            state = X4HostLifecycleState.FROZEN;
        }

        @Override
        public X4HostRegistrationReservation<X4HostFrames.WorldObject> freezeAndReserveRegistration() {
            if (state != X4HostLifecycleState.CONFIGURING || reservationActive) {
                throw new IllegalStateException("closing-failure probe can reserve only from configuring");
            }
            state = X4HostLifecycleState.FROZEN;
            reservationActive = true;
            return new X4HostRegistrationReservation<>() {
                private boolean complete;

                @Override
                public X4HostSpec<X4HostFrames.WorldObject> specification() {
                    return specification;
                }

                @Override
                public void commit(X4HostRegistrationMembership membership) {
                    completeReservation();
                }

                @Override
                public void abort() {
                    completeReservation();
                }

                private void completeReservation() {
                    if (complete || !reservationActive) {
                        throw new IllegalStateException("closing-failure probe reservation is no longer active");
                    }
                    complete = true;
                    reservationActive = false;
                }
            };
        }

        @Override
        public X4PreparedSnapshot prepare(X4HostFrames.WorldObject frame) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retire() {
            state = X4HostLifecycleState.RETIRED;
            completion.complete(state);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            state = X4HostLifecycleState.CLOSED;
            completion.complete(state);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(failure);
        }
    }
}
