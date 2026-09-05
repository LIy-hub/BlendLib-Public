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
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

/** Precise r2 probes for X4 raw-snapshot, registry, close, and concurrent-prepare repairs. */
class X4HostAdapterSecondRepairContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_r2:models/host");

    @Test
    void preparedSnapshotsExposeNoPublicRawRenderSnapshotAndClosedLeasesCannotSubmit() throws IOException {
        assertFalse(Arrays.stream(X4PreparedSnapshot.class.getMethods())
                .anyMatch(method -> method.getReturnType() == ModelRenderSnapshot.class));
        assertFalse(Arrays.stream(X4PreparedSnapshot.class.getMethods())
                .anyMatch(method -> method.getName().equals("snapshot")));
        Path sourceFile = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src/client/java/com/liy/blendlib/fabric/client/host/X4PreparedSnapshot.java");
        String source = Files.readString(sourceFile);
        assertFalse(source.contains("public ModelRenderSnapshot"));
        assertTrue(source.contains("SubmissionHold"), "raw renderer input must remain tied to an internal submit hold");

        var generation = X4LifecycleTestSupport.published(301L);
        AtomicReference<ModelRenderSnapshot> submitted = new AtomicReference<>();
        X4HostIdentity identity = identity("raw-escape");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(301L), new BlendRenderer((snapshot, context) -> submitted.set(snapshot)), identity, generation.session());
        adapter.freeze();
        X4PreparedSnapshot prepared = adapter.prepare(worldFrame(identity, 0x00F00011));
        adapter.submit(prepared, context());
        assertEquals(0x00F00011, submitted.get().packedLight());
        prepared.close();
        assertTrue(prepared.closed());
        assertThrows(IllegalStateException.class, () -> adapter.submit(prepared, context()));
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
    }

    @Test
    void twoThreadsEnterPrepareTogetherRetainTwoSameGenerationLeasesAndDrainBackToFrozen() throws Exception {
        var generation = X4LifecycleTestSupport.published(302L);
        CountDownLatch enteredValidation = new CountDownLatch(2);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        BarrierWorldConfiguration configuration = new BarrierWorldConfiguration(enteredValidation, releaseValidation);
        AtomicReference<List<Integer>> submittedLights = new AtomicReference<>(new ArrayList<>());
        X4HostIdentity identity = identity("concurrent-prepare");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = new X4HostAdapterBuilder<
                        BarrierWorldConfiguration, X4HostFrames.WorldObject>(
                                X4HostKind.WORLD_OBJECT,
                                new FakeLookup(302L),
                                new BlendRenderer((snapshot, context) -> submittedLights.get().add(snapshot.packedLight())),
                                generation.session())
                .model(KEY)
                .identity(identity)
                .configuration(configuration)
                .build();
        adapter.freeze();

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<X4PreparedSnapshot> first = new AtomicReference<>();
        AtomicReference<X4PreparedSnapshot> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstThread = new Thread(() -> prepareAfter(start, adapter, worldFrame(identity, 0x00F00021), first, failure),
                "x4-r2-concurrent-prepare-first");
        Thread secondThread = new Thread(() -> prepareAfter(start, adapter, worldFrame(identity, 0x00F00022), second, failure),
                "x4-r2-concurrent-prepare-second");
        firstThread.start();
        secondThread.start();
        start.countDown();
        await(enteredValidation);
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state(), "both prepare calls reached the real concurrent validation barrier");
        releaseValidation.countDown();
        join(firstThread);
        join(secondThread);
        if (failure.get() != null) {
            throw new AssertionError("concurrent preparation failed", failure.get());
        }

        X4PreparedSnapshot firstLease = first.get();
        X4PreparedSnapshot secondLease = second.get();
        assertTrue(firstLease != null && secondLease != null);
        assertFalse(firstLease == secondLease);
        assertEquals(302L, firstLease.generation());
        assertEquals(302L, secondLease.generation());
        assertEquals(2, adapter.leaseDiagnostics().activeSnapshotLeases());
        adapter.submit(firstLease, context());
        adapter.submit(secondLease, context());
        assertEquals(List.of(0x00F00021, 0x00F00022), submittedLights.get());
        firstLease.close();
        secondLease.close();
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
        assertEquals(0, adapter.leaseDiagnostics().activeSnapshotLeases());
    }

    @Test
    void retirementAndDrainRemainSafeWhenAClosedLeaseRacesWithABlockedSubmit() throws Exception {
        var generation = X4LifecycleTestSupport.published(303L);
        CountDownLatch enteredRenderer = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        X4HostIdentity identity = identity("retire-drain-race");
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                new FakeLookup(303L),
                new BlendRenderer((snapshot, context) -> {
                    enteredRenderer.countDown();
                    await(releaseRenderer);
                }),
                identity,
                generation.session());
        adapter.freeze();
        X4PreparedSnapshot lease = adapter.prepare(worldFrame(identity, 0x00F00031));
        AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        AtomicReference<Throwable> retireFailure = new AtomicReference<>();
        CountDownLatch submitStarted = new CountDownLatch(1);
        CountDownLatch submitFinished = new CountDownLatch(1);
        CountDownLatch retireStarted = new CountDownLatch(1);
        CountDownLatch retireFinished = new CountDownLatch(1);
        Thread submitting = daemonWorker(
                "x4-r2-retire-submit", () -> adapter.submit(lease, context()),
                submitFailure, submitStarted, submitFinished);
        Thread retiring = daemonWorker(
                "x4-r2-retire-observer", adapter::retire, retireFailure, retireStarted, retireFinished);
        var drain = adapter.drainCompletion().toCompletableFuture();
        try {
            submitting.start();
            await(submitStarted);
            await(enteredRenderer);

            retiring.start();
            await(retireStarted);
            awaitState(adapter, X4HostLifecycleState.RETIRING, retireFinished, retireFailure);
            assertEquals(1L, retireFinished.getCount(),
                    "public retire must wait for the already-admitted submit and its snapshot");
            assertTrue(retiring.isAlive(), "public retire returned before the admitted submit drained");
            assertFalse(drain.isDone(), "retire drain completed while the admitted submit was still rendering");
            X4HostLeaseDiagnostics retiringDiagnostics = adapter.leaseDiagnostics();
            assertTrue(retiringDiagnostics.retirementRequested());
            assertEquals("", retiringDiagnostics.terminalFailureType());

            lease.close();
            assertThrows(IllegalStateException.class, () -> adapter.prepare(worldFrame(identity, 0x00F00032)));
            assertEquals(X4HostLifecycleState.RETIRING, adapter.state());
            releaseRenderer.countDown();

            await(submitFinished);
            await(retireFinished);
            join(submitting);
            join(retiring);
        } finally {
            releaseRenderer.countDown();
            try {
                lease.close();
            } finally {
                cleanupWorker(submitting);
                cleanupWorker(retiring);
            }
        }
        assertNull(submitFailure.get(), "admitted submit failed");
        assertNull(retireFailure.get(), "public retire failed");
        assertEquals(X4HostLifecycleState.RETIRED, drain.get(5L, TimeUnit.SECONDS));
        assertEquals(0, adapter.leaseDiagnostics().activeSnapshotLeases());
    }

    @Test
    void registryAcceptsOnlyAThisOperationFreezeAndRejectsAllOtherLifecycleStates() {
        for (X4HostLifecycleState initialState : X4HostLifecycleState.values()) {
            X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
            RegistryProbeAdapter adapter = new RegistryProbeAdapter(identity("state-" + initialState.name().toLowerCase()), initialState);
            try {
                if (initialState == X4HostLifecycleState.CONFIGURING) {
                    X4HostRegistrationReceipt<X4HostFrames.WorldObject> receipt = registry.register(adapter);
                    assertSame(adapter, receipt.adapter());
                    assertEquals(1, adapter.freezeCalls.get());
                    assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
                    assertEquals(1, registry.registrations().size());
                } else {
                    assertThrows(IllegalStateException.class, () -> registry.register(adapter), initialState.name());
                    assertEquals(0, adapter.freezeCalls.get(), initialState.name());
                    assertTrue(registry.registrations().isEmpty(), initialState.name());
                }
            } finally {
                registry.close();
            }
        }
    }

    @Test
    void registryRejectsAnAdapterWithoutItsInternalLifecycleReservation() {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        RegistryProbeAdapter delegate = new RegistryProbeAdapter(identity("unmanaged"), X4HostLifecycleState.CONFIGURING);
        X4HostAdapter<X4HostFrames.WorldObject> unmanaged = new UnmanagedRegistryAdapter(delegate);
        try {
            assertThrows(IllegalStateException.class, () -> registry.register(unmanaged));
            assertEquals(0, delegate.freezeCalls.get());
            assertEquals(X4HostLifecycleState.CONFIGURING, unmanaged.state());
            assertTrue(registry.registrations().isEmpty());
        } finally {
            registry.close();
        }
    }

    @Test
    void registryRevalidatesFreezeEpochAgainstStateRacesReplacementAndCallbackReentry() throws Exception {
        X4HostAdapterRegistry freezeRaceRegistry = new X4HostAdapterRegistry();
        CountDownLatch freezeEntered = new CountDownLatch(1);
        CountDownLatch releaseFreeze = new CountDownLatch(1);
        RegistryProbeAdapter racing = new RegistryProbeAdapter(identity("freeze-race"), X4HostLifecycleState.CONFIGURING);
        racing.onFreeze = () -> {
            freezeEntered.countDown();
            await(releaseFreeze);
        };
        AtomicReference<Throwable> raceFailure = new AtomicReference<>();
        Thread registering = new Thread(() -> {
            try {
                freezeRaceRegistry.register(racing);
            } catch (Throwable failure) {
                raceFailure.set(failure);
            }
        }, "x4-r2-registry-freeze-race");
        registering.start();
        await(freezeEntered);
        racing.state = X4HostLifecycleState.PREPARED;
        releaseFreeze.countDown();
        join(registering);
        assertTrue(raceFailure.get() instanceof IllegalStateException);
        assertTrue(freezeRaceRegistry.registrations().isEmpty());
        freezeRaceRegistry.close();

        X4HostAdapterRegistry replacementRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter first = new RegistryProbeAdapter(identity("replacement"), X4HostLifecycleState.CONFIGURING);
        X4HostRegistrationReceipt<X4HostFrames.WorldObject> firstReceipt = replacementRegistry.register(first);
        assertTrue(replacementRegistry.retire(firstReceipt));
        RegistryProbeAdapter replacement = new RegistryProbeAdapter(identity("replacement"), X4HostLifecycleState.CONFIGURING);
        X4HostRegistrationReceipt<X4HostFrames.WorldObject> replacementReceipt = replacementRegistry.register(replacement);
        assertFalse(replacementRegistry.retire(firstReceipt));
        assertSame(replacement, replacementReceipt.adapter());
        replacementRegistry.close();

        X4HostAdapterRegistry reentryRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter nested = new RegistryProbeAdapter(identity("nested"), X4HostLifecycleState.CONFIGURING);
        RegistryProbeAdapter outer = new RegistryProbeAdapter(identity("outer"), X4HostLifecycleState.CONFIGURING);
        outer.onFreeze = () -> assertThrows(IllegalStateException.class, () -> reentryRegistry.register(nested));
        assertThrows(IllegalStateException.class, () -> reentryRegistry.register(outer));
        assertTrue(reentryRegistry.registrations().isEmpty(), "ignored callback reentry must invalidate the outer operation");
        reentryRegistry.close();

        X4HostAdapterRegistry closeReentryRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter closeOuter = new RegistryProbeAdapter(identity("close-reentry"), X4HostLifecycleState.CONFIGURING);
        closeOuter.onFreeze = closeReentryRegistry::close;
        assertThrows(IllegalStateException.class, () -> closeReentryRegistry.register(closeOuter));
        assertTrue(closeReentryRegistry.registrations().isEmpty());
    }

    @Test
    void registryReservationPreventsCloseFromCrossingTheLifecycleCommitWindow() throws Exception {
        for (int attempt = 0; attempt < 64; attempt++) {
            X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
            RegistryProbeAdapter adapter = new RegistryProbeAdapter(
                    identity("reservation-window-" + attempt), X4HostLifecycleState.CONFIGURING);
            CountDownLatch commitEntered = new CountDownLatch(1);
            CountDownLatch releaseCommit = new CountDownLatch(1);
            CountDownLatch closeReturned = new CountDownLatch(1);
            adapter.onReservationCommit = () -> {
                commitEntered.countDown();
                await(releaseCommit);
            };
            AtomicReference<X4HostRegistrationReceipt<X4HostFrames.WorldObject>> receipt = new AtomicReference<>();
            AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
            Thread registrar = new Thread(() -> {
                try {
                    receipt.set(registry.register(adapter));
                } catch (Throwable failure) {
                    registrationFailure.set(failure);
                }
            }, "x4-r3-reservation-register-" + attempt);
            Thread closer = new Thread(() -> {
                try {
                    adapter.close();
                } finally {
                    closeReturned.countDown();
                }
            }, "x4-r3-reservation-close-" + attempt);
            try {
                registrar.start();
                await(commitEntered);
                closer.start();
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                        "close must not cross an active lifecycle reservation");
                assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
                releaseCommit.countDown();
                join(registrar);
                join(closer);

                assertEquals(1, adapter.reservationCommitCalls.get());
                assertEquals(0, adapter.reservationAbortCalls.get());
                assertEquals(1, adapter.closeCalls.get());
                assertEquals(X4HostLifecycleState.CLOSED, adapter.state());
                assertTrue(registry.registrations().isEmpty(),
                        "a close that wins after reservation release must revoke the exact membership before CLOSED");
                if (receipt.get() != null) {
                    assertNull(registrationFailure.get());
                    assertFalse(registry.retire(receipt.get()), "the receipt becomes stale when direct close wins later");
                } else {
                    assertTrue(registrationFailure.get() instanceof IllegalStateException,
                            "a close that revokes PENDING membership must make registration fail closed");
                }
            } finally {
                releaseCommit.countDown();
                if (closer.isAlive()) {
                    closer.interrupt();
                }
                if (registrar.isAlive()) {
                    registrar.interrupt();
                }
                joinIfAlive(closer);
                joinIfAlive(registrar);
                registry.close();
            }
        }
    }

    @Test
    void factoryManagedReservationSharesTheActualLifecycleBoundaryWithPrepareCloseAndRetire() throws Exception {
        var generation = X4LifecycleTestSupport.published(304L);
        X4HostIdentity closeIdentity = identity("factory-reservation-close");
        X4HostAdapter<X4HostFrames.WorldObject> closeAdapter = worldAdapter(
                new FakeLookup(304L), new BlendRenderer((snapshot, context) -> { }), closeIdentity, generation.session());
        X4HostRegistrationLifecycle<X4HostFrames.WorldObject> closeLifecycle = lifecycle(closeAdapter);
        X4HostRegistrationReservation<X4HostFrames.WorldObject> closeReservation = closeLifecycle.freezeAndReserveRegistration();
        assertEquals(X4HostLifecycleState.FROZEN, closeAdapter.state());
        assertThrows(IllegalStateException.class, () -> closeAdapter.prepare(worldFrame(closeIdentity, 0x00F00041)));
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            try {
                closeAdapter.close();
            } finally {
                closeReturned.countDown();
            }
        }, "x4-r3-factory-reservation-close");
        closer.start();
        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
        closeReservation.abort();
        join(closer);
        assertEquals(X4HostLifecycleState.CLOSED, closeAdapter.state());

        X4HostIdentity retireIdentity = identity("factory-reservation-retire");
        X4HostAdapter<X4HostFrames.WorldObject> retireAdapter = worldAdapter(
                new FakeLookup(304L), new BlendRenderer((snapshot, context) -> { }), retireIdentity, generation.session());
        X4HostRegistrationReservation<X4HostFrames.WorldObject> retireReservation = lifecycle(retireAdapter)
                .freezeAndReserveRegistration();
        CountDownLatch retireReturned = new CountDownLatch(1);
        Thread retiring = new Thread(() -> {
            try {
                retireAdapter.retire();
            } finally {
                retireReturned.countDown();
            }
        }, "x4-r3-factory-reservation-retire");
        retiring.start();
        assertFalse(retireReturned.await(100, TimeUnit.MILLISECONDS));
        retireReservation.abort();
        join(retiring);
        assertEquals(X4HostLifecycleState.RETIRED, retireAdapter.state());
    }

    @Test
    void registryReservationFailsClosedWhenCloseOrRetireWinsBeforeReservation() throws Exception {
        for (boolean retire : List.of(false, true)) {
            for (int attempt = 0; attempt < 32; attempt++) {
                X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
                RegistryProbeAdapter adapter = new RegistryProbeAdapter(
                        identity((retire ? "retire" : "close") + "-wins-" + attempt), X4HostLifecycleState.CONFIGURING);
                CountDownLatch beforeReservation = new CountDownLatch(1);
                CountDownLatch releaseReservation = new CountDownLatch(1);
                adapter.onBeforeReservation = () -> {
                    beforeReservation.countDown();
                    await(releaseReservation);
                };
                AtomicReference<X4HostRegistrationReceipt<X4HostFrames.WorldObject>> receipt = new AtomicReference<>();
                AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
                Thread registrar = new Thread(() -> {
                    try {
                        receipt.set(registry.register(adapter));
                    } catch (Throwable failure) {
                        registrationFailure.set(failure);
                    }
                }, "x4-r3-" + (retire ? "retire" : "close") + "-wins-register-" + attempt);
                Thread terminal = new Thread(retire ? adapter::retire : adapter::close,
                        "x4-r3-" + (retire ? "retire" : "close") + "-wins-terminal-" + attempt);
                try {
                    registrar.start();
                    await(beforeReservation);
                    terminal.start();
                    join(terminal);
                    assertEquals(retire ? X4HostLifecycleState.RETIRED : X4HostLifecycleState.CLOSED, adapter.state());
                    releaseReservation.countDown();
                    join(registrar);

                    assertNull(receipt.get());
                    assertTrue(registrationFailure.get() instanceof IllegalStateException);
                    assertEquals(0, adapter.reservationCommitCalls.get());
                    assertEquals(0, adapter.reservationAbortCalls.get());
                    assertTrue(registry.registrations().isEmpty());
                } finally {
                    releaseReservation.countDown();
                    if (terminal.isAlive()) {
                        terminal.interrupt();
                    }
                    if (registrar.isAlive()) {
                        registrar.interrupt();
                    }
                    joinIfAlive(terminal);
                    joinIfAlive(registrar);
                    registry.close();
                }
            }
        }
    }

    @Test
    void registryReservationAbortsOnDuplicateRegistryCloseCommitFailureAndOwnerReentry() throws Exception {
        X4HostAdapterRegistry duplicateRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter incumbent = new RegistryProbeAdapter(identity("reservation-duplicate"), X4HostLifecycleState.CONFIGURING);
        RegistryProbeAdapter duplicate = new RegistryProbeAdapter(identity("reservation-duplicate"), X4HostLifecycleState.CONFIGURING);
        duplicateRegistry.register(incumbent);
        assertThrows(IllegalStateException.class, () -> duplicateRegistry.register(duplicate));
        assertEquals(0, duplicate.reservationCommitCalls.get());
        assertEquals(1, duplicate.reservationAbortCalls.get());
        assertEquals(X4HostLifecycleState.FROZEN, duplicate.state());
        assertEquals(1, duplicateRegistry.registrations().size());
        duplicateRegistry.close();

        X4HostAdapterRegistry closingRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter closing = new RegistryProbeAdapter(identity("reservation-registry-close"), X4HostLifecycleState.CONFIGURING);
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch releaseReservation = new CountDownLatch(1);
        closing.onReservationAcquired = () -> {
            reserved.countDown();
            await(releaseReservation);
        };
        AtomicReference<Throwable> closingFailure = new AtomicReference<>();
        Thread closingRegistrar = new Thread(() -> {
            try {
                closingRegistry.register(closing);
            } catch (Throwable failure) {
                closingFailure.set(failure);
            }
        }, "x4-r3-registry-close-during-reservation");
        try {
            closingRegistrar.start();
            await(reserved);
            closingRegistry.close();
            releaseReservation.countDown();
            join(closingRegistrar);
            assertTrue(closingFailure.get() instanceof IllegalStateException);
            assertEquals(0, closing.reservationCommitCalls.get());
            assertEquals(1, closing.reservationAbortCalls.get());
            assertTrue(closingRegistry.registrations().isEmpty());
        } finally {
            releaseReservation.countDown();
            if (closingRegistrar.isAlive()) {
                closingRegistrar.interrupt();
            }
            joinIfAlive(closingRegistrar);
        }

        X4HostAdapterRegistry failingRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter failing = new RegistryProbeAdapter(identity("reservation-commit-failure"), X4HostLifecycleState.CONFIGURING);
        IllegalStateException commitFailure = new IllegalStateException("reservation-commit-failure");
        failing.onReservationCommit = () -> {
            throw commitFailure;
        };
        assertSame(commitFailure, assertThrows(IllegalStateException.class, () -> failingRegistry.register(failing)));
        assertEquals(1, failing.reservationCommitCalls.get());
        assertEquals(1, failing.reservationAbortCalls.get());
        assertEquals(X4HostLifecycleState.FROZEN, failing.state());
        assertTrue(failingRegistry.registrations().isEmpty());
        failingRegistry.close();

        X4HostAdapterRegistry reentryRegistry = new X4HostAdapterRegistry();
        RegistryProbeAdapter reentrant = new RegistryProbeAdapter(identity("reservation-owner-reentry"), X4HostLifecycleState.CONFIGURING);
        reentrant.onReservationCommit = reentrant::close;
        assertThrows(IllegalStateException.class, () -> reentryRegistry.register(reentrant));
        assertEquals(1, reentrant.reservationCommitCalls.get());
        assertEquals(1, reentrant.reservationAbortCalls.get());
        assertTrue(reentryRegistry.registrations().isEmpty());
        reentryRegistry.close();
    }

    @Test
    void interruptedLifecycleCloseDoesNotCrossAnActiveReservation() throws Exception {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        RegistryProbeAdapter adapter = new RegistryProbeAdapter(identity("reservation-interrupt"), X4HostLifecycleState.CONFIGURING);
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        adapter.onReservationCommit = () -> {
            commitEntered.countDown();
            await(releaseCommit);
        };
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registrar = new Thread(() -> {
            try {
                registry.register(adapter);
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        }, "x4-r3-reservation-interrupt-register");
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread interruptedCloser = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "x4-r3-reservation-interrupt-close");
        try {
            registrar.start();
            await(commitEntered);
            interruptedCloser.start();
            awaitThreadWaiting(interruptedCloser);
            interruptedCloser.interrupt();
            join(interruptedCloser);
            assertTrue(closeFailure.get() instanceof IllegalStateException);
            assertTrue(interruptPreserved.get());
            assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
            releaseCommit.countDown();
            join(registrar);
            assertNull(registrationFailure.get());
            assertEquals(1, registry.registrations().size());
        } finally {
            releaseCommit.countDown();
            if (interruptedCloser.isAlive()) {
                interruptedCloser.interrupt();
            }
            if (registrar.isAlive()) {
                registrar.interrupt();
            }
            joinIfAlive(interruptedCloser);
            joinIfAlive(registrar);
            registry.close();
        }
    }

    @Test
    void concurrentRegistryCloseSharesTheSameAggregateAndAttemptsEveryAdapter() throws Exception {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        IllegalStateException firstFailure = new IllegalStateException("first-close");
        IllegalStateException secondFailure = new IllegalStateException("second-close");
        RegistryProbeAdapter first = new RegistryProbeAdapter(identity("close-first"), X4HostLifecycleState.CONFIGURING);
        first.onClose = () -> {
            firstEntered.countDown();
            await(releaseFirst);
            throw firstFailure;
        };
        RegistryProbeAdapter second = new RegistryProbeAdapter(identity("close-second"), X4HostLifecycleState.CONFIGURING);
        second.onClose = () -> {
            throw secondFailure;
        };
        registry.register(first);
        registry.register(second);

        AtomicReference<Throwable> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> secondResult = new AtomicReference<>();
        CountDownLatch secondReturned = new CountDownLatch(1);
        Thread firstCloser = new Thread(() -> closeCapturing(registry, firstResult, null), "x4-r2-registry-close-first");
        Thread secondCloser = new Thread(
                () -> closeCapturing(registry, secondResult, secondReturned), "x4-r2-registry-close-second");
        firstCloser.start();
        await(firstEntered);
        secondCloser.start();
        assertFalse(secondReturned.await(100, TimeUnit.MILLISECONDS), "second close must join the owner completion");
        releaseFirst.countDown();
        join(firstCloser);
        join(secondCloser);

        assertSame(firstResult.get(), secondResult.get());
        IllegalStateException aggregate = assertThrows(IllegalStateException.class, () -> throwUnchecked(firstResult.get()));
        assertSame(firstFailure, aggregate.getCause());
        assertEquals(1, aggregate.getCause().getSuppressed().length);
        assertSame(secondFailure, aggregate.getCause().getSuppressed()[0]);
        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
    }

    @Test
    void concurrentRegistryCloseRethrowsTheSameFatalAndDefinesInterruptedWaiterBehavior() throws Exception {
        X4HostAdapterRegistry fatalRegistry = new X4HostAdapterRegistry();
        CountDownLatch fatalEntered = new CountDownLatch(1);
        CountDownLatch releaseFatal = new CountDownLatch(1);
        OutOfMemoryError fatal = new OutOfMemoryError("registry-close-fatal");
        IllegalStateException suppressed = new IllegalStateException("after-fatal");
        RegistryProbeAdapter fatalAdapter = new RegistryProbeAdapter(identity("fatal-close"), X4HostLifecycleState.CONFIGURING);
        fatalAdapter.onClose = () -> {
            fatalEntered.countDown();
            await(releaseFatal);
            throw fatal;
        };
        RegistryProbeAdapter afterFatal = new RegistryProbeAdapter(identity("after-fatal"), X4HostLifecycleState.CONFIGURING);
        afterFatal.onClose = () -> {
            throw suppressed;
        };
        fatalRegistry.register(fatalAdapter);
        fatalRegistry.register(afterFatal);
        AtomicReference<Throwable> ownerResult = new AtomicReference<>();
        AtomicReference<Throwable> observerResult = new AtomicReference<>();
        Thread owner = new Thread(() -> closeCapturing(fatalRegistry, ownerResult, null), "x4-r2-registry-fatal-owner");
        Thread observer = new Thread(() -> closeCapturing(fatalRegistry, observerResult, null), "x4-r2-registry-fatal-observer");
        owner.start();
        await(fatalEntered);
        observer.start();
        releaseFatal.countDown();
        join(owner);
        join(observer);
        assertSame(fatal, ownerResult.get());
        assertSame(fatal, observerResult.get());
        assertEquals(1, fatal.getSuppressed().length);
        assertSame(suppressed, fatal.getSuppressed()[0]);
        assertEquals(1, afterFatal.closeCalls.get());

        X4HostAdapterRegistry interruptedRegistry = new X4HostAdapterRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RegistryProbeAdapter blocking = new RegistryProbeAdapter(identity("interrupt-blocking"), X4HostLifecycleState.CONFIGURING);
        blocking.onClose = () -> {
            entered.countDown();
            await(release);
        };
        interruptedRegistry.register(blocking);
        Thread closingOwner = new Thread(interruptedRegistry::close, "x4-r2-registry-interrupt-owner");
        AtomicReference<Throwable> interruptedResult = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CountDownLatch observerEntered = new CountDownLatch(1);
        Thread interruptedObserver = new Thread(() -> {
            observerEntered.countDown();
            try {
                interruptedRegistry.close();
            } catch (Throwable failure) {
                interruptedResult.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "x4-r2-registry-interrupted-observer");
        closingOwner.start();
        await(entered);
        interruptedObserver.start();
        await(observerEntered);
        awaitThreadWaiting(interruptedObserver);
        interruptedObserver.interrupt();
        join(interruptedObserver);
        assertTrue(interruptedResult.get() instanceof IllegalStateException);
        assertTrue(interruptPreserved.get(), "interrupted close observers must restore their interrupt status");
        release.countDown();
        join(closingOwner);
    }

    @Test
    void registryCloseReentryFromAnAdapterDoesNotDeadlockTheCloseOwner() {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        RegistryProbeAdapter reentrant = new RegistryProbeAdapter(identity("close-owner-reentry"), X4HostLifecycleState.CONFIGURING);
        reentrant.onClose = registry::close;
        registry.register(reentrant);
        registry.close();
        assertEquals(1, reentrant.closeCalls.get());
    }

    private static void prepareAfter(
            CountDownLatch start,
            X4HostAdapter<X4HostFrames.WorldObject> adapter,
            X4HostFrames.WorldObject frame,
            AtomicReference<X4PreparedSnapshot> result,
            AtomicReference<Throwable> failure) {
        try {
            await(start);
            result.set(adapter.prepare(frame));
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static void closeCapturing(
            X4HostAdapterRegistry registry, AtomicReference<Throwable> result, CountDownLatch returned) {
        try {
            registry.close();
        } catch (Throwable failure) {
            result.set(failure);
        } finally {
            if (returned != null) {
                returned.countDown();
            }
        }
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

    @SuppressWarnings("unchecked")
    private static X4HostRegistrationLifecycle<X4HostFrames.WorldObject> lifecycle(
            X4HostAdapter<X4HostFrames.WorldObject> adapter) {
        assertTrue(adapter instanceof X4HostRegistrationLifecycle<?>);
        return (X4HostRegistrationLifecycle<X4HostFrames.WorldObject>) adapter;
    }

    private static X4HostFrames.WorldObject worldFrame(X4HostIdentity identity, int light) {
        return new X4HostFrames.WorldObject(
                X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, light, 0, 0xFFFFFFFF, true),
                identity.scope(),
                0L);
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_r2:scope"), BlendResourceId.parse("x4_r2:host/" + local));
    }

    private static RenderSubmissionContext context() {
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                X4HostAdapterSecondRepairContractsTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("X4 r2 renderer must not use collector method " + method.getName());
                });
        return new RenderSubmissionContext(new PoseStack(), collector);
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

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for X4 r2 barrier");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
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
        thread.join(Duration.ofSeconds(5).toMillis());
        assertFalse(thread.isAlive(), "X4 r2 concurrent operation did not finish");
    }

    private static void joinIfAlive(Thread thread) throws InterruptedException {
        if (thread.isAlive()) {
            join(thread);
        }
    }

    private static void cleanupWorker(Thread worker) {
        if (!worker.isAlive()) return;
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
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
        throw new AssertionError("close observer never waited for the shared close completion");
    }

    private static final class BarrierWorldConfiguration implements X4HostConfiguration<X4HostFrames.WorldObject> {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BarrierWorldConfiguration(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public X4HostKind hostKind() {
            return X4HostKind.WORLD_OBJECT;
        }

        @Override
        public float maximumBoundsExtent() {
            return X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT;
        }

        @Override
        public void validateFrame(X4HostSpec<X4HostFrames.WorldObject> specification, X4HostFrames.WorldObject frame) {
            assertEquals(specification.identity().scope(), frame.worldId());
            entered.countDown();
            await(release);
        }

        @Override
        public void validatePreparedHandle(ModelRenderHandle handle) {
            X4HostConfiguration.super.validatePreparedHandle(handle);
        }
    }

    private static final class FakeLookup implements ClientModelLookup {
        private final ClientModelView view;

        private FakeLookup(long generation) {
            MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, generation);
            view = new ClientModelView(KEY, generation, false, handle, Optional.of(new ClientDiagnostic(
                    ClientDiagnosticSeverity.ERROR,
                    "X4-R2-MISSING",
                    KEY.resourceId(),
                    KEY.resourceId(),
                    "x4-r2",
                    "fixture missing model",
                    "none")));
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

    private static final class RegistryProbeAdapter
            implements X4HostAdapter<X4HostFrames.WorldObject>, X4HostRegistrationLifecycle<X4HostFrames.WorldObject> {
        private final Object lifecycleMonitor = new Object();
        private final X4HostSpec<X4HostFrames.WorldObject> specification;
        private final CompletableFuture<X4HostLifecycleState> completion = new CompletableFuture<>();
        private final AtomicInteger freezeCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger reservationCommitCalls = new AtomicInteger();
        private final AtomicInteger reservationAbortCalls = new AtomicInteger();
        private volatile X4HostLifecycleState state;
        private ProbeReservation activeReservation;
        private X4HostRegistrationMembership membership;
        private volatile Runnable onBeforeReservation = () -> { };
        private volatile Runnable onFreeze = () -> { };
        private volatile Runnable onReservationAcquired = () -> { };
        private volatile Runnable onReservationCommit = () -> { };
        private volatile Runnable onClose = () -> { };

        private RegistryProbeAdapter(X4HostIdentity identity, X4HostLifecycleState initialState) {
            specification = new X4HostSpec<>(
                    X4HostKind.WORLD_OBJECT,
                    KEY,
                    identity,
                    new X4HostConfigurations.WorldObject(
                            identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));
            state = initialState;
            if (initialState == X4HostLifecycleState.RETIRED || initialState == X4HostLifecycleState.CLOSED) {
                completion.complete(initialState);
            }
        }

        @Override
        public X4HostSpec<X4HostFrames.WorldObject> configure() {
            return specification;
        }

        @Override
        public X4HostLifecycleState state() {
            synchronized (lifecycleMonitor) {
                return state;
            }
        }

        @Override
        public X4HostLeaseDiagnostics leaseDiagnostics() {
            synchronized (lifecycleMonitor) {
                return new X4HostLeaseDiagnostics(
                        state, 0L, 0, 0, false, state == X4HostLifecycleState.CLOSED, "");
            }
        }

        @Override
        public CompletableFuture<X4HostLifecycleState> drainCompletion() {
            return completion;
        }

        @Override
        public void freeze() {
            synchronized (lifecycleMonitor) {
                requireConfiguringLocked();
                freezeCalls.incrementAndGet();
            }
            onFreeze.run();
            synchronized (lifecycleMonitor) {
                requireConfiguringLocked();
                state = X4HostLifecycleState.FROZEN;
            }
        }

        @Override
        public X4HostRegistrationReservation<X4HostFrames.WorldObject> freezeAndReserveRegistration() {
            onBeforeReservation.run();
            synchronized (lifecycleMonitor) {
                requireConfiguringLocked();
                freezeCalls.incrementAndGet();
            }
            onFreeze.run();
            ProbeReservation reservation;
            synchronized (lifecycleMonitor) {
                requireConfiguringLocked();
                reservation = new ProbeReservation(Thread.currentThread());
                state = X4HostLifecycleState.FROZEN;
                activeReservation = reservation;
            }
            // This test-only hook runs after the lifecycle reservation exists but before the
            // registry receives it, exercising registry-close abort without a stale state read.
            onReservationAcquired.run();
            return reservation;
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
            for (;;) {
                ProbeReservation reservation;
                X4HostRegistrationMembership membershipToRevoke;
                synchronized (lifecycleMonitor) {
                    reservation = activeReservation;
                    if (reservation == null) {
                        membershipToRevoke = membership;
                    } else {
                        membershipToRevoke = null;
                        reservation.requireNonOwner("retire");
                    }
                }
                if (reservation != null) {
                    awaitReservation(reservation, "retire");
                    continue;
                }
                if (membershipToRevoke != null) {
                    membershipToRevoke.revoke();
                }
                synchronized (lifecycleMonitor) {
                    if (membership == membershipToRevoke) {
                        membership = null;
                    }
                    state = X4HostLifecycleState.RETIRED;
                    completion.complete(state);
                    return;
                }
            }
        }

        @Override
        public void close() {
            for (;;) {
                ProbeReservation reservation;
                X4HostRegistrationMembership membershipToRevoke;
                synchronized (lifecycleMonitor) {
                    reservation = activeReservation;
                    if (reservation == null) {
                        if (state == X4HostLifecycleState.CLOSED) {
                            return;
                        }
                        membershipToRevoke = membership;
                    } else {
                        membershipToRevoke = null;
                        reservation.requireNonOwner("close");
                    }
                }
                if (reservation != null) {
                    awaitReservation(reservation, "close");
                    continue;
                }
                if (membershipToRevoke != null) {
                    membershipToRevoke.revoke();
                }
                synchronized (lifecycleMonitor) {
                    if (state == X4HostLifecycleState.CLOSED) {
                        return;
                    }
                    if (membership == membershipToRevoke) {
                        membership = null;
                    }
                    closeCalls.incrementAndGet();
                    state = X4HostLifecycleState.CLOSED;
                    completion.complete(state);
                    break;
                }
            }
            onClose.run();
        }

        private void requireConfiguringLocked() {
            if (state != X4HostLifecycleState.CONFIGURING || activeReservation != null) {
                throw new IllegalStateException("probe can freeze only from configuring");
            }
        }

        private void awaitReservation(ProbeReservation reservation, String transition) {
            try {
                reservation.awaitCompletion();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted probe " + transition + " while registration was reserved", exception);
            }
        }

        private final class ProbeReservation implements X4HostRegistrationReservation<X4HostFrames.WorldObject> {
            private final Thread ownerThread;
            private final CountDownLatch completed = new CountDownLatch(1);
            private boolean complete;

            private ProbeReservation(Thread ownerThread) {
                this.ownerThread = ownerThread;
            }

            @Override
            public X4HostSpec<X4HostFrames.WorldObject> specification() {
                return specification;
            }

            @Override
            public void commit(X4HostRegistrationMembership membership) {
                reservationCommitCalls.incrementAndGet();
                onReservationCommit.run();
                synchronized (lifecycleMonitor) {
                    requireCurrentLocked();
                    if (state != X4HostLifecycleState.FROZEN) {
                        throw new IllegalStateException("probe reservation lost frozen state");
                    }
                    RegistryProbeAdapter.this.membership = Objects.requireNonNull(membership, "membership");
                    completeLocked();
                }
            }

            @Override
            public void abort() {
                reservationAbortCalls.incrementAndGet();
                synchronized (lifecycleMonitor) {
                    requireCurrentLocked();
                    completeLocked();
                }
            }

            private void requireCurrentLocked() {
                if (activeReservation != this || complete) {
                    throw new IllegalStateException("probe registration reservation is no longer active");
                }
            }

            private void completeLocked() {
                activeReservation = null;
                complete = true;
                completed.countDown();
            }

            private void requireNonOwner(String transition) {
                if (ownerThread == Thread.currentThread()) {
                    throw new IllegalStateException("probe owner cannot " + transition + " while registration is reserved");
                }
            }

            private void awaitCompletion() throws InterruptedException {
                completed.await();
            }
        }
    }

    private static final class UnmanagedRegistryAdapter implements X4HostAdapter<X4HostFrames.WorldObject> {
        private final RegistryProbeAdapter delegate;

        private UnmanagedRegistryAdapter(RegistryProbeAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public X4HostSpec<X4HostFrames.WorldObject> configure() {
            return delegate.configure();
        }

        @Override
        public X4HostLifecycleState state() {
            return delegate.state();
        }

        @Override
        public X4HostLeaseDiagnostics leaseDiagnostics() {
            return delegate.leaseDiagnostics();
        }

        @Override
        public CompletableFuture<X4HostLifecycleState> drainCompletion() {
            return delegate.drainCompletion();
        }

        @Override
        public void freeze() {
            delegate.freeze();
        }

        @Override
        public X4PreparedSnapshot prepare(X4HostFrames.WorldObject frame) {
            return delegate.prepare(frame);
        }

        @Override
        public void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context) {
            delegate.submit(prepared, context);
        }

        @Override
        public void retire() {
            delegate.retire();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
