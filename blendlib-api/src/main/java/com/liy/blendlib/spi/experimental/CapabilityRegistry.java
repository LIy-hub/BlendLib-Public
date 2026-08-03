package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Thread-safe controlled registry implementing metadata-only discovery and deterministic freeze.
 *
 * <p>The registry follows {@code register -> discover -> freeze}. It snapshots provider metadata
 * at registration, performs no provider lifecycle callback during discovery, and permanently rejects
 * all registration/discovery/freeze mutation after a plan is frozen. Untrusted metadata callbacks run
 * outside the registry monitor; an epoch-checked transition commits their result only if the registry
 * operation is still current.</p>
 */
@ExperimentalBlendLibSpi
public final class CapabilityRegistry {
    static final int MAX_PROVIDER_OFFERS = 1_024;
    static final int MAX_CAPABILITY_REQUESTS = 1_024;
    private static final Comparator<CapabilityOffer> OFFER_ORDER = Comparator
            .comparingInt(CapabilityOffer::priority).reversed()
            .thenComparing(offer -> offer.providerId().value())
            .thenComparing(offer -> offer.capabilityId().value())
            .thenComparing(CapabilityOffer::protocolVersion);

    private final Map<BlendResourceId, RegisteredProvider> providers = new HashMap<>();
    private final IdentityHashMap<BlendProvider, BlendResourceId> providerInstances = new IdentityHashMap<>();
    private RegistryState state = RegistryState.REGISTERING;
    private List<CapabilityRequest> discoveredRequests = List.of();
    private CapabilityPlan frozenPlan;
    private Object frozenEvent;
    private long frozenGeneration = -1L;
    private boolean mutationActive;
    private long mutationEpoch;

    /** Creates an empty registry in its registration phase. */
    public CapabilityRegistry() {
    }

    /**
     * Registers one provider after defensively snapshotting its immutable metadata claims.
     * Collection acquisition and its single traversal are fail-closed and bounded to 1,024 offers;
     * caller exceptions are normalized rather than trusted as registry diagnostics.
     *
     * @param provider controlled provider whose metadata is snapshotted
     */
    public void register(BlendProvider provider) {
        provider = Objects.requireNonNull(provider, "provider");
        long epoch = beginRegistration();
        boolean committed = false;
        try {
            BlendResourceId providerId;
            try {
                providerId = Objects.requireNonNull(
                        externalMetadata(provider::providerId), "provider.providerId()");
            } catch (Throwable exception) {
                ExperimentalControlBoundary.rethrowIfFatal(exception);
                throw failure(CapabilityDiagnostic.unscoped(
                        CapabilityErrorCode.INVALID_PROVIDER_OFFER,
                        BlendDiagnosticSeverity.ERROR,
                        "Provider identity lookup failed with " + CapabilityDiagnostic.causeType(exception)));
            }
            if (!ExperimentalControlBoundary.isValidId(providerId)) {
                throw failure(CapabilityDiagnostic.unscoped(
                        CapabilityErrorCode.INVALID_PROVIDER_OFFER,
                        BlendDiagnosticSeverity.ERROR,
                        "Provider identity is outside the bounded canonical Experimental SPI identity policy"));
            }

            Collection<CapabilityOffer> suppliedOffers;
            try {
                suppliedOffers = Objects.requireNonNull(
                        externalMetadata(provider::offers), "provider.offers()");
            } catch (Throwable exception) {
                ExperimentalControlBoundary.rethrowIfFatal(exception);
                throw invalidProvider(providerId,
                        "Provider offer lookup failed with " + CapabilityDiagnostic.causeType(exception));
            }
            List<CapabilityOffer> offers = new ArrayList<>();
            Set<BlendResourceId> offeredCapabilityIds = new HashSet<>();
            Iterator<CapabilityOffer> iterator;
            try {
                iterator = Objects.requireNonNull(
                        externalMetadata(suppliedOffers::iterator), "provider offers iterator");
            } catch (Throwable exception) {
                ExperimentalControlBoundary.rethrowIfFatal(exception);
                throw offerTraversalFailure(providerId, "iterator acquisition", exception);
            }
            while (hasNextOffer(providerId, iterator)) {
                if (offers.size() >= MAX_PROVIDER_OFFERS) {
                    throw invalidProvider(providerId,
                            "Provider offer count exceeds the fixed limit of " + MAX_PROVIDER_OFFERS);
                }
                CapabilityOffer offer = nextOffer(providerId, iterator);
                if (offer == null) {
                    throw invalidProvider(providerId, "Provider returned a null capability offer");
                }
                if (!offer.providerId().equals(providerId)) {
                    throw invalidProvider(providerId,
                            "Provider offer identity does not match provider identity: " + offer.providerId());
                }
                if (!offeredCapabilityIds.add(offer.capabilityId())) {
                    throw failure(CapabilityDiagnostic.provider(
                            CapabilityErrorCode.DUPLICATE_PROVIDER_CAPABILITY,
                            BlendDiagnosticSeverity.ERROR,
                            providerId,
                            "Provider offered the same capability more than once: " + offer.capabilityId()));
                }
                offers.add(offer);
            }
            synchronized (this) {
                verifyRegistration(epoch);
                if (providers.containsKey(providerId)) {
                    throw failure(CapabilityDiagnostic.provider(
                            CapabilityErrorCode.DUPLICATE_PROVIDER_ID,
                            BlendDiagnosticSeverity.ERROR,
                            providerId,
                            "Provider identity is already registered"));
                }
                BlendResourceId previousIdentity = providerInstances.get(provider);
                if (previousIdentity != null) {
                    throw failure(CapabilityDiagnostic.provider(
                            CapabilityErrorCode.DUPLICATE_PROVIDER_ID,
                            BlendDiagnosticSeverity.ERROR,
                            providerId,
                            "Provider object identity is already registered"));
                }
                providers.put(providerId, new RegisteredProvider(providerId, provider, offers));
                providerInstances.put(provider, providerId);
                mutationActive = false;
                committed = true;
            }
        } finally {
            if (!committed) {
                abortRegistration(epoch);
            }
        }
    }

    /**
     * Freezes the requested capability set for metadata-only discovery.
     *
     * @param requests immutable request metadata to negotiate
     * @return all relevant offers in priority-descending/provider-id-ordinal reporting order
     */
    public List<CapabilityOffer> discover(Collection<CapabilityRequest> requests) {
        long epoch = beginDiscovery();
        boolean committed = false;
        try {
            if (requests == null) {
                throw invalidRequest("Capability request collection is null");
            }
            List<CapabilityRequest> copiedRequests = new ArrayList<>();
            Set<BlendResourceId> requestedIds = new HashSet<>();
            Iterator<CapabilityRequest> iterator;
            try {
                iterator = Objects.requireNonNull(externalMetadata(requests::iterator), "requests iterator");
            } catch (Throwable exception) {
                ExperimentalControlBoundary.rethrowIfFatal(exception);
                throw invalidRequest("Capability request iterator acquisition failed with "
                        + CapabilityDiagnostic.causeType(exception));
            }
            while (hasNextRequest(iterator)) {
                if (copiedRequests.size() >= MAX_CAPABILITY_REQUESTS) {
                    throw invalidRequest("Capability request count exceeds the fixed limit of "
                            + MAX_CAPABILITY_REQUESTS);
                }
                CapabilityRequest request = nextRequest(iterator);
                if (request == null) {
                    throw invalidRequest("Capability request collection contains null");
                }
                if (!requestedIds.add(request.capabilityId())) {
                    throw invalidRequest("Duplicate capability request: " + request.capabilityId());
                }
                copiedRequests.add(request);
            }

            List<CapabilityRequest> sortedRequests = copiedRequests.stream()
                    .sorted(Comparator.comparing(request -> request.capabilityId().value()))
                    .toList();
            List<CapabilityOffer> discovered;
            synchronized (this) {
                verifyDiscovery(epoch);
                discoveredRequests = sortedRequests;
                state = RegistryState.DISCOVERED;
                discovered = providers.values().stream()
                        .flatMap(provider -> provider.offers.stream())
                        .filter(offer -> requestedIds.contains(offer.capabilityId()))
                        .sorted(OFFER_ORDER)
                        .toList();
                mutationActive = false;
                committed = true;
            }
            return discovered;
        } finally {
            if (!committed) {
                abortRegistration(epoch);
            }
        }
    }

    private synchronized long beginDiscovery() {
        rejectMutationDuringCallback("discover");
        requireNoMutation("discover");
        if (state == RegistryState.FROZEN) {
            throw frozenFailure();
        }
        if (state != RegistryState.REGISTERING) {
            throw invalidState("discover may only be called once after registration");
        }
        mutationActive = true;
        return ++mutationEpoch;
    }

    /**
     * Resolves and permanently freezes an immutable capability plan for one generation.
     *
     * @param generation non-negative generation being prepared
     * @return immutable plan; callers must use {@link CapabilityPlan#requirePublishable()} before publication
     */
    public synchronized CapabilityPlan freeze(long generation) {
        rejectMutationDuringCallback("freeze");
        requireNoMutation("freeze");
        if (state == RegistryState.FROZEN) {
            throw frozenFailure();
        }
        if (state != RegistryState.DISCOVERED) {
            throw invalidState("freeze requires completed metadata discovery");
        }
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Object event = new Object();
        CapabilityPlan candidate = CapabilityPlan.freeze(
                generation, discoveredRequests, providers.values(), this, event);
        frozenEvent = event;
        frozenGeneration = generation;
        frozenPlan = candidate;
        state = RegistryState.FROZEN;
        frozenPlan.validateForLifecycle();
        return frozenPlan;
    }

    /**
     * Returns whether this registry has permanently frozen its generation plan.
     *
     * @return whether no later mutation is permitted
     */
    public synchronized boolean isFrozen() {
        return state == RegistryState.FROZEN;
    }

    /**
     * Returns the frozen immutable plan when one exists.
     *
     * @return optional immutable plan
     */
    public synchronized java.util.Optional<CapabilityPlan> frozenPlan() {
        return java.util.Optional.ofNullable(frozenPlan);
    }

    /**
     * Returns registered provider identities in ordinal deterministic order.
     *
     * @return immutable sorted provider identity list
     */
    public synchronized List<BlendResourceId> registeredProviderIds() {
        return providers.keySet().stream().sorted(Comparator.comparing(BlendResourceId::value)).toList();
    }

    synchronized boolean validatesFrozenPlan(CapabilityPlan candidate, Object event) {
        return state == RegistryState.FROZEN
                && frozenPlan == candidate
                && frozenEvent == event
                && candidate != null
                && candidate.generation() == frozenGeneration;
    }

    private synchronized long beginRegistration() {
        rejectMutationDuringCallback("register");
        requireNoMutation("register");
        if (state == RegistryState.FROZEN) {
            throw frozenFailure();
        }
        if (state != RegistryState.REGISTERING) {
            throw invalidState("register is only legal before discovery");
        }
        mutationActive = true;
        return ++mutationEpoch;
    }

    private synchronized void verifyRegistration(long epoch) {
        if (!mutationActive || mutationEpoch != epoch || state != RegistryState.REGISTERING) {
            throw invalidState("register callback changed the registry operation identity");
        }
    }

    private synchronized void verifyDiscovery(long epoch) {
        if (!mutationActive || mutationEpoch != epoch || state != RegistryState.REGISTERING) {
            throw invalidState("discover callback changed the registry operation identity");
        }
    }

    private synchronized void abortRegistration(long epoch) {
        if (mutationActive && mutationEpoch == epoch) {
            mutationActive = false;
        }
    }

    private void requireNoMutation(String operation) {
        if (mutationActive) {
            throw invalidState(operation + " is unavailable during an active registry operation");
        }
    }

    private void rejectMutationDuringCallback(String operation) {
        if (ExperimentalControlBoundary.inExternalCallback()) {
            throw invalidState(operation + " is unavailable during an external provider callback");
        }
    }

    private CapabilityNegotiationException frozenFailure() {
        return failure(new CapabilityDiagnostic(
                CapabilityErrorCode.REGISTRY_FROZEN,
                BlendDiagnosticSeverity.ERROR,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                "Capability registry is frozen and cannot be mutated"));
    }

    private CapabilityNegotiationException invalidState(String message) {
        return failure(new CapabilityDiagnostic(
                CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                BlendDiagnosticSeverity.ERROR,
                java.util.Optional.empty(),
                java.util.Optional.empty(), message));
    }

    private static CapabilityNegotiationException failure(CapabilityDiagnostic diagnostic) {
        return new CapabilityNegotiationException(diagnostic);
    }

    private static CapabilityNegotiationException invalidProvider(BlendResourceId providerId, String message) {
        return failure(CapabilityDiagnostic.provider(
                CapabilityErrorCode.INVALID_PROVIDER_OFFER,
                BlendDiagnosticSeverity.ERROR,
                providerId,
                message));
    }

    private static CapabilityNegotiationException invalidRequest(String message) {
        return failure(CapabilityDiagnostic.unscoped(
                CapabilityErrorCode.INVALID_CAPABILITY_REQUEST,
                BlendDiagnosticSeverity.ERROR,
                message));
    }

    private static boolean hasNextRequest(Iterator<CapabilityRequest> iterator) {
        try {
            return externalMetadata(iterator::hasNext);
        } catch (Throwable exception) {
            ExperimentalControlBoundary.rethrowIfFatal(exception);
            throw invalidRequest("Capability request iterator traversal failed with "
                    + CapabilityDiagnostic.causeType(exception));
        }
    }

    private static CapabilityRequest nextRequest(Iterator<CapabilityRequest> iterator) {
        try {
            return externalMetadata(iterator::next);
        } catch (Throwable exception) {
            ExperimentalControlBoundary.rethrowIfFatal(exception);
            throw invalidRequest("Capability request retrieval failed with "
                    + CapabilityDiagnostic.causeType(exception));
        }
    }

    private static boolean hasNextOffer(BlendResourceId providerId, Iterator<CapabilityOffer> iterator) {
        try {
            return externalMetadata(iterator::hasNext);
        } catch (Throwable exception) {
            ExperimentalControlBoundary.rethrowIfFatal(exception);
            throw offerTraversalFailure(providerId, "iterator traversal", exception);
        }
    }

    private static CapabilityOffer nextOffer(BlendResourceId providerId, Iterator<CapabilityOffer> iterator) {
        try {
            return externalMetadata(iterator::next);
        } catch (Throwable exception) {
            ExperimentalControlBoundary.rethrowIfFatal(exception);
            throw offerTraversalFailure(providerId, "offer retrieval", exception);
        }
    }

    private static CapabilityNegotiationException offerTraversalFailure(
            BlendResourceId providerId,
            String operation,
            Throwable exception) {
        return invalidProvider(providerId,
                "Provider offer " + operation + " failed with " + CapabilityDiagnostic.causeType(exception));
    }

    private static <T> T externalMetadata(Supplier<T> callback) {
        return ExperimentalControlBoundary.callExternal(callback);
    }

    static record RegisteredProvider(
            BlendResourceId providerId,
            BlendProvider provider,
            List<CapabilityOffer> offers) {
        RegisteredProvider {
            providerId = Objects.requireNonNull(providerId, "providerId");
            provider = Objects.requireNonNull(provider, "provider");
            offers = List.copyOf(Objects.requireNonNull(offers, "offers"));
        }
    }

    private enum RegistryState {
        REGISTERING,
        DISCOVERED,
        FROZEN
    }
}
