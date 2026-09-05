package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

/** Precise r4 probes for registry ownership, fatal cleanup selection, and abort recovery. */
class X4HostAdapterFourthRepairContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_r4:models/host");

    @Test
    void directTerminalTransitionsNeverRetainMembershipAcross1024AttemptsAndPermitReplacement() {
        var generation = X4LifecycleTestSupport.published(401L);
        int retainedTerminalEntries = 0;
        int replacementFailures = 0;
        try {
            for (int attempt = 0; attempt < 1_024; attempt++) {
                X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
                X4HostIdentity identity = identity("terminal-membership-" + attempt);
                try {
                    X4HostAdapter<X4HostFrames.WorldObject> original = worldAdapter(
                            new FakeLookup(401L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
                    registry.register(original);
                    if ((attempt & 1) == 0) {
                        original.close();
                        assertEquals(X4HostLifecycleState.CLOSED, original.state());
                    } else {
                        original.retire();
                        assertEquals(X4HostLifecycleState.RETIRED, original.state());
                    }
                    retainedTerminalEntries += registry.registrations().size();
                    try {
                        X4HostAdapter<X4HostFrames.WorldObject> replacement = worldAdapter(
                                new FakeLookup(401L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
                        X4HostRegistrationReceipt<X4HostFrames.WorldObject> receipt = registry.register(replacement);
                        assertTrue(registry.retire(receipt));
                    } catch (IllegalStateException expectedUntilTheRepair) {
                        replacementFailures++;
                    }
                } finally {
                    registry.close();
                }
            }
        } finally {
            generation.session().retire();
        }
        assertEquals(0, retainedTerminalEntries,
                "a public close/retire must revoke exact registry membership before the adapter becomes terminal");
        assertEquals(0, replacementFailures,
                "a direct terminal transition must free the exact host identity for a fresh configuring replacement");
    }

    @Test
    void concurrentDirectCloseOrRetireAndReplacementRegistrationLeaveNoTerminalMembership() throws Exception {
        var generation = X4LifecycleTestSupport.published(405L);
        try {
            for (boolean close : List.of(false, true)) {
                for (int attempt = 0; attempt < 64; attempt++) {
                    X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
                    X4HostIdentity identity = identity((close ? "close" : "retire") + "-register-race-" + attempt);
                    X4HostAdapter<X4HostFrames.WorldObject> original = worldAdapter(
                            new FakeLookup(405L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
                    registry.register(original);
                    X4HostAdapter<X4HostFrames.WorldObject> racingReplacement = worldAdapter(
                            new FakeLookup(405L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
                    AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
                    AtomicReference<X4HostRegistrationReceipt<X4HostFrames.WorldObject>> racingReceipt = new AtomicReference<>();
                    Thread terminal = new Thread(() -> {
                        try {
                            start.await();
                            if (close) {
                                original.close();
                            } else {
                                original.retire();
                            }
                        } catch (Throwable failure) {
                            terminalFailure.set(failure);
                        }
                    }, "x4-r4-" + (close ? "close" : "retire") + "-terminal-" + attempt);
                    Thread registrar = new Thread(() -> {
                        try {
                            start.await();
                            racingReceipt.set(registry.register(racingReplacement));
                        } catch (Throwable failure) {
                            registrationFailure.set(failure);
                        }
                    }, "x4-r4-" + (close ? "close" : "retire") + "-register-" + attempt);
                    try {
                        terminal.start();
                        registrar.start();
                        start.countDown();
                        join(terminal);
                        join(registrar);

                        assertNull(terminalFailure.get());
                        assertEquals(close ? X4HostLifecycleState.CLOSED : X4HostLifecycleState.RETIRED, original.state());
                        if (racingReceipt.get() != null) {
                            assertNull(registrationFailure.get());
                            assertTrue(registry.retire(racingReceipt.get()));
                        } else {
                            assertTrue(registrationFailure.get() instanceof IllegalStateException,
                                    "a racing registration may lose only to the pre-revocation duplicate check");
                        }
                        assertTrue(registry.registrations().isEmpty(),
                                "only the exact membership that won the race may be present, and it was retired above");

                        X4HostAdapter<X4HostFrames.WorldObject> successor = worldAdapter(
                                new FakeLookup(405L),
                                new BlendRenderer((snapshot, context) -> { }),
                                identity,
                                generation.session());
                        X4HostRegistrationReceipt<X4HostFrames.WorldObject> successorReceipt = registry.register(successor);
                        assertTrue(registry.retire(successorReceipt));
                    } finally {
                        start.countDown();
                        registry.close();
                    }
                }
            }
        } finally {
            generation.session().retire();
        }
    }

    @Test
    void staleReceiptCannotRetireAReplacementAfterTheAdapterSelfRevokesItsMembership() {
        var generation = X4LifecycleTestSupport.published(402L);
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        X4HostIdentity identity = identity("terminal-receipt-aba");
        try {
            X4HostAdapter<X4HostFrames.WorldObject> original = worldAdapter(
                    new FakeLookup(402L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
            X4HostRegistrationReceipt<X4HostFrames.WorldObject> stale = registry.register(original);
            original.close();
            assertTrue(registry.registrations().isEmpty(), "terminal adapters may not remain inspectable registry owners");

            X4HostAdapter<X4HostFrames.WorldObject> replacement = worldAdapter(
                    new FakeLookup(402L), new BlendRenderer((snapshot, context) -> { }), identity, generation.session());
            X4HostRegistrationReceipt<X4HostFrames.WorldObject> current = registry.register(replacement);
            assertFalse(registry.retire(stale), "a self-revoked receipt must remain stale across an ABA replacement");
            assertTrue(registry.retire(current));
        } finally {
            registry.close();
            generation.session().retire();
        }
    }

    @Test
    void submitPreservesThePrimaryVirtualMachineErrorWhenLastPinCleanupThrowsThreadDeath() {
        OutOfMemoryError rendererFailure = new OutOfMemoryError("renderer-primary");
        ThreadDeath cleanupFailure = new ThreadDeath();
        Throwable observed = submitWithLastPinCleanupFailure(rendererFailure, cleanupFailure);
        assertSame(rendererFailure, observed, "the selected fatal must retain the renderer primary object identity");
        assertEquals(1, observed.getSuppressed().length);
        assertSame(cleanupFailure, observed.getSuppressed()[0]);
    }

    @Test
    void submitPromotesCleanupFatalOverOrdinaryPrimaryAndRetainsThePrimaryAsSuppressed() {
        IllegalStateException rendererFailure = new IllegalStateException("renderer-primary");
        ThreadDeath cleanupFailure = new ThreadDeath();
        Throwable observed = submitWithLastPinCleanupFailure(rendererFailure, cleanupFailure);
        assertSame(cleanupFailure, observed, "a fatal cleanup failure must outrank an ordinary renderer failure");
        assertEquals(1, observed.getSuppressed().length);
        assertSame(rendererFailure, observed.getSuppressed()[0]);
    }

    @Test
    void failedPrepareKeepsItsPrimaryVirtualMachineErrorWhenPinCleanupThrowsThreadDeath() {
        var generation = X4LifecycleTestSupport.published(404L);
        OutOfMemoryError preparationFailure = new OutOfMemoryError("prepare-primary");
        ThreadDeath cleanupFailure = new ThreadDeath();
        generation.provider().onRetire(() -> throwUnchecked(cleanupFailure));
        X4HostIdentity identity = identity("prepare-failure");
        LateFailureMissingHandle handle = new LateFailureMissingHandle(generation.session(), preparationFailure);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F00044,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(new Bounds(Vec3.ZERO, Vec3.ZERO), true));
        ClientDiagnostic diagnostic = new ClientDiagnostic(
                ClientDiagnosticSeverity.ERROR,
                "X4-R4-PREPARE-MISSING",
                KEY.resourceId(),
                KEY.resourceId(),
                "x4-r4",
                "fixture missing model",
                "none");
        X4HostFrames.WorldObject frame = new X4HostFrames.WorldObject(
                X4SnapshotFrame.extractedMissing(
                        identity,
                        X4Transform.IDENTITY,
                        0x00F00044,
                        0,
                        0xFFFFFFFF,
                        true,
                        snapshot,
                        new X4MissingModelDiagnostic(404L, diagnostic)),
                identity.scope(),
                0L);
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(404L), new BlendRenderer((submitted, context) -> { }), identity, generation.session());
        adapter.freeze();

        Throwable observed = assertThrows(Throwable.class, () -> adapter.prepare(frame));
        assertSame(preparationFailure, observed);
        assertEquals(1, observed.getSuppressed().length);
        assertSame(cleanupFailure, observed.getSuppressed()[0]);
    }

    @Test
    void finalPinReleaseFailureUpgradesAnAlreadyClosingAdapterAndCompletesDrainExceptionally() throws Exception {
        var generation = X4LifecycleTestSupport.published(406L);
        OutOfMemoryError releaseFailure = new OutOfMemoryError("final-pin-release");
        generation.provider().onRetire(() -> throwUnchecked(releaseFailure));
        X4HostIdentity identity = identity("closing-final-pin-failure");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(406L), new BlendRenderer((submitted, context) -> { }), identity, generation.session());
        adapter.freeze();
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        AtomicReference<Throwable> publicCloseFailure = new AtomicReference<>();
        AtomicReference<Throwable> directCloseFailure = new AtomicReference<>();
        CountDownLatch publicCloseStarted = new CountDownLatch(1);
        CountDownLatch publicCloseFinished = new CountDownLatch(1);
        CountDownLatch directCloseStarted = new CountDownLatch(1);
        CountDownLatch directCloseFinished = new CountDownLatch(1);
        Thread publicClose = daemonWorker(
                "x4-r4-final-pin-public-close", adapter::close,
                publicCloseFailure, publicCloseStarted, publicCloseFinished);
        Thread directClose = daemonWorker(
                "x4-r4-final-pin-direct-close", prepared::close,
                directCloseFailure, directCloseStarted, directCloseFinished);
        var drain = adapter.drainCompletion().toCompletableFuture();
        try {
            generation.session().retire();
            publicClose.start();
            await(publicCloseStarted, "final-pin public close worker did not start");
            awaitState(adapter, X4HostLifecycleState.CLOSING, publicCloseFinished, publicCloseFailure);
            assertEquals(1L, publicCloseFinished.getCount(),
                    "public close must wait while the final prepared snapshot can still produce B");
            assertTrue(publicClose.isAlive(), "public close returned before the final prepared snapshot released");
            assertFalse(drain.isDone(), "close drain completed before the final prepared snapshot released");
            X4HostLeaseDiagnostics closingDiagnostics = adapter.leaseDiagnostics();
            assertTrue(closingDiagnostics.closeRequested());
            assertEquals("", closingDiagnostics.terminalFailureType(),
                    "provisional final-pin failure must not be published before the snapshot contributes");

            directClose.start();
            await(directCloseStarted, "final-pin direct close worker did not start");
            await(directCloseFinished, "final-pin direct close did not publish its provider failure");
            await(publicCloseFinished, "public close did not replay the final-pin provider failure");
            join(directClose);
            join(publicClose);
        } finally {
            try {
                prepared.close();
            } catch (Throwable ignored) {
                // The worker above deliberately exercises the one failing release.
            } finally {
                cleanupWorker(directClose);
                cleanupWorker(publicClose);
            }
        }
        assertFalse(directClose.isAlive(), "final-pin direct close worker did not terminate");
        assertFalse(publicClose.isAlive(), "final-pin public close worker did not terminate");
        assertSame(releaseFailure, directCloseFailure.get());
        assertSame(releaseFailure, publicCloseFailure.get());
        assertEquals(0, releaseFailure.getSuppressed().length);
        assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
        ExecutionException completionFailure = assertThrows(
                ExecutionException.class, () -> drain.get(5L, TimeUnit.SECONDS));
        assertSame(releaseFailure, completionFailure.getCause());
    }

    @Test
    void abortFailuresNeverPoisonTheRegistryAndPreserveTheRegistrationPrimary() {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        int reusableRegistrations = 0;
        try {
            for (int attempt = 0; attempt < 256; attempt++) {
                IllegalStateException registrationFailure = new IllegalStateException("registration-" + attempt);
                IllegalStateException abortFailure = new IllegalStateException("abort-" + attempt);
                RegistrationProbeAdapter aborting = new RegistrationProbeAdapter(
                        identity("abort-" + attempt), registrationFailure, abortFailure);
                try {
                    Throwable observed = assertThrows(Throwable.class, () -> registry.register(aborting));
                    assertSame(registrationFailure, observed,
                            "the ordinary registration failure remains primary when abort cleanup also fails");
                    assertEquals(1, observed.getSuppressed().length);
                    assertSame(abortFailure, observed.getSuppressed()[0]);
                } catch (AssertionError expectedUntilTheRepair) {
                    // Count actual registry reusability below so the red proof reports all 256 poisoned attempts.
                }

                RegistrationProbeAdapter healthy = new RegistrationProbeAdapter(identity("healthy-" + attempt), null, null);
                try {
                    X4HostRegistrationReceipt<X4HostFrames.WorldObject> receipt = registry.register(healthy);
                    reusableRegistrations++;
                    assertTrue(registry.retire(receipt));
                } catch (IllegalStateException expectedUntilTheRepair) {
                    // The final assertion records the deterministic poison count instead of aborting the loop early.
                }
            }
        } finally {
            registry.close();
        }
        assertEquals(256, reusableRegistrations,
                "an abort exception must not retain the registry's active-operation sentinel");
    }

    private static Throwable submitWithLastPinCleanupFailure(Throwable rendererFailure, Throwable cleanupFailure) {
        var generation = X4LifecycleTestSupport.published(403L);
        generation.provider().onRetire(() -> throwUnchecked(cleanupFailure));
        AtomicReference<X4PreparedSnapshot> preparedReference = new AtomicReference<>();
        X4HostIdentity identity = identity(rendererFailure instanceof OutOfMemoryError
                ? "submit-failure-oome"
                : "submit-failure-ordinary");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(403L),
                new BlendRenderer((snapshot, context) -> {
                    preparedReference.get().close();
                    throwUnchecked(rendererFailure);
                }),
                identity,
                generation.session());
        adapter.freeze();
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity));
        preparedReference.set(prepared);
        generation.session().retire();
        return assertThrows(Throwable.class, () -> adapter.submit(prepared, context()));
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

    private static X4HostFrames.WorldObject worldFrame(X4HostIdentity identity) {
        return new X4HostFrames.WorldObject(
                X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, 0x00F00041, 0, 0xFFFFFFFF, true),
                identity.scope(),
                0L);
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_r4:scope"), BlendResourceId.parse("x4_r4:host/" + local));
    }

    private static RenderSubmissionContext context() {
        SubmitNodeCollector collector = (SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
                X4HostAdapterFourthRepairContractsTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("X4 r4 renderer must not use collector method " + method.getName());
                });
        return new RenderSubmissionContext(new PoseStack(), collector);
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

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000L);
        assertFalse(thread.isAlive(), "X4 r4 lifecycle race must not deadlock");
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
            view = new ClientModelView(KEY, generation, false, handle, Optional.of(new ClientDiagnostic(
                    ClientDiagnosticSeverity.ERROR,
                    "X4-R4-MISSING",
                    KEY.resourceId(),
                    KEY.resourceId(),
                    "x4-r4",
                    "fixture missing model",
                    "none")));
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

    private static final class LateFailureMissingHandle implements ModelRenderHandle {
        private final com.liy.blendlib.spi.experimental.ProviderLifecycleSession session;
        private final OutOfMemoryError failure;
        private int modelKeyCalls;

        private LateFailureMissingHandle(
                com.liy.blendlib.spi.experimental.ProviderLifecycleSession session,
                OutOfMemoryError failure) {
            this.session = session;
            this.failure = failure;
        }

        @Override
        public BlendModelKey modelKey() {
            modelKeyCalls++;
            if (modelKeyCalls == 3) {
                session.retire();
                throw failure;
            }
            return KEY;
        }

        @Override
        public long generation() {
            return 404L;
        }

        @Override
        public Bounds bounds() {
            return new Bounds(Vec3.ZERO, Vec3.ZERO);
        }

        @Override
        public float unitsToBlocksScale() {
            return 1.0F;
        }

        @Override
        public List<PreparedRenderPrimitive> primitives() {
            return List.of();
        }

        @Override
        public Transform nodeTransform(int nodeIndex) {
            if (nodeIndex != 0) {
                throw new IndexOutOfBoundsException(nodeIndex);
            }
            return Transform.IDENTITY;
        }

        @Override
        public boolean missingModel() {
            return true;
        }
    }

    private static final class RegistrationProbeAdapter
            implements X4HostAdapter<X4HostFrames.WorldObject>, X4HostRegistrationLifecycle<X4HostFrames.WorldObject> {
        private final X4HostSpec<X4HostFrames.WorldObject> specification;
        private final Throwable commitFailure;
        private final Throwable abortFailure;
        private final CompletableFuture<X4HostLifecycleState> completion = new CompletableFuture<>();
        private X4HostLifecycleState state = X4HostLifecycleState.CONFIGURING;
        private boolean reservationActive;

        private RegistrationProbeAdapter(X4HostIdentity identity, Throwable commitFailure, Throwable abortFailure) {
            specification = new X4HostSpec<>(
                    X4HostKind.WORLD_OBJECT,
                    KEY,
                    identity,
                    new X4HostConfigurations.WorldObject(
                            identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));
            this.commitFailure = commitFailure;
            this.abortFailure = abortFailure;
        }

        @Override
        public X4HostSpec<X4HostFrames.WorldObject> configure() {
            return specification;
        }

        @Override
        public synchronized X4HostLifecycleState state() {
            return state;
        }

        @Override
        public synchronized X4HostLeaseDiagnostics leaseDiagnostics() {
            return new X4HostLeaseDiagnostics(state, 0L, 0, 0, false, state == X4HostLifecycleState.CLOSED, "");
        }

        @Override
        public CompletableFuture<X4HostLifecycleState> drainCompletion() {
            return completion;
        }

        @Override
        public synchronized void freeze() {
            requireConfiguring();
            state = X4HostLifecycleState.FROZEN;
        }

        @Override
        public synchronized X4HostRegistrationReservation<X4HostFrames.WorldObject> freezeAndReserveRegistration() {
            requireConfiguring();
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
                    if (commitFailure != null) {
                        throwUnchecked(commitFailure);
                    }
                    complete();
                }

                @Override
                public void abort() {
                    complete();
                    if (abortFailure != null) {
                        throwUnchecked(abortFailure);
                    }
                }

                private synchronized void complete() {
                    if (complete || !reservationActive) {
                        throw new IllegalStateException("probe reservation is no longer active");
                    }
                    reservationActive = false;
                    complete = true;
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
        public synchronized void retire() {
            state = X4HostLifecycleState.RETIRED;
            completion.complete(state);
        }

        @Override
        public synchronized void close() {
            state = X4HostLifecycleState.CLOSED;
            completion.complete(state);
        }

        private void requireConfiguring() {
            if (state != X4HostLifecycleState.CONFIGURING || reservationActive) {
                throw new IllegalStateException("probe requires a configuring lifecycle");
            }
        }
    }
}
