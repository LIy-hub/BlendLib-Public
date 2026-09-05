package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelLookupBootstrap;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically publishes complete client model generations and retires replaced generations. */
public final class ClientModelRegistry {
    record ResourceOwnerPublication(
            ModelRegistryGeneration activeGeneration, ModelRegistryGeneration displacedGeneration, boolean published) { }

    @FunctionalInterface
    interface ResourceOwnerCasPort {
        ResourceOwnerPublication compareAndSet(
                AtomicReference<ModelRegistryGeneration> active,
                ModelRegistryGeneration replacement);
    }

    private final AtomicReference<ModelRegistryGeneration> active;
    private final AtomicLong nextGenerationId;
    private final AtomicLong retiredGenerationCount = new AtomicLong();
    private final AtomicLong staleGenerationCount = new AtomicLong();
    private final AtomicInteger mostRecentlyRetiredBackendHandleCount = new AtomicInteger();
    private final AtomicInteger peakActiveBackendHandleCount;
    private final ClientGenerationResourceOwner resourceOwner;
    private final ResourceOwnerCasPort resourceOwnerCasPort;
    private final boolean minecraft2612ShutdownBootstrapEligible;
    private ClientFinalFrameShutdownCoordinator shutdownCoordinator;
    private ClientModelLookup serviceLookup;

    public ClientModelRegistry() {
        this(ModelRegistryGeneration.empty(0L));
    }

    /**
     * Creates the Minecraft 26.1.2 client registry with BlendLib's trusted render-owner callbacks.
     *
     * <p>This adapter factory does not enable GPU allocation or attach a render resource. It only installs the
     * fixed Minecraft render-thread handoff and ordinary fenced-task spine that a later, complete resource
     * transaction may use. Every call returns a fresh registry with isolated mutable generation state. The one
     * client-wide final-present shutdown coordinator is installed only if this registry later receives the sealed
     * production-service bootstrap capability. This still does not enable allocation or a production attachment
     * caller. The public no-argument constructor remains the CPU-only, ownerless compatibility path.</p>
     */
    public static ClientModelRegistry createMinecraft2612Client() {
        return new ClientModelRegistry(
                ModelRegistryGeneration.empty(0L),
                Minecraft2612GenerationRenderOwner.create(),
                true,
                ClientModelRegistry::normalResourceOwnerCompareAndSet);
    }

    ClientModelRegistry(ModelRegistryGeneration initialGeneration) {
        this(initialGeneration, null);
    }

    ClientModelRegistry(
            ModelRegistryGeneration initialGeneration, ClientGenerationResourceOwner.RenderOwnerCallbacks renderOwner) {
        this(initialGeneration, renderOwner, false, ClientModelRegistry::normalResourceOwnerCompareAndSet);
    }

    ClientModelRegistry(
            ModelRegistryGeneration initialGeneration,
            ClientGenerationResourceOwner.RenderOwnerCallbacks renderOwner,
            ResourceOwnerCasPort resourceOwnerCasPort) {
        this(initialGeneration, renderOwner, false, resourceOwnerCasPort);
    }

    /** Package-private test constructor; policy metrics must materialize before the owner may invoke CAS. */
    ClientModelRegistry(
            ModelRegistryGeneration initialGeneration,
            ClientGenerationResourceOwner.RenderOwnerCallbacks renderOwner,
            ResourceOwnerCasPort resourceOwnerCasPort,
            ClientGenerationResourceOwner.PolicyMetricsMaterializer policyMetricsMaterializer) {
        this(initialGeneration, renderOwner, false, resourceOwnerCasPort, policyMetricsMaterializer);
    }

    private ClientModelRegistry(
            ModelRegistryGeneration initialGeneration,
            ClientGenerationResourceOwner.RenderOwnerCallbacks renderOwner,
            boolean minecraft2612ShutdownBootstrapEligible,
            ResourceOwnerCasPort resourceOwnerCasPort) {
        this(
                initialGeneration,
                renderOwner,
                minecraft2612ShutdownBootstrapEligible,
                resourceOwnerCasPort,
                ClientGenerationResourceOwner.DEFAULT_POLICY_METRICS_MATERIALIZER);
    }

    private ClientModelRegistry(
            ModelRegistryGeneration initialGeneration,
            ClientGenerationResourceOwner.RenderOwnerCallbacks renderOwner,
            boolean minecraft2612ShutdownBootstrapEligible,
            ResourceOwnerCasPort resourceOwnerCasPort,
            ClientGenerationResourceOwner.PolicyMetricsMaterializer policyMetricsMaterializer) {
        ModelRegistryGeneration checkedInitialGeneration = Objects.requireNonNull(initialGeneration, "initialGeneration");
        this.active = new AtomicReference<>();
        this.nextGenerationId = new AtomicLong(checkedInitialGeneration.generationId());
        this.peakActiveBackendHandleCount = new AtomicInteger(checkedInitialGeneration.backendHandleCount());
        this.resourceOwner = new ClientGenerationResourceOwner(this, renderOwner, policyMetricsMaterializer);
        this.resourceOwnerCasPort = Objects.requireNonNull(resourceOwnerCasPort, "resourceOwnerCasPort");
        this.minecraft2612ShutdownBootstrapEligible = minecraft2612ShutdownBootstrapEligible;
        this.resourceOwner.adoptInitialGeneration(checkedInitialGeneration);
        this.active.set(checkedInitialGeneration);
    }

    /** Reserves a monotonic generation identifier before prepare begins. */
    public long reserveNextGenerationId() {
        return nextGenerationId.incrementAndGet();
    }

    public ModelRegistryGeneration current() {
        return active.get();
    }

    /**
     * Reload-private D1 lookup for the exact immutable X7 state attached during transaction
     * preallocation. It intentionally exposes neither a general policy factory nor a resource
     * owner to the public facade.
     */
    X7ProductionPolicyOwner policyOwnerForActiveSource(
            BlendModelKey key, long generationId, ModelRenderHandle expectedRenderHandle) {
        BlendModelKey checkedKey = Objects.requireNonNull(key, "key");
        ModelRenderHandle checkedRenderHandle = Objects.requireNonNull(expectedRenderHandle, "expectedRenderHandle");
        ModelRegistryGeneration generation = active.get();
        if (generation.generationId() != generationId) {
            throw new IllegalStateException("X7 policy projection source generation is no longer active");
        }
        ModelHandle handle = generation.handles().get(checkedKey);
        if (handle == null || handle.renderHandle() != checkedRenderHandle || handle.missing()) {
            throw new IllegalStateException("X7 policy projection source handle is no longer active");
        }
        return resourceOwner.policyOwnerForActive(generation, checkedKey, handle, checkedRenderHandle);
    }

    /**
     * Performs an allocation-free lookup in the active immutable generation.
     *
     * <p>Callers bind missing fallbacks before renderer submit; submit code must consume its already-built snapshot
     * and must not query this registry.</p>
     */
    public Optional<ModelHandle> find(BlendModelKey key) {
        return active.get().find(key);
    }

    /**
     * Installs and returns this registry's one source-owned lookup during client-service bootstrap.
     *
     * <p>The required sealed capability is privately constructed by {@code BlendLibClientServices}.
     * There is intentionally no no-argument lookup accessor: a caller holding an arbitrary
     * registry cannot obtain an issuer for managed generation ownership.</p>
     */
    public synchronized ClientModelLookup installServiceLookup(ClientModelLookupBootstrap bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        if (serviceLookup == null) {
            installMinecraft2612ShutdownCoordinatorIfEligible();
            serviceLookup = new RegistryBackedModelLookup(this);
        }
        return serviceLookup;
    }

    /**
     * Atomically exposes a complete generation. A late stale prepare result is retired instead of replacing a newer
     * generation.
     */
    public ModelRegistryGeneration publish(ModelRegistryGeneration replacement) {
        return publish(PendingGenerationTransaction.cpuOnly(Objects.requireNonNull(replacement, "replacement")));
    }

    ModelRegistryGeneration publish(PendingGenerationTransaction transaction) {
        return resourceOwner.offerPrepared(Objects.requireNonNull(transaction, "transaction"));
    }

    ModelRegistryGeneration publish(
            PendingGenerationTransaction transaction,
            ClientGenerationResourceOwner.PolicyFreezeBarrier policyFreezeBarrier,
            ClientGenerationResourceOwner.PublicationAdoptionBarrier publicationAdoptionBarrier) {
        return resourceOwner.offerPrepared(
                Objects.requireNonNull(transaction, "transaction"),
                Objects.requireNonNull(policyFreezeBarrier, "policyFreezeBarrier"),
                Objects.requireNonNull(publicationAdoptionBarrier, "publicationAdoptionBarrier"));
    }

    /** Retires all current and previously retiring generations without assuming a render-thread owner exists. */
    public void close() {
        ClientFinalFrameShutdownCoordinator coordinator = shutdownCoordinator;
        if (coordinator == null) {
            resourceOwner.closeRegistry();
        } else {
            coordinator.clientStoppingFallback();
        }
    }

    ClientGenerationResourceOwner.DrainDiagnostics resourceLifecycleDiagnostics() {
        return resourceOwner.diagnostics();
    }

    ClientGenerationResourceOwner generationResourceOwner() {
        return resourceOwner;
    }

    ResourceOwnerPublication publishAtomicallyFromResourceOwner(
            ModelRegistryGeneration checkedReplacement,
            ClientGenerationResourceOwner.ResourceOwnerPublicationPermit publicationPermit) {
        Objects.requireNonNull(checkedReplacement, "checkedReplacement");
        Objects.requireNonNull(publicationPermit, "publicationPermit").consumeFor(this, checkedReplacement);
        nextGenerationId.accumulateAndGet(checkedReplacement.generationId(), Math::max);
        ResourceOwnerPublication publication = resourceOwnerCasPort.compareAndSet(active, checkedReplacement);
        if (publication.published()) {
            peakActiveBackendHandleCount.accumulateAndGet(
                    checkedReplacement.backendHandleCount(), Math::max);
        }
        return publication;
    }

    private static ResourceOwnerPublication normalResourceOwnerCompareAndSet(
            AtomicReference<ModelRegistryGeneration> active,
            ModelRegistryGeneration checkedReplacement) {
        while (true) {
            ModelRegistryGeneration previous = active.get();
            if (checkedReplacement.generationId() <= previous.generationId()) {
                return new ResourceOwnerPublication(previous, null, false);
            }
            if (active.compareAndSet(previous, checkedReplacement)) {
                return new ResourceOwnerPublication(checkedReplacement, previous, true);
            }
        }
    }

    /**
     * Internal lifecycle observation for reload-retention regression tests.
     *
     * <p>The registry owns exactly one active generation: {@link #current()}. The returned retired-handle count is
     * derived from the lifecycle owner's currently retained non-active records, so it is zero only after terminal
     * cleanup has released owner retention. Immutable snapshots can retain a previously captured generation
     * independently; retiring one never clears its handles. This method is package-private to avoid publishing an
     * application metric/configuration API in v1.</p>
     */
    ReloadRetentionMetrics reloadRetentionMetrics() {
        ModelRegistryGeneration current = active.get();
        return new ReloadRetentionMetrics(
                current.generationId(),
                current.backendHandleCount(),
                current.loadedBackendHandleCount(),
                current.missingBackendHandleCount(),
                resourceOwner.retainedRetiredBackendHandleCount(),
                mostRecentlyRetiredBackendHandleCount.get(),
                retiredGenerationCount.get(),
                staleGenerationCount.get(),
                peakActiveBackendHandleCount.get());
    }

    void recordRetirementFromResourceOwner(ModelRegistryGeneration retiredGeneration, boolean stale) {
        mostRecentlyRetiredBackendHandleCount.set(retiredGeneration.backendHandleCount());
        retiredGenerationCount.incrementAndGet();
        if (stale) {
            staleGenerationCount.incrementAndGet();
        }
    }

    private void installMinecraft2612ShutdownCoordinatorIfEligible() {
        if (!minecraft2612ShutdownBootstrapEligible || shutdownCoordinator != null) {
            return;
        }
        ClientFinalFrameShutdownCoordinator coordinator = new ClientFinalFrameShutdownCoordinator(
                resourceOwner,
                Minecraft2612OwnedFinalFenceAdapter.createPinned(),
                System::nanoTime,
                50_000_000L,
                1_000_000);
        Minecraft2612FinalFrameShutdownHooks.install(coordinator);
        shutdownCoordinator = coordinator;
    }
}
