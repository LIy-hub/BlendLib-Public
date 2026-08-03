package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ProviderLifecycleSessionTest {
    private static final BlendResourceId CAPABILITY = BlendResourceId.parse("example:capability/render");
    private static final BlendResourceId SECOND_CAPABILITY = BlendResourceId.parse("example:capability/material");
    private static final CapabilityVersionRange VERSION_RANGE = new CapabilityVersionRange(
            new CapabilityVersion(1, 0, 0), new CapabilityVersion(2, 0, 0));

    @AfterEach
    void clearPlatformAdapterControl() {
        PlatformAdapterControl.global().uninstall();
    }

    @Test
    void prepareFailureIsIsolatedToItsGenerationAndStopsLaterProviderCallbacks() {
        CountingProvider failing = new CountingProvider("example:first", CAPABILITY, 20, true, false);
        CountingProvider neverPrepared = new CountingProvider("example:second", SECOND_CAPABILITY, 10, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(11L, failing, neverPrepared),
                List.of(failing, neverPrepared));

        ProviderLifecycleResult result = session.prepare();

        assertFalse(result.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_PREPARE_FAILURE, result.diagnostics().getFirst().code());
        assertEquals(ProviderLifecycleState.FAILED, session.state());
        assertEquals(1, failing.prepareCount);
        assertEquals(0, neverPrepared.prepareCount);
        assertThrows(CapabilityNegotiationException.class, session::apply);

        CountingProvider independent = new CountingProvider("example:independent", CAPABILITY, 1, false, false);
        ProviderLifecycleSession independentSession = new ProviderLifecycleSession(planFor(12L, independent), List.of(independent));
        assertTrue(independentSession.prepare().successful());
        assertEquals(ProviderLifecycleState.PREPARED, independentSession.state());
    }

    @Test
    void samePackageCodeCannotMintALifecycleAcceptedPlanOutsideARegistryFreezeEvent() {
        CountingProvider provider = new CountingProvider("example:package_forge", CAPABILITY, 1, false, false);
        CapabilityPlan forged = CapabilityPlan.freeze(
                10_001L,
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)),
                List.of(new CapabilityRegistry.RegisteredProvider(
                        provider.providerId(), provider, List.copyOf(provider.offers()))),
                new CapabilityRegistry(),
                new Object());

        CapabilityNegotiationException exception = assertThrows(
                CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(forged, List.of(provider)));

        assertEquals(CapabilityErrorCode.INVALID_FROZEN_PLAN, exception.diagnostic().code());
        assertEquals(0, provider.prepareCount);
        assertEquals(0, provider.applyCount);
        assertEquals(0, provider.retireCount);
        assertEquals(0, provider.closeCount.get());
    }

    @Test
    void prepareCallbackCannotReenterCloseAndThenLetTheOuterTransitionSucceed() {
        CountingProvider provider = new CountingProvider("example:prepare_reentry", CAPABILITY, 1, false, false);
        AtomicReference<ProviderLifecycleSession> sessionReference = new AtomicReference<>();
        provider.prepareAction = () -> sessionReference.get().close();
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(10_002L, provider), List.of(provider));
        sessionReference.set(session);

        ProviderLifecycleResult result = session.prepare();

        assertFalse(result.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_PREPARE_FAILURE, result.diagnostics().getFirst().code());
        assertEquals(ProviderLifecycleState.FAILED, session.state());
        assertEquals(1, provider.prepareCount);
        assertEquals(0, provider.retireCount);
        assertEquals(0, provider.closeCount.get());
    }

    @Test
    void lifecycleFailureNeverCallsUntrustedExceptionMessageOrToString() {
        CountingProvider provider = new CountingProvider("example:hostile_exception", CAPABILITY, 1, false, false);
        provider.prepareFailure = new HostileRuntimeException();
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(10_003L, provider), List.of(provider));

        ProviderLifecycleResult result = session.prepare();

        assertFalse(result.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_PREPARE_FAILURE, result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
        assertFalse(result.diagnostics().getFirst().message().contains("attacker"));
        assertEquals(ProviderLifecycleState.FAILED, session.state());
    }

    @Test
    void applyFailureIsIsolatedAndDoesNotInvokeLaterApplyCallbacks() {
        CountingProvider failing = new CountingProvider("example:first", CAPABILITY, 20, false, true);
        CountingProvider neverApplied = new CountingProvider("example:second", SECOND_CAPABILITY, 10, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(13L, failing, neverApplied),
                List.of(failing, neverApplied));

        assertTrue(session.prepare().successful());
        ProviderLifecycleResult result = session.apply();

        assertFalse(result.successful());
        assertEquals(CapabilityErrorCode.PROVIDER_APPLY_FAILURE, result.diagnostics().getFirst().code());
        assertEquals(ProviderLifecycleState.FAILED, session.state());
        assertEquals(1, failing.applyCount);
        assertEquals(0, neverApplied.applyCount);
    }

    @Test
    void publishedGenerationWaitsForPinsThenRetiresAndClosesExactlyOnce() {
        CountingProvider provider = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(14L, provider), List.of(provider));
        assertTrue(session.prepare().successful());
        assertTrue(session.apply().successful());
        session.publish();

        ProviderLease lease = session.pin();
        assertEquals(14L, lease.generation());
        session.retire();

        assertEquals(ProviderLifecycleState.RETIRING, session.state());
        assertEquals(0, provider.retireCount);
        assertEquals(0, provider.closeCount.get());
        assertFalse(lease.isClosed());

        lease.close();
        lease.close();
        session.retire();
        session.close();

        assertTrue(lease.isClosed());
        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertEquals(1, provider.retireCount);
        assertEquals(1, provider.closeCount.get());
    }

    @RepeatedTest(25)
    void concurrentRetireAndLeaseReleaseDelayRetireAndCloseUntilTheLastPinDrains() throws Exception {
        CountingProvider provider = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(141L, provider), List.of(provider));
        publish(session);
        ProviderLease racingLease = session.pin();
        ProviderLease finalLease = session.pin();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> retirement = executor.submit(() -> {
                start.await();
                session.retire();
                return null;
            });
            Future<?> duplicateRetirement = executor.submit(() -> {
                start.await();
                session.retire();
                return null;
            });
            Future<?> release = executor.submit(() -> {
                start.await();
                racingLease.close();
                racingLease.close();
                return null;
            });
            start.countDown();
            retirement.get();
            duplicateRetirement.get();
            release.get();
        }

        assertEquals(ProviderLifecycleState.RETIRING, session.state());
        assertEquals(0, provider.retireCount);
        assertEquals(0, provider.closeCount.get());

        finalLease.close();
        finalLease.close();
        session.retire();
        session.close();

        assertEquals(ProviderLifecycleState.CLOSED, session.state());
        assertEquals(1, provider.retireCount);
        assertEquals(1, provider.closeCount.get());
        assertEquals(List.of("retire", "close"), provider.retirementEvents);
    }

    @Test
    void constructorRejectsAPlanWhoseSelectionsAndFrozenProviderBindingsDisagree() throws Exception {
        CountingProvider provider = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        CapabilityPlan forged = planFor(142L, provider);
        Field bindings = CapabilityPlan.class.getDeclaredField("selectedProviderBindings");
        bindings.setAccessible(true);
        bindings.set(forged, List.of());

        CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(forged, List.of(provider)));

        assertEquals(CapabilityErrorCode.INVALID_FROZEN_PLAN, exception.diagnostic().code());
        assertEquals(BlendDiagnosticSeverity.ERROR, exception.diagnostic().severity());
        assertTrue(exception.diagnostic().code().code().startsWith("BLENDLIB-X1-CAP-"));
        assertTrue(exception.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
        assertEquals(0, provider.prepareCount);
        assertEquals(0, provider.applyCount);
        assertEquals(0, provider.retireCount);
        assertEquals(0, provider.closeCount.get());
    }

    @Test
    void constructorRejectsAProviderBindingThatSubstitutesADifferentObjectForTheFrozenSnapshot() throws Exception {
        CountingProvider registered = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        CountingProvider substitute = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        CapabilityPlan plan = planFor(1421L, registered);
        CapabilityOffer selectedOffer = plan.selections().getFirst().selectedOffer().orElseThrow();
        Field bindings = CapabilityPlan.class.getDeclaredField("selectedProviderBindings");
        bindings.setAccessible(true);
        bindings.set(plan, List.of(new CapabilityPlan.ProviderBinding(
                registered.providerId, substitute, selectedOffer)));

        CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(plan, List.of(substitute)));

        assertEquals(CapabilityErrorCode.INVALID_FROZEN_PLAN, exception.diagnostic().code());
        assertEquals(BlendDiagnosticSeverity.ERROR, exception.diagnostic().severity());
        assertEquals(0, registered.prepareCount);
        assertEquals(0, substitute.prepareCount);
        assertEquals(0, registered.closeCount.get());
        assertEquals(0, substitute.closeCount.get());
    }

    @Test
    void lifecycleRejectsCoherentlyForgedRequestOfferAndDiagnosticData() throws Exception {
        CountingProvider requestProvider = providerForStage("forged_request");
        CapabilityPlan requestPlan = planFor(1422L, requestProvider);
        CapabilitySelection original = requestPlan.selections().getFirst();
        CapabilityRequest alteredRequest = CapabilityRequest.required(
                CAPABILITY,
                new CapabilityVersionRange(new CapabilityVersion(0, 9, 0), new CapabilityVersion(2, 0, 0)));
        setSelections(requestPlan, List.of(new CapabilitySelection(
                alteredRequest,
                CapabilitySelectionOutcome.SELECTED,
                original.selectedOffer(),
                java.util.Optional.empty())));
        assertInvalidPlan(() -> new ProviderLifecycleSession(requestPlan, List.of(requestProvider)));

        CountingProvider offerProvider = providerForStage("forged_offer");
        CapabilityPlan offerPlan = planFor(1423L, offerProvider);
        CapabilitySelection originalOfferSelection = offerPlan.selections().getFirst();
        CapabilityOffer alteredOffer = new CapabilityOffer(
                offerProvider.providerId,
                CAPABILITY,
                new CapabilityVersion(1, 1, 0),
                1);
        setSelections(offerPlan, List.of(new CapabilitySelection(
                originalOfferSelection.request(),
                CapabilitySelectionOutcome.SELECTED,
                java.util.Optional.of(alteredOffer),
                java.util.Optional.empty())));
        Field bindings = CapabilityPlan.class.getDeclaredField("selectedProviderBindings");
        bindings.setAccessible(true);
        bindings.set(offerPlan, List.of(new CapabilityPlan.ProviderBinding(
                offerProvider.providerId, offerProvider, alteredOffer)));
        assertInvalidPlan(() -> new ProviderLifecycleSession(offerPlan, List.of(offerProvider)));

        CapabilityFallback fallback = new CapabilityFallback(
                BlendResourceId.parse("blendlib:cpu_fallback"),
                "semantic-equivalent CPU fallback");
        CapabilityRegistry fallbackRegistry = new CapabilityRegistry();
        fallbackRegistry.discover(List.of(CapabilityRequest.optional(CAPABILITY, VERSION_RANGE, fallback)));
        CapabilityPlan diagnosticPlan = fallbackRegistry.freeze(1424L);
        CapabilitySelection fallbackSelection = diagnosticPlan.selections().getFirst();
        CapabilityDiagnostic alteredDiagnostic = CapabilityDiagnostic.capability(
                CapabilityErrorCode.OPTIONAL_FALLBACK,
                BlendDiagnosticSeverity.WARNING,
                CAPABILITY,
                "forged but structurally valid fallback diagnostic");
        setSelections(diagnosticPlan, List.of(new CapabilitySelection(
                fallbackSelection.request(),
                CapabilitySelectionOutcome.FALLBACK,
                java.util.Optional.empty(),
                java.util.Optional.of(alteredDiagnostic))));
        assertInvalidPlan(() -> new ProviderLifecycleSession(diagnosticPlan, List.of()));

        assertEquals(0, requestProvider.prepareCount);
        assertEquals(0, offerProvider.prepareCount);
        assertEquals(0, requestProvider.closeCount.get());
        assertEquals(0, offerProvider.closeCount.get());
    }

    @Test
    void everyLifecycleTransitionRevalidatesTheFrozenPlanBeforeCallingAProvider() throws Exception {
        CountingProvider prepareProvider = providerForStage("prepare");
        CapabilityPlan preparePlan = planFor(143L, prepareProvider);
        ProviderLifecycleSession prepareSession = new ProviderLifecycleSession(preparePlan, List.of(prepareProvider));
        tamperSelections(preparePlan);
        assertInvalidPlan(prepareSession::prepare);
        assertEquals(0, prepareProvider.prepareCount);

        CountingProvider applyProvider = providerForStage("apply");
        CapabilityPlan applyPlan = planFor(144L, applyProvider);
        ProviderLifecycleSession applySession = new ProviderLifecycleSession(applyPlan, List.of(applyProvider));
        assertTrue(applySession.prepare().successful());
        tamperSelections(applyPlan);
        assertInvalidPlan(applySession::apply);
        assertEquals(0, applyProvider.applyCount);

        CountingProvider publishProvider = providerForStage("publish");
        CapabilityPlan publishPlan = planFor(145L, publishProvider);
        ProviderLifecycleSession publishSession = new ProviderLifecycleSession(publishPlan, List.of(publishProvider));
        assertTrue(publishSession.prepare().successful());
        assertTrue(publishSession.apply().successful());
        tamperSelections(publishPlan);
        assertInvalidPlan(publishSession::publish);

        CountingProvider pinProvider = providerForStage("pin");
        CapabilityPlan pinPlan = planFor(146L, pinProvider);
        ProviderLifecycleSession pinSession = new ProviderLifecycleSession(pinPlan, List.of(pinProvider));
        publish(pinSession);
        tamperSelections(pinPlan);
        assertInvalidPlan(pinSession::pin);

        CountingProvider retireProvider = providerForStage("retire");
        CapabilityPlan retirePlan = planFor(147L, retireProvider);
        ProviderLifecycleSession retireSession = new ProviderLifecycleSession(retirePlan, List.of(retireProvider));
        publish(retireSession);
        tamperSelections(retirePlan);
        assertInvalidPlan(retireSession::retire);
        assertInvalidPlan(retireSession::close);
        assertEquals(0, retireProvider.retireCount);
        assertEquals(0, retireProvider.closeCount.get());

        CountingProvider releaseProvider = providerForStage("lease_release");
        CapabilityPlan releasePlan = planFor(148L, releaseProvider);
        ProviderLifecycleSession releaseSession = new ProviderLifecycleSession(releasePlan, List.of(releaseProvider));
        publish(releaseSession);
        ProviderLease lease = releaseSession.pin();
        releaseSession.retire();
        tamperSelections(releasePlan);
        assertInvalidPlan(lease::close);
        assertEquals(0, releaseProvider.retireCount);
        assertEquals(0, releaseProvider.closeCount.get());
    }

    @Test
    void pinIsUnavailableBeforeSuccessfulPublication() {
        CountingProvider provider = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(15L, provider), List.of(provider));

        assertThrows(CapabilityNegotiationException.class, session::pin);
        assertTrue(session.prepare().successful());
        assertThrows(CapabilityNegotiationException.class, session::pin);
        assertTrue(session.apply().successful());
        session.publish();
        assertEquals(15L, session.pin().generation());
    }

    @Test
    void lifecycleUsesTheFrozenOfferSnapshotWithoutRediscoveryCallbacks() {
        CountingProvider provider = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        CapabilityPlan plan = planFor(16L, provider);
        int offersAtFreeze = provider.offersCount;
        int identitiesAtFreeze = provider.providerIdCount;
        provider.offerVersion = new CapabilityVersion(1, 1, 0);

        ProviderLifecycleSession session = new ProviderLifecycleSession(plan, List.of(provider));

        assertEquals(offersAtFreeze, provider.offersCount);
        assertEquals(identitiesAtFreeze, provider.providerIdCount);
        assertEquals(List.of(provider.providerId), session.selectedProviderIds());
        assertEquals(0, provider.prepareCount);
        session.close();
    }

    @Test
    void lifecycleRequiresTheExactProviderInstanceBoundByTheFrozenRegistry() {
        CountingProvider registered = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        CountingProvider impersonator = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        CapabilityPlan plan = planFor(17L, registered);

        CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(plan, List.of(impersonator)));

        assertEquals(CapabilityErrorCode.PLAN_PROVIDER_MISSING, exception.diagnostic().code());
        assertEquals(1, registered.offersCount);
        assertEquals(0, impersonator.offersCount);
    }

    @Test
    void lifecycleRequiresTheCompleteFrozenProviderObjectSnapshotWithoutExtrasOrOmissions() {
        CountingProvider selected = new CountingProvider("example:selected", CAPABILITY, 1, false, false);
        CountingProvider unselected = new CountingProvider("example:unselected", SECOND_CAPABILITY, 1, false, false);
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(selected);
        registry.register(unselected);
        registry.discover(List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        CapabilityPlan plan = registry.freeze(171L);

        CapabilityNegotiationException omitted = assertThrows(CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(plan, List.of(selected)));
        assertEquals(CapabilityErrorCode.PLAN_PROVIDER_MISSING, omitted.diagnostic().code());

        CountingProvider extra = new CountingProvider("example:extra", CAPABILITY, 1, false, false);
        CapabilityNegotiationException added = assertThrows(CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(plan, List.of(selected, unselected, extra)));
        assertEquals(CapabilityErrorCode.PLAN_PROVIDER_MISSING, added.diagnostic().code());
        assertEquals(0, selected.prepareCount);
        assertEquals(0, unselected.prepareCount);
        assertEquals(0, extra.prepareCount);
        assertEquals(0, selected.closeCount.get());
        assertEquals(0, unselected.closeCount.get());
        assertEquals(0, extra.closeCount.get());
    }

    @Test
    void overlappingGenerationsShareOwnershipAndCloseAfterTheLastRetires() {
        CountingProvider provider = new CountingProvider("example:provider", CAPABILITY, 1, false, false);
        ProviderLifecycleSession generationTen = new ProviderLifecycleSession(planFor(10L, provider), List.of(provider));
        ProviderLifecycleSession generationEleven = new ProviderLifecycleSession(planFor(11L, provider), List.of(provider));
        publish(generationTen);
        publish(generationEleven);

        generationTen.retire();

        assertEquals(ProviderLifecycleState.CLOSED, generationTen.state());
        assertEquals(ProviderLifecycleState.PUBLISHED, generationEleven.state());
        assertEquals(0, provider.closeCount.get());

        generationEleven.retire();
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void adapterControlAndLifecycleShareOneCloseOnceOwnerSet() {
        CountingProvider adapter = new CountingProvider("example:adapter", CAPABILITY, 1, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(18L, adapter), List.of(adapter));
        PlatformAdapterControl.global().install(adapter);

        session.close();
        assertEquals(0, adapter.closeCount.get());

        PlatformAdapterControl.global().uninstall();
        PlatformAdapterControl.global().uninstall();
        session.close();
        assertEquals(1, adapter.closeCount.get());

        CountingProvider reverse = new CountingProvider("example:reverse_adapter", CAPABILITY, 1, false, false);
        ProviderLifecycleSession reverseSession = new ProviderLifecycleSession(planFor(181L, reverse), List.of(reverse));
        PlatformAdapterControl.global().install(reverse);
        PlatformAdapterControl.global().uninstall();
        assertEquals(0, reverse.closeCount.get());
        reverseSession.close();
        reverseSession.close();
        assertEquals(1, reverse.closeCount.get());
    }

    @RepeatedTest(25)
    void concurrentFinalReleasesAreLinearizedToOneProviderClose() throws Exception {
        CountingProvider adapter = new CountingProvider("example:adapter", CAPABILITY, 1, false, false);
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(19L, adapter), List.of(adapter));
        PlatformAdapterControl.global().install(adapter);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> sessionRelease = executor.submit(() -> {
                start.await();
                session.retire();
                return null;
            });
            Future<?> controlRelease = executor.submit(() -> {
                start.await();
                PlatformAdapterControl.global().uninstall();
                return null;
            });
            start.countDown();
            sessionRelease.get();
            controlRelease.get();
        }

        assertEquals(1, adapter.closeCount.get());
    }

    @Test
    void retireAndCloseFailuresAreReturnedTogetherAndCloseIsNeverRetried() {
        CountingProvider provider = new CountingProvider("example:failing", CAPABILITY, 1, false, false);
        provider.failRetire = true;
        provider.failClose = true;
        ProviderLifecycleSession session = new ProviderLifecycleSession(planFor(20L, provider), List.of(provider));
        publish(session);

        ProviderLifecycleResult result = session.retire();

        assertFalse(result.successful());
        assertEquals(ProviderLifecycleStage.CLOSE, result.stage());
        assertEquals(List.of(CapabilityErrorCode.PROVIDER_RETIRE_FAILURE, CapabilityErrorCode.PROVIDER_CLOSE_FAILURE),
                result.diagnostics().stream().map(CapabilityDiagnostic::code).toList());
        assertEquals(1, provider.closeCount.get());
        assertEquals(result.diagnostics(), session.retire().diagnostics());
        assertEquals(1, provider.closeCount.get());
    }

    @Test
    void terminallyClosedProviderCannotAcquireASecondLifecycleOwner() {
        CountingProvider provider = new CountingProvider("example:terminal_provider", CAPABILITY, 1, false, false);
        ProviderLifecycleSession first = new ProviderLifecycleSession(planFor(21L, provider), List.of(provider));
        first.close();
        CapabilityPlan laterPlan = planFor(22L, provider);

        CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class,
                () -> new ProviderLifecycleSession(laterPlan, List.of(provider)));

        assertEquals(CapabilityErrorCode.PROVIDER_OWNERSHIP_CONFLICT, exception.diagnostic().code());
        assertEquals(1, provider.closeCount.get());
        assertTrue(exception.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
    }

    @Test
    void providerOwnershipUsesObjectIdentityRatherThanEquals() {
        AlwaysEqualProvider first = new AlwaysEqualProvider("example:first_identity");
        AlwaysEqualProvider second = new AlwaysEqualProvider("example:second_identity");

        List<ProviderOwnership.Handle> handles = ProviderOwnership.acquireAll(List.of(first, second));
        handles.get(0).release();
        handles.get(1).release();

        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
    }

    private static void publish(ProviderLifecycleSession session) {
        assertTrue(session.prepare().successful());
        assertTrue(session.apply().successful());
        session.publish();
    }

    private static CountingProvider providerForStage(String stage) {
        return new CountingProvider("example:provider_" + stage, CAPABILITY, 1, false, false);
    }

    private static void tamperSelections(CapabilityPlan plan) throws Exception {
        setSelections(plan, List.of());
    }

    private static void setSelections(CapabilityPlan plan, List<CapabilitySelection> replacement) throws Exception {
        Field selections = CapabilityPlan.class.getDeclaredField("selections");
        selections.setAccessible(true);
        selections.set(plan, replacement);
    }

    private static void assertInvalidPlan(org.junit.jupiter.api.function.Executable executable) {
        CapabilityNegotiationException exception = assertThrows(CapabilityNegotiationException.class, executable);
        assertEquals(CapabilityErrorCode.INVALID_FROZEN_PLAN, exception.diagnostic().code());
        assertEquals(BlendDiagnosticSeverity.ERROR, exception.diagnostic().severity());
        assertTrue(exception.diagnostic().code().code().startsWith("BLENDLIB-X1-CAP-"));
        assertTrue(exception.diagnostic().message().length() <= CapabilityDiagnostic.MAX_MESSAGE_LENGTH);
    }

    private static CapabilityPlan planFor(long generation, CountingProvider... providers) {
        CapabilityRegistry registry = new CapabilityRegistry();
        for (CountingProvider provider : providers) {
            registry.register(provider);
        }
        registry.discover(java.util.Arrays.stream(providers)
                .map(provider -> CapabilityRequest.required(provider.capability, VERSION_RANGE))
                .toList());
        return registry.freeze(generation);
    }

    private static final class CountingProvider implements PlatformAdapter {
        private final BlendResourceId providerId;
        private final BlendResourceId capability;
        private final int priority;
        private final boolean failPrepare;
        private final boolean failApply;
        private boolean failRetire;
        private boolean failClose;
        private Runnable prepareAction = () -> { };
        private RuntimeException prepareFailure;
        private CapabilityVersion offerVersion = new CapabilityVersion(1, 0, 0);
        private int providerIdCount;
        private int offersCount;
        private int prepareCount;
        private int applyCount;
        private int retireCount;
        private final AtomicInteger closeCount = new AtomicInteger();
        private final List<String> retirementEvents = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private CountingProvider(
                String providerId,
                BlendResourceId capability,
                int priority,
                boolean failPrepare,
                boolean failApply) {
            this.providerId = BlendResourceId.parse(providerId);
            this.capability = capability;
            this.priority = priority;
            this.failPrepare = failPrepare;
            this.failApply = failApply;
        }

        @Override
        public BlendResourceId providerId() {
            providerIdCount++;
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            offersCount++;
            return List.of(new CapabilityOffer(providerId, capability, offerVersion, priority));
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            return new RegistrationReceipt(providerId, specification.hostKind(), specification.model());
        }

        @Override
        public void prepare(ProviderLifecycleContext context) {
            prepareCount++;
            prepareAction.run();
            if (prepareFailure != null) {
                throw prepareFailure;
            }
            if (failPrepare) {
                throw new IllegalStateException("prepare failure");
            }
        }

        @Override
        public void apply(ProviderLifecycleContext context) {
            applyCount++;
            if (failApply) {
                throw new IllegalStateException("apply failure");
            }
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCount++;
            retirementEvents.add("retire");
            if (failRetire) {
                throw new IllegalStateException("retire failure");
            }
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            retirementEvents.add("close");
            if (failClose) {
                throw new IllegalStateException("close failure");
            }
        }
    }

    private static final class AlwaysEqualProvider implements BlendProvider {
        private final BlendResourceId providerId;
        private final AtomicInteger closeCount = new AtomicInteger();

        private AlwaysEqualProvider(String providerId) {
            this.providerId = BlendResourceId.parse(providerId);
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AlwaysEqualProvider;
        }

        @Override
        public int hashCode() {
            return 1;
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
}
