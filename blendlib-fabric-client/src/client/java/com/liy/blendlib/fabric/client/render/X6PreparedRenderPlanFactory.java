package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.spi.experimental.ProviderLease;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Creates one pin-owning X6 plan only after all adapter-side preparation completed. */
public final class X6PreparedRenderPlanFactory {
    private static final ProviderPin DEFAULT_PROVIDER_PIN = X6MaterialProviderGeneration::pinSnapshot;
    private static final PostPinPlanAssembler OWNER_REQUIRED_UNREACHABLE_ASSEMBLER = input -> {
        throw new AssertionError("owner-required factory path must fail before provider pinning");
    };
    private static final LeaseReleaseWorkerLauncher DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER =
            PlatformLeaseReleaseWorker::new;
    private static final SuppressionAppender DEFAULT_SUPPRESSION_APPENDER = Throwable::addSuppressed;

    private X6PreparedRenderPlanFactory() {
    }

    /**
     * Freezes the cross-cutting X6 handoff for one exact prepared render handle.
     *
     * <p>The binding snapshot is mandatory: its exact handle identity, static node/palette shape,
     * or captured skinned mesh identity is checked before X1 is pinned. The returned plan then
     * accepts later frame snapshots only when they retain that same handle object. This synchronous
     * preparation boundary may wait for a controlled lease-release worker after a post-pin failure,
     * so a host must invoke it from its preparation/lifecycle-owner worker rather than a render or
     * controller thread.</p>
     */
    @Deprecated(forRemoval = false)
    public static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                DEFAULT_PROVIDER_PIN,
                OWNER_REQUIRED_UNREACHABLE_ASSEMBLER,
                DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER,
                DEFAULT_SUPPRESSION_APPENDER,
                true,
                null);
    }

    /**
     * Freezes one source-bound registry-managed X6 plan and transfers its exact D1 parent to the
     * drain bridge.
     *
     * <p>The composite is sampled by the concrete registry-backed lookup and contains its own
     * immutable snapshot. A raw generation lease plus a caller-supplied snapshot is
     * intentionally not an X6 public admission surface: it cannot prove source provenance when
     * two registries reuse the same raw handle. The caller retains the composite's parent if X6
     * structural prevalidation or lifecycle admission fails. After successful admission this
     * factory owns it: a later pin/publication failure closes it exactly once, while a published
     * plan transfers it to the lifecycle bridge and releases it only after plan close plus all
     * admitted X6 submissions drain.</p>
     */
    public static X6PlanResult<X6PreparedRenderPlan> prepareManaged(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ClientGenerationLeaseBinding sharedGenerationBinding,
            X6LifecycleDrainDispatcher lifecycleOwner) {
        lifecycleOwner = Objects.requireNonNull(lifecycleOwner, "lifecycleOwner");
        sharedGenerationBinding = Objects.requireNonNull(sharedGenerationBinding, "sharedGenerationBinding");
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                sharedGenerationBinding.snapshot(),
                DEFAULT_PROVIDER_PIN,
                input -> new X6PreparedRenderPlan(
                        input.variants(),
                        input.layers(),
                        input.materials(),
                        input.geometry(),
                        input.bindingSnapshot().handle(),
                        input.boundSkinnedMeshCount(),
                        input.providers().materialBindings(),
                        Objects.requireNonNull(input.lifecycleDrain(), "lifecycleDrain"),
                        input.permitsFrozenCpuRoute()),
                DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER,
                DEFAULT_SUPPRESSION_APPENDER,
                false,
                lifecycleOwner,
                sharedGenerationBinding);
    }

    /**
     * Freezes one publishable X6 plan with its required host lifecycle-owner dispatcher.
     *
     * <p>The owner must admit and retain the prebuilt bridge before X1 is pinned, then drain it
     * asynchronously through reload/shutdown. This additive adapter surface is Experimental: the
     * old six-argument descriptor remains linkable but deliberately returns an owner-required
     * preparation failure after all legacy pre-pin validation has completed.</p>
     */
    public static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            X6LifecycleDrainDispatcher lifecycleOwner) {
        lifecycleOwner = Objects.requireNonNull(lifecycleOwner, "lifecycleOwner");
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                DEFAULT_PROVIDER_PIN,
                input -> new X6PreparedRenderPlan(
                        input.variants(),
                        input.layers(),
                        input.materials(),
                        input.geometry(),
                        input.bindingSnapshot().handle(),
                        input.boundSkinnedMeshCount(),
                        input.providers().materialBindings(),
                        Objects.requireNonNull(input.lifecycleDrain(), "lifecycleDrain")),
                DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER,
                DEFAULT_SUPPRESSION_APPENDER,
                false,
                lifecycleOwner);
    }

    /** Package-private deterministic seam for post-pin ownership-failure regression tests. */
    static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                providerPin,
                planAssembler,
                DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER,
                DEFAULT_SUPPRESSION_APPENDER,
                false,
                null);
    }

    /** Package-private deterministic seam for lifecycle admission with the normal rollback worker. */
    static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler,
            X6LifecycleDrainDispatcher lifecycleOwner) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                providerPin,
                planAssembler,
                DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER,
                DEFAULT_SUPPRESSION_APPENDER,
                false,
                Objects.requireNonNull(lifecycleOwner, "lifecycleOwner"));
    }

    /** Package-private deterministic seam for source-bound managed-parent regression tests. */
    static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler,
            X6LifecycleDrainDispatcher lifecycleOwner,
            ClientGenerationLeaseBinding sharedGenerationBinding) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                providerPin,
                planAssembler,
                DEFAULT_LEASE_RELEASE_WORKER_LAUNCHER,
                DEFAULT_SUPPRESSION_APPENDER,
                false,
                Objects.requireNonNull(lifecycleOwner, "lifecycleOwner"),
                Objects.requireNonNull(sharedGenerationBinding, "sharedGenerationBinding"));
    }

    /**
     * Package-private deterministic seam for post-pin release and throwable-precedence regression
     * tests. Launchers and workers receive only an exact-once release controller, never the raw
     * lease; the factory resolves every launch/start/await ambiguity through that same controller.
     */
    static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler,
            LeaseReleaseWorkerLauncher leaseReleaseWorkerLauncher,
            SuppressionAppender suppressionAppender) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                providerPin,
                planAssembler,
                leaseReleaseWorkerLauncher,
                suppressionAppender,
                false,
                null);
    }

    /** Package-private deterministic seam for lifecycle-admission and post-pin rollback tests. */
    static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler,
            LeaseReleaseWorkerLauncher leaseReleaseWorkerLauncher,
            SuppressionAppender suppressionAppender,
            X6LifecycleDrainDispatcher lifecycleOwner) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                providerPin,
                planAssembler,
                leaseReleaseWorkerLauncher,
                suppressionAppender,
                false,
                Objects.requireNonNull(lifecycleOwner, "lifecycleOwner"));
    }

    private static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler,
            LeaseReleaseWorkerLauncher leaseReleaseWorkerLauncher,
            SuppressionAppender suppressionAppender,
            boolean ownerRequired,
            X6LifecycleDrainDispatcher lifecycleOwner) {
        return prepare(
                variants,
                layers,
                materials,
                geometry,
                providers,
                bindingSnapshot,
                providerPin,
                planAssembler,
                leaseReleaseWorkerLauncher,
                suppressionAppender,
                ownerRequired,
                lifecycleOwner,
                null);
    }

    private static X6PlanResult<X6PreparedRenderPlan> prepare(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            ProviderPin providerPin,
            PostPinPlanAssembler planAssembler,
            LeaseReleaseWorkerLauncher leaseReleaseWorkerLauncher,
            SuppressionAppender suppressionAppender,
            boolean ownerRequired,
            X6LifecycleDrainDispatcher lifecycleOwner,
            ClientGenerationLeaseBinding sharedGenerationBinding) {
        variants = Objects.requireNonNull(variants, "variants");
        layers = Objects.requireNonNull(layers, "layers");
        materials = Objects.requireNonNull(materials, "materials");
        geometry = Objects.requireNonNull(geometry, "geometry");
        providers = Objects.requireNonNull(providers, "providers");
        bindingSnapshot = Objects.requireNonNull(bindingSnapshot, "bindingSnapshot");
        providerPin = Objects.requireNonNull(providerPin, "providerPin");
        planAssembler = Objects.requireNonNull(planAssembler, "planAssembler");
        leaseReleaseWorkerLauncher = Objects.requireNonNull(
                leaseReleaseWorkerLauncher, "leaseReleaseWorkerLauncher");
        suppressionAppender = Objects.requireNonNull(suppressionAppender, "suppressionAppender");
        BlendModelKey modelKey = variants.modelKey();
        long generation = variants.generation();
        List<X6Diagnostic> diagnostics = new ArrayList<>(providers.diagnostics());
        if (!modelKey.equals(layers.modelKey()) || generation != layers.generation()
                || !modelKey.equals(materials.modelKey()) || generation != materials.generation()
                || !modelKey.equals(geometry.modelKey()) || generation != geometry.generation()
                || !modelKey.equals(providers.modelKey()) || generation != providers.generation()
                || !modelKey.equals(bindingSnapshot.handle().modelKey()) || generation != bindingSnapshot.generation()) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    modelKey,
                    generation,
                    null,
                    "X6 variants, layers, materials, geometry, provider generation, and binding snapshot must match exactly before publication"));
            return X6PlanResult.failure(ordered(diagnostics));
        }
        materials.collectCoverageDiagnostics(geometry, diagnostics);
        if (hasError(diagnostics)) {
            return X6PlanResult.failure(ordered(diagnostics));
        }
        final int boundSkinnedMeshCount;
        try {
            boundSkinnedMeshCount = geometry.bindForSnapshot(bindingSnapshot);
        } catch (Throwable exception) {
            rethrowIfFatal(exception);
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.GEOMETRY_MISMATCH,
                    modelKey,
                    generation,
                    null,
                    "X6 exact snapshot/handle binding failed before publication"));
            return X6PlanResult.failure(ordered(diagnostics));
        }
        if (!providers.publishable()) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.CAPABILITY_FAILURE,
                    modelKey,
                    generation,
                    null,
                    "X6 cannot publish from a material provider generation in state "
                            + providers.lifecycleState().map(Enum::name).orElse("FAILED")));
            return X6PlanResult.failure(ordered(diagnostics));
        }
        if (ownerRequired) {
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                    modelKey,
                    generation,
                    null,
                    "X6 successful render-plan publication requires an explicit asynchronous lifecycle owner dispatcher"));
            return X6PlanResult.failure(ordered(diagnostics));
        }
        ClientGenerationLeaseBinding acceptedSharedGenerationBinding = null;
        boolean permitsFrozenCpuRoute = true;
        if (sharedGenerationBinding != null) {
            try {
                if (sharedGenerationBinding.snapshot() != bindingSnapshot) {
                    throw new IllegalArgumentException(
                            "X6 managed ownership requires the exact source-bound snapshot composite");
                }
                sharedGenerationBinding.requireManagedForPlan();
                permitsFrozenCpuRoute = sharedGenerationBinding.permitsFrozenCpuRoute(bindingSnapshot);
                acceptedSharedGenerationBinding = sharedGenerationBinding;
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                        modelKey,
                        generation,
                        null,
                        "X6 requires one open source-bound registry-generation lease composite for its managed plan path"));
                return X6PlanResult.failure(ordered(diagnostics));
            }
        }
        X6LifecycleDrainBridge lifecycleDrain = null;
        if (lifecycleOwner != null) {
            lifecycleDrain = new X6LifecycleDrainBridge(generation);
            if (!lifecycleDrain.admit(lifecycleOwner)) {
                Throwable admissionFailure = selectFailure(
                        lifecycleDrain.completion().admissionFailure(),
                        lifecycleDrain.completion().cancellationFailure(),
                        suppressionAppender);
                rethrowIfFatal(admissionFailure);
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                        modelKey,
                        generation,
                        null,
                        "X6 lifecycle owner dispatcher rejected or violated pre-pin bridge admission; no provider lease was pinned"));
                return X6PlanResult.failure(ordered(diagnostics));
            }
        }
        // Successful admission is the explicit ownership-transfer point for the managed entry.
        // Prevalidation/admission failures above leave the caller-owned opaque lease untouched.
        ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceipt = null;
        if (acceptedSharedGenerationBinding != null) {
            try {
                sharedGenerationReceipt = acceptedSharedGenerationBinding.transferToPlan();
            } catch (Throwable failure) {
                X6LifecycleDrainBridge.DetachedLeases detached = lifecycleDrain == null
                        ? null
                        : lifecycleDrain.detachBeforePublication();
                Throwable cancellationFailure = detached == null ? null : detached.cancellationFailure();
                Throwable sharedLeaseFailure = detached == null
                        ? null
                        : closeSharedGenerationReceipt(detached.sharedGenerationReceipt());
                Throwable cleanupFailure = selectFailure(cancellationFailure, sharedLeaseFailure, suppressionAppender);
                Throwable selected = selectFailure(failure, cleanupFailure, suppressionAppender);
                rethrowIfFatal(selected);
                if (!(failure instanceof RuntimeException)) {
                    throw unchecked(failure);
                }
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                        modelKey,
                        generation,
                        null,
                        "X6 managed ownership was closed or already transferred before provider pinning"));
                addCancellationDiagnostic(diagnostics, modelKey, generation, cancellationFailure);
                addSharedLeaseDiagnostic(diagnostics, modelKey, generation, sharedLeaseFailure);
                return X6PlanResult.failure(ordered(diagnostics));
            }
        }
        final ProviderLease lease;
        try {
            // X1 makes the state check and increment atomic; this can still lose a retire race.
            lease = Objects.requireNonNull(providerPin.pin(providers), "providerPin returned null");
        } catch (Throwable exception) {
            X6LifecycleDrainBridge.DetachedLeases detached = lifecycleDrain == null
                    ? null
                    : lifecycleDrain.detachBeforePublication();
            Throwable cancellationFailure = detached == null ? null : detached.cancellationFailure();
            Throwable sharedLeaseFailure = closeSharedGenerationReceipt(sharedGenerationReceipt);
            Throwable cleanupFailure = selectFailure(cancellationFailure, sharedLeaseFailure, suppressionAppender);
            Throwable selected = selectFailure(exception, cleanupFailure, suppressionAppender);
            rethrowIfFatal(selected);
            if (!(exception instanceof RuntimeException)) {
                throw unchecked(exception);
            }
            diagnostics.add(X6Diagnostic.error(
                    X6DiagnosticCode.PROVIDER_FAILURE,
                    modelKey,
                    generation,
                    null,
                    "X6 could not pin the published material provider generation; it retired or closed before publication"));
            addCancellationDiagnostic(diagnostics, modelKey, generation, cancellationFailure);
            addSharedLeaseDiagnostic(diagnostics, modelKey, generation, sharedLeaseFailure);
            return X6PlanResult.failure(ordered(diagnostics));
        }
        boolean attachedToLifecycleDrain = false;
        try {
            if (lifecycleDrain != null) {
                if (!lifecycleDrain.attachLeases(lease, sharedGenerationReceipt)) {
                    throw new X6PreparationException(
                            X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                            "X6 lifecycle owner admission became invalid before the provider lease attached");
                }
                attachedToLifecycleDrain = true;
            }
            X6PreparedRenderPlan plan = planAssembler.assemble(new PostPinPlanInput(
                    variants,
                    layers,
                    materials,
                    geometry,
                    providers,
                    bindingSnapshot,
                    boundSkinnedMeshCount,
                    lifecycleDrain == null ? lease : null,
                    lifecycleDrain,
                    permitsFrozenCpuRoute));
            if (lifecycleDrain != null && !lifecycleDrain.publicationReady()) {
                throw new X6PreparationException(
                        X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                        "X6 lifecycle owner invoked its admitted bridge before successful-plan publication completed");
            }
            return X6PlanResult.success(plan, ordered(diagnostics));
        } catch (Throwable exception) {
            X6LifecycleDrainBridge.DetachedLeases detached = lifecycleDrain == null
                    ? null
                    : lifecycleDrain.detachBeforePublication();
            Throwable cancellationFailure = detached == null ? null : detached.cancellationFailure();
            ProviderLease leaseToRelease = lease;
            ClientGenerationLeaseBinding.PlanTransferReceipt receiptToClose = sharedGenerationReceipt;
            if (attachedToLifecycleDrain) {
                leaseToRelease = Objects.requireNonNull(
                        detached == null ? null : detached.providerLease(),
                        "attached X6 lifecycle bridge must detach its exact provider lease");
                receiptToClose = detached == null ? null : detached.sharedGenerationReceipt();
            }
            LeaseReleaseResult releaseResult = releaseAfterFailedPublication(
                    leaseToRelease, generation, leaseReleaseWorkerLauncher);
            Throwable sharedLeaseFailure = closeSharedGenerationReceipt(receiptToClose);
            Throwable cleanupFailure = selectFailure(cancellationFailure, sharedLeaseFailure, suppressionAppender);
            if (isFatal(exception)) {
                addSuppressedSafely(exception, cleanupFailure, suppressionAppender);
                addReleaseFailuresSafely(exception, releaseResult, suppressionAppender);
                rethrowIfFatal(exception);
            }
            if (isFatal(cleanupFailure)) {
                addSelectedCleanupContextSafely(cleanupFailure, exception, releaseResult, suppressionAppender);
                rethrowIfFatal(cleanupFailure);
            }
            Throwable fatalCleanupFailure = releaseResult.firstFatalFailure();
            if (fatalCleanupFailure != null) {
                addSelectedCleanupContextSafely(
                        fatalCleanupFailure, exception, releaseResult, suppressionAppender);
                addSuppressedSafely(fatalCleanupFailure, cancellationFailure, suppressionAppender);
                rethrowIfFatal(fatalCleanupFailure);
            }
            addReleaseFailuresSafely(exception, releaseResult, suppressionAppender);
            addSuppressedSafely(exception, cleanupFailure, suppressionAppender);
            if (exception instanceof X6PreparationException preparationException) {
                diagnostics.add(X6Diagnostic.error(
                        preparationException.code(),
                        modelKey,
                        generation,
                        null,
                        preparationException.getMessage()));
            } else if (exception instanceof RuntimeException) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.GEOMETRY_MISMATCH,
                        modelKey,
                        generation,
                        null,
                        "X6 render-plan publication could not retain its final frozen geometry/material bindings"));
            } else {
                throw unchecked(exception);
            }
            if (releaseResult.hasFailure()) {
                diagnostics.add(X6Diagnostic.error(
                        X6DiagnosticCode.PROVIDER_FAILURE,
                        modelKey,
                        generation,
                        null,
                        releaseResult.diagnosticMessage()));
            }
            addCancellationDiagnostic(diagnostics, modelKey, generation, cancellationFailure);
            addSharedLeaseDiagnostic(diagnostics, modelKey, generation, sharedLeaseFailure);
            return X6PlanResult.failure(ordered(diagnostics));
        }
    }

    /**
     * Runs the potentially terminal X1 release away from the preparation caller.
     *
     * <p>The caller still waits for completion because it cannot choose the required fatal-primary /
     * fatal-cleanup precedence before the release finishes. Interrupted callers keep waiting and
     * regain their interrupted status afterward. The worker catches every throwable, so a completed
     * call cannot leave an unobserved cleanup failure or a live worker behind.</p>
     */
    private static LeaseReleaseResult releaseAfterFailedPublication(
            ProviderLease lease,
            long generation,
            LeaseReleaseWorkerLauncher leaseReleaseWorkerLauncher) {
        LeaseReleaseController controller = new LeaseReleaseController(lease);
        LeaseReleaseWorker worker = null;
        Throwable launcherFailure = null;
        Throwable startFailure = null;
        Throwable awaitFailure = null;
        Throwable workerTerminalFailure = null;
        boolean workerStarted = false;
        try {
            worker = Objects.requireNonNull(
                    leaseReleaseWorkerLauncher.launch(controller, generation),
                    "leaseReleaseWorkerLauncher returned null");
        } catch (Throwable failure) {
            launcherFailure = failure;
        }
        if (launcherFailure == null) {
            try {
                worker.start();
                workerStarted = true;
            } catch (Throwable failure) {
                startFailure = failure;
            }
            if (workerStarted) {
                try {
                    workerTerminalFailure = worker.awaitCompletion();
                } catch (Throwable failure) {
                    awaitFailure = failure;
                }
            }
        }
        LeaseReleaseController.ReleaseAttempt fallbackAttempt = controller.releaseOnceWithAttempt();
        return new LeaseReleaseResult(
                launcherFailure,
                startFailure,
                awaitFailure,
                workerTerminalFailure,
                fallbackAttempt.failure(),
                fallbackAttempt.initiatedByCaller());
    }

    private static boolean isFatal(Throwable exception) {
        return exception instanceof Error;
    }

    /** Selects an exact Error before an ordinary primary, retaining the other identity as metadata. */
    private static Throwable selectFailure(
            Throwable primary, Throwable cleanup, SuppressionAppender suppressionAppender) {
        if (primary == null) {
            return cleanup;
        }
        if (cleanup == null || cleanup == primary) {
            return primary;
        }
        Throwable selected = primary instanceof Error || !(cleanup instanceof Error) ? primary : cleanup;
        addSuppressedSafely(selected, selected == primary ? cleanup : primary, suppressionAppender);
        return selected;
    }

    private static void addCancellationDiagnostic(
            List<X6Diagnostic> diagnostics, BlendModelKey modelKey, long generation, Throwable cancellationFailure) {
        if (cancellationFailure == null) {
            return;
        }
        diagnostics.add(X6Diagnostic.error(
                X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                modelKey,
                generation,
                null,
                "X6 lifecycle owner admission cancellation failed after unsuccessful publication; no successful plan was returned"));
    }

    private static void addSharedLeaseDiagnostic(
            List<X6Diagnostic> diagnostics, BlendModelKey modelKey, long generation, Throwable sharedLeaseFailure) {
        if (sharedLeaseFailure == null) {
            return;
        }
        diagnostics.add(X6Diagnostic.error(
                X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                modelKey,
                generation,
                null,
                "X6 could not release its accepted shared registry-generation lease after unsuccessful publication"));
    }

    private static Throwable closeSharedGenerationReceipt(
            ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceipt) {
        if (sharedGenerationReceipt == null) {
            return null;
        }
        try {
            sharedGenerationReceipt.close();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void addReleaseFailuresSafely(
            Throwable primary,
            LeaseReleaseResult releaseResult,
            SuppressionAppender suppressionAppender) {
        for (Throwable failure : releaseResult.distinctFailures()) {
            addSuppressedSafely(primary, failure, suppressionAppender);
        }
    }

    private static void addSelectedCleanupContextSafely(
            Throwable selectedCleanup,
            Throwable primary,
            LeaseReleaseResult releaseResult,
            SuppressionAppender suppressionAppender) {
        addSuppressedSafely(selectedCleanup, primary, suppressionAppender);
        for (Throwable failure : releaseResult.distinctFailures()) {
            if (failure != selectedCleanup) {
                addSuppressedSafely(selectedCleanup, failure, suppressionAppender);
            }
        }
    }

    /**
     * Adds best-effort metadata without allowing ordinary bookkeeping failures to replace the
     * selected throwable. Errors thrown by the appender are never swallowed and retain identity.
     */
    static void addSuppressedSafely(
            Throwable primary,
            Throwable secondary,
            SuppressionAppender suppressionAppender) {
        suppressionAppender = Objects.requireNonNull(suppressionAppender, "suppressionAppender");
        if (primary == null || secondary == null || primary == secondary) {
            return;
        }
        try {
            suppressionAppender.add(primary, secondary);
        } catch (Throwable suppressionFailure) {
            rethrowIfFatal(suppressionFailure);
            // Ordinary suppression bookkeeping is best effort and cannot replace the selection.
        }
    }

    private static RuntimeException unchecked(Throwable exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (exception instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Unexpected checked failure at the X6 plan-publication boundary", exception);
    }

    private static boolean hasError(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR);
    }

    private static void rethrowIfFatal(Throwable exception) {
        if (exception instanceof Error error) {
            throw error;
        }
    }

    private static List<X6Diagnostic> ordered(List<X6Diagnostic> diagnostics) {
        return diagnostics.stream()
                .sorted(java.util.Comparator.comparing((X6Diagnostic value) -> value.code().code())
                        .thenComparing(value -> value.subjectId().map(id -> id.value()).orElse(""))
                        .thenComparing(X6Diagnostic::message))
                .toList();
    }

    @FunctionalInterface
    interface ProviderPin {
        ProviderLease pin(X6MaterialProviderGeneration providers);
    }

    @FunctionalInterface
    interface PostPinPlanAssembler {
        X6PreparedRenderPlan assemble(PostPinPlanInput input);
    }

    @FunctionalInterface
    interface LeaseReleaseWorkerLauncher {
        LeaseReleaseWorker launch(LeaseReleaseController controller, long generation);
    }

    interface LeaseReleaseWorker {
        void start();

        /**
         * Returns the started worker's terminal controller-release failure, or {@code null} after
         * that worker completed a successful release. A caller that returns early without taking
         * ownership is contained by the factory's exact-once fallback.
         */
        Throwable awaitCompletion();
    }

    @FunctionalInterface
    interface SuppressionAppender {
        void add(Throwable primary, Throwable secondary);
    }

    record PostPinPlanInput(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            X6MaterialProviderGeneration providers,
            ModelRenderSnapshot bindingSnapshot,
            int boundSkinnedMeshCount,
            ProviderLease lease,
            X6LifecycleDrainBridge lifecycleDrain,
            boolean permitsFrozenCpuRoute) {
        PostPinPlanInput {
            variants = Objects.requireNonNull(variants, "variants");
            layers = Objects.requireNonNull(layers, "layers");
            materials = Objects.requireNonNull(materials, "materials");
            geometry = Objects.requireNonNull(geometry, "geometry");
            providers = Objects.requireNonNull(providers, "providers");
            bindingSnapshot = Objects.requireNonNull(bindingSnapshot, "bindingSnapshot");
            if (lifecycleDrain == null) {
                lease = Objects.requireNonNull(lease, "lease");
            } else if (lease != null) {
                throw new IllegalArgumentException("public lifecycle-owner plan assembly must not expose its raw provider lease");
            }
        }
    }

    private record LeaseReleaseResult(
            Throwable launcherFailure,
            Throwable startFailure,
            Throwable awaitFailure,
            Throwable workerTerminalFailure,
            Throwable terminalCloseFailure,
            boolean fallbackCloseAttempted) {
        private boolean hasFailure() {
            return launcherFailure != null
                    || startFailure != null
                    || awaitFailure != null
                    || workerTerminalFailure != null
                    || terminalCloseFailure != null;
        }

        private Throwable firstFatalFailure() {
            if (isFatal(launcherFailure)) {
                return launcherFailure;
            }
            if (isFatal(startFailure)) {
                return startFailure;
            }
            if (isFatal(awaitFailure)) {
                return awaitFailure;
            }
            if (isFatal(workerTerminalFailure)) {
                return workerTerminalFailure;
            }
            if (isFatal(terminalCloseFailure)) {
                return terminalCloseFailure;
            }
            return null;
        }

        private List<Throwable> distinctFailures() {
            List<Throwable> failures = new ArrayList<>(5);
            addDistinct(failures, launcherFailure);
            addDistinct(failures, startFailure);
            addDistinct(failures, awaitFailure);
            addDistinct(failures, workerTerminalFailure);
            addDistinct(failures, terminalCloseFailure);
            return failures;
        }

        private static void addDistinct(List<Throwable> failures, Throwable candidate) {
            if (candidate == null || failures.stream().anyMatch(existing -> existing == candidate)) {
                return;
            }
            failures.add(candidate);
        }

        private String diagnosticMessage() {
            if (workerTerminalFailure != null && !fallbackCloseAttempted) {
                return "X6 started the controlled provider-generation release worker, but its terminal lease close failed "
                        + "after render-plan publication failed; the generation may remain pinned";
            }
            if (awaitFailure != null) {
                return workerFailureDiagnostic("X6 could not await the started controlled provider-generation release worker "
                        + "after render-plan publication failed");
            }
            if (startFailure != null) {
                return workerFailureDiagnostic("X6 could not start the controlled provider-generation release worker "
                        + "after render-plan publication failed");
            }
            if (launcherFailure != null) {
                return workerFailureDiagnostic("X6 could not launch the controlled provider-generation release worker "
                        + "after render-plan publication failed");
            }
            if (fallbackCloseAttempted && terminalCloseFailure != null) {
                return "X6 could not synchronously fallback-close the provider-generation lease after render-plan publication failed; "
                        + "the generation may remain pinned";
            }
            return "X6 could not release the provider-generation lease after render-plan publication failed; "
                    + "the generation may remain pinned";
        }

        private String workerFailureDiagnostic(String prefix) {
            if (fallbackCloseAttempted) {
                return terminalCloseFailure == null
                        ? prefix + "; the lifecycle owner synchronously released the lease"
                        : prefix + " and the synchronous fallback lease close failed; the generation may remain pinned";
            }
            return terminalCloseFailure == null
                    ? prefix + "; the release controller had already released the lease"
                    : prefix + " after the release controller had already reached a terminal close failure; "
                            + "the generation may remain pinned";
        }
    }

    /** Internal exact-once owner for failed-publication release; it never exposes the raw lease. */
    static final class LeaseReleaseController {
        private enum ReleaseState {
            OPEN,
            RELEASING,
            TERMINAL
        }

        private final ProviderLease lease;
        private final AtomicReference<ReleaseState> state = new AtomicReference<>(ReleaseState.OPEN);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private volatile Throwable cleanupFailure;

        private LeaseReleaseController(ProviderLease lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
        }

        Throwable releaseOnce() {
            return releaseOnceWithAttempt().failure();
        }

        ReleaseAttempt releaseOnceWithAttempt() {
            if (state.compareAndSet(ReleaseState.OPEN, ReleaseState.RELEASING)) {
                Throwable observedFailure = null;
                try {
                    lease.close();
                } catch (Throwable failure) {
                    observedFailure = failure;
                }
                cleanupFailure = observedFailure;
                state.set(ReleaseState.TERMINAL);
                terminal.countDown();
                return new ReleaseAttempt(true, observedFailure);
            }
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    terminal.await();
                    break;
                } catch (InterruptedException ignored) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return new ReleaseAttempt(false, cleanupFailure);
        }

        private record ReleaseAttempt(boolean initiatedByCaller, Throwable failure) {
        }
    }

    private static final class PlatformLeaseReleaseWorker implements LeaseReleaseWorker {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private PlatformLeaseReleaseWorker(LeaseReleaseController controller, long generation) {
            thread = Thread.ofPlatform()
                    .name("blendlib-x6-lease-release-g" + generation)
                    .inheritInheritableThreadLocals(false)
                    .unstarted(() -> {
                        try {
                            Throwable cleanupFailure = controller.releaseOnce();
                            if (cleanupFailure != null) {
                                failure.compareAndSet(null, cleanupFailure);
                            }
                        } catch (Throwable cleanupFailure) {
                            failure.compareAndSet(null, cleanupFailure);
                        }
                    });
        }

        @Override
        public void start() {
            thread.start();
        }

        @Override
        public Throwable awaitCompletion() {
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException ignored) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return failure.get();
        }
    }
}
