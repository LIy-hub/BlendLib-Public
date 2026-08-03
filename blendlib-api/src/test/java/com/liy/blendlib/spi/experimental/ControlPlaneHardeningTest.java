package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendApiDiagnostic;
import com.liy.blendlib.api.BlendApiDiagnosticCode;
import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendRegistrationException;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ControlPlaneHardeningTest {
    private static final BlendResourceId CAPABILITY = BlendResourceId.parse("example:capability/hardened");
    private static final CapabilityVersionRange VERSION_RANGE = new CapabilityVersionRange(
            new CapabilityVersion(1, 0, 0), new CapabilityVersion(2, 0, 0));
    private static final BlendModelKey MODEL = BlendModelKey.parse("example:hardened_model");
    private static final BlendAnimationKey ANIMATION = BlendAnimationKey.parse("example:hardened_animation");

    @Test
    void fatalAdapterRegistrationTerminallyDetachesAndClosesTheAdapterBeforeRethrow() {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider adapter = new ReentrantProvider("example:fatal_adapter_register");
        control.install(adapter);
        control.register(specification("accepted-before-fatal"));
        adapter.registerAction = ignored -> {
            throw new FatalLifecycleError();
        };

        assertThrows(FatalLifecycleError.class,
                () -> control.register(specification("fatal-registration")));

        assertTrue(control.adapterId().isEmpty());
        assertEquals(0, control.registrationCount());
        assertEquals(1, adapter.closeCount.get());
        BlendRegistrationException unavailable = assertThrows(
                BlendRegistrationException.class,
                () -> control.register(specification("after-fatal")));
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_UNAVAILABLE,
                unavailable.diagnostic().code());
        assertEquals(2, adapter.registerCount.get());
    }

    @RepeatedTest(10)
    void concurrentRetireObserverCannotReportSuccessBeforeTheActiveRetirementCompletes() throws Exception {
        ReentrantProvider provider = new ReentrantProvider("example:concurrent_retire_result");
        CountDownLatch retireEntered = new CountDownLatch(1);
        CountDownLatch releaseRetire = new CountDownLatch(1);
        provider.retireAction = ignored -> {
            retireEntered.countDown();
            await(releaseRetire);
            throw new HostileAssertionError();
        };
        ProviderLifecycleSession session = sessionFor(20_018L, provider);
        publish(session);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ProviderLifecycleResult> first = executor.submit(session::retire);
            assertTrue(retireEntered.await(2, TimeUnit.SECONDS));
            Future<ProviderLifecycleResult> observer = executor.submit(session::retire);
            try {
                assertThrows(TimeoutException.class,
                        () -> observer.get(200, TimeUnit.MILLISECONDS));
            } finally {
                releaseRetire.countDown();
            }
            ProviderLifecycleResult firstResult = first.get(2, TimeUnit.SECONDS);
            ProviderLifecycleResult observerResult = observer.get(2, TimeUnit.SECONDS);
            assertFalse(firstResult.successful());
            assertEquals(firstResult, observerResult);
        }
        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertEquals(1, provider.retireCount.get());
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void discoverTraversesCallerCollectionOutsideTheRegistryMonitorAndRejectsNestedDiscovery() {
        CapabilityRegistry registry = new CapabilityRegistry();
        ReentrantProvider provider = new ReentrantProvider("example:nested_discover_collection");
        registry.register(provider);
        CapabilityRequest request = CapabilityRequest.required(CAPABILITY, VERSION_RANGE);
        AtomicReference<CapabilityNegotiationException> nestedFailure = new AtomicReference<>();
        AtomicInteger iteratorCalls = new AtomicInteger();
        Collection<CapabilityRequest> requests = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityRequest> iterator() {
                iteratorCalls.incrementAndGet();
                nestedFailure.set(assertThrows(
                        CapabilityNegotiationException.class,
                        () -> registry.discover(List.of(request))));
                return List.of(request).iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };

        List<CapabilityOffer> discovered = registry.discover(requests);

        assertEquals(1, iteratorCalls.get());
        assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                nestedFailure.get().diagnostic().code());
        assertEquals(List.of(provider.offers().iterator().next()), discovered);
        assertFalse(registry.isFrozen());
    }

    @Test
    @SuppressWarnings("removal")
    void everyFatalAdapterEntryPointTerminalizesBeforeRethrowingTheOriginalFailure() {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider identity = new ReentrantProvider("example:fatal_adapter_identity");
        FatalLifecycleError identityFailure = new FatalLifecycleError();
        identity.providerIdAction = () -> {
            throw identityFailure;
        };

        assertEquals(identityFailure, assertThrows(FatalLifecycleError.class, () -> control.install(identity)));
        assertTrue(control.adapterId().isEmpty());
        assertEquals(1, identity.providerIdCount.get());
        assertEquals(1, identity.closeCount.get());
        assertThrows(BlendRegistrationException.class, () -> control.install(identity));
        assertEquals(1, identity.providerIdCount.get());
        assertEquals(1, identity.closeCount.get());

        ReentrantProvider closing = new ReentrantProvider("example:fatal_adapter_uninstall");
        FatalLifecycleError closeFailure = new FatalLifecycleError();
        AtomicReference<Boolean> detachedDuringClose = new AtomicReference<>(false);
        closing.closeAction = () -> {
            detachedDuringClose.set(control.adapterId().isEmpty() && control.registrationCount() == 0);
            throw closeFailure;
        };
        control.install(closing);
        assertEquals(closeFailure, assertThrows(FatalLifecycleError.class, control::uninstall));
        assertTrue(control.adapterId().isEmpty());
        assertEquals(0, control.registrationCount());
        assertEquals(1, closing.closeCount.get());
        assertTrue(detachedDuringClose.get());

        ReentrantProvider threadDeath = new ReentrantProvider("example:thread_death_adapter");
        control.install(threadDeath);
        threadDeath.registerAction = ignored -> {
            throw new ThreadDeath();
        };
        assertThrows(ThreadDeath.class, () -> control.register(specification("thread-death")));
        assertTrue(control.adapterId().isEmpty());
        assertEquals(1, threadDeath.closeCount.get());
    }

    @Test
    void fatalAdapterRegistrationWinsOverConcurrentUninstallAndSecondaryCloseFailure() throws Exception {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider adapter = new ReentrantProvider("example:fatal_adapter_race");
        CountDownLatch registerEntered = new CountDownLatch(1);
        CountDownLatch releaseRegister = new CountDownLatch(1);
        FatalLifecycleError fatal = new FatalLifecycleError();
        adapter.registerAction = ignored -> {
            registerEntered.countDown();
            await(releaseRegister);
            throw fatal;
        };
        adapter.closeAction = () -> {
            throw new HostileAssertionError();
        };
        control.install(adapter);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationReceipt> registration = executor.submit(
                    () -> control.register(specification("fatal-race")));
            assertTrue(registerEntered.await(2, TimeUnit.SECONDS));
            Future<BlendApiDiagnosticCode> uninstall = executor.submit(() -> assertThrows(
                    BlendRegistrationException.class, control::uninstall).diagnostic().code());
            assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                    uninstall.get(2, TimeUnit.SECONDS));
            releaseRegister.countDown();
            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> registration.get(2, TimeUnit.SECONDS));
            assertEquals(fatal, failure.getCause());
        }

        assertTrue(control.adapterId().isEmpty());
        assertEquals(0, control.registrationCount());
        assertEquals(1, adapter.registerCount.get());
        assertEquals(1, adapter.closeCount.get());
    }

    @Test
    void concurrentRetireObserversShareFatalCompletionAndCloseExactlyOnce() throws Exception {
        ReentrantProvider provider = new ReentrantProvider("example:concurrent_fatal_retire");
        CountDownLatch retireEntered = new CountDownLatch(1);
        CountDownLatch releaseRetire = new CountDownLatch(1);
        FatalLifecycleError fatal = new FatalLifecycleError();
        provider.retireAction = ignored -> {
            retireEntered.countDown();
            await(releaseRetire);
            throw fatal;
        };
        ProviderLifecycleSession session = sessionFor(20_019L, provider);
        publish(session);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<ProviderLifecycleResult> first = executor.submit(session::retire);
            assertTrue(retireEntered.await(2, TimeUnit.SECONDS));
            Future<ProviderLifecycleResult> second = executor.submit(session::retire);
            Future<Void> close = executor.submit(() -> {
                session.close();
                return null;
            });
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));
            releaseRetire.countDown();
            for (Future<?> observer : List.of(first, second, close)) {
                java.util.concurrent.ExecutionException failure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> observer.get(2, TimeUnit.SECONDS));
                assertEquals(fatal, failure.getCause());
            }
        }

        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertFalse(session.retire().successful());
        assertEquals(1, provider.retireCount.get());
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void pinDrainRetirementSharesCompletionWithConcurrentCloseAndAllowsInterruptedObserver() throws Exception {
        ReentrantProvider provider = new ReentrantProvider("example:pinned_retire_observers");
        CountDownLatch retireEntered = new CountDownLatch(1);
        CountDownLatch releaseRetire = new CountDownLatch(1);
        provider.retireAction = ignored -> {
            retireEntered.countDown();
            await(releaseRetire);
        };
        ProviderLifecycleSession session = sessionFor(20_020L, provider);
        publish(session);
        ProviderLease lease = session.pin();
        assertTrue(session.retire().successful());
        assertEquals(0, provider.retireCount.get());

        AtomicReference<CapabilityNegotiationException> interruption = new AtomicReference<>();
        AtomicReference<Boolean> interruptedFlag = new AtomicReference<>(false);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> drain = executor.submit(lease::close);
            assertTrue(retireEntered.await(2, TimeUnit.SECONDS));
            Future<Void> close = executor.submit(() -> {
                session.close();
                return null;
            });
            assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));

            Thread observer = Thread.ofPlatform().start(() -> {
                try {
                    session.retire();
                } catch (CapabilityNegotiationException exception) {
                    interruption.set(exception);
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (observer.getState() != Thread.State.WAITING
                    && observer.getState() != Thread.State.TIMED_WAITING
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            observer.interrupt();
            observer.join(2_000L);
            assertFalse(observer.isAlive());
            assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                    interruption.get().diagnostic().code());
            assertTrue(interruption.get().diagnostic().message().contains("Interrupted"));
            assertTrue(interruptedFlag.get());

            releaseRetire.countDown();
            drain.get(2, TimeUnit.SECONDS);
            close.get(2, TimeUnit.SECONDS);
        }

        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertEquals(1, provider.retireCount.get());
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void discoveryTraversalIsBoundedFailClosedRetryableAndDoesNotBlockRegistryReads() throws Exception {
        CapabilityRequest request = CapabilityRequest.required(CAPABILITY, VERSION_RANGE);
        CapabilityRegistry blocking = new CapabilityRegistry();
        blocking.register(new ReentrantProvider("example:blocking_request_iterator"));
        CountDownLatch iteratorEntered = new CountDownLatch(1);
        CountDownLatch releaseIterator = new CountDownLatch(1);
        Collection<CapabilityRequest> blockingRequests = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityRequest> iterator() {
                iteratorEntered.countDown();
                await(releaseIterator);
                return List.of(request).iterator();
            }

            @Override
            public int size() {
                throw new AssertionError("discover must not call size");
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<List<CapabilityOffer>> discovery = executor.submit(() -> blocking.discover(blockingRequests));
            assertTrue(iteratorEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, executor.submit(blocking::registeredProviderIds).get(1, TimeUnit.SECONDS).size());
            CapabilityNegotiationException busy = executor.submit(() -> assertThrows(
                    CapabilityNegotiationException.class,
                    () -> blocking.discover(List.of(request)))).get(1, TimeUnit.SECONDS);
            assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE, busy.diagnostic().code());
            releaseIterator.countDown();
            assertEquals(1, discovery.get(2, TimeUnit.SECONDS).size());
        }

        CapabilityRegistry hostile = new CapabilityRegistry();
        hostile.register(new ReentrantProvider("example:hostile_request_iterator"));
        Collection<CapabilityRequest> traversalFailure = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityRequest> iterator() {
                throw new HostileRuntimeException();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        assertEquals(CapabilityErrorCode.INVALID_CAPABILITY_REQUEST, assertThrows(
                CapabilityNegotiationException.class,
                () -> hostile.discover(traversalFailure)).diagnostic().code());
        assertEquals(1, hostile.discover(List.of(request)).size());

        CapabilityRegistry oversized = new CapabilityRegistry();
        oversized.register(new ReentrantProvider("example:oversized_request_iterator"));
        AtomicInteger traversed = new AtomicInteger();
        Collection<CapabilityRequest> endless = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityRequest> iterator() {
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public CapabilityRequest next() {
                        traversed.incrementAndGet();
                        return CapabilityRequest.required(
                                BlendResourceId.parse("example:request_" + traversed.get()), VERSION_RANGE);
                    }
                };
            }

            @Override
            public int size() {
                throw new AssertionError("discover must not call size");
            }
        };
        assertEquals(CapabilityErrorCode.INVALID_CAPABILITY_REQUEST, assertThrows(
                CapabilityNegotiationException.class,
                () -> oversized.discover(endless)).diagnostic().code());
        assertEquals(CapabilityRegistry.MAX_CAPABILITY_REQUESTS, traversed.get());
        assertEquals(CapabilityErrorCode.INVALID_CAPABILITY_REQUEST, assertThrows(
                CapabilityNegotiationException.class,
                () -> oversized.discover(null)).diagnostic().code());
        assertEquals(CapabilityErrorCode.INVALID_CAPABILITY_REQUEST, assertThrows(
                CapabilityNegotiationException.class,
                () -> oversized.discover(java.util.Arrays.asList(request, null))).diagnostic().code());

        CapabilityRegistry fatal = new CapabilityRegistry();
        fatal.register(new ReentrantProvider("example:fatal_request_iterator"));
        Collection<CapabilityRequest> fatalTraversal = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityRequest> iterator() {
                throw new FatalLifecycleError();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        assertThrows(FatalLifecycleError.class, () -> fatal.discover(fatalTraversal));
        assertEquals(1, fatal.discover(List.of(request)).size());
    }

    @Test
    void capabilityDiagnosticKeysRemainUniqueAndAppendOnly() {
        assertEquals(CapabilityErrorCode.values().length,
                java.util.Arrays.stream(CapabilityErrorCode.values())
                        .map(CapabilityErrorCode::code)
                        .distinct()
                        .count());
        assertEquals("BLENDLIB-X1-CAP-017", CapabilityErrorCode.INVALID_CAPABILITY_REQUEST.code());
    }

    @Test
    void registryGenerationRecordRejectsPlanGenerationTamperingBeforeOwnershipOrCallbacks() throws Exception {
        ReentrantProvider provider = new ReentrantProvider("example:generation_tamper");
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        CapabilityPlan plan = registry.freeze(20_000L);
        Field generation = CapabilityPlan.class.getDeclaredField("generation");
        generation.setAccessible(true);
        generation.setLong(plan, 20_001L);

        CapabilityNegotiationException failure = assertThrows(
                CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(plan, List.of(provider)));

        assertEquals(CapabilityErrorCode.INVALID_FROZEN_PLAN, failure.diagnostic().code());
        assertEquals(0, provider.prepareCount.get());
        assertEquals(0, provider.closeCount.get());
    }

    @Test
    void metadataErrorsAreNormalizedOrRethrownWithoutLeavingRegistryMutationBusy() {
        CapabilityRegistry containedRegistry = new CapabilityRegistry();
        ReentrantProvider contained = new ReentrantProvider("example:error_metadata");
        AtomicInteger containedCalls = new AtomicInteger();
        contained.providerIdAction = () -> {
            if (containedCalls.getAndIncrement() == 0) {
                throw new HostileAssertionError();
            }
        };

        CapabilityNegotiationException containedFailure = assertThrows(
                CapabilityNegotiationException.class, () -> containedRegistry.register(contained));

        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, containedFailure.diagnostic().code());
        assertBoundedAndSanitized(containedFailure.diagnostic());
        containedRegistry.register(contained);
        assertEquals(List.of(contained.providerId), containedRegistry.registeredProviderIds());

        CapabilityRegistry fatalRegistry = new CapabilityRegistry();
        ReentrantProvider fatal = new ReentrantProvider("example:fatal_metadata");
        AtomicInteger fatalCalls = new AtomicInteger();
        fatal.providerIdAction = () -> {
            if (fatalCalls.getAndIncrement() == 0) {
                throw new FatalLifecycleError();
            }
        };

        assertThrows(FatalLifecycleError.class, () -> fatalRegistry.register(fatal));

        fatalRegistry.register(fatal);
        assertEquals(List.of(fatal.providerId), fatalRegistry.registeredProviderIds());
    }

    @Test
    void nonRuntimeLifecycleFailuresAreContainedAndNeverLeaveABusyOrFalseSuccessfulSession() {
        ReentrantProvider prepare = new ReentrantProvider("example:error_prepare");
        prepare.prepareAction = ignored -> {
            throw new HostileAssertionError();
        };
        ProviderLifecycleSession prepareSession = sessionFor(20_010L, prepare);
        assertSafeFailure(prepareSession.prepare(), CapabilityErrorCode.PROVIDER_PREPARE_FAILURE);
        assertEquals(ProviderLifecycleState.FAILED, prepareSession.state());
        assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE, assertThrows(
                CapabilityNegotiationException.class, prepareSession::prepare).diagnostic().code());
        prepareSession.close();
        assertEquals(1, prepare.closeCount.get());

        ReentrantProvider apply = new ReentrantProvider("example:error_apply");
        apply.applyAction = ignored -> {
            throw new HostileAssertionError();
        };
        ProviderLifecycleSession applySession = sessionFor(20_011L, apply);
        assertTrue(applySession.prepare().successful());
        assertSafeFailure(applySession.apply(), CapabilityErrorCode.PROVIDER_APPLY_FAILURE);
        assertEquals(ProviderLifecycleState.FAILED, applySession.state());
        assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE, assertThrows(
                CapabilityNegotiationException.class, applySession::apply).diagnostic().code());
        applySession.close();
        assertEquals(1, apply.closeCount.get());

        ReentrantProvider retire = new ReentrantProvider("example:error_retire");
        retire.retireAction = ignored -> {
            throw new HostileAssertionError();
        };
        ProviderLifecycleSession retireSession = sessionFor(20_012L, retire);
        publish(retireSession);
        assertSafeFailure(retireSession.retire(), CapabilityErrorCode.PROVIDER_RETIRE_FAILURE);
        assertEquals(ProviderLifecycleState.CLOSED, retireSession.state());
        assertFalse(retireSession.retire().successful());
        assertEquals(1, retire.retireCount.get());
        assertEquals(1, retire.closeCount.get());

        ReentrantProvider close = new ReentrantProvider("example:error_close");
        close.closeAction = () -> {
            throw new HostileAssertionError();
        };
        ProviderLifecycleSession closeSession = sessionFor(20_013L, close);
        publish(closeSession);
        assertSafeFailure(closeSession.retire(), CapabilityErrorCode.PROVIDER_CLOSE_FAILURE);
        assertEquals(ProviderLifecycleState.CLOSED, closeSession.state());
        assertFalse(closeSession.retire().successful());
        assertEquals(1, close.closeCount.get());

        ReentrantProvider closeEntry = new ReentrantProvider("example:error_close_entry");
        closeEntry.retireAction = ignored -> {
            throw new HostileAssertionError();
        };
        ProviderLifecycleSession closeEntrySession = sessionFor(20_014L, closeEntry);
        publish(closeEntrySession);
        closeEntrySession.close();
        assertEquals(ProviderLifecycleState.CLOSED, closeEntrySession.state());
        assertFalse(closeEntrySession.retire().successful());
        assertEquals(1, closeEntry.closeCount.get());

        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider adapterClose = new ReentrantProvider("example:error_adapter_close");
        adapterClose.closeAction = () -> {
            throw new HostileAssertionError();
        };
        control.install(adapterClose);
        assertSafeRegistrationFailure(assertThrows(BlendRegistrationException.class, control::uninstall));
        assertTrue(control.adapterId().isEmpty());
        assertEquals(1, adapterClose.closeCount.get());
        ReentrantProvider replacement = new ReentrantProvider("example:adapter_after_error_close");
        control.install(replacement);
        control.uninstall();
        assertEquals(1, replacement.closeCount.get());
    }

    @Test
    void fatalLifecycleFailuresRestoreTerminalStateAndReleaseOwnershipBeforeRethrow() {
        ReentrantProvider prepare = new ReentrantProvider("example:fatal_prepare");
        prepare.prepareAction = ignored -> {
            throw new FatalLifecycleError();
        };
        ProviderLifecycleSession prepareSession = sessionFor(20_015L, prepare);

        assertThrows(FatalLifecycleError.class, prepareSession::prepare);

        assertEquals(ProviderLifecycleState.CLOSED, prepareSession.state());
        assertFalse(prepareSession.retire().successful());
        assertEquals(CapabilityErrorCode.PROVIDER_PREPARE_FAILURE,
                prepareSession.diagnostics().getFirst().code());
        assertEquals(1, prepare.closeCount.get());

        ReentrantProvider retire = new ReentrantProvider("example:fatal_retire");
        retire.retireAction = ignored -> {
            throw new FatalLifecycleError();
        };
        ProviderLifecycleSession retireSession = sessionFor(20_016L, retire);
        publish(retireSession);

        assertThrows(FatalLifecycleError.class, retireSession::retire);

        assertEquals(ProviderLifecycleState.CLOSED, retireSession.state());
        assertFalse(retireSession.retire().successful());
        assertEquals(1, retire.retireCount.get());
        assertEquals(1, retire.closeCount.get());

        ReentrantProvider close = new ReentrantProvider("example:fatal_close");
        close.closeAction = () -> {
            throw new FatalLifecycleError();
        };
        ProviderLifecycleSession closeSession = sessionFor(20_017L, close);
        publish(closeSession);

        assertThrows(FatalLifecycleError.class, closeSession::retire);

        assertEquals(ProviderLifecycleState.CLOSED, closeSession.state());
        assertFalse(closeSession.retire().successful());
        assertEquals(1, close.closeCount.get());
    }

    @Test
    void hostileHostIdentityCallbacksRunAfterOperationBeginAndNeverUnderTheControlMonitor() throws Exception {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider adapter = new ReentrantProvider("example:host_identity_reentrant");
        control.install(adapter);
        control.register(specification(new Object()));
        HostileHost reentrantHost = new HostileHost(() -> control.uninstall());

        BlendRegistrationException reentrantFailure = assertThrows(
                BlendRegistrationException.class,
                () -> control.register(specification(reentrantHost)));

        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, reentrantFailure.diagnostic().code());
        assertEquals(1, adapter.registerCount.get());
        assertEquals(0, adapter.closeCount.get());
        assertEquals(adapter.providerId, control.adapterId().orElseThrow());
        assertEquals(1, control.registrationCount());

        CountDownLatch identityEntered = new CountDownLatch(1);
        CountDownLatch releaseIdentity = new CountDownLatch(1);
        HostileHost blockingHost = new HostileHost(() -> {
            identityEntered.countDown();
            await(releaseIdentity);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationReceipt> registration = executor.submit(
                    () -> control.register(specification(blockingHost)));
            assertTrue(identityEntered.await(2, TimeUnit.SECONDS));
            Future<BlendApiDiagnosticCode> uninstall = executor.submit(() -> assertThrows(
                    BlendRegistrationException.class, control::uninstall).diagnostic().code());
            try {
                assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                        uninstall.get(1, TimeUnit.SECONDS));
            } finally {
                releaseIdentity.countDown();
            }
            assertEquals(adapter.providerId, registration.get(2, TimeUnit.SECONDS).adapterId());
        }
        assertEquals(2, adapter.registerCount.get());
        assertEquals(2, control.registrationCount());
    }

    @AfterEach
    void clearControl() {
        try {
            PlatformAdapterControl.global().uninstall();
        } catch (BlendRegistrationException ignored) {
            // A hostile close callback is expected in specific tests; uninstall still clears the control.
        }
    }

    @Test
    void applyAndPublishReentryFailsTheOuterApplyWithoutPublishingAClosedProvider() {
        ReentrantProvider provider = new ReentrantProvider("example:apply_reentry");
        AtomicReference<ProviderLifecycleSession> reference = new AtomicReference<>();
        provider.applyAction = ignored -> reference.get().publish();
        ProviderLifecycleSession session = sessionFor(20_001L, provider);
        reference.set(session);
        assertTrue(session.prepare().successful());

        ProviderLifecycleResult result = session.apply();

        assertFalse(result.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_APPLY_FAILURE, result.diagnostics().getFirst().code());
        assertEquals(ProviderLifecycleState.FAILED, session.state());
        assertThrows(CapabilityNegotiationException.class, session::publish);
        assertEquals(0, provider.closeCount.get());
        session.close();
        assertEquals(1, provider.retireCount.get());
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void retireAndCloseCallbacksCannotReenterLifecycleAndEveryHandleClosesOnce() {
        ReentrantProvider retireProvider = new ReentrantProvider("example:retire_reentry");
        AtomicReference<ProviderLifecycleSession> retireReference = new AtomicReference<>();
        retireProvider.retireAction = ignored -> retireReference.get().pin();
        ProviderLifecycleSession retireSession = sessionFor(20_002L, retireProvider);
        retireReference.set(retireSession);
        publish(retireSession);

        ProviderLifecycleResult retireResult = retireSession.retire();

        assertFalse(retireResult.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_RETIRE_FAILURE, retireResult.diagnostics().getFirst().code());
        assertEquals(ProviderLifecycleState.CLOSED, retireSession.state());
        assertEquals(1, retireProvider.retireCount.get());
        assertEquals(1, retireProvider.closeCount.get());

        ReentrantProvider closeProvider = new ReentrantProvider("example:close_reentry");
        AtomicReference<ProviderLifecycleSession> closeReference = new AtomicReference<>();
        closeProvider.closeAction = () -> closeReference.get().close();
        ProviderLifecycleSession closeSession = sessionFor(20_003L, closeProvider);
        closeReference.set(closeSession);
        publish(closeSession);

        ProviderLifecycleResult closeResult = closeSession.retire();

        assertFalse(closeResult.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_CLOSE_FAILURE,
                closeResult.diagnostics().getLast().code());
        assertEquals(ProviderLifecycleState.CLOSED, closeSession.state());
        assertEquals(1, closeProvider.retireCount.get());
        assertEquals(1, closeProvider.closeCount.get());
        closeSession.close();
        assertEquals(1, closeProvider.closeCount.get());
    }

    @RepeatedTest(20)
    void concurrentMutationIsRejectedWithoutWaitingForTheProviderCallbackMonitor() throws Exception {
        ReentrantProvider provider = new ReentrantProvider("example:concurrent_prepare");
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        provider.prepareAction = ignored -> {
            callbackEntered.countDown();
            await(releaseCallback);
        };
        ProviderLifecycleSession session = sessionFor(20_004L, provider);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ProviderLifecycleResult> preparation = executor.submit(session::prepare);
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            Future<CapabilityErrorCode> concurrentClose = executor.submit(() -> assertThrows(
                    CapabilityNegotiationException.class, session::close).diagnostic().code());
            assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                    concurrentClose.get(2, TimeUnit.SECONDS));
            releaseCallback.countDown();
            assertTrue(preparation.get(2, TimeUnit.SECONDS).successful());
        }

        assertEquals(ProviderLifecycleState.PREPARED, session.state());
        session.close();
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void registryMetadataCallbacksCannotReenterOrOverwriteTheOuterRegistration() {
        CapabilityRegistry registry = new CapabilityRegistry();
        ReentrantProvider provider = new ReentrantProvider("example:metadata_reentry");
        provider.providerIdAction = () -> registry.discover(List.of());

        CapabilityNegotiationException exception = assertThrows(
                CapabilityNegotiationException.class, () -> registry.register(provider));

        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, exception.diagnostic().code());
        assertTrue(registry.registeredProviderIds().isEmpty());

        CapabilityRegistry offersRegistry = new CapabilityRegistry();
        ReentrantProvider offersProvider = new ReentrantProvider("example:offers_reentry");
        offersProvider.offersAction = () -> offersRegistry.discover(List.of());
        CapabilityNegotiationException offersFailure = assertThrows(
                CapabilityNegotiationException.class, () -> offersRegistry.register(offersProvider));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, offersFailure.diagnostic().code());
        assertTrue(offersRegistry.registeredProviderIds().isEmpty());

        CapabilityRegistry iteratorRegistry = new CapabilityRegistry();
        BlendResourceId providerId = BlendResourceId.parse("example:iterator_reentry");
        AtomicInteger iteratorCalls = new AtomicInteger();
        Collection<CapabilityOffer> offers = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityOffer> iterator() {
                iteratorCalls.incrementAndGet();
                iteratorRegistry.freeze(1L);
                return List.<CapabilityOffer>of().iterator();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        CapabilityNegotiationException iteratorFailure = assertThrows(
                CapabilityNegotiationException.class,
                () -> iteratorRegistry.register(new FixedMetadataProvider(providerId, offers)));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, iteratorFailure.diagnostic().code());
        assertEquals(1, iteratorCalls.get());
        assertTrue(iteratorRegistry.registeredProviderIds().isEmpty());
    }

    @Test
    void adapterIdentityRegisterAndUnregisterCallbacksCannotInstallOrRemoveNestedAdapters() {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider nested = new ReentrantProvider("example:nested_adapter");
        ReentrantProvider identityOuter = new ReentrantProvider("example:identity_outer");
        identityOuter.providerIdAction = () -> control.install(nested);
        BlendRegistrationException identityFailure = assertThrows(
                BlendRegistrationException.class, () -> control.install(identityOuter));
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, identityFailure.diagnostic().code());
        assertTrue(control.adapterId().isEmpty());

        ReentrantProvider registerOuter = new ReentrantProvider("example:register_outer");
        control.install(registerOuter);
        registerOuter.registerAction = ignored -> control.uninstall();
        BlendRegistrationException registerFailure = assertThrows(
                BlendRegistrationException.class, () -> control.register(specification("register-host")));
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, registerFailure.diagnostic().code());
        assertEquals(registerOuter.providerId, control.adapterId().orElseThrow());
        assertEquals(0, control.registrationCount());
        control.uninstall();
        assertEquals(1, registerOuter.closeCount.get());

        ReentrantProvider nestedRegisterOuter = new ReentrantProvider("example:nested_register_outer");
        control.install(nestedRegisterOuter);
        nestedRegisterOuter.registerAction = ignored -> control.register(specification("nested-register-host"));
        BlendRegistrationException nestedRegisterFailure = assertThrows(
                BlendRegistrationException.class,
                () -> control.register(specification("outer-register-host")));
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                nestedRegisterFailure.diagnostic().code());
        assertEquals(0, control.registrationCount());
        control.uninstall();
        assertEquals(1, nestedRegisterOuter.closeCount.get());

        ReentrantProvider closeOuter = new ReentrantProvider("example:close_outer");
        closeOuter.closeAction = () -> control.install(nested);
        control.install(closeOuter);
        BlendRegistrationException closeFailure = assertThrows(BlendRegistrationException.class, control::uninstall);
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, closeFailure.diagnostic().code());
        assertTrue(control.adapterId().isEmpty());
        assertEquals(1, closeOuter.closeCount.get());
        assertEquals(0, nested.closeCount.get());
    }

    @RepeatedTest(20)
    void concurrentAdapterUninstallIsRejectedWithoutDeadlockingARegisterCallback() throws Exception {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider adapter = new ReentrantProvider("example:concurrent_adapter");
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        adapter.registerAction = ignored -> {
            callbackEntered.countDown();
            await(releaseCallback);
        };
        control.install(adapter);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationReceipt> registration = executor.submit(
                    () -> control.register(specification("concurrent-host")));
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            Future<BlendApiDiagnosticCode> uninstall = executor.submit(() -> assertThrows(
                    BlendRegistrationException.class, control::uninstall).diagnostic().code());
            assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                    uninstall.get(2, TimeUnit.SECONDS));
            releaseCallback.countDown();
            assertEquals(adapter.providerId, registration.get(2, TimeUnit.SECONDS).adapterId());
        }

        assertEquals(1, control.registrationCount());
        control.uninstall();
        assertEquals(1, adapter.closeCount.get());
    }

    @Test
    void everyExternalLifecycleAndAdapterFailureUsesOnlyASafeThrowableType() {
        ReentrantProvider apply = new ReentrantProvider("example:hostile_apply");
        apply.applyFailure = new HostileRuntimeException();
        ProviderLifecycleSession applySession = sessionFor(20_005L, apply);
        assertTrue(applySession.prepare().successful());
        assertSafeFailure(applySession.apply(), CapabilityErrorCode.PROVIDER_APPLY_FAILURE);

        ReentrantProvider retire = new ReentrantProvider("example:hostile_retire");
        retire.retireFailure = new HostileRuntimeException();
        ProviderLifecycleSession retireSession = sessionFor(20_006L, retire);
        publish(retireSession);
        assertSafeFailure(retireSession.retire(), CapabilityErrorCode.PROVIDER_RETIRE_FAILURE);

        ReentrantProvider close = new ReentrantProvider("example:hostile_close");
        close.closeFailure = new HostileRuntimeException();
        ProviderLifecycleSession closeSession = sessionFor(20_007L, close);
        publish(closeSession);
        ProviderLifecycleResult closeResult = closeSession.retire();
        assertFalse(closeResult.successful());
        CapabilityDiagnostic closeDiagnostic = closeResult.diagnostics().getLast();
        assertEquals(CapabilityErrorCode.PROVIDER_CLOSE_FAILURE, closeDiagnostic.code());
        assertBoundedAndSanitized(closeDiagnostic);

        PlatformAdapterControl control = PlatformAdapterControl.global();
        ReentrantProvider identity = new ReentrantProvider("example:hostile_identity");
        identity.providerIdFailure = new HostileRuntimeException();
        assertSafeRegistrationFailure(assertThrows(BlendRegistrationException.class, () -> control.install(identity)));

        ReentrantProvider registering = new ReentrantProvider("example:hostile_register");
        registering.registerFailure = new HostileRuntimeException();
        control.install(registering);
        assertSafeRegistrationFailure(assertThrows(
                BlendRegistrationException.class, () -> control.register(specification("hostile-register-host"))));
        control.uninstall();

        ReentrantProvider closing = new ReentrantProvider("example:hostile_adapter_close");
        closing.closeFailure = new HostileRuntimeException();
        control.install(closing);
        assertSafeRegistrationFailure(assertThrows(BlendRegistrationException.class, control::uninstall));
        assertTrue(control.adapterId().isEmpty());
    }

    @Test
    void allExperimentalIdentityAndDiagnosticStringsAreBoundedAndSanitized() {
        BlendResourceId oversized = BlendResourceId.parse("example:" + "x".repeat(100_000));
        assertThrows(IllegalArgumentException.class,
                () -> CapabilityRequest.required(oversized, VERSION_RANGE));
        assertThrows(IllegalArgumentException.class,
                () -> new CapabilityOffer(oversized, CAPABILITY, new CapabilityVersion(1, 0, 0), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CapabilityOffer(BlendResourceId.parse("example:provider"), oversized,
                        new CapabilityVersion(1, 0, 0), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CapabilityFallback(BlendResourceId.parse("example:fallback"), "unsafe\u0000text"));
        assertThrows(IllegalArgumentException.class,
                () -> BlendResourceId.parse("example:unicode_雪"));
        assertThrows(IllegalArgumentException.class,
                () -> BlendResourceId.parse("example:control_\u0001"));

        CapabilityDiagnostic diagnostic = CapabilityDiagnostic.provider(
                CapabilityErrorCode.INVALID_PROVIDER_OFFER,
                BlendDiagnosticSeverity.ERROR,
                oversized,
                "unsafe\u0000" + "z".repeat(100_000));
        assertTrue(diagnostic.providerId().isEmpty());
        assertBoundedAndSanitized(diagnostic);

        AtomicInteger iteratorCalls = new AtomicInteger();
        Collection<CapabilityOffer> hostileOffers = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityOffer> iterator() {
                iteratorCalls.incrementAndGet();
                throw new HostileRuntimeException();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        CapabilityNegotiationException registrationFailure = assertThrows(
                CapabilityNegotiationException.class,
                () -> new CapabilityRegistry().register(new FixedMetadataProvider(oversized, hostileOffers)));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, registrationFailure.diagnostic().code());
        assertTrue(registrationFailure.diagnostic().providerId().isEmpty());
        assertBoundedAndSanitized(registrationFailure.diagnostic());
        assertEquals(0, iteratorCalls.get());
    }

    private static ProviderLifecycleSession sessionFor(long generation, ReentrantProvider provider) {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        return new ProviderLifecycleSession(registry.freeze(generation), List.of(provider));
    }

    private static void publish(ProviderLifecycleSession session) {
        assertTrue(session.prepare().successful());
        assertTrue(session.apply().successful());
        session.publish();
    }

    private static <H> HostRegistrationSpec<H> specification(H host) {
        return BlendLib.entity(host).model(MODEL).animation(AnimationRequest.loop(ANIMATION)).build();
    }

    private static void assertSafeFailure(ProviderLifecycleResult result, CapabilityErrorCode code) {
        assertFalse(result.successful());
        assertEquals(code, result.diagnostics().getFirst().code());
        assertBoundedAndSanitized(result.diagnostics().getFirst());
    }

    private static void assertBoundedAndSanitized(CapabilityDiagnostic diagnostic) {
        assertTrue(diagnostic.message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
        assertFalse(diagnostic.message().contains("attacker"));
        assertTrue(diagnostic.message().codePoints().noneMatch(Character::isISOControl));
        diagnostic.capabilityId().ifPresent(id -> assertTrue(id.value().length()
                <= ExperimentalControlBoundary.MAX_ID_LENGTH));
        diagnostic.providerId().ifPresent(id -> assertTrue(id.value().length()
                <= ExperimentalControlBoundary.MAX_ID_LENGTH));
    }

    private static void assertSafeRegistrationFailure(BlendRegistrationException exception) {
        assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, exception.diagnostic().code());
        assertTrue(exception.diagnostic().message().length() <= BlendApiDiagnostic.MAX_MESSAGE_LENGTH);
        assertFalse(exception.diagnostic().message().contains("attacker"));
        assertTrue(exception.diagnostic().message().codePoints().noneMatch(Character::isISOControl));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test callback timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test callback interrupted");
        }
    }

    private static class FixedMetadataProvider implements BlendProvider {
        final BlendResourceId providerId;
        private final Collection<CapabilityOffer> offers;

        FixedMetadataProvider(BlendResourceId providerId, Collection<CapabilityOffer> offers) {
            this.providerId = providerId;
            this.offers = offers;
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return offers;
        }
    }

    private static final class ReentrantProvider extends FixedMetadataProvider implements PlatformAdapter {
        private Runnable providerIdAction = () -> { };
        private Runnable offersAction = () -> { };
        private java.util.function.Consumer<ProviderLifecycleContext> prepareAction = ignored -> { };
        private java.util.function.Consumer<ProviderLifecycleContext> applyAction = ignored -> { };
        private java.util.function.Consumer<ProviderLifecycleContext> retireAction = ignored -> { };
        private java.util.function.Consumer<HostRegistrationSpec<?>> registerAction = ignored -> { };
        private Runnable closeAction = () -> { };
        private RuntimeException providerIdFailure;
        private RuntimeException applyFailure;
        private RuntimeException retireFailure;
        private RuntimeException registerFailure;
        private RuntimeException closeFailure;
        private final AtomicInteger retireCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger prepareCount = new AtomicInteger();
        private final AtomicInteger registerCount = new AtomicInteger();
        private final AtomicInteger providerIdCount = new AtomicInteger();

        ReentrantProvider(String providerId) {
            super(BlendResourceId.parse(providerId), List.of(new CapabilityOffer(
                    BlendResourceId.parse(providerId), CAPABILITY, new CapabilityVersion(1, 0, 0), 1)));
        }

        @Override
        public BlendResourceId providerId() {
            providerIdCount.incrementAndGet();
            providerIdAction.run();
            if (providerIdFailure != null) {
                throw providerIdFailure;
            }
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            offersAction.run();
            return super.offers();
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            prepareCount.incrementAndGet();
            prepareAction.accept(context);
        }

        @Override
        public void apply(ProviderLifecycleContext context) {
            applyAction.accept(context);
            if (applyFailure != null) {
                throw applyFailure;
            }
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCount.incrementAndGet();
            retireAction.accept(context);
            if (retireFailure != null) {
                throw retireFailure;
            }
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            registerCount.incrementAndGet();
            registerAction.accept(specification);
            if (registerFailure != null) {
                throw registerFailure;
            }
            return new RegistrationReceipt(providerId, specification.hostKind(), specification.model());
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closeAction.run();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class HostileRuntimeException extends RuntimeException {
        @SuppressWarnings("serial")
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("attacker getMessage");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("attacker toString");
        }
    }

    private static final class HostileAssertionError extends AssertionError {
        @SuppressWarnings("serial")
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("attacker getMessage");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("attacker toString");
        }
    }

    private static final class FatalLifecycleError extends VirtualMachineError {
        @SuppressWarnings("serial")
        private static final long serialVersionUID = 1L;
    }

    private static final class HostileHost {
        private final Runnable identityAction;

        private HostileHost(Runnable identityAction) {
            this.identityAction = identityAction;
        }

        @Override
        public int hashCode() {
            identityAction.run();
            return 31;
        }

        @Override
        public boolean equals(Object other) {
            identityAction.run();
            return this == other;
        }
    }
}
