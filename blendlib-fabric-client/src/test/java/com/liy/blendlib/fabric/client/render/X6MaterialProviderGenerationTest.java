package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.spi.experimental.CapabilityErrorCode;
import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityNegotiationException;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRegistry;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilitySelectionOutcome;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleState;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** X1 lifecycle integration tests for the X6 adapter-private material provider bridge. */
class X6MaterialProviderGenerationTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x6_provider_test:actor/base");
    private static final BlendResourceId CAPABILITY = id("standard_material");
    private static final CapabilityVersionRange VERSION_RANGE = CapabilityVersion.CURRENT_PROTOCOL_RANGE;

    @Test
    void bridgeFreezesSelectedProviderBeforePinAndRetiresOnlyAfterPinsDrain() {
        TestProvider lower = provider("lower", CAPABILITY, 5, true, false);
        TestProvider higher = provider("higher", CAPABILITY, 50, true, false);
        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 91L, List.of(lower, higher), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertTrue(generation.publishable());
        assertEquals(id("higher"), generation.capabilityPlan().orElseThrow()
                .selectionFor(CAPABILITY).orElseThrow().selectedOffer().orElseThrow().providerId());
        assertEquals(List.of(new X6MaterialProviderBinding(
                CAPABILITY, CapabilitySelectionOutcome.SELECTED, java.util.Optional.of(id("higher")), java.util.Optional.empty())),
                generation.materialBindings());
        assertEquals(0, lower.prepareCalls.get(), "unselected providers do not enter lifecycle callbacks");
        assertEquals(1, higher.prepareCalls.get());
        assertEquals(1, higher.applyCalls.get());

        int offersAfterFreeze = higher.offerCalls.get();
        ProviderLease first = generation.pinSnapshot();
        ProviderLease second = generation.pinSnapshot();
        assertEquals(offersAfterFreeze, higher.offerCalls.get(), "pins cannot rediscover provider metadata");
        generation.retire();
        assertFalse(generation.publishable(), "RETIRING must never remain factory-publishable");
        assertEquals(ProviderLifecycleState.RETIRING, generation.lifecycleState().orElseThrow());
        assertEquals(0, higher.retireCalls.get(), "retirement waits for every exact-generation pin");
        first.close();
        assertEquals(0, higher.retireCalls.get());
        second.close();
        assertEquals(1, higher.retireCalls.get());
        assertEquals(1, higher.closeCalls.get());
        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
        generation.close();
        assertEquals(1, higher.closeCalls.get(), "generation close remains precise after retirement completed");
    }

    @Test
    void currentX6RequestDoesNotSilentlySelectAHistoricalProtocolOffer() {
        TestProvider legacy = provider(
                "legacy_protocol", CAPABILITY, 5, true, false, CapabilityVersion.INITIAL_PROTOCOL);
        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 90_001L, List.of(legacy), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));

        try {
            assertFalse(generation.publishable());
            assertTrue(generation.diagnostics().stream()
                    .anyMatch(value -> value.code() == X6DiagnosticCode.CAPABILITY_FAILURE));
            assertEquals(0, legacy.prepareCalls.get());
            assertEquals(0, legacy.applyCalls.get());
            assertEquals(0, legacy.retireCalls.get());
            assertEquals(0, legacy.closeCalls.get());
        } finally {
            generation.close();
        }
    }

    @Test
    void broadCallerRangeWithOnlyHistoricalOfferCannotEnterCurrentX6Generation() {
        CapabilityVersionRange broadCallerRange = new CapabilityVersionRange(
                CapabilityVersion.INITIAL_PROTOCOL, new CapabilityVersion(2, 0, 0));
        TestProvider legacy = provider(
                "broad_legacy_protocol", CAPABILITY, 5, true, false, CapabilityVersion.INITIAL_PROTOCOL);

        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 90_002L, List.of(legacy), List.of(CapabilityRequest.required(CAPABILITY, broadCallerRange)));

        try {
            assertFalse(generation.publishable(), "X6 must intersect broad caller input with its current protocol range");
            assertTrue(generation.diagnostics().stream()
                    .anyMatch(value -> value.code() == X6DiagnosticCode.CAPABILITY_FAILURE));
            assertTrue(generation.capabilityPlan().isEmpty(), "a historical offer must not become a current X6 plan");
            assertEquals(0, legacy.prepareCalls.get());
            assertEquals(0, legacy.applyCalls.get());
            assertEquals(0, legacy.retireCalls.get());
            assertEquals(0, legacy.closeCalls.get());
        } finally {
            generation.close();
        }
    }

    @Test
    void broadCallerRangeWithCurrentOfferPublishesAfterX6ProtocolIntersection() {
        CapabilityVersionRange broadCallerRange = new CapabilityVersionRange(
                CapabilityVersion.INITIAL_PROTOCOL, new CapabilityVersion(2, 0, 0));
        TestProvider current = provider(
                "broad_current_protocol", CAPABILITY, 5, true, false, CapabilityVersion.CURRENT_PROTOCOL);

        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 90_003L, List.of(current), List.of(CapabilityRequest.required(CAPABILITY, broadCallerRange)));

        try {
            assertTrue(generation.publishable());
            assertEquals(CapabilityVersion.CURRENT_PROTOCOL, generation.capabilityPlan().orElseThrow()
                    .selectionFor(CAPABILITY).orElseThrow().selectedOffer().orElseThrow().protocolVersion());
            assertEquals(1, current.prepareCalls.get());
            assertEquals(1, current.applyCalls.get());
        } finally {
            generation.close();
        }
        assertEquals(1, current.retireCalls.get());
        assertEquals(1, current.closeCalls.get());
    }

    @Test
    void optionalFallbackAndRequiredFailuresStayModelGenerationLocal() {
        X6MaterialProviderGeneration fallback = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                92L,
                List.of(),
                List.of(CapabilityRequest.optional(
                        CAPABILITY,
                        VERSION_RANGE,
                        new CapabilityFallback(id("safe_vanilla_fallback"), "uses the declared standard material path"))));
        try {
            assertTrue(fallback.publishable());
            assertEquals(CapabilitySelectionOutcome.FALLBACK, fallback.capabilityPlan().orElseThrow()
                    .selectionFor(CAPABILITY).orElseThrow().outcome());
            assertEquals(CapabilitySelectionOutcome.FALLBACK, fallback.materialBindings().getFirst().outcome());
        } finally {
            fallback.close();
        }

        TestProvider broken = provider("broken", CAPABILITY, 9, true, true);
        X6MaterialProviderGeneration failed = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 93L, List.of(broken), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(failed.publishable());
        assertTrue(failed.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE));

        TestProvider independent = provider("independent", CAPABILITY, 9, true, false);
        X6MaterialProviderGeneration succeeding = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 94L, List.of(independent), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        try {
            assertTrue(succeeding.publishable(), "a failed generation must not poison an independent provider generation");
            assertEquals(1, independent.prepareCalls.get());
        } finally {
            succeeding.close();
        }
    }

    @Test
    void conflictsAndProvidersThatDoNotAdvertiseFrozenMaterialCapabilityCannotPublish() {
        TestProvider left = provider("left", CAPABILITY, 12, true, false);
        TestProvider right = provider("right", CAPABILITY, 12, true, false);
        X6MaterialProviderGeneration conflict = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 95L, List.of(left, right), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(conflict.publishable());
        assertTrue(conflict.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.CAPABILITY_FAILURE));

        TestProvider inconsistent = provider("inconsistent", CAPABILITY, 12, false, false);
        X6MaterialProviderGeneration mismatch = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 96L, List.of(inconsistent), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(mismatch.publishable());
        assertTrue(mismatch.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.CAPABILITY_FAILURE));
        assertEquals(0, inconsistent.prepareCalls.get(), "bridge validates material capability advertisement before callbacks");
        assertEquals(1, inconsistent.retireCalls.get(), "post-freeze rejection must release selected ownership through X1");
        assertEquals(1, inconsistent.closeCalls.get(), "only the X1 owner may close a post-freeze selected provider");
    }

    @Test
    void duplicateCanonicalProviderIdsFailClosedBeforeRegistryOrLifecycleRegardlessOfInputOrder() {
        TestProvider forwardA = provider("duplicate_order", CAPABILITY, 30, true, false);
        TestProvider forwardB = provider("duplicate_order", CAPABILITY, 10, true, false);
        TestProvider reverseA = provider("duplicate_order", CAPABILITY, 30, true, false);
        TestProvider reverseB = provider("duplicate_order", CAPABILITY, 10, true, false);
        BlendResourceId duplicateId = forwardA.providerId();

        TestProvider metadataFirstBroken = provider("duplicate_metadata", CAPABILITY, 30, true, false);
        metadataFirstBroken.supportedFailure = new IllegalStateException("must be masked by duplicate identity");
        TestProvider metadataFirstPeer = provider("duplicate_metadata", CAPABILITY, 10, true, false);
        TestProvider metadataLastPeer = provider("duplicate_metadata", CAPABILITY, 10, true, false);
        TestProvider metadataLastBroken = provider("duplicate_metadata", CAPABILITY, 30, true, false);
        metadataLastBroken.supportedFailure = new IllegalStateException("must be masked by duplicate identity");
        BlendResourceId metadataDuplicateId = metadataFirstBroken.providerId();

        X6MaterialProviderGeneration forward = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 109L, List.of(forwardA, forwardB), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        X6MaterialProviderGeneration reverse = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 109L, List.of(reverseB, reverseA), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        X6MaterialProviderGeneration metadataFirst = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                112L,
                List.of(metadataFirstBroken, metadataFirstPeer),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        X6MaterialProviderGeneration metadataLast = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                112L,
                List.of(metadataLastPeer, metadataLastBroken),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertAll(
                () -> assertDuplicateFailure(forward, duplicateId),
                () -> assertDuplicateFailure(reverse, duplicateId),
                () -> assertEquals(forward.diagnostics(), reverse.diagnostics(),
                        "input order must not change duplicate-id semantics"),
                () -> assertDuplicateFailure(metadataFirst, metadataDuplicateId),
                () -> assertDuplicateFailure(metadataLast, metadataDuplicateId),
                () -> assertEquals(metadataFirst.diagnostics(), metadataLast.diagnostics(),
                        "duplicate identity must win before order-dependent capability metadata"),
                () -> assertNoCallbacks(forwardA),
                () -> assertNoCallbacks(forwardB),
                () -> assertNoCallbacks(reverseA),
                () -> assertNoCallbacks(reverseB),
                () -> assertNoCallbacks(metadataFirstBroken),
                () -> assertNoCallbacks(metadataFirstPeer),
                () -> assertNoCallbacks(metadataLastPeer),
                () -> assertNoCallbacks(metadataLastBroken),
                forward::close,
                reverse::close,
                metadataFirst::close,
                metadataLast::close,
                () -> assertNoCallbacks(forwardA),
                () -> assertNoCallbacks(forwardB),
                () -> assertNoCallbacks(reverseA),
                () -> assertNoCallbacks(reverseB),
                () -> assertNoCallbacks(metadataFirstBroken),
                () -> assertNoCallbacks(metadataFirstPeer),
                () -> assertNoCallbacks(metadataLastPeer),
                () -> assertNoCallbacks(metadataLastBroken));
    }

    @Test
    void duplicateObjectAndHostileOnePassCollectionUseTheSameFailClosedIdentityDiagnostic() {
        TestProvider repeated = provider("duplicate_object", CAPABILITY, 20, true, false);
        X6MaterialProviderGeneration repeatedResult = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 110L, List.of(repeated, repeated), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));

        TestProvider hostileA = provider("duplicate_hostile", CAPABILITY, 20, true, false);
        TestProvider hostileB = provider("duplicate_hostile", CAPABILITY, 10, true, false);
        IteratorOnlyProviderCollection hostileProviders =
                new IteratorOnlyProviderCollection(List.of(hostileA, hostileB));
        X6MaterialProviderGeneration hostileResult = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                111L,
                hostileProviders,
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertAll(
                () -> assertDuplicateFailure(repeatedResult, repeated.providerId()),
                () -> assertDuplicateFailure(hostileResult, hostileA.providerId()),
                () -> assertEquals(1, hostileProviders.iteratorCalls.get(),
                        "caller-owned provider collection must be traversed exactly once"),
                () -> assertNoCallbacks(repeated),
                () -> assertNoCallbacks(hostileA),
                () -> assertNoCallbacks(hostileB),
                repeatedResult::close,
                hostileResult::close,
                () -> assertNoCallbacks(repeated),
                () -> assertNoCallbacks(hostileA),
                () -> assertNoCallbacks(hostileB));
    }

    @Test
    void directX1RegistryRejectsDuplicateProviderIdWithCanonicalCap001BeforeContenderOffers() {
        TestProvider first = provider("x1_duplicate_control", CAPABILITY, 20, true, false);
        TestProvider contender = provider("x1_duplicate_control", CAPABILITY, 10, true, false);
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(first);

        CapabilityNegotiationException duplicate = assertThrows(
                CapabilityNegotiationException.class, () -> registry.register(contender));

        assertEquals(CapabilityErrorCode.DUPLICATE_PROVIDER_ID, duplicate.diagnostic().code());
        assertEquals("BLENDLIB-X1-CAP-001", duplicate.diagnostic().code().code());
        assertEquals(Optional.of(first.providerId()), duplicate.diagnostic().providerId());
        assertEquals(1, first.offerCalls.get());
        assertEquals(0, contender.offerCalls.get(), "X1 rejects the duplicate identity before contender offers");
        assertEquals(0, first.prepareCalls.get());
        assertEquals(0, contender.prepareCalls.get());
        assertEquals(0, first.closeCalls.get(), "registry metadata remains caller-owned before any lifecycle session");
        assertEquals(0, contender.closeCalls.get());
    }

    @Test
    void retireClosedAndConcurrentPinRaceBecomeLocalFactoryDiagnosticsAndDoubleCloseIsIdempotent() throws InterruptedException {
        TestProvider provider = provider("race", CAPABILITY, 7, true, false);
        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 97L, List.of(provider), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertTrue(generation.publishable());
        ProviderLease blocker = generation.pinSnapshot();
        Thread retireThread = new Thread(generation::retire, "x6-provider-retire-test");
        retireThread.start();
        long retireDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (generation.lifecycleState().orElseThrow() != ProviderLifecycleState.RETIRING
                && System.nanoTime() < retireDeadline) {
            Thread.yield();
        }
        assertEquals(ProviderLifecycleState.RETIRING, generation.lifecycleState().orElseThrow());
        assertThrows(IllegalStateException.class, generation::pinSnapshot, "no new pin may win after retire starts");
        Inputs inputs = inputs(97L);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PlanResult<X6PreparedRenderPlan> failedFactory = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                generation,
                inputs.bindingSnapshot(),
                lifecycleOwner);
        assertFalse(failedFactory.publishable());
        assertTrue(failedFactory.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.CAPABILITY_FAILURE));
        assertEquals(0, lifecycleOwner.registrationCount(), "retired pre-pin state must reject before lifecycle-owner admission");
        assertEquals(0, lifecycleOwner.cancelCount());
        assertEquals(0, lifecycleOwner.registeredCount());
        assertEquals(0, lifecycleOwner.pendingCount());
        blocker.close();
        retireThread.join(5_000L);
        assertFalse(retireThread.isAlive());
        assertEquals(ProviderLifecycleState.CLOSED, generation.lifecycleState().orElseThrow());
        generation.close();
        generation.close();
        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
    }

    @Test
    void nonfatalNullAndCallbackFailuresDiagnoseWhileFatalLifecycleFailuresPropagate() {
        X6MaterialProviderGeneration nullProvider = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                98L,
                Collections.<MaterialProvider>singletonList(null),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(nullProvider.publishable());
        assertTrue(nullProvider.diagnostics().stream().anyMatch(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE));

        TestProvider fatal = provider("fatal", CAPABILITY, 8, true, false);
        fatal.fatalPrepare = true;
        assertThrows(TestFatal.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 99L, List.of(fatal), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
    }

    @Test
    void providerInputAssertionErrorEscapesWithExactIdentity() {
        AssertionError expected = new AssertionError("provider input");
        AssertionError actual = assertThrows(AssertionError.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                9_001L,
                new HostileProviderCollection(expected),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
        assertSame(expected, actual);
    }

    @Test
    void providerIdAssertionErrorEscapesWithExactIdentity() {
        TestProvider provider = provider("provider_id_assertion", CAPABILITY, 10, true, false);
        AssertionError expected = new AssertionError("provider id");
        provider.providerIdError = expected;
        assertProviderGenerationError(expected, provider, 9_002L);
    }

    @Test
    void supportedMetadataLinkageErrorEscapesWithExactIdentity() {
        TestProvider provider = provider("supported_linkage", CAPABILITY, 10, true, false);
        LinkageError expected = new LinkageError("supported metadata");
        provider.supportedError = expected;
        assertProviderGenerationError(expected, provider, 9_003L);
    }

    @Test
    void offersAssertionErrorEscapesWithExactIdentity() {
        TestProvider provider = provider("offers_assertion", CAPABILITY, 10, true, false);
        AssertionError expected = new AssertionError("offers");
        provider.offerError = expected;
        assertProviderGenerationError(expected, provider, 9_004L);
    }

    @Test
    void prepareAssertionErrorEscapesWithExactIdentityAfterTerminalCleanup() {
        TestProvider provider = provider("prepare_assertion", CAPABILITY, 10, true, false);
        AssertionError expected = new AssertionError("prepare");
        provider.prepareError = expected;
        assertProviderGenerationError(expected, provider, 9_005L);
        assertEquals(1, provider.closeCalls.get(), "fatal prepare must still release selected ownership exactly once");
    }

    @Test
    void applyLinkageErrorEscapesWithExactIdentityAfterTerminalCleanup() {
        TestProvider provider = provider("apply_linkage", CAPABILITY, 10, true, false);
        LinkageError expected = new LinkageError("apply");
        provider.applyError = expected;
        assertProviderGenerationError(expected, provider, 9_006L);
        assertEquals(1, provider.closeCalls.get(), "fatal apply must still release selected ownership exactly once");
    }

    @Test
    void retireAssertionErrorEscapesWithExactIdentityAfterTerminalCleanup() {
        TestProvider provider = provider("retire_assertion", CAPABILITY, 10, true, false);
        AssertionError expected = new AssertionError("retire");
        provider.retireError = expected;
        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 9_007L, List.of(provider), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertTrue(generation.publishable());
        AssertionError actual = assertThrows(AssertionError.class, generation::retire);
        assertAll(
                () -> assertSame(expected, actual),
                () -> assertEquals(ProviderLifecycleState.CLOSED, generation.lifecycleState().orElseThrow()),
                () -> assertEquals(1, provider.retireCalls.get()),
                () -> assertEquals(1, provider.closeCalls.get()));
    }

    @Test
    void closeLinkageErrorEscapesWithExactIdentityAfterTerminalCleanup() {
        TestProvider provider = provider("close_linkage", CAPABILITY, 10, true, false);
        LinkageError expected = new LinkageError("close");
        provider.closeError = expected;
        X6MaterialProviderGeneration generation = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 9_008L, List.of(provider), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertTrue(generation.publishable());
        LinkageError actual = assertThrows(LinkageError.class, generation::retire);
        assertAll(
                () -> assertSame(expected, actual),
                () -> assertEquals(ProviderLifecycleState.CLOSED, generation.lifecycleState().orElseThrow()),
                () -> assertEquals(1, provider.retireCalls.get()),
                () -> assertEquals(1, provider.closeCalls.get()));
    }

    @Test
    @SuppressWarnings("removal")
    void outOfMemoryAndThreadDeathRemainFatalWhileOrdinaryProviderInputRemainsContained() {
        OutOfMemoryError outOfMemory = new OutOfMemoryError("provider input");
        assertSame(outOfMemory, assertThrows(OutOfMemoryError.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                9_009L,
                new HostileProviderCollection(outOfMemory),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)))));

        ThreadDeath threadDeath = new ThreadDeath();
        assertSame(threadDeath, assertThrows(ThreadDeath.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                9_010L,
                new HostileProviderCollection(threadDeath),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)))));

        CapabilityRequest optional = CapabilityRequest.optional(
                CAPABILITY,
                VERSION_RANGE,
                new CapabilityFallback(id("ordinary_input_fallback"), "declared standard fallback"));
        X6MaterialProviderGeneration ordinary = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                9_011L,
                new HostileProviderCollection(new IllegalStateException("ordinary input")),
                List.of(optional));
        try {
            assertTrue(ordinary.publishable());
        } finally {
            ordinary.close();
        }
    }

    @Test
    void preSessionProvidersRemainCallerOwnedAcrossMetadataAndRegistryFailure() {
        TestProvider shared = provider("shared", CAPABILITY, 30, true, false);
        X6MaterialProviderGeneration published = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 100L, List.of(shared), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertTrue(published.publishable());
        ProviderLease pin = published.pinSnapshot();
        MetadataRejectingProvider metadataRejected = new MetadataRejectingProvider();
        TestProvider hostileOffers = provider("hostile_offers", CAPABILITY, 10, true, false);
        hostileOffers.offerFailure = new IllegalStateException("registry offer failure");
        try {
            X6MaterialProviderGeneration replacement = X6MaterialProviderGeneration.prepareAndPublish(
                    KEY,
                    101L,
                    List.of(shared, metadataRejected, hostileOffers),
                    List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
            assertFalse(replacement.publishable());
            assertTrue(published.publishable(), "a rejected next generation must not disturb the pinned old generation");
            assertEquals(0, shared.closeCalls.get(), "X6 may not close a caller-owned pre-session shared provider");
            assertEquals(0, metadataRejected.closeCalls.get(), "metadata rejection must not be directly closed again during registry failure");
            assertEquals(0, hostileOffers.closeCalls.get(), "registry failure before session creation remains caller-owned");

            published.retire();
            assertEquals(0, shared.retireCalls.get(), "X1 retirement waits for the old generation pin");
            assertEquals(0, shared.closeCalls.get());
            pin.close();
            assertEquals(1, shared.retireCalls.get());
            assertEquals(1, shared.closeCalls.get(), "only X1 releases the shared identity after the final pin drains");
            published.close();
            assertEquals(1, shared.closeCalls.get(), "X1 terminal close remains exactly once");
        } finally {
            if (!pin.isClosed()) {
                pin.close();
            }
            published.close();
        }
    }

    @Test
    void optionalFallbackSurvivesNullAndHostileProviderInputWhileFatalInputKeepsIdentity() {
        CapabilityRequest optional = CapabilityRequest.optional(
                CAPABILITY,
                VERSION_RANGE,
                new CapabilityFallback(id("input_fallback"), "declared standard fallback"));
        X6MaterialProviderGeneration nullFallback = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 102L, Collections.<MaterialProvider>singletonList(null), List.of(optional));
        try {
            assertTrue(nullFallback.publishable());
            assertEquals(CapabilitySelectionOutcome.FALLBACK, nullFallback.materialBindings().getFirst().outcome());
        } finally {
            nullFallback.close();
        }

        X6MaterialProviderGeneration hostileFallback = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 103L, new HostileProviderCollection(new IllegalStateException("hostile provider iterator")), List.of(optional));
        try {
            assertTrue(hostileFallback.publishable(), "a containable input traversal failure leaves only the explicit fallback");
            assertEquals(CapabilitySelectionOutcome.FALLBACK, hostileFallback.materialBindings().getFirst().outcome());
        } finally {
            hostileFallback.close();
        }

        TestProvider iteratorOnly = provider("iterator_only", CAPABILITY, 10, true, false);
        X6MaterialProviderGeneration iteratorOnlyResult = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 104L, new IteratorOnlyProviderCollection(iteratorOnly), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        try {
            assertTrue(iteratorOnlyResult.publishable(), "provider input snapshot must not call hostile size or toArray");
        } finally {
            iteratorOnlyResult.close();
        }

        TestFatal fatal = new TestFatal();
        TestFatal thrown = assertThrows(TestFatal.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 105L, new HostileProviderCollection(fatal), List.of(optional)));
        assertSame(fatal, thrown, "fatal input traversal keeps its original object identity");
    }

    @Test
    void lifecycleCleanupConsumesOrdinaryResultAndRethrowsFatalCleanupIdentity() {
        TestProvider ordinary = provider("ordinary_cleanup", CAPABILITY, 10, true, true);
        ordinary.closeFailure = new IllegalStateException("ordinary close failure");
        X6MaterialProviderGeneration ordinaryResult = X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 106L, List.of(ordinary), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertFalse(ordinaryResult.publishable());
        assertEquals(1, ordinary.retireCalls.get());
        assertEquals(1, ordinary.closeCalls.get());
        assertEquals(
                2,
                ordinaryResult.diagnostics().stream()
                        .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                        .count(),
                "prepare and terminal close diagnostics must each be retained once");

        TestProvider prepareFatalCleanup = provider("prepare_fatal_cleanup", CAPABILITY, 10, true, true);
        CleanupOutOfMemoryError prepareFatal = new CleanupOutOfMemoryError();
        prepareFatalCleanup.closeError = prepareFatal;
        CleanupOutOfMemoryError prepareThrown = assertThrows(CleanupOutOfMemoryError.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 107L, List.of(prepareFatalCleanup), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
        assertSame(prepareFatal, prepareThrown);
        assertEquals(1, prepareFatalCleanup.retireCalls.get());
        assertEquals(1, prepareFatalCleanup.closeCalls.get());

        TestProvider applyFatalCleanup = provider("apply_fatal_cleanup", CAPABILITY, 10, true, false);
        applyFatalCleanup.failApply = true;
        ThreadDeath cleanupDeath = new ThreadDeath();
        applyFatalCleanup.closeError = cleanupDeath;
        ThreadDeath applyThrown = assertThrows(ThreadDeath.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, 108L, List.of(applyFatalCleanup), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
        assertSame(cleanupDeath, applyThrown);
        assertEquals(1, applyFatalCleanup.retireCalls.get());
        assertEquals(1, applyFatalCleanup.closeCalls.get());
    }

    private static Inputs inputs(long generation) {
        BlendResourceId part = id("part");
        PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(
                0,
                StaticGeometry.of(
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new int[] {0, 1, 2}),
                new RenderMaterial(id("textures/base.png"), RenderLayer.SOLID, false, false, 0xFFFFFFFF, false));
        X6PreparedGeometryCatalog geometry = new X6PreparedGeometryCatalog(KEY, generation, Map.of(part, primitive));
        X6MaterialPlan materials = X6MaterialPlan.prepare(KEY, generation, Map.of(
                part, new X6MaterialIntent(id("textures/plan.png"), X6MaterialMode.OPAQUE, false, false, null))).plan().orElseThrow();
        X6VariantApplicationPlan variants = new X6VariantApplicationPlan(
                KEY,
                generation,
                List.of(new X6DrawPrimitive(part, geometry.binding(part), materials.material(part), 0xFFFFFFFF)),
                List.of());
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, generation, List.of(), geometry).plan().orElseThrow();
        X6TestRenderHandle handle = new X6TestRenderHandle(KEY, generation, List.of(primitive), Map.of(0, Transform.IDENTITY), false);
        ModelRenderSnapshot bindingSnapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        return new Inputs(geometry, materials, variants, layers, bindingSnapshot);
    }

    private static TestProvider provider(
            String name, BlendResourceId capability, int priority, boolean supportsCapability, boolean failPrepare) {
        return provider(name, capability, priority, supportsCapability, failPrepare, CapabilityVersion.CURRENT_PROTOCOL);
    }

    private static TestProvider provider(
            String name,
            BlendResourceId capability,
            int priority,
            boolean supportsCapability,
            boolean failPrepare,
            CapabilityVersion protocolVersion) {
        return new TestProvider(id(name), capability, priority, supportsCapability, failPrepare, protocolVersion);
    }

    private static void assertProviderGenerationError(Error expected, TestProvider provider, long generation) {
        Error actual = assertThrows(Error.class, () -> X6MaterialProviderGeneration.prepareAndPublish(
                KEY, generation, List.of(provider), List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE))));
        assertSame(expected, actual);
    }

    private static void assertDuplicateFailure(
            X6MaterialProviderGeneration generation, BlendResourceId duplicateProviderId) {
        assertFalse(generation.publishable());
        assertTrue(generation.capabilityPlan().isEmpty(), "duplicate input must not retain an X1 plan");
        assertTrue(generation.materialBindings().isEmpty(), "duplicate input must not expose a selected binding");
        assertTrue(generation.lifecycleState().isEmpty(), "duplicate input must fail before session ownership");
        assertEquals(1, generation.diagnostics().size());
        X6Diagnostic duplicate = generation.diagnostics().getFirst();
        assertEquals(X6DiagnosticSeverity.ERROR, duplicate.severity());
        assertEquals(X6DiagnosticCode.CAPABILITY_FAILURE, duplicate.code());
        assertEquals(Optional.of(duplicateProviderId), duplicate.subjectId());
        assertEquals(
                "BLENDLIB-X1-CAP-001: Provider identity is already registered or being registered",
                duplicate.message());
    }

    private static void assertNoCallbacks(TestProvider provider) {
        assertEquals(0, provider.supportedCalls.get(),
                "duplicate identity scan must precede material-capability metadata");
        assertEquals(0, provider.offerCalls.get(), "duplicate preflight must precede registry offer metadata");
        assertEquals(0, provider.prepareCalls.get());
        assertEquals(0, provider.applyCalls.get());
        assertEquals(0, provider.retireCalls.get());
        assertEquals(0, provider.closeCalls.get(), "pre-session duplicate providers remain caller-owned");
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("x6_provider_test:" + path);
    }

    private static final class TestProvider implements MaterialProvider {
        private final BlendResourceId providerId;
        private final CapabilityOffer offer;
        private final Set<BlendResourceId> supported;
        private final boolean failPrepare;
        private boolean fatalPrepare;
        private boolean failApply;
        private Error providerIdError;
        private Error offerError;
        private Error supportedError;
        private Error prepareError;
        private Error applyError;
        private Error retireError;
        private RuntimeException offerFailure;
        private RuntimeException supportedFailure;
        private RuntimeException closeFailure;
        private Error closeError;
        private final AtomicInteger offerCalls = new AtomicInteger();
        private final AtomicInteger supportedCalls = new AtomicInteger();
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger applyCalls = new AtomicInteger();
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TestProvider(
                BlendResourceId providerId,
                BlendResourceId capability,
                int priority,
                boolean supportsCapability,
                boolean failPrepare,
                CapabilityVersion protocolVersion) {
            this.providerId = providerId;
            this.offer = new CapabilityOffer(providerId, capability, protocolVersion, priority);
            this.supported = supportsCapability ? Set.of(capability) : Set.of();
            this.failPrepare = failPrepare;
        }

        @Override
        public BlendResourceId providerId() {
            if (providerIdError != null) {
                throw providerIdError;
            }
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            offerCalls.incrementAndGet();
            if (offerError != null) {
                throw offerError;
            }
            if (offerFailure != null) {
                throw offerFailure;
            }
            return List.of(offer);
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            supportedCalls.incrementAndGet();
            if (supportedError != null) {
                throw supportedError;
            }
            if (supportedFailure != null) {
                throw supportedFailure;
            }
            return supported;
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            prepareCalls.incrementAndGet();
            if (prepareError != null) {
                throw prepareError;
            }
            if (failPrepare) {
                throw new IllegalStateException("isolated prepare failure");
            }
            if (fatalPrepare) {
                throw new TestFatal();
            }
        }

        @Override
        public void apply(ProviderLifecycleContext context) {
            applyCalls.incrementAndGet();
            if (applyError != null) {
                throw applyError;
            }
            if (failApply) {
                throw new IllegalStateException("isolated apply failure");
            }
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
            if (retireError != null) {
                throw retireError;
            }
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (closeError != null) {
                throw closeError;
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private record Inputs(
            X6PreparedGeometryCatalog geometry,
            X6MaterialPlan materials,
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            ModelRenderSnapshot bindingSnapshot) {
    }

    private static final class TestFatal extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }

    private static final class CleanupOutOfMemoryError extends OutOfMemoryError {
        private static final long serialVersionUID = 1L;
    }

    private static final class HostileProviderCollection extends AbstractCollection<MaterialProvider> {
        private final Error fatal;
        private final RuntimeException nonfatal;

        private HostileProviderCollection(RuntimeException nonfatal) {
            this.nonfatal = nonfatal;
            this.fatal = null;
        }

        private HostileProviderCollection(Error fatal) {
            this.nonfatal = null;
            this.fatal = fatal;
        }

        @Override
        public Iterator<MaterialProvider> iterator() {
            if (fatal != null) {
                throw fatal;
            }
            throw nonfatal;
        }

        @Override
        public int size() {
            throw new AssertionError("X6 must not use hostile provider collection size");
        }
    }

    private static final class IteratorOnlyProviderCollection extends AbstractCollection<MaterialProvider> {
        private final List<MaterialProvider> providers;
        private final AtomicInteger iteratorCalls = new AtomicInteger();

        private IteratorOnlyProviderCollection(MaterialProvider provider) {
            this(List.of(provider));
        }

        private IteratorOnlyProviderCollection(Collection<? extends MaterialProvider> providers) {
            this.providers = List.copyOf(providers);
        }

        @Override
        public Iterator<MaterialProvider> iterator() {
            if (iteratorCalls.incrementAndGet() != 1) {
                throw new AssertionError("X6 must traverse caller-owned provider input exactly once");
            }
            return providers.iterator();
        }

        @Override
        public int size() {
            throw new AssertionError("X6 must not use hostile provider collection size");
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("X6 must not use hostile provider collection toArray");
        }
    }

    private static final class MetadataRejectingProvider implements MaterialProvider {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public BlendResourceId providerId() {
            throw new IllegalStateException("metadata rejection");
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            throw new AssertionError("metadata-rejected provider must not reach offers");
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            throw new AssertionError("metadata-rejected provider must not reach supported capabilities");
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
