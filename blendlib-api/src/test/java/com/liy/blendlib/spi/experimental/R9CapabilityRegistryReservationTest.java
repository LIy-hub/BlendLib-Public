package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("removal")
final class R9CapabilityRegistryReservationTest {
    private static final long LARGE_EPOCH = 4_097L;

    @ParameterizedTest(name = "successful registration at epoch {0}")
    @ValueSource(longs = {127L, 128L, 129L, LARGE_EPOCH})
    void successfulRegistrationReleasesEveryInFlightReference(long operationEpoch) throws Exception {
        CapabilityRegistry registry = registryAtNextEpoch(operationEpoch);
        MutableProvider provider = new MutableProvider(
                "r9:success_" + operationEpoch,
                "r9:success_capability_" + operationEpoch);

        registry.register(provider);

        assertEquals(operationEpoch, mutationEpoch(registry));
        assertNoInFlightReferences(registry);
        int completedOfferCalls = provider.offerCalls.get();
        CapabilityNegotiationException duplicate = assertThrows(
                CapabilityNegotiationException.class,
                () -> registry.register(provider));
        assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID, duplicate.diagnostic().code());
        assertEquals(completedOfferCalls, provider.offerCalls.get());
        assertNoInFlightReferences(registry);
    }

    @ParameterizedTest(name = "epoch={0}, point={1}, fatal={2}")
    @MethodSource("reservationFailureCases")
    void everyPostReservationFailureReleasesTheSameProviderForRetry(
            long operationEpoch,
            FailurePoint failurePoint,
            boolean fatal) throws Exception {
        CapabilityRegistry registry = registryAtNextEpoch(operationEpoch);
        MutableProvider provider = new MutableProvider(
                "r9:failure_" + operationEpoch + '_' + failurePoint.name().toLowerCase() + '_' + fatal,
                "r9:failure_capability_" + operationEpoch + '_' + failurePoint.name().toLowerCase() + '_' + fatal);
        provider.failurePoint = failurePoint;
        provider.fatalFailure = fatal;

        if (fatal) {
            assertSame(provider.fatal, assertThrows(ThreadDeath.class, () -> registry.register(provider)));
        } else {
            CapabilityNegotiationException failure = assertThrows(
                    CapabilityNegotiationException.class,
                    () -> registry.register(provider));
            assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, failure.diagnostic().code());
        }

        assertEquals(operationEpoch, mutationEpoch(registry));
        assertNoInFlightReferences(registry);
        provider.failurePoint = FailurePoint.NONE;
        provider.fatalFailure = false;
        registry.register(provider);
        assertEquals(List.of(provider.providerId), registry.registeredProviderIds());
        assertNoInFlightReferences(registry);
    }

    @Test
    void staleReservationCleanupCannotRemoveANewerReservationForTheSameProvider() throws Exception {
        CapabilityRegistry registry = registryAtNextEpoch(130L);
        MutableProvider provider = new MutableProvider("r9:aba", "r9:aba_capability");
        provider.failurePoint = FailurePoint.OFFERS;
        provider.offersEntered = new CountDownLatch(1);
        provider.releaseOffers = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> winner = executor.submit(() -> captureFailure(() -> registry.register(provider)));
            try {
                assertTrue(provider.offersEntered.await(2, TimeUnit.SECONDS));
                completeRegistration(registry, 128L, provider.providerId, provider);
                assertEquals(130L, providerInstanceReservation(registry, provider));

                CapabilityNegotiationException contender = assertThrows(
                        CapabilityNegotiationException.class,
                        () -> registry.register(provider));
                assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID, contender.diagnostic().code());
                assertEquals(1, provider.offerCalls.get());
            } finally {
                provider.releaseOffers.countDown();
            }

            Throwable winnerFailure = winner.get(2, TimeUnit.SECONDS);
            assertTrue(winnerFailure instanceof CapabilityNegotiationException);
            assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER,
                    ((CapabilityNegotiationException) winnerFailure).diagnostic().code());
        }

        assertNoInFlightReferences(registry);
        provider.failurePoint = FailurePoint.NONE;
        registry.register(provider);
        assertEquals(List.of(provider.providerId), registry.registeredProviderIds());
        assertNoInFlightReferences(registry);
    }

    private static Stream<Arguments> reservationFailureCases() {
        return Stream.of(127L, 128L, 129L, LARGE_EPOCH)
                .flatMap(epoch -> Stream.of(
                                FailurePoint.OFFERS,
                                FailurePoint.ITERATOR,
                                FailurePoint.HAS_NEXT,
                                FailurePoint.NEXT)
                        .flatMap(point -> Stream.of(false, true)
                                .map(fatal -> Arguments.of(epoch, point, fatal))));
    }

    private static CapabilityRegistry registryAtNextEpoch(long operationEpoch) throws ReflectiveOperationException {
        CapabilityRegistry registry = new CapabilityRegistry();
        Field field = CapabilityRegistry.class.getDeclaredField("mutationEpoch");
        field.setAccessible(true);
        field.setLong(registry, operationEpoch - 1L);
        return registry;
    }

    private static long mutationEpoch(CapabilityRegistry registry) throws ReflectiveOperationException {
        Field field = CapabilityRegistry.class.getDeclaredField("mutationEpoch");
        field.setAccessible(true);
        return field.getLong(registry);
    }

    private static void assertNoInFlightReferences(CapabilityRegistry registry)
            throws ReflectiveOperationException {
        assertEquals(0, mapField(registry, "providerReservations").size());
        assertEquals(0, mapField(registry, "providerInstanceReservations").size());
        assertEquals(0, mapField(registry, "activeRegistrations").size());
        assertEquals(0, mapField(registry, "activeRegistrationInstances").size());
    }

    private static Long providerInstanceReservation(CapabilityRegistry registry, BlendProvider provider)
            throws ReflectiveOperationException {
        return (Long) mapField(registry, "providerInstanceReservations").get(provider);
    }

    private static Map<?, ?> mapField(CapabilityRegistry registry, String name) throws ReflectiveOperationException {
        Field field = CapabilityRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(registry);
    }

    private static void completeRegistration(
            CapabilityRegistry registry,
            long operationEpoch,
            BlendResourceId providerId,
            BlendProvider provider) throws ReflectiveOperationException {
        Method method = CapabilityRegistry.class.getDeclaredMethod(
                "completeRegistration", long.class, BlendResourceId.class, BlendProvider.class);
        method.setAccessible(true);
        method.invoke(registry, operationEpoch, providerId, provider);
    }

    private static Throwable captureFailure(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
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
        NONE,
        OFFERS,
        ITERATOR,
        HAS_NEXT,
        NEXT
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static final class MutableProvider implements BlendProvider {
        private final BlendResourceId providerId;
        private final BlendResourceId capabilityId;
        private final ThreadDeath fatal = new ThreadDeath();
        private final AtomicInteger offerCalls = new AtomicInteger();
        private FailurePoint failurePoint = FailurePoint.NONE;
        private boolean fatalFailure;
        private CountDownLatch offersEntered;
        private CountDownLatch releaseOffers;

        private MutableProvider(String providerId, String capabilityId) {
            this.providerId = BlendResourceId.parse(providerId);
            this.capabilityId = BlendResourceId.parse(capabilityId);
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            int invocation = offerCalls.incrementAndGet();
            if (invocation == 1 && offersEntered != null) {
                offersEntered.countDown();
                await(releaseOffers);
            }
            if (failurePoint == FailurePoint.OFFERS) {
                fail();
            }
            CapabilityOffer offer = new CapabilityOffer(
                    providerId, capabilityId, CapabilityVersion.INITIAL_PROTOCOL, 1);
            return new AbstractCollection<>() {
                @Override
                public Iterator<CapabilityOffer> iterator() {
                    if (failurePoint == FailurePoint.ITERATOR) {
                        fail();
                    }
                    return new Iterator<>() {
                        private boolean consumed;

                        @Override
                        public boolean hasNext() {
                            if (failurePoint == FailurePoint.HAS_NEXT) {
                                fail();
                            }
                            return !consumed;
                        }

                        @Override
                        public CapabilityOffer next() {
                            if (failurePoint == FailurePoint.NEXT) {
                                fail();
                            }
                            consumed = true;
                            return offer;
                        }
                    };
                }

                @Override
                public int size() {
                    return 1;
                }
            };
        }

        @Override
        public boolean equals(Object other) {
            throw new AssertionError("registry cleanup must not call provider.equals");
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        private void fail() {
            if (fatalFailure) {
                throw fatal;
            }
            throw new MetadataFailure();
        }
    }

    @SuppressWarnings("serial")
    private static final class MetadataFailure extends RuntimeException {
    }
}
