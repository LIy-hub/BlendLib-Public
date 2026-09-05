package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.CapabilityDiagnostic;
import com.liy.blendlib.spi.experimental.CapabilityErrorCode;
import com.liy.blendlib.spi.experimental.CapabilityNegotiationException;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityPlan;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilitySelection;
import com.liy.blendlib.spi.experimental.CapabilitySelectionOutcome;
import com.liy.blendlib.spi.experimental.CapabilityRegistry;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleResult;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import com.liy.blendlib.spi.experimental.ProviderLifecycleState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Adapter-private bridge from X1's Experimental material-provider protocol to one X6 generation. */
public final class X6MaterialProviderGeneration implements AutoCloseable {
    private final BlendModelKey modelKey;
    private final long generation;
    private final CapabilityPlan capabilityPlan;
    private final ProviderLifecycleSession lifecycleSession;
    private final List<X6Diagnostic> diagnostics;

    private X6MaterialProviderGeneration(
            BlendModelKey modelKey,
            long generation,
            CapabilityPlan capabilityPlan,
            ProviderLifecycleSession lifecycleSession,
            List<X6Diagnostic> diagnostics) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.capabilityPlan = capabilityPlan;
        this.lifecycleSession = lifecycleSession;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((capabilityPlan == null) != (lifecycleSession == null)) {
            throw new IllegalArgumentException("A provider plan and lifecycle session must either both exist or both be absent");
        }
    }

    /**
     * Negotiates and publishes a model-local provider generation.
     *
     * <p>Provider identity and supported-material metadata are copied through the local
     * {@link #snapshotProviderMetadata} boundary before X1 registration. This prevents an
     * ordinary hostile metadata failures, null, mutation, or later metadata call from escaping
     * validation. Every {@link Error} retains its exact identity and escapes after terminal cleanup.
     * Registry-owned offer snapshotting and lifecycle callbacks continue to use the
     * exact external provider object, so X1 ownership/close races retain their identity semantics.</p>
     */
    public static X6MaterialProviderGeneration prepareAndPublish(
            BlendModelKey modelKey,
            long generation,
            Collection<? extends MaterialProvider> providers,
            Collection<CapabilityRequest> requests) {
        BlendModelKey checkedModelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        List<X6Diagnostic> diagnostics = new ArrayList<>();
        ProviderLifecycleSession session = null;
        List<MaterialProvider> providerList = List.of();
        try {
            providerList = snapshotProviderInputs(checkedModelKey, generation, providers, diagnostics);
            List<CapabilityRequest> requestList = snapshotRequests(checkedModelKey, generation, requests, diagnostics);
            requestList = constrainRequestsToCurrentX6Protocol(checkedModelKey, generation, requestList, diagnostics);
            if (hasError(diagnostics)) {
                return failed(checkedModelKey, generation, diagnostics);
            }
            MetadataSnapshotResult metadata = snapshotProviderMetadata(checkedModelKey, generation, providerList, diagnostics);
            if (hasError(diagnostics)) {
                return failed(checkedModelKey, generation, diagnostics);
            }
            CapabilityRegistry registry = new CapabilityRegistry();
            for (MaterialProvider provider : metadata.providers()) {
                registry.register(provider);
            }
            registry.discover(requestList);
            CapabilityPlan plan = registry.freeze(generation);
            if (!plan.isPublishable()) {
                diagnostics.addAll(plan.diagnostics().stream()
                        .map(value -> fromCapability(checkedModelKey, generation, value))
                        .toList());
                return failed(checkedModelKey, generation, diagnostics);
            }
            validateSelectedCurrentX6Protocols(checkedModelKey, generation, plan, diagnostics);
            if (hasError(diagnostics)) {
                return failed(checkedModelKey, generation, diagnostics);
            }
            // From this point the selected provider identities are owned only by X1's shared
            // lifecycle session. Every later X6 rejection retires that owner instead of closing
            // caller-owned provider objects directly.
            session = new ProviderLifecycleSession(plan, metadata.providers());
            validateSelectedMaterialCapabilities(checkedModelKey, generation, plan, metadata.byId(), diagnostics);
            if (hasError(diagnostics)) {
                appendRetirementDiagnostics(checkedModelKey, generation, session, diagnostics);
                return failed(checkedModelKey, generation, diagnostics);
            }
            ProviderLifecycleResult preparation = session.prepare();
            diagnostics.addAll(preparation.diagnostics().stream()
                    .map(value -> fromCapability(checkedModelKey, generation, value))
                    .toList());
            if (!preparation.successful()) {
                appendRetirementDiagnostics(checkedModelKey, generation, session, diagnostics);
                return failed(checkedModelKey, generation, diagnostics);
            }
            ProviderLifecycleResult application = session.apply();
            diagnostics.addAll(application.diagnostics().stream()
                    .map(value -> fromCapability(checkedModelKey, generation, value))
                    .toList());
            if (!application.successful()) {
                appendRetirementDiagnostics(checkedModelKey, generation, session, diagnostics);
                return failed(checkedModelKey, generation, diagnostics);
            }
            session.publish();
            return new X6MaterialProviderGeneration(checkedModelKey, generation, plan, session, ordered(diagnostics));
        } catch (Throwable exception) {
            Throwable cleanupFailure = null;
            if (session != null) {
                try {
                    appendRetirementDiagnostics(checkedModelKey, generation, session, diagnostics);
                } catch (Throwable failure) {
                    cleanupFailure = failure;
                }
            }
            Throwable fatalFailure = selectFatalFailure(exception, cleanupFailure);
            if (fatalFailure != null) {
                rethrowIfFatal(fatalFailure);
            }
            if (cleanupFailure != null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.PROVIDER_FAILURE,
                        checkedModelKey,
                        generation,
                        null,
                        "Material provider lifecycle cleanup failed with " + safeThrowableType(cleanupFailure)));
            }
            if (exception instanceof CapabilityNegotiationException negotiation) {
                diagnostics.add(fromCapability(checkedModelKey, generation, negotiation.diagnostic()));
            } else {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.PROVIDER_FAILURE,
                        checkedModelKey,
                        generation,
                        null,
                        "Material provider preparation failed with " + safeThrowableType(exception)));
            }
            return failed(checkedModelKey, generation, diagnostics);
        }
    }

    /**
     * Intersects caller-controlled requests with X6's current host contract before generic X1
     * negotiation. X1's registry intentionally remains a general versioned data-plane; this
     * adapter is the point that prevents a broad caller range from selecting a historical 1.0
     * provider into the current X6 lifecycle.
     */
    private static List<CapabilityRequest> constrainRequestsToCurrentX6Protocol(
            BlendModelKey modelKey,
            long generation,
            List<CapabilityRequest> requests,
            List<X6Diagnostic> diagnostics) {
        List<CapabilityRequest> constrained = new ArrayList<>(requests.size());
        CapabilityVersionRange currentRange = CapabilityVersion.CURRENT_PROTOCOL_RANGE;
        for (CapabilityRequest request : requests) {
            CapabilityVersion minInclusive = laterOf(
                    request.supportedVersions().minInclusive(), currentRange.minInclusive());
            CapabilityVersion maxExclusive = earlierOf(
                    request.supportedVersions().maxExclusive(), currentRange.maxExclusive());
            if (minInclusive.compareTo(maxExclusive) >= 0) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.CAPABILITY_FAILURE,
                        modelKey,
                        generation,
                        request.capabilityId(),
                        "X6 current Experimental SPI protocol range " + currentRange
                                + " does not overlap the caller request"));
                continue;
            }
            constrained.add(new CapabilityRequest(
                    request.capabilityId(),
                    new CapabilityVersionRange(minInclusive, maxExclusive),
                    request.requirement(),
                    request.fallback()));
        }
        return List.copyOf(constrained);
    }

    private static CapabilityVersion laterOf(CapabilityVersion first, CapabilityVersion second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static CapabilityVersion earlierOf(CapabilityVersion first, CapabilityVersion second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    /**
     * Takes exactly one bounded pass over caller-owned provider input without invoking its size,
     * contains, or array-conversion methods. Null elements remain in this private snapshot so the
     * metadata boundary can issue the normal local diagnostic and preserve an explicit fallback.
     */
    private static List<MaterialProvider> snapshotProviderInputs(
            BlendModelKey modelKey,
            long generation,
            Collection<? extends MaterialProvider> supplied,
            List<X6Diagnostic> diagnostics) {
        List<MaterialProvider> copied = new ArrayList<>();
        try {
            Iterator<? extends MaterialProvider> iterator = Objects.requireNonNull(supplied, "providers").iterator();
            iterator = Objects.requireNonNull(iterator, "providers iterator");
            while (iterator.hasNext()) {
                if (copied.size() >= X6Ids.MAX_VARIANTS) {
                    throw new IllegalArgumentException("MaterialProvider input exceeds the X6 bounded metadata limit");
                }
                copied.add(iterator.next());
            }
            return Collections.unmodifiableList(new ArrayList<>(copied));
        } catch (Throwable exception) {
            rethrowIfFatal(exception);
            diagnostics.add(X6Diagnostic.warning(
                    X6DiagnosticCode.PROVIDER_FAILURE,
                    modelKey,
                    generation,
                    null,
                    "MaterialProvider input was excluded with " + safeThrowableType(exception)));
            return List.of();
        }
    }

    /** Takes one bounded pass over requests because caller collections are also external control-plane input. */
    private static List<CapabilityRequest> snapshotRequests(
            BlendModelKey modelKey,
            long generation,
            Collection<CapabilityRequest> supplied,
            List<X6Diagnostic> diagnostics) {
        List<CapabilityRequest> copied = new ArrayList<>();
        try {
            Iterator<CapabilityRequest> iterator = Objects.requireNonNull(supplied, "requests").iterator();
            iterator = Objects.requireNonNull(iterator, "requests iterator");
            while (iterator.hasNext()) {
                if (copied.size() >= X6Ids.MAX_VARIANTS) {
                    throw new IllegalArgumentException("Capability request input exceeds the X6 bounded metadata limit");
                }
                CapabilityRequest request = iterator.next();
                if (request == null) {
                    throw new NullPointerException("requests contains null");
                }
                copied.add(request);
            }
            return List.copyOf(copied);
        } catch (Throwable exception) {
            rethrowIfFatal(exception);
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.CAPABILITY_FAILURE,
                    modelKey,
                    generation,
                    null,
                    "Capability request input was rejected with " + safeThrowableType(exception)));
            return List.of();
        }
    }

    private static MetadataSnapshotResult snapshotProviderMetadata(
            BlendModelKey modelKey,
            long generation,
            List<MaterialProvider> providers,
            List<X6Diagnostic> diagnostics) {
        IdentityHashMap<MaterialProvider, Boolean> seen = new IdentityHashMap<>();
        IdentityHashMap<MaterialProvider, BlendResourceId> providerIdsByObject = new IdentityHashMap<>();
        Set<BlendResourceId> observedProviderIds = new LinkedHashSet<>();
        Set<BlendResourceId> duplicateProviderIds = new LinkedHashSet<>();
        Map<BlendResourceId, MaterialProvider> uniqueProvidersById = new LinkedHashMap<>();
        for (MaterialProvider provider : providers) {
            if (provider == null) {
                diagnostics.add(X6Diagnostic.warning(
                        X6DiagnosticCode.PROVIDER_FAILURE,
                        modelKey,
                        generation,
                        null,
                        "Null MaterialProvider was excluded before capability discovery"));
                continue;
            }
            if (seen.put(provider, Boolean.TRUE) != null) {
                BlendResourceId providerId = providerIdsByObject.get(provider);
                if (providerId == null) {
                    diagnostics.add(X6Diagnostic.error(
                            X6DiagnosticCode.CAPABILITY_FAILURE,
                            modelKey,
                            generation,
                            null,
                            CapabilityErrorCode.DUPLICATE_PROVIDER_ID.code()
                                    + ": Provider object identity is repeated before registration"));
                } else {
                    diagnoseDuplicateProviderId(
                            modelKey, generation, providerId, duplicateProviderIds, diagnostics);
                    uniqueProvidersById.remove(providerId);
                }
                continue;
            }
            BlendResourceId providerId = snapshotProviderId(modelKey, generation, provider, diagnostics);
            if (providerId == null) {
                continue;
            }
            providerIdsByObject.put(provider, providerId);
            if (!observedProviderIds.add(providerId)) {
                diagnoseDuplicateProviderId(
                        modelKey, generation, providerId, duplicateProviderIds, diagnostics);
                uniqueProvidersById.remove(providerId);
                continue;
            }
            uniqueProvidersById.put(providerId, provider);
        }
        if (hasError(diagnostics)) {
            return new MetadataSnapshotResult(List.of(), Map.of());
        }

        Map<BlendResourceId, ProviderMetadata> byId = new LinkedHashMap<>();
        for (Map.Entry<BlendResourceId, MaterialProvider> entry : uniqueProvidersById.entrySet()) {
            BlendResourceId providerId = entry.getKey();
            ProviderMetadata metadata = snapshotOneProvider(
                    modelKey, generation, entry.getValue(), providerId, diagnostics);
            if (metadata == null) {
                continue;
            }
            byId.put(providerId, metadata);
        }
        List<MaterialProvider> accepted = new ArrayList<>(byId.size());
        byId.values().forEach(metadata -> accepted.add(metadata.provider()));
        return new MetadataSnapshotResult(List.copyOf(accepted), Map.copyOf(byId));
    }

    /** Reads one canonical provider identity before any registry offer or lifecycle callback. */
    private static BlendResourceId snapshotProviderId(
            BlendModelKey modelKey,
            long generation,
            MaterialProvider provider,
            List<X6Diagnostic> diagnostics) {
        try {
            return X6Ids.requireId(provider.providerId(), "provider.providerId()");
        } catch (Throwable exception) {
            rethrowIfFatal(exception);
            diagnostics.add(X6Diagnostic.warning(
                    X6DiagnosticCode.PROVIDER_FAILURE,
                    modelKey,
                    generation,
                    null,
                    "MaterialProvider identity was excluded with " + safeThrowableType(exception)));
            return null;
        }
    }

    /** Performs one bounded, Throwable-contained material-capability read per unique provider id. */
    private static ProviderMetadata snapshotOneProvider(
            BlendModelKey modelKey,
            long generation,
            MaterialProvider provider,
            BlendResourceId providerId,
            List<X6Diagnostic> diagnostics) {
        try {
            return new ProviderMetadata(provider, providerId, snapshotCapabilities(provider.supportedMaterialCapabilities()));
        } catch (Throwable exception) {
            rethrowIfFatal(exception);
            diagnostics.add(X6Diagnostic.warning(
                    X6DiagnosticCode.PROVIDER_FAILURE,
                    modelKey,
                    generation,
                    providerId,
                    "MaterialProvider metadata was excluded with " + safeThrowableType(exception)));
            return null;
        }
    }

    /** Mirrors X1 CAP-001 before registration, so caller ordering can never choose a lifecycle owner. */
    private static void diagnoseDuplicateProviderId(
            BlendModelKey modelKey,
            long generation,
            BlendResourceId providerId,
            Set<BlendResourceId> diagnosedProviderIds,
            List<X6Diagnostic> diagnostics) {
        if (!diagnosedProviderIds.add(providerId)) {
            return;
        }
        diagnostics.add(fromCapability(
                modelKey,
                generation,
                CapabilityDiagnostic.provider(
                        CapabilityErrorCode.DUPLICATE_PROVIDER_ID,
                        BlendDiagnosticSeverity.ERROR,
                        providerId,
                        "Provider identity is already registered or being registered")));
    }

    /** Copies a hostile-capable Set without calling its size or contains methods. */
    private static Set<BlendResourceId> snapshotCapabilities(Set<BlendResourceId> supplied) {
        Iterator<BlendResourceId> iterator = Objects.requireNonNull(supplied, "supportedMaterialCapabilities").iterator();
        iterator = Objects.requireNonNull(iterator, "supportedMaterialCapabilities iterator");
        Set<BlendResourceId> copied = new LinkedHashSet<>();
        while (iterator.hasNext()) {
            if (copied.size() >= X6Ids.MAX_VARIANTS) {
                throw new IllegalArgumentException("MaterialProvider capability count exceeds the X6 bounded metadata limit");
            }
            BlendResourceId capability = X6Ids.requireId(iterator.next(), "supported material capability");
            if (!copied.add(capability)) {
                throw new IllegalArgumentException("MaterialProvider supplied a duplicate material capability");
            }
        }
        return Set.copyOf(copied);
    }

    /** Releases only a session-owned selected-provider identity and retains its full terminal result. */
    private static void appendRetirementDiagnostics(
            BlendModelKey modelKey,
            long generation,
            ProviderLifecycleSession session,
            List<X6Diagnostic> diagnostics) {
        ProviderLifecycleResult result = Objects.requireNonNull(session, "session").retire();
        diagnostics.addAll(result.diagnostics().stream()
                .map(value -> fromCapability(modelKey, generation, value))
                .toList());
    }

    private static void validateSelectedMaterialCapabilities(
            BlendModelKey modelKey,
            long generation,
            CapabilityPlan plan,
            Map<BlendResourceId, ProviderMetadata> providers,
            List<X6Diagnostic> diagnostics) {
        for (CapabilitySelection selection : plan.selections()) {
            if (selection.selectedOffer().isEmpty()) {
                continue;
            }
            BlendResourceId providerId = selection.selectedOffer().orElseThrow().providerId();
            ProviderMetadata provider = providers.get(providerId);
            if (provider == null) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.CAPABILITY_FAILURE,
                        modelKey,
                        generation,
                        providerId,
                        "Frozen X1 plan selected a provider absent from the X6 metadata snapshot"));
                continue;
            }
            if (!provider.supportedCapabilities().contains(selection.request().capabilityId())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.CAPABILITY_FAILURE,
                        modelKey,
                        generation,
                        providerId,
                        "Selected MaterialProvider did not advertise the frozen material capability in its immutable metadata snapshot"));
            }
        }
    }

    /** Redundant post-freeze guard: a current X6 session must never own a non-current offer. */
    private static void validateSelectedCurrentX6Protocols(
            BlendModelKey modelKey,
            long generation,
            CapabilityPlan plan,
            List<X6Diagnostic> diagnostics) {
        CapabilityVersionRange currentRange = CapabilityVersion.CURRENT_PROTOCOL_RANGE;
        for (CapabilitySelection selection : plan.selections()) {
            Optional<CapabilityOffer> selected = selection.selectedOffer();
            if (selected.isEmpty()) {
                continue;
            }
            CapabilityOffer offer = selected.orElseThrow();
            if (!currentRange.contains(offer.protocolVersion())) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.CAPABILITY_FAILURE,
                        modelKey,
                        generation,
                        offer.providerId(),
                        "Frozen X1 plan selected a provider outside X6 current Experimental SPI protocol range "
                                + currentRange));
            }
        }
    }

    private static X6MaterialProviderGeneration failed(
            BlendModelKey modelKey, long generation, List<X6Diagnostic> diagnostics) {
        return new X6MaterialProviderGeneration(modelKey, generation, null, null, ordered(diagnostics));
    }

    private static boolean hasError(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR);
    }

    private static List<X6Diagnostic> ordered(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream()
                .sorted(Comparator.comparing((X6Diagnostic value) -> value.code().code())
                        .thenComparing(value -> value.subjectId().map(BlendResourceId::value).orElse(""))
                        .thenComparing(X6Diagnostic::message))
                .toList();
    }

    private static X6Diagnostic fromCapability(BlendModelKey modelKey, long generation, CapabilityDiagnostic diagnostic) {
        BlendResourceId subject = diagnostic.capabilityId().orElseGet(() -> diagnostic.providerId().orElse(null));
        X6DiagnosticSeverity severity = switch (diagnostic.severity()) {
            case ERROR -> X6DiagnosticSeverity.ERROR;
            case WARNING -> X6DiagnosticSeverity.WARNING;
            case INFO -> X6DiagnosticSeverity.INFO;
        };
        return new X6Diagnostic(
                severity,
                providerLifecycleCode(diagnostic.code()) ? X6DiagnosticCode.PROVIDER_FAILURE : X6DiagnosticCode.CAPABILITY_FAILURE,
                modelKey,
                generation,
                Optional.ofNullable(subject),
                diagnostic.code().code() + ": " + diagnostic.message());
    }

    private static boolean providerLifecycleCode(CapabilityErrorCode code) {
        return switch (code) {
            case PROVIDER_PREPARE_FAILURE, PROVIDER_APPLY_FAILURE, PROVIDER_RETIRE_FAILURE, PROVIDER_CLOSE_FAILURE,
                    PROVIDER_OWNERSHIP_CONFLICT -> true;
            default -> false;
        };
    }

    private static void rethrowIfFatal(Throwable exception) {
        if (exception instanceof Error error) {
            throw error;
        }
    }

    private static boolean isFatal(Throwable exception) {
        return exception instanceof Error;
    }

    private static String safeThrowableType(Throwable exception) {
        rethrowIfFatal(exception);
        try {
            String simpleName = exception.getClass().getSimpleName();
            return simpleName == null || simpleName.isBlank() ? "Throwable" : simpleName;
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            return "Throwable";
        }
    }

    private static Throwable selectFatalFailure(Throwable primaryFailure, Throwable cleanupFailure) {
        if (isFatal(primaryFailure)) {
            retainSecondary(primaryFailure, cleanupFailure);
            return primaryFailure;
        }
        if (isFatal(cleanupFailure)) {
            retainSecondary(cleanupFailure, primaryFailure);
            return cleanupFailure;
        }
        return null;
    }

    private static void retainSecondary(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure == null || secondaryFailure == null || primaryFailure == secondaryFailure) {
            return;
        }
        try {
            primaryFailure.addSuppressed(secondaryFailure);
        } catch (Throwable suppressionFailure) {
            rethrowIfFatal(suppressionFailure);
            // Ordinary suppression bookkeeping cannot replace the selected fatal failure.
        }
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public boolean publishable() {
        return lifecycleSession != null && lifecycleSession.state() == ProviderLifecycleState.PUBLISHED;
    }

    public Optional<CapabilityPlan> capabilityPlan() {
        return Optional.ofNullable(capabilityPlan);
    }

    public List<X6Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Returns the lifecycle's live state for factory diagnostics; submit never reads this bridge. */
    public Optional<ProviderLifecycleState> lifecycleState() {
        return lifecycleSession == null ? Optional.empty() : Optional.of(lifecycleSession.state());
    }

    /** Freezes selected-provider/fallback metadata under the exact X1 outcome semantics. */
    public List<X6MaterialProviderBinding> materialBindings() {
        if (capabilityPlan == null) {
            return List.of();
        }
        return capabilityPlan.selections().stream()
                .map(selection -> new X6MaterialProviderBinding(
                        selection.request().capabilityId(),
                        selection.outcome(),
                        selection.selectedOffer().map(offer -> offer.providerId()),
                        selection.outcome() == CapabilitySelectionOutcome.FALLBACK
                                ? selection.request().fallback().map(fallback -> fallback.fallbackId())
                                : Optional.empty()))
                .toList();
    }

    /** Issues one exact generation pin after preparation/application/publication have completed. */
    public ProviderLease pinSnapshot() {
        if (lifecycleSession == null || lifecycleSession.state() != ProviderLifecycleState.PUBLISHED) {
            throw new IllegalStateException("A failed X6 material provider generation cannot issue a snapshot lease");
        }
        return lifecycleSession.pin();
    }

    /** Starts retirement; X1 waits for every snapshot lease before retire/close callbacks execute. */
    public ProviderLifecycleResult retire() {
        if (lifecycleSession == null) {
            return new ProviderLifecycleResult(
                    com.liy.blendlib.spi.experimental.ProviderLifecycleStage.CLOSE,
                    !hasError(diagnostics),
                    List.of());
        }
        return lifecycleSession.retire();
    }

    @Override
    public void close() {
        retire();
    }

    private record ProviderMetadata(
            MaterialProvider provider,
            BlendResourceId providerId,
            Set<BlendResourceId> supportedCapabilities) {
        private ProviderMetadata {
            provider = Objects.requireNonNull(provider, "provider");
            providerId = X6Ids.requireId(providerId, "providerId");
            supportedCapabilities = Set.copyOf(Objects.requireNonNull(supportedCapabilities, "supportedCapabilities"));
        }
    }

    private record MetadataSnapshotResult(List<MaterialProvider> providers, Map<BlendResourceId, ProviderMetadata> byId) {
        private MetadataSnapshotResult {
            providers = List.copyOf(providers);
            byId = Map.copyOf(byId);
        }
    }
}
