package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class CapabilityRegistryContractTest {
    private static final BlendResourceId CAPABILITY = BlendResourceId.parse("example:capability/mesh");
    private static final CapabilityVersionRange VERSION_ONE = new CapabilityVersionRange(
            new CapabilityVersion(1, 0, 0), new CapabilityVersion(2, 0, 0));

    @Test
    void rejectsDuplicateProviderIdentityBeforeDiscovery() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider("example:duplicate", CAPABILITY, new CapabilityVersion(1, 0, 0), 1));

        CapabilityNegotiationException duplicate = assertThrows(CapabilityNegotiationException.class,
                () -> registry.register(provider("example:duplicate", CAPABILITY, new CapabilityVersion(1, 1, 0), 2)));
        assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID, duplicate.diagnostic().code());
    }

    @Test
    void requiredUnknownAndVersionMismatchFailClosedWithStableDiagnostics() {
        CapabilityRegistry unknownRegistry = new CapabilityRegistry();
        unknownRegistry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CapabilityPlan unknownPlan = unknownRegistry.freeze(1L);

        assertFalse(unknownPlan.isPublishable());
        assertEquals(CapabilityErrorCode.REQUIRED_UNSUPPORTED, unknownPlan.diagnostics().getFirst().code());
        assertEquals(CapabilityErrorCode.REQUIRED_UNSUPPORTED,
                assertThrows(CapabilityNegotiationException.class, unknownPlan::requirePublishable).diagnostic().code());

        CapabilityRegistry mismatchedRegistry = new CapabilityRegistry();
        mismatchedRegistry.register(provider("example:v2", CAPABILITY, new CapabilityVersion(2, 0, 0), 1));
        mismatchedRegistry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CapabilityPlan mismatchPlan = mismatchedRegistry.freeze(2L);

        assertFalse(mismatchPlan.isPublishable());
        assertEquals(CapabilityErrorCode.VERSION_MISMATCH, mismatchPlan.diagnostics().getFirst().code());
    }

    @Test
    void plansAndSelectionsHaveNoPublicForgeableConstructorAndRejectCrossSemanticShapes() {
        assertEquals(0, CapabilityPlan.class.getConstructors().length);
        assertEquals(0, CapabilitySelection.class.getConstructors().length);

        CapabilityRequest required = CapabilityRequest.required(CAPABILITY, VERSION_ONE);
        CapabilityDiagnostic fallback = CapabilityDiagnostic.capability(
                CapabilityErrorCode.OPTIONAL_FALLBACK,
                BlendDiagnosticSeverity.WARNING,
                CAPABILITY,
                "forged fallback");
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySelection(
                required,
                CapabilitySelectionOutcome.FALLBACK,
                Optional.empty(),
                Optional.of(fallback)));

        CapabilityOffer wrongCapabilityAndVersion = new CapabilityOffer(
                BlendResourceId.parse("example:provider"),
                BlendResourceId.parse("example:capability/other"),
                new CapabilityVersion(9, 0, 0),
                1);
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySelection(
                required,
                CapabilitySelectionOutcome.SELECTED,
                Optional.of(wrongCapabilityAndVersion),
                Optional.empty()));
    }

    @Test
    void malformedAndLongProviderClaimsRemainStructuredAndBounded() {
        BlendResourceId nullOfferProviderId = BlendResourceId.parse("example:null_offer");
        BlendProvider nullOfferProvider = new BlendProvider() {
            @Override
            public BlendResourceId providerId() {
                return nullOfferProviderId;
            }

            @Override
            public Collection<CapabilityOffer> offers() {
                return java.util.Collections.singletonList(null);
            }
        };
        CapabilityNegotiationException nullOffer = assertThrows(CapabilityNegotiationException.class,
                () -> new CapabilityRegistry().register(nullOfferProvider));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, nullOffer.diagnostic().code());

        BlendResourceId longProviderId = BlendResourceId.parse("example:" + "p".repeat(700));
        BlendProvider mismatchedLongProvider = new SimpleProvider(longProviderId, List.of(new CapabilityOffer(
                BlendResourceId.parse("example:other"), CAPABILITY, new CapabilityVersion(1, 0, 0), 1)));
        CapabilityNegotiationException longIdentity = assertThrows(CapabilityNegotiationException.class,
                () -> new CapabilityRegistry().register(mismatchedLongProvider));
        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, longIdentity.diagnostic().code());
        assertTrue(longIdentity.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
    }

    @Test
    void oversizedProviderIdentityIsRejectedWithoutRetainingCallerTextInDiagnosticFields() {
        BlendResourceId oversized = BlendResourceId.parse("example:" + "p".repeat(100_000));
        BlendProvider provider = new SimpleProvider(oversized, List.of());
        CapabilityRegistry registry = new CapabilityRegistry();

        CapabilityNegotiationException exception = assertThrows(
                CapabilityNegotiationException.class,
                () -> registry.register(provider));

        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, exception.diagnostic().code());
        assertTrue(exception.diagnostic().providerId().isEmpty());
        assertTrue(exception.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
        assertFalse(exception.diagnostic().message().contains("p".repeat(1_024)));
    }

    @Test
    void providerOfferIteratorFailuresAreNormalizedWithoutRetainingCallerDiagnostics() {
        for (IteratorFailurePoint failurePoint : IteratorFailurePoint.values()) {
            AtomicInteger iteratorCalls = new AtomicInteger();
            BlendResourceId providerId = BlendResourceId.parse("example:hostile_" + failurePoint.name().toLowerCase());
            BlendProvider provider = new SimpleProvider(providerId, hostileOffers(failurePoint, iteratorCalls));

            CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class,
                    () -> new CapabilityRegistry().register(provider));

            assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, exception.diagnostic().code());
            assertEquals(1, iteratorCalls.get());
            assertFalse(exception.diagnostic().message().contains("attacker-secret"));
            assertFalse(exception.diagnostic().message().contains(CapabilityErrorCode.REQUIRED_UNSUPPORTED.code()));
            assertTrue(exception.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
        }
    }

    @Test
    void providerOfferCollectionIsConsumedExactlyOnce() {
        AtomicInteger iteratorCalls = new AtomicInteger();
        BlendResourceId providerId = BlendResourceId.parse("example:one_shot");
        CapabilityOffer offer = new CapabilityOffer(
                providerId, CAPABILITY, new CapabilityVersion(1, 0, 0), 1);
        Collection<CapabilityOffer> oneShot = new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityOffer> iterator() {
                if (iteratorCalls.incrementAndGet() != 1) {
                    throw new IllegalStateException("collection was traversed more than once");
                }
                return List.of(offer).iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };

        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new SimpleProvider(providerId, oneShot));
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        assertTrue(registry.freeze(10L).isPublishable());
        assertEquals(1, iteratorCalls.get());
    }

    @Test
    void providerOfferTraversalHasAFixedCountBound() {
        BlendResourceId providerId = BlendResourceId.parse("example:bounded_offers");
        List<CapabilityOffer> offers = java.util.stream.IntStream
                .rangeClosed(0, CapabilityRegistry.MAX_PROVIDER_OFFERS)
                .mapToObj(index -> new CapabilityOffer(
                        providerId,
                        BlendResourceId.parse("example:capability/claim_" + index),
                        new CapabilityVersion(1, 0, 0),
                        0))
                .toList();

        CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class,
                () -> new CapabilityRegistry().register(new SimpleProvider(providerId, offers)));

        assertEquals(CapabilityErrorCode.INVALID_PROVIDER_OFFER, exception.diagnostic().code());
        assertTrue(exception.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
    }

    @Test
    void optionalRequestUsesOnlyExplicitObservableSafeFallback() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilityFallback fallback = new CapabilityFallback(BlendResourceId.parse("blendlib:cpu_fallback"),
                "CPU baseline is semantic-equivalent for this capability request");
        registry.discover(List.of(CapabilityRequest.optional(CAPABILITY, VERSION_ONE, fallback)));
        CapabilityPlan plan = registry.freeze(3L);

        assertTrue(plan.isPublishable());
        CapabilitySelection selection = plan.selectionFor(CAPABILITY).orElseThrow();
        assertEquals(CapabilitySelectionOutcome.FALLBACK, selection.outcome());
        assertTrue(selection.selectedOffer().isEmpty());
        assertEquals(CapabilityErrorCode.OPTIONAL_FALLBACK, selection.diagnostic().orElseThrow().code());
        assertEquals(BlendResourceId.parse("blendlib:cpu_fallback"),
                selection.request().fallback().orElseThrow().fallbackId());
    }

    @Test
    void tiedHighestPriorityClaimsFailInsteadOfUsingRegistrationOrder() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider("example:zeta", CAPABILITY, new CapabilityVersion(1, 0, 0), 50));
        registry.register(provider("example:alpha", CAPABILITY, new CapabilityVersion(1, 0, 0), 50));
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CapabilityPlan plan = registry.freeze(4L);

        assertFalse(plan.isPublishable());
        assertEquals(CapabilityErrorCode.TOP_PRIORITY_CONFLICT, plan.diagnostics().getFirst().code());
        assertThrows(CapabilityNegotiationException.class, plan::requirePublishable);
    }

    @Test
    void discoveryAndPlanOrderingAreIndependentOfRegistrationOrder() {
        CapabilityOffer alpha = new CapabilityOffer(BlendResourceId.parse("example:alpha"), CAPABILITY,
                new CapabilityVersion(1, 0, 0), 10);
        CapabilityOffer zeta = new CapabilityOffer(BlendResourceId.parse("example:zeta"), CAPABILITY,
                new CapabilityVersion(1, 1, 0), 20);

        CapabilityRegistry first = new CapabilityRegistry();
        first.register(provider(alpha));
        first.register(provider(zeta));
        List<CapabilityOffer> firstDiscovery = first.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CapabilityPlan firstPlan = first.freeze(5L);

        CapabilityRegistry second = new CapabilityRegistry();
        second.register(provider(zeta));
        second.register(provider(alpha));
        List<CapabilityOffer> secondDiscovery = second.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CapabilityPlan secondPlan = second.freeze(5L);

        assertEquals(List.of(zeta, alpha), firstDiscovery);
        assertEquals(firstDiscovery, secondDiscovery);
        assertEquals(firstPlan.selectedOffersInReportingOrder(), secondPlan.selectedOffersInReportingOrder());
        assertEquals(List.of(zeta), firstPlan.selectedOffersInReportingOrder());
    }

    @Test
    void freezeMakesAllLaterMutationsExplicitlyIllegal() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider("example:provider", CAPABILITY, new CapabilityVersion(1, 0, 0), 1));
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        registry.freeze(6L);

        assertTrue(registry.isFrozen());
        assertEquals(CapabilityErrorCode.REGISTRY_FROZEN,
                assertThrows(CapabilityNegotiationException.class,
                        () -> registry.register(provider("example:late", CAPABILITY, new CapabilityVersion(1, 0, 0), 2)))
                        .diagnostic().code());
        assertEquals(CapabilityErrorCode.REGISTRY_FROZEN,
                assertThrows(CapabilityNegotiationException.class,
                        () -> registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE))))
                        .diagnostic().code());
        assertEquals(CapabilityErrorCode.REGISTRY_FROZEN,
                assertThrows(CapabilityNegotiationException.class, () -> registry.freeze(7L)).diagnostic().code());
    }

    @RepeatedTest(20)
    void concurrentFreezeRaceProducesExactlyOneImmutablePlan() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider("example:provider", CAPABILITY, new CapabilityVersion(1, 0, 0), 1));
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<String> freeze = () -> {
                start.await();
                try {
                    return "plan:" + registry.freeze(8L).generation();
                } catch (CapabilityNegotiationException exception) {
                    return "error:" + exception.diagnostic().code();
                }
            };
            Future<String> first = executor.submit(freeze);
            Future<String> second = executor.submit(freeze);
            start.countDown();
            List<String> results = List.of(first.get(), second.get());

            assertEquals(1L, results.stream().filter(value -> value.equals("plan:8")).count());
            assertEquals(1L, results.stream().filter(value -> value.equals("error:" + CapabilityErrorCode.REGISTRY_FROZEN)).count());
        }
        assertEquals(8L, registry.frozenPlan().orElseThrow().generation());
    }

    @RepeatedTest(20)
    void concurrentLateRegistrationAndFreezeCannotMutateTheFrozenPlan() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(provider("example:initial", CAPABILITY, new CapabilityVersion(1, 0, 0), 1));
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_ONE)));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> lateRegistration = executor.submit(() -> {
                start.await();
                try {
                    registry.register(provider("example:late", CAPABILITY, new CapabilityVersion(1, 0, 0), 2));
                    return "late-register-succeeded";
                } catch (CapabilityNegotiationException exception) {
                    return "late-register-error:" + exception.diagnostic().code();
                }
            });
            Future<String> freeze = executor.submit(() -> {
                start.await();
                return "freeze:" + registry.freeze(9L).generation();
            });
            start.countDown();

            assertEquals("freeze:9", freeze.get());
            String registrationResult = lateRegistration.get();
            assertTrue(registrationResult.equals("late-register-error:" + CapabilityErrorCode.INVALID_LIFECYCLE_STATE)
                    || registrationResult.equals("late-register-error:" + CapabilityErrorCode.REGISTRY_FROZEN));
        }
        assertEquals(List.of(BlendResourceId.parse("example:initial")), registry.registeredProviderIds());
        assertEquals(List.of(BlendResourceId.parse("example:initial")), registry.frozenPlan().orElseThrow()
                .selectedOffersInReportingOrder().stream().map(CapabilityOffer::providerId).toList());
    }

    private static SimpleProvider provider(String providerId, BlendResourceId capabilityId, CapabilityVersion version, int priority) {
        return provider(new CapabilityOffer(BlendResourceId.parse(providerId), capabilityId, version, priority));
    }

    private static SimpleProvider provider(CapabilityOffer offer) {
        return new SimpleProvider(offer.providerId(), List.of(offer));
    }

    private static Collection<CapabilityOffer> hostileOffers(
            IteratorFailurePoint failurePoint,
            AtomicInteger iteratorCalls) {
        return new AbstractCollection<>() {
            @Override
            public Iterator<CapabilityOffer> iterator() {
                iteratorCalls.incrementAndGet();
                if (failurePoint == IteratorFailurePoint.ITERATOR) {
                    throw hostileFailure();
                }
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        if (failurePoint == IteratorFailurePoint.HAS_NEXT) {
                            throw hostileFailure();
                        }
                        return true;
                    }

                    @Override
                    public CapabilityOffer next() {
                        throw hostileFailure();
                    }
                };
            }

            @Override
            public int size() {
                return 1;
            }
        };
    }

    private static CapabilityNegotiationException hostileFailure() {
        return new CapabilityNegotiationException(CapabilityDiagnostic.unscoped(
                CapabilityErrorCode.REQUIRED_UNSUPPORTED,
                BlendDiagnosticSeverity.ERROR,
                "attacker-secret-" + "x".repeat(CapabilityDiagnostic.MAX_MESSAGE_LENGTH - 16)));
    }

    private enum IteratorFailurePoint {
        ITERATOR,
        HAS_NEXT,
        NEXT
    }

    private record SimpleProvider(BlendResourceId providerId, Collection<CapabilityOffer> offers) implements BlendProvider {
    }
}
