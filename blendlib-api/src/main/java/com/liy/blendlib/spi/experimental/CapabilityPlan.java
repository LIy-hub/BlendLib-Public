package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Frozen, immutable capability selection plan pinned to one resource generation.
 *
 * <p>Only {@link CapabilityRegistry#freeze(long)} can produce a valid plan. The plan retains the
 * exact registry identity and that registry's private freeze-event identity together with complete
 * request and provider-object metadata snapshots, so every lifecycle transition can recompute and
 * validate its selections and bindings before invoking a provider. Public callers can inspect but
 * cannot construct plans.</p>
 */
@ExperimentalBlendLibSpi
public final class CapabilityPlan {
    private static final Comparator<CapabilityOffer> OFFER_ORDER = Comparator
            .comparingInt(CapabilityOffer::priority).reversed()
            .thenComparing(offer -> offer.providerId().value())
            .thenComparing(offer -> offer.capabilityId().value())
            .thenComparing(CapabilityOffer::protocolVersion);

    private final long generation;
    private final List<CapabilitySelection> selections;
    private final List<ProviderBinding> selectedProviderBindings;
    private final List<CapabilityRequest> frozenRequests;
    private final List<FrozenProviderSnapshot> frozenProviderSnapshots;
    private final CapabilityRegistry sourceRegistry;
    private final Object registryFreezeEvent;

    private CapabilityPlan(
            long generation,
            List<CapabilitySelection> selections,
            List<ProviderBinding> selectedProviderBindings,
            List<CapabilityRequest> frozenRequests,
            List<FrozenProviderSnapshot> frozenProviderSnapshots,
            CapabilityRegistry sourceRegistry,
            Object registryFreezeEvent) {
        this.generation = generation;
        this.selections = List.copyOf(selections);
        this.selectedProviderBindings = List.copyOf(selectedProviderBindings);
        this.frozenRequests = List.copyOf(frozenRequests);
        this.frozenProviderSnapshots = List.copyOf(frozenProviderSnapshots);
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "sourceRegistry");
        this.registryFreezeEvent = Objects.requireNonNull(registryFreezeEvent, "registryFreezeEvent");
    }

    static CapabilityPlan freeze(
            long generation,
            Collection<CapabilityRequest> requests,
            Collection<CapabilityRegistry.RegisteredProvider> providers,
            CapabilityRegistry sourceRegistry,
            Object registryFreezeEvent) {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        List<CapabilityRequest> orderedRequests = List.copyOf(Objects.requireNonNull(requests, "requests")).stream()
                .sorted(Comparator.comparing(request -> request.capabilityId().value()))
                .toList();
        List<FrozenProviderSnapshot> providerSnapshots = List.copyOf(
                Objects.requireNonNull(providers, "providers")).stream()
                .map(FrozenProviderSnapshot::from)
                .toList();

        Set<BlendResourceId> distinctRequests = new HashSet<>();
        for (CapabilityRequest request : orderedRequests) {
            Objects.requireNonNull(request, "requests contains null");
            if (!distinctRequests.add(request.capabilityId())) {
                throw new IllegalArgumentException("A capability plan cannot contain duplicate request ids");
            }
        }

        List<CapabilitySelection> finalSelections = new ArrayList<>(orderedRequests.size());
        List<ProviderBinding> bindings = new ArrayList<>();
        for (CapabilityRequest request : orderedRequests) {
            CapabilitySelection selection = select(request, providerSnapshots);
            finalSelections.add(selection);
            selection.selectedOffer().ifPresent(offer -> bindings.add(bindingFor(offer, providerSnapshots)));
        }
        bindings.sort(Comparator.comparing(ProviderBinding::offer, OFFER_ORDER));
        CapabilityPlan plan = new CapabilityPlan(
                generation,
                finalSelections,
                bindings,
                orderedRequests,
                providerSnapshots,
                sourceRegistry,
                registryFreezeEvent);
        return plan;
    }

    /**
     * Returns the non-negative resource generation.
     *
     * @return resource-generation identity
     */
    public long generation() {
        return generation;
    }

    /**
     * Returns one immutable outcome for every requested capability.
     *
     * @return immutable registry-produced selection list
     */
    public List<CapabilitySelection> selections() {
        return selections;
    }

    /**
     * Returns whether every request has either a selected provider or an explicit safe fallback.
     *
     * @return whether the frozen plan can publish safely
     */
    public boolean isPublishable() {
        return selections.stream().allMatch(CapabilitySelection::isPublishable);
    }

    /**
     * Returns the final selection for one canonical capability id.
     *
     * @param capabilityId requested capability id
     * @return matching immutable selection when present
     */
    public Optional<CapabilitySelection> selectionFor(BlendResourceId capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        return selections.stream().filter(selection -> selection.request().capabilityId().equals(capabilityId)).findFirst();
    }

    /**
     * Returns diagnostics in deterministic request-id order.
     *
     * @return immutable diagnostic list
     */
    public List<CapabilityDiagnostic> diagnostics() {
        return selections.stream()
                .flatMap(selection -> selection.diagnostic().stream())
                .toList();
    }

    /**
     * Returns selected provider claims ordered by priority descending and provider id ordinal ascending.
     *
     * <p>This reporting order is deterministic and does not imply registration-order preference.</p>
     *
     * @return immutable ordered selected offers
     */
    public List<CapabilityOffer> selectedOffersInReportingOrder() {
        return selectedProviderBindings.stream().map(ProviderBinding::offer).toList();
    }

    /**
     * Fails explicitly when this plan cannot be published safely.
     *
     * @throws CapabilityNegotiationException when any required selection failed
     */
    public void requirePublishable() {
        validateForLifecycle();
        if (!isPublishable()) {
            CapabilityDiagnostic diagnostic = diagnostics().stream()
                    .filter(value -> value.severity() == BlendDiagnosticSeverity.ERROR)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Failed capability plan has no error diagnostic"));
            throw new CapabilityNegotiationException(diagnostic);
        }
    }

    List<ProviderBinding> selectedProviderBindings() {
        return selectedProviderBindings;
    }

    List<BlendProvider> frozenProviderInstances() {
        return frozenProviderSnapshots.stream().map(FrozenProviderSnapshot::provider).toList();
    }

    void validateForLifecycle() {
        try {
            validateFrozenInvariants();
        } catch (CapabilityNegotiationException exception) {
            throw exception;
        } catch (Throwable exception) {
            ExperimentalControlBoundary.rethrowIfFatal(exception);
            throw invalidFrozenPlan();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapabilityPlan that)) {
            return false;
        }
        return generation == that.generation && selections.equals(that.selections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(generation, selections);
    }

    @Override
    public String toString() {
        return "CapabilityPlan[generation=" + generation + ", selections=" + selections + ']';
    }

    private static CapabilitySelection select(
            CapabilityRequest request,
            List<FrozenProviderSnapshot> providers) {
        List<CapabilityOffer> sameCapability = providers.stream()
                .flatMap(snapshot -> snapshot.offers().stream())
                .filter(offer -> offer.capabilityId().equals(request.capabilityId()))
                .sorted(OFFER_ORDER)
                .toList();
        if (sameCapability.isEmpty()) {
            return unresolved(request, CapabilityErrorCode.REQUIRED_UNSUPPORTED,
                    "No provider advertises capability " + request.capabilityId());
        }

        List<CapabilityOffer> compatible = sameCapability.stream()
                .filter(offer -> request.supportedVersions().contains(offer.protocolVersion()))
                .toList();
        if (compatible.isEmpty()) {
            return unresolved(request, CapabilityErrorCode.VERSION_MISMATCH,
                    "No provider offers " + request.capabilityId() + " in " + request.supportedVersions());
        }

        CapabilityOffer highest = compatible.getFirst();
        long topPriorityClaims = compatible.stream().filter(offer -> offer.priority() == highest.priority()).count();
        if (topPriorityClaims > 1L) {
            return unresolved(request, CapabilityErrorCode.TOP_PRIORITY_CONFLICT,
                    "Multiple providers tie at highest priority " + highest.priority() + " for " + request.capabilityId());
        }
        return new CapabilitySelection(request, CapabilitySelectionOutcome.SELECTED,
                Optional.of(highest), Optional.empty());
    }

    private static CapabilitySelection unresolved(
            CapabilityRequest request,
            CapabilityErrorCode causeCode,
            String reason) {
        if (request.requirement() == CapabilityRequirement.OPTIONAL) {
            CapabilityFallback fallback = request.fallback().orElseThrow();
            CapabilityDiagnostic diagnostic = CapabilityDiagnostic.capability(
                    CapabilityErrorCode.OPTIONAL_FALLBACK,
                    BlendDiagnosticSeverity.WARNING,
                    request.capabilityId(),
                    reason + "; selected declared semantic-equivalent fallback " + fallback.fallbackId());
            return new CapabilitySelection(request, CapabilitySelectionOutcome.FALLBACK,
                    Optional.empty(), Optional.of(diagnostic));
        }
        CapabilityDiagnostic diagnostic = CapabilityDiagnostic.capability(
                causeCode, BlendDiagnosticSeverity.ERROR, request.capabilityId(), reason);
        return new CapabilitySelection(request, CapabilitySelectionOutcome.FAILED,
                Optional.empty(), Optional.of(diagnostic));
    }

    private static ProviderBinding bindingFor(
            CapabilityOffer offer,
            List<FrozenProviderSnapshot> providers) {
        return providers.stream()
                .filter(snapshot -> snapshot.providerId().equals(offer.providerId())
                        && snapshot.offers().contains(offer))
                .map(snapshot -> new ProviderBinding(snapshot.providerId(), snapshot.provider(), offer))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Selected offer has no frozen provider snapshot"));
    }

    private void validateFrozenInvariants() {
        if (generation < 0L
                || sourceRegistry == null
                || registryFreezeEvent == null
                || !sourceRegistry.validatesFrozenPlan(this, registryFreezeEvent)) {
            throw invalidFrozenPlan();
        }
        Objects.requireNonNull(selections, "selections");
        Objects.requireNonNull(selectedProviderBindings, "selectedProviderBindings");
        Objects.requireNonNull(frozenRequests, "frozenRequests");
        Objects.requireNonNull(frozenProviderSnapshots, "frozenProviderSnapshots");

        List<CapabilityRequest> orderedRequests = frozenRequests.stream()
                .peek(CapabilityPlan::validateRequest)
                .sorted(Comparator.comparing(request -> request.capabilityId().value()))
                .toList();
        if (!orderedRequests.equals(frozenRequests)) {
            throw invalidFrozenPlan();
        }
        Set<BlendResourceId> requestIds = new HashSet<>();
        for (CapabilityRequest request : orderedRequests) {
            if (!requestIds.add(request.capabilityId())) {
                throw invalidFrozenPlan();
            }
        }

        Set<BlendResourceId> providerIds = new HashSet<>();
        java.util.IdentityHashMap<BlendProvider, Boolean> providerIdentities = new java.util.IdentityHashMap<>();
        for (FrozenProviderSnapshot provider : frozenProviderSnapshots) {
            Objects.requireNonNull(provider, "frozenProviderSnapshots contains null");
            BlendResourceId providerId = Objects.requireNonNull(provider.providerId(), "frozen providerId");
            ExperimentalControlBoundary.requireId(providerId, "frozen providerId");
            BlendProvider providerObject = Objects.requireNonNull(provider.provider(), "frozen provider object");
            List<CapabilityOffer> offers = Objects.requireNonNull(provider.offers(), "frozen provider offers");
            if (!providerIds.add(providerId)
                    || providerIdentities.put(providerObject, Boolean.TRUE) != null) {
                throw invalidFrozenPlan();
            }
            Set<BlendResourceId> offeredIds = new HashSet<>();
            for (CapabilityOffer offer : offers) {
                validateOffer(offer);
                if (!offer.providerId().equals(providerId)
                        || !offeredIds.add(offer.capabilityId())) {
                    throw invalidFrozenPlan();
                }
            }
        }

        List<CapabilitySelection> expectedSelections = orderedRequests.stream()
                .map(request -> select(request, frozenProviderSnapshots))
                .toList();
        for (CapabilitySelection selection : selections) {
            Objects.requireNonNull(selection, "selections contains null").validateFrozenSemantics();
        }
        if (!expectedSelections.equals(selections)) {
            throw invalidFrozenPlan();
        }

        List<ProviderBinding> expectedBindings = new ArrayList<>();
        for (CapabilitySelection selection : expectedSelections) {
            selection.selectedOffer().ifPresent(offer -> expectedBindings.add(bindingFor(offer, frozenProviderSnapshots)));
        }
        expectedBindings.sort(Comparator.comparing(ProviderBinding::offer, OFFER_ORDER));
        if (expectedBindings.size() != selectedProviderBindings.size()) {
            throw invalidFrozenPlan();
        }
        for (int index = 0; index < expectedBindings.size(); index++) {
            ProviderBinding expected = expectedBindings.get(index);
            ProviderBinding actual = Objects.requireNonNull(
                    selectedProviderBindings.get(index), "selectedProviderBindings contains null");
            if (!expected.providerId().equals(actual.providerId())
                    || expected.provider() != actual.provider()
                    || !expected.offer().equals(actual.offer())) {
                throw invalidFrozenPlan();
            }
        }
    }

    private static void validateRequest(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.capabilityId(), "request capabilityId");
        ExperimentalControlBoundary.requireId(request.capabilityId(), "request capabilityId");
        CapabilityVersionRange versions = Objects.requireNonNull(
                request.supportedVersions(), "request supportedVersions");
        validateVersion(versions.minInclusive());
        validateVersion(versions.maxExclusive());
        if (versions.minInclusive().compareTo(versions.maxExclusive()) >= 0) {
            throw invalidFrozenPlan();
        }
        CapabilityRequirement requirement = Objects.requireNonNull(request.requirement(), "request requirement");
        Optional<CapabilityFallback> fallback = Objects.requireNonNull(request.fallback(), "request fallback");
        if ((requirement == CapabilityRequirement.REQUIRED && fallback.isPresent())
                || (requirement == CapabilityRequirement.OPTIONAL && fallback.isEmpty())) {
            throw invalidFrozenPlan();
        }
        fallback.ifPresent(CapabilityPlan::validateFallback);
    }

    private static void validateFallback(CapabilityFallback fallback) {
        Objects.requireNonNull(fallback.fallbackId(), "fallbackId");
        ExperimentalControlBoundary.requireId(fallback.fallbackId(), "fallbackId");
        String explanation = Objects.requireNonNull(fallback.explanation(), "fallback explanation");
        ExperimentalControlBoundary.requireText(
                explanation, CapabilityFallback.MAX_EXPLANATION_LENGTH, "fallback explanation");
    }

    private static void validateOffer(CapabilityOffer offer) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(offer.providerId(), "offer providerId");
        Objects.requireNonNull(offer.capabilityId(), "offer capabilityId");
        ExperimentalControlBoundary.requireId(offer.providerId(), "offer providerId");
        ExperimentalControlBoundary.requireId(offer.capabilityId(), "offer capabilityId");
        validateVersion(offer.protocolVersion());
        if (offer.priority() < -CapabilityOffer.MAX_ABSOLUTE_PRIORITY
                || offer.priority() > CapabilityOffer.MAX_ABSOLUTE_PRIORITY) {
            throw invalidFrozenPlan();
        }
    }

    private static void validateVersion(CapabilityVersion version) {
        Objects.requireNonNull(version, "version");
        if (version.major() < 0 || version.major() > CapabilityVersion.MAX_COMPONENT
                || version.minor() < 0 || version.minor() > CapabilityVersion.MAX_COMPONENT
                || version.patch() < 0 || version.patch() > CapabilityVersion.MAX_COMPONENT) {
            throw invalidFrozenPlan();
        }
    }

    private static CapabilityNegotiationException invalidFrozenPlan() {
        return new CapabilityNegotiationException(CapabilityDiagnostic.unscoped(
                CapabilityErrorCode.INVALID_FROZEN_PLAN,
                BlendDiagnosticSeverity.ERROR,
                "Frozen capability plan failed registry-snapshot invariant validation"));
    }

    private record FrozenProviderSnapshot(
            BlendResourceId providerId,
            BlendProvider provider,
            List<CapabilityOffer> offers) {
        private FrozenProviderSnapshot {
            providerId = Objects.requireNonNull(providerId, "providerId");
            provider = Objects.requireNonNull(provider, "provider");
            offers = List.copyOf(Objects.requireNonNull(offers, "offers"));
        }

        private static FrozenProviderSnapshot from(CapabilityRegistry.RegisteredProvider provider) {
            Objects.requireNonNull(provider, "provider");
            return new FrozenProviderSnapshot(provider.providerId(), provider.provider(), provider.offers());
        }
    }

    static record ProviderBinding(BlendResourceId providerId, BlendProvider provider, CapabilityOffer offer) {
        ProviderBinding {
            providerId = Objects.requireNonNull(providerId, "providerId");
            provider = Objects.requireNonNull(provider, "provider");
            offer = Objects.requireNonNull(offer, "offer");
        }
    }
}
