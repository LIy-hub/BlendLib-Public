package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

final class R8LifecycleAndRegistryRegressionTest {
    private static final CapabilityVersionRange VERSION_RANGE = new CapabilityVersionRange(
            CapabilityVersion.INITIAL_PROTOCOL, new CapabilityVersion(2, 0, 0));

    @RepeatedTest(20)
    void ordinaryRetirementUsesOneCompletionForInitiatorWaiterAndPostCompletionObservers() throws Exception {
        LifecycleProvider provider = new LifecycleProvider("r8:ordinary", "r8:ordinary_capability");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        provider.closeAction = () -> {
            closeEntered.countDown();
            await(releaseClose);
        };
        ProviderLifecycleSession session = publishedSession(80L, provider);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<ProviderLifecycleResult> initiator = executor.submit(session::retire);
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            Future<ProviderLifecycleResult> waiter = executor.submit(session::retire);
            Future<?> closeWaiter = executor.submit(() -> {
                session.close();
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> waiter.get(150, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> closeWaiter.get(150, TimeUnit.MILLISECONDS));
                CapabilityNegotiationException ownershipConflict = assertThrows(
                        CapabilityNegotiationException.class,
                        () -> new ProviderLifecycleSession(planFor(81L, provider), List.of(provider)));
                assertEquals(CapabilityErrorCode.PROVIDER_OWNERSHIP_CONFLICT, ownershipConflict.diagnostic().code());
            } finally {
                releaseClose.countDown();
            }

            ProviderLifecycleResult initiated = initiator.get(2, TimeUnit.SECONDS);
            assertSame(initiated, waiter.get(2, TimeUnit.SECONDS));
            closeWaiter.get(2, TimeUnit.SECONDS);
            assertSame(initiated, session.retire());
            session.close();
        }

        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
    }

    @Test
    @SuppressWarnings("removal")
    void fatalPrepareApplyAndCloseRemainTheSamePostCompletionFailure() {
        assertTerminalFatalIdentity(ProviderLifecycleStage.PREPARE);
        assertTerminalFatalIdentity(ProviderLifecycleStage.APPLY);
        assertTerminalFatalIdentity(ProviderLifecycleStage.CLOSE);
    }

    @Test
    void interruptedObserverDoesNotPublishEarlyAndAProviderCannotReenterItsOwnRetirement() throws Exception {
        LifecycleProvider provider = new LifecycleProvider("r8:interrupted", "r8:interrupted_capability");
        CountDownLatch retireEntered = new CountDownLatch(1);
        CountDownLatch releaseRetire = new CountDownLatch(1);
        AtomicReference<ProviderLifecycleSession> reference = new AtomicReference<>();
        AtomicReference<CapabilityNegotiationException> reentry = new AtomicReference<>();
        provider.retireAction = ignored -> {
            reentry.set(assertThrows(CapabilityNegotiationException.class, reference.get()::retire));
            retireEntered.countDown();
            await(releaseRetire);
        };
        ProviderLifecycleSession session = publishedSession(82L, provider);
        reference.set(session);
        AtomicReference<CapabilityNegotiationException> interrupted = new AtomicReference<>();
        AtomicReference<Boolean> interruptedFlag = new AtomicReference<>(false);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ProviderLifecycleResult> initiator = executor.submit(session::retire);
            assertTrue(retireEntered.await(2, TimeUnit.SECONDS));
            Thread observer = Thread.ofPlatform().start(() -> {
                try {
                    session.retire();
                } catch (CapabilityNegotiationException exception) {
                    interrupted.set(exception);
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                waitUntilBlocked(observer);
                observer.interrupt();
                observer.join(2_000L);
                assertFalse(observer.isAlive());
                assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE, interrupted.get().diagnostic().code());
                assertTrue(interrupted.get().diagnostic().message().contains("Interrupted"));
                assertTrue(interruptedFlag.get());
                assertEquals(CapabilityErrorCode.INVALID_LIFECYCLE_STATE, reentry.get().diagnostic().code());
            } finally {
                releaseRetire.countDown();
            }
            ProviderLifecycleResult completion = initiator.get(2, TimeUnit.SECONDS);
            assertSame(completion, session.retire());
        }

        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
    }

    @Test
    @SuppressWarnings("removal")
    void duplicateProviderIdNeverInvokesOffersOrIteratorAndKeepsCap001OverFatal() {
        CapabilityRegistry registry = new CapabilityRegistry();
        RegistryProvider first = new RegistryProvider("r8:duplicate", "r8:first_capability");
        registry.register(first);
        AtomicInteger offers = new AtomicInteger();
        AtomicInteger iterators = new AtomicInteger();
        RegistryProvider duplicate = new RegistryProvider("r8:duplicate", "r8:duplicate_capability");
        duplicate.offersSupplier = () -> {
            offers.incrementAndGet();
            return new AbstractCollection<>() {
                @Override
                public Iterator<CapabilityOffer> iterator() {
                    iterators.incrementAndGet();
                    throw new ThreadDeath();
                }

                @Override
                public int size() {
                    return 0;
                }
            };
        };

        CapabilityNegotiationException failure = assertThrows(
                CapabilityNegotiationException.class, () -> registry.register(duplicate));

        assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID, failure.diagnostic().code());
        assertEquals(0, offers.get());
        assertEquals(0, iterators.get());
    }

    @RepeatedTest(25)
    void sameIdReservationRejectsConcurrentLoserBeforeOffersAndWinnerFailureAllowsRetry() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        RegistryProvider winner = new RegistryProvider("r8:reservation", "r8:reservation_capability");
        RegistryProvider retry = new RegistryProvider("r8:reservation", "r8:retry_capability");
        CountDownLatch winnerOffersEntered = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        winner.offersSupplier = () -> {
            winnerOffersEntered.countDown();
            await(releaseWinner);
            throw new HostileMetadataFailure();
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> winning = executor.submit(() -> captureFailure(() -> registry.register(winner)));
            assertTrue(winnerOffersEntered.await(2, TimeUnit.SECONDS));
            Future<Throwable> losing = executor.submit(() -> captureFailure(() -> registry.register(retry)));
            Throwable loser = losing.get(1, TimeUnit.SECONDS);
            assertTrue(loser instanceof CapabilityNegotiationException);
            assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID,
                    ((CapabilityNegotiationException) loser).diagnostic().code());
            assertEquals(0, retry.offerCalls.get());
            releaseWinner.countDown();
            Throwable winnerFailure = winning.get(2, TimeUnit.SECONDS);
            assertTrue(winnerFailure instanceof CapabilityNegotiationException);
            assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER,
                    ((CapabilityNegotiationException) winnerFailure).diagnostic().code());
        }

        registry.register(retry);
        assertEquals(List.of(BlendResourceId.parse("r8:reservation")), registry.registeredProviderIds());
        assertEquals(1, retry.offerCalls.get());
    }

    @RepeatedTest(25)
    void differentProviderIdsMayCollectOffersConcurrentlyWithoutPartialPublication() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        CountDownLatch bothOffersEntered = new CountDownLatch(2);
        CountDownLatch releaseOffers = new CountDownLatch(1);
        RegistryProvider first = blockingProvider("r8:first", "r8:first_capability", bothOffersEntered, releaseOffers);
        RegistryProvider second = blockingProvider("r8:second", "r8:second_capability", bothOffersEntered, releaseOffers);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstRegistration = executor.submit(() -> registry.register(first));
            Future<?> secondRegistration = executor.submit(() -> registry.register(second));
            try {
                assertTrue(bothOffersEntered.await(2, TimeUnit.SECONDS));
                assertTrue(registry.registeredProviderIds().isEmpty());
            } finally {
                releaseOffers.countDown();
            }
            firstRegistration.get(2, TimeUnit.SECONDS);
            secondRegistration.get(2, TimeUnit.SECONDS);
        }

        assertEquals(List.of(BlendResourceId.parse("r8:first"), BlendResourceId.parse("r8:second")),
                registry.registeredProviderIds());
    }

    @Test
    @SuppressWarnings("removal")
    void providerMetadataFailuresRollbackReservationsAndPermitSafeRetries() {
        assertRegistrationRetryAfterFailure("r8:ordinary_retry", false, FailurePoint.OFFERS);
        assertRegistrationRetryAfterFailure("r8:fatal_retry", true, FailurePoint.OFFERS);
        assertRegistrationRetryAfterFailure("r8:iterator_retry", false, FailurePoint.ITERATOR);
        assertRegistrationRetryAfterFailure("r8:identity_retry", false, FailurePoint.IDENTITY);
    }

    @Test
    void nestedRegistrationAndHostileMetadataAreSafeAndLeaveNoReservationBehind() {
        CapabilityRegistry nestedRegistry = new CapabilityRegistry();
        RegistryProvider inner = new RegistryProvider("r8:inner", "r8:inner_capability");
        RegistryProvider outer = new RegistryProvider("r8:outer", "r8:outer_capability");
        outer.offersSupplier = () -> {
            nestedRegistry.register(inner);
            return outer.normalOffers();
        };
        CapabilityNegotiationException nestedFailure = assertThrows(
                CapabilityNegotiationException.class, () -> nestedRegistry.register(outer));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, nestedFailure.diagnostic().code());
        assertTrue(nestedRegistry.registeredProviderIds().isEmpty());
        nestedRegistry.register(inner);

        CapabilityRegistry hostileRegistry = new CapabilityRegistry();
        RegistryProvider hostile = new RegistryProvider("r8:hostile", "r8:hostile_capability");
        hostile.offersSupplier = () -> {
            throw new HostileMetadataFailure();
        };
        CapabilityNegotiationException hostileFailure = assertThrows(
                CapabilityNegotiationException.class, () -> hostileRegistry.register(hostile));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, hostileFailure.diagnostic().code());
        assertFalse(hostileFailure.diagnostic().message().contains("metadata-secret"));
        assertTrue(hostileFailure.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
        hostile.offersSupplier = hostile::normalOffers;
        hostileRegistry.register(hostile);
        assertEquals(List.of(BlendResourceId.parse("r8:hostile")), hostileRegistry.registeredProviderIds());
    }

    @SuppressWarnings("removal")
    private static void assertTerminalFatalIdentity(ProviderLifecycleStage stage) {
        LifecycleProvider provider = new LifecycleProvider(
                "r8:fatal_" + stage.name().toLowerCase(),
                "r8:fatal_capability_" + stage.name().toLowerCase());
        ThreadDeath fatal = new ThreadDeath();
        if (stage == ProviderLifecycleStage.PREPARE) {
            provider.prepareAction = ignored -> {
                throw fatal;
            };
        } else if (stage == ProviderLifecycleStage.APPLY) {
            provider.applyAction = ignored -> {
                throw fatal;
            };
        } else {
            provider.closeAction = () -> {
                throw fatal;
            };
        }
        ProviderLifecycleSession session = sessionFor(90L + stage.ordinal(), provider);
        if (stage == ProviderLifecycleStage.APPLY || stage == ProviderLifecycleStage.CLOSE) {
            assertTrue(session.prepare().successful());
        }
        if (stage == ProviderLifecycleStage.CLOSE) {
            assertTrue(session.apply().successful());
            session.publish();
            assertSame(fatal, assertThrows(ThreadDeath.class, session::retire));
        } else if (stage == ProviderLifecycleStage.APPLY) {
            assertSame(fatal, assertThrows(ThreadDeath.class, session::apply));
        } else {
            assertSame(fatal, assertThrows(ThreadDeath.class, session::prepare));
        }
        assertSame(fatal, assertThrows(ThreadDeath.class, session::retire));
        assertSame(fatal, assertThrows(ThreadDeath.class, session::close));
        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertEquals(1, provider.closeCalls.get());
    }

    @SuppressWarnings("removal")
    private static void assertRegistrationRetryAfterFailure(String providerId, boolean fatal, FailurePoint point) {
        CapabilityRegistry registry = new CapabilityRegistry();
        RegistryProvider provider = new RegistryProvider(providerId, providerId + "_capability");
        AtomicInteger attempts = new AtomicInteger();
        ThreadDeath terminal = new ThreadDeath();
        if (point == FailurePoint.IDENTITY) {
            provider.providerIdSupplier = () -> {
                if (attempts.getAndIncrement() == 0) {
                    throw new HostileMetadataFailure();
                }
                return provider.providerId;
            };
        } else if (point == FailurePoint.ITERATOR) {
            provider.offersSupplier = () -> new AbstractCollection<>() {
                @Override
                public Iterator<CapabilityOffer> iterator() {
                    if (attempts.getAndIncrement() == 0) {
                        throw new HostileMetadataFailure();
                    }
                    return provider.normalOffers().iterator();
                }

                @Override
                public int size() {
                    return 1;
                }
            };
        } else {
            provider.offersSupplier = () -> {
                if (attempts.getAndIncrement() == 0) {
                    if (fatal) {
                        throw terminal;
                    }
                    throw new HostileMetadataFailure();
                }
                return provider.normalOffers();
            };
        }

        if (fatal) {
            assertSame(terminal, assertThrows(ThreadDeath.class, () -> registry.register(provider)));
        } else {
            CapabilityNegotiationException failure = assertThrows(
                    CapabilityNegotiationException.class, () -> registry.register(provider));
            assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, failure.diagnostic().code());
        }
        registry.register(provider);
        assertEquals(List.of(provider.providerId), registry.registeredProviderIds());
    }

    private static RegistryProvider blockingProvider(
            String providerId,
            String capabilityId,
            CountDownLatch entered,
            CountDownLatch release) {
        RegistryProvider provider = new RegistryProvider(providerId, capabilityId);
        provider.offersSupplier = () -> {
            entered.countDown();
            await(release);
            return provider.normalOffers();
        };
        return provider;
    }

    private static ProviderLifecycleSession publishedSession(long generation, LifecycleProvider provider) {
        ProviderLifecycleSession session = sessionFor(generation, provider);
        assertTrue(session.prepare().successful());
        assertTrue(session.apply().successful());
        session.publish();
        return session;
    }

    private static ProviderLifecycleSession sessionFor(long generation, LifecycleProvider provider) {
        return new ProviderLifecycleSession(planFor(generation, provider), List.of(provider));
    }

    private static CapabilityPlan planFor(long generation, LifecycleProvider provider) {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider);
        registry.discover(List.of(CapabilityRequest.required(provider.capabilityId, VERSION_RANGE)));
        return registry.freeze(generation);
    }

    private static Throwable captureFailure(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive()
                && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(thread.isAlive());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test callback release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private enum FailurePoint {
        IDENTITY,
        OFFERS,
        ITERATOR
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static final class LifecycleProvider implements BlendProvider {
        private final BlendResourceId providerId;
        private final BlendResourceId capabilityId;
        private java.util.function.Consumer<ProviderLifecycleContext> prepareAction = ignored -> { };
        private java.util.function.Consumer<ProviderLifecycleContext> applyAction = ignored -> { };
        private java.util.function.Consumer<ProviderLifecycleContext> retireAction = ignored -> { };
        private Runnable closeAction = () -> { };
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private LifecycleProvider(String providerId, String capabilityId) {
            this.providerId = BlendResourceId.parse(providerId);
            this.capabilityId = BlendResourceId.parse(capabilityId);
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(
                    providerId, capabilityId, CapabilityVersion.INITIAL_PROTOCOL, 1));
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            prepareAction.accept(context);
        }

        @Override
        public void apply(ProviderLifecycleContext context) {
            applyAction.accept(context);
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
            retireAction.accept(context);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeAction.run();
        }
    }

    private static final class RegistryProvider implements BlendProvider {
        private final BlendResourceId providerId;
        private final BlendResourceId capabilityId;
        private Supplier<BlendResourceId> providerIdSupplier;
        private Supplier<Collection<CapabilityOffer>> offersSupplier;
        private final AtomicInteger offerCalls = new AtomicInteger();

        private RegistryProvider(String providerId, String capabilityId) {
            this.providerId = BlendResourceId.parse(providerId);
            this.capabilityId = BlendResourceId.parse(capabilityId);
            providerIdSupplier = () -> this.providerId;
            offersSupplier = this::normalOffers;
        }

        @Override
        public BlendResourceId providerId() {
            return providerIdSupplier.get();
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            offerCalls.incrementAndGet();
            return offersSupplier.get();
        }

        private Collection<CapabilityOffer> normalOffers() {
            return List.of(new CapabilityOffer(
                    providerId, capabilityId, CapabilityVersion.INITIAL_PROTOCOL, 1));
        }
    }

    @SuppressWarnings("serial")
    private static final class HostileMetadataFailure extends RuntimeException {
        @Override
        public String getMessage() {
            throw new AssertionError("metadata-secret");
        }

        @Override
        public String toString() {
            throw new AssertionError("metadata-secret");
        }
    }
}
