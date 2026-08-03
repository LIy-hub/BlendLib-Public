package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Generation-scoped executor for frozen selected providers.
 *
 * <p>This class implements {@code freeze -> prepare -> apply -> publish -> retire -> close}.
 * Selection and provider metadata are taken only from the registry's frozen snapshots; every
 * transition revalidates the complete registry-produced plan before any callback. Session construction
 * performs no provider metadata or lifecycle callback. Provider callbacks run outside the session
 * monitor and commit through an epoch-checked transition, so reentrant or racing lifecycle mutation
 * cannot overwrite the active state. Provider instances use shared identity ownership so overlapping
 * generations and adapter control cannot close an active provider.</p>
 */
@ExperimentalBlendLibSpi
public final class ProviderLifecycleSession implements AutoCloseable {
    private static final long RETIREMENT_OBSERVER_TIMEOUT_SECONDS = 30L;
    private final long generation;
    private final CapabilityPlan plan;
    private final List<OwnedProvider> selectedProviders;
    private final List<CapabilityDiagnostic> diagnostics = new ArrayList<>();
    private final List<CapabilityDiagnostic> retirementDiagnostics = new ArrayList<>();
    private ProviderLifecycleState state = ProviderLifecycleState.FROZEN;
    private boolean retired;
    private boolean retireInvoked;
    private boolean closeInvoked;
    private int pins;
    private boolean transitionActive;
    private ProviderLifecycleStage activeStage;
    private long transitionEpoch;
    private RetirementCompletion retirementCompletion;

    /**
     * Creates a generation session from one registry-produced publishable plan.
     *
     * @param plan frozen publishable capability plan
     * @param providers exact provider instances registered in the plan's frozen snapshot
     * @throws CapabilityNegotiationException if a selected provider is absent or terminally closed
     */
    public ProviderLifecycleSession(CapabilityPlan plan, Collection<? extends BlendProvider> providers) {
        this.plan = Objects.requireNonNull(plan, "plan");
        plan.validateForLifecycle();
        this.generation = plan.generation();
        plan.requirePublishable();
        Objects.requireNonNull(providers, "providers");

        IdentityHashMap<BlendProvider, Boolean> suppliedProviders = new IdentityHashMap<>();
        for (BlendProvider provider : providers) {
            provider = Objects.requireNonNull(provider, "providers contains null");
            if (suppliedProviders.put(provider, Boolean.TRUE) != null) {
                throw failure(CapabilityDiagnostic.unscoped(
                        CapabilityErrorCode.DUPLICATE_PROVIDER_ID,
                        BlendDiagnosticSeverity.ERROR,
                        "The same provider instance occurs more than once in the lifecycle session"));
            }
        }
        List<BlendProvider> frozenProviderInstances = plan.frozenProviderInstances();
        if (suppliedProviders.size() != frozenProviderInstances.size()
                || frozenProviderInstances.stream().anyMatch(provider -> !suppliedProviders.containsKey(provider))) {
            throw failure(CapabilityDiagnostic.unscoped(
                    CapabilityErrorCode.PLAN_PROVIDER_MISSING,
                    BlendDiagnosticSeverity.ERROR,
                    "Lifecycle providers must exactly match the frozen registry provider-object snapshot"));
        }

        List<BlendProvider> providersToOwn = new ArrayList<>();
        List<BlendResourceId> providerIds = new ArrayList<>();
        IdentityHashMap<BlendProvider, Boolean> selectedIdentities = new IdentityHashMap<>();
        for (CapabilityPlan.ProviderBinding binding : plan.selectedProviderBindings()) {
            if (!suppliedProviders.containsKey(binding.provider())) {
                throw failure(CapabilityDiagnostic.provider(
                        CapabilityErrorCode.PLAN_PROVIDER_MISSING,
                        BlendDiagnosticSeverity.ERROR,
                        binding.providerId(),
                        "Frozen plan provider instance is absent from the lifecycle session"));
            }
            if (selectedIdentities.put(binding.provider(), Boolean.TRUE) == null) {
                providersToOwn.add(binding.provider());
                providerIds.add(binding.providerId());
            }
        }

        List<ProviderOwnership.Handle> ownership;
        try {
            ownership = ProviderOwnership.acquireAll(providersToOwn);
        } catch (ProviderOwnership.OwnershipConflictException exception) {
            BlendResourceId providerId = providerIdFor(exception.provider(), providersToOwn, providerIds);
            throw failure(CapabilityDiagnostic.provider(
                    CapabilityErrorCode.PROVIDER_OWNERSHIP_CONFLICT,
                    BlendDiagnosticSeverity.ERROR,
                    providerId,
                    "Provider ownership is unavailable for the selected provider identity"));
        }

        List<OwnedProvider> owned = new ArrayList<>(providersToOwn.size());
        for (int index = 0; index < providersToOwn.size(); index++) {
            owned.add(new OwnedProvider(providerIds.get(index), providersToOwn.get(index), ownership.get(index)));
        }
        selectedProviders = List.copyOf(owned);
    }

    /**
     * Returns the explicit resource-generation scope of this session.
     *
     * @return non-negative generation number
     */
    public long generation() {
        return generation;
    }

    /**
     * Returns the immutable frozen plan selected before this session began.
     *
     * @return frozen immutable plan
     */
    public CapabilityPlan plan() {
        return plan;
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return current session state
     */
    public synchronized ProviderLifecycleState state() {
        return state;
    }

    /**
     * Returns a snapshot of all isolated session diagnostics recorded so far.
     *
     * @return immutable diagnostic snapshot
     */
    public synchronized List<CapabilityDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /**
     * Returns selected provider ids in deterministic lifecycle order.
     *
     * @return immutable provider id order
     */
    public List<BlendResourceId> selectedProviderIds() {
        return selectedProviders.stream().map(OwnedProvider::providerId).toList();
    }

    /**
     * Runs selected-provider preparation for this frozen generation.
     *
     * @return isolated preparation result
     */
    public ProviderLifecycleResult prepare() {
        long epoch = beginTransition(ProviderLifecycleState.FROZEN, ProviderLifecycleStage.PREPARE);
        return invoke(epoch, ProviderLifecycleStage.PREPARE, ProviderLifecycleState.PREPARED, BlendProvider::prepare,
                CapabilityErrorCode.PROVIDER_PREPARE_FAILURE);
    }

    /**
     * Runs selected-provider adapter application after successful preparation.
     *
     * @return isolated application result
     */
    public ProviderLifecycleResult apply() {
        long epoch = beginTransition(ProviderLifecycleState.PREPARED, ProviderLifecycleStage.APPLY);
        return invoke(epoch, ProviderLifecycleStage.APPLY, ProviderLifecycleState.APPLIED, BlendProvider::apply,
                CapabilityErrorCode.PROVIDER_APPLY_FAILURE);
    }

    /** Marks the fully applied immutable generation as publishable and pin-capable. */
    public synchronized void publish() {
        rejectMutationDuringCallback(ProviderLifecycleStage.PUBLISH);
        requireNoTransition(ProviderLifecycleStage.PUBLISH);
        validatePlan();
        requireState(ProviderLifecycleState.APPLIED, ProviderLifecycleStage.PUBLISH);
        state = ProviderLifecycleState.PUBLISHED;
    }

    /**
     * Pins the published generation for one immutable snapshot consumer.
     *
     * @return independently closable generation lease
     */
    public synchronized ProviderLease pin() {
        rejectMutationDuringCallback(ProviderLifecycleStage.PUBLISH);
        requireNoTransition(ProviderLifecycleStage.PUBLISH);
        validatePlan();
        requireState(ProviderLifecycleState.PUBLISHED, ProviderLifecycleStage.PUBLISH);
        pins++;
        return new Lease(generation);
    }

    /**
     * Requests retirement, then invokes generation retire callbacks and releases provider ownership
     * only after all issued pins drain.
     *
     * <p>The returned diagnostics contain every retire and provider-close failure observed so far.
     * A provider shared by another active generation or adapter control remains open.</p>
     *
     * @return cumulative isolated retirement/close result
     */
    public ProviderLifecycleResult retire() {
        long epoch;
        RetirementCompletion observedCompletion = null;
        synchronized (this) {
            rejectMutationDuringCallback(ProviderLifecycleStage.RETIRE);
            validatePlan();
            if (transitionActive) {
                if (retired && (activeStage == ProviderLifecycleStage.RETIRE
                        || activeStage == ProviderLifecycleStage.CLOSE)) {
                    observedCompletion = retirementCompletion;
                } else {
                    throw transitionFailure(ProviderLifecycleStage.RETIRE);
                }
            } else if (!retired) {
                retired = true;
                if (state != ProviderLifecycleState.CLOSED) {
                    state = ProviderLifecycleState.RETIRING;
                }
            }
            if (observedCompletion == null) {
                epoch = beginRetirementIfDrained();
                if (epoch < 0L) {
                    return retirementResult(ProviderLifecycleStage.RETIRE);
                }
            } else {
                epoch = -1L;
            }
        }
        if (observedCompletion != null) {
            return awaitRetirement(observedCompletion);
        }
        return executeRetirement(epoch);
    }

    /** Requests retirement and eventual shared-ownership release. This method is idempotent. */
    @Override
    public void close() {
        retire();
    }

    private ProviderLifecycleResult invoke(
            long epoch,
            ProviderLifecycleStage stage,
            ProviderLifecycleState successState,
            ProviderCallback callback,
            CapabilityErrorCode failureCode) {
        List<CapabilityDiagnostic> transitionDiagnostics = new ArrayList<>();
        ProviderLifecycleContext context = new ProviderLifecycleContext(generation, plan, stage);
        boolean completed = false;
        try {
            for (OwnedProvider owned : selectedProviders) {
                try {
                    ExperimentalControlBoundary.runExternal(() -> callback.invoke(owned.provider(), context));
                } catch (Throwable exception) {
                    CapabilityDiagnostic diagnostic = providerFailure(failureCode, owned.providerId(), stage, exception);
                    transitionDiagnostics.add(diagnostic);
                    if (ExperimentalControlBoundary.isFatal(exception)) {
                        terminateAfterFatalTransition(epoch, stage, transitionDiagnostics);
                        ExperimentalControlBoundary.rethrowIfFatal(exception);
                    }
                    ProviderLifecycleResult result = completeTransitionFailure(
                            epoch, stage, transitionDiagnostics);
                    completed = true;
                    return result;
                }
                verifyTransition(epoch, stage);
            }
            ProviderLifecycleResult result = completeTransitionSuccess(
                    epoch, stage, successState, transitionDiagnostics);
            completed = true;
            return result;
        } finally {
            if (!completed) {
                abandonTransition(epoch, stage);
            }
        }
    }

    private RetireInvocation invokeRetire(long epoch) {
        List<CapabilityDiagnostic> transitionDiagnostics = new ArrayList<>();
        Throwable fatalFailure = null;
        ProviderLifecycleContext context = new ProviderLifecycleContext(generation, plan, ProviderLifecycleStage.RETIRE);
        for (OwnedProvider owned : selectedProviders) {
            try {
                ExperimentalControlBoundary.runExternal(() -> owned.provider().retire(context));
            } catch (Throwable exception) {
                CapabilityDiagnostic diagnostic = providerFailure(CapabilityErrorCode.PROVIDER_RETIRE_FAILURE,
                        owned.providerId(), ProviderLifecycleStage.RETIRE, exception);
                transitionDiagnostics.add(diagnostic);
                if (ExperimentalControlBoundary.isFatal(exception)) {
                    fatalFailure = exception;
                    break;
                }
            }
            verifyTransition(epoch, ProviderLifecycleStage.RETIRE);
        }
        return new RetireInvocation(transitionDiagnostics, fatalFailure);
    }

    private synchronized ProviderLifecycleResult retirementResult(ProviderLifecycleStage stage) {
        boolean successful = retirementDiagnostics.stream()
                .noneMatch(value -> value.severity() == BlendDiagnosticSeverity.ERROR);
        return new ProviderLifecycleResult(stage, successful, retirementDiagnostics);
    }

    private void release(Lease lease) {
        long epoch;
        synchronized (this) {
            rejectMutationDuringCallback(ProviderLifecycleStage.CLOSE);
            if (lease.closed) {
                return;
            }
            requireNoTransition(ProviderLifecycleStage.CLOSE);
            validatePlan();
            lease.closed = true;
            pins--;
            if (pins < 0) {
                throw new IllegalStateException("Provider lease pin count underflow");
            }
            epoch = beginRetirementIfDrained();
        }
        if (epoch >= 0L) {
            executeRetirement(epoch);
        }
    }

    private synchronized long beginTransition(
            ProviderLifecycleState expected,
            ProviderLifecycleStage stage) {
        rejectMutationDuringCallback(stage);
        requireNoTransition(stage);
        validatePlan();
        requireState(expected, stage);
        transitionActive = true;
        activeStage = stage;
        return ++transitionEpoch;
    }

    private synchronized long beginRetirementIfDrained() {
        if (!retired || pins > 0 || closeInvoked) {
            return -1L;
        }
        transitionActive = true;
        activeStage = ProviderLifecycleStage.RETIRE;
        retireInvoked = true;
        retirementCompletion = new RetirementCompletion();
        return ++transitionEpoch;
    }

    private ProviderLifecycleResult executeRetirement(long epoch) {
        List<CapabilityDiagnostic> failures = new ArrayList<>();
        Throwable fatalFailure = null;
        boolean completed = false;
        try {
            RetireInvocation invocation = invokeRetire(epoch);
            failures.addAll(invocation.diagnostics());
            fatalFailure = invocation.fatalFailure();
            synchronized (this) {
                verifyTransitionState(epoch, ProviderLifecycleStage.RETIRE);
                activeStage = ProviderLifecycleStage.CLOSE;
                closeInvoked = true;
            }
            for (OwnedProvider owned : selectedProviders) {
                Throwable exception = owned.ownership().release();
                if (exception != null) {
                    failures.add(providerFailure(CapabilityErrorCode.PROVIDER_CLOSE_FAILURE,
                            owned.providerId(), ProviderLifecycleStage.CLOSE, exception));
                    if (fatalFailure == null && ExperimentalControlBoundary.isFatal(exception)) {
                        fatalFailure = exception;
                    }
                }
                verifyTransition(epoch, ProviderLifecycleStage.CLOSE);
            }
            ProviderLifecycleResult result;
            synchronized (this) {
                verifyTransitionState(epoch, ProviderLifecycleStage.CLOSE);
                retirementDiagnostics.addAll(failures);
                diagnostics.addAll(failures);
                state = ProviderLifecycleState.CLOSED;
                clearTransition();
                result = retirementResult(ProviderLifecycleStage.CLOSE);
            }
            completed = true;
            retirementCompletion.complete(result, fatalFailure);
            if (fatalFailure != null) {
                ExperimentalControlBoundary.rethrowIfFatal(fatalFailure);
            }
            return result;
        } finally {
            if (!completed) {
                emergencyClose(epoch, failures);
                ProviderLifecycleResult result;
                synchronized (this) {
                    result = retirementResult(ProviderLifecycleStage.CLOSE);
                }
                retirementCompletion.complete(result, null);
            }
        }
    }

    private ProviderLifecycleResult awaitRetirement(RetirementCompletion completion) {
        boolean completed;
        try {
            completed = completion.await(RETIREMENT_OBSERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw retirementObserverFailure("Interrupted while waiting for active retirement completion");
        }
        if (!completed) {
            throw retirementObserverFailure("Timed out waiting for active retirement completion");
        }
        Throwable fatalFailure = completion.fatalFailure();
        if (fatalFailure != null) {
            ExperimentalControlBoundary.rethrowIfFatal(fatalFailure);
        }
        return completion.result();
    }

    private void terminateAfterFatalTransition(
            long epoch,
            ProviderLifecycleStage stage,
            List<CapabilityDiagnostic> failures) {
        synchronized (this) {
            verifyTransitionState(epoch, stage);
            diagnostics.addAll(failures);
            retirementDiagnostics.addAll(failures);
            retired = true;
            retireInvoked = true;
            closeInvoked = true;
            state = ProviderLifecycleState.CLOSED;
            clearTransition();
        }
        List<CapabilityDiagnostic> closeFailures = releaseAllOwnership();
        synchronized (this) {
            retirementDiagnostics.addAll(closeFailures);
            diagnostics.addAll(closeFailures);
        }
    }

    private void emergencyClose(long epoch, List<CapabilityDiagnostic> knownFailures) {
        List<CapabilityDiagnostic> closeFailures = releaseAllOwnership();
        synchronized (this) {
            if (!transitionActive || transitionEpoch != epoch) {
                return;
            }
            List<CapabilityDiagnostic> retained = new ArrayList<>(knownFailures);
            retained.addAll(closeFailures);
            retirementDiagnostics.addAll(retained);
            diagnostics.addAll(retained);
            retired = true;
            retireInvoked = true;
            closeInvoked = true;
            state = ProviderLifecycleState.CLOSED;
            clearTransition();
        }
    }

    private List<CapabilityDiagnostic> releaseAllOwnership() {
        List<CapabilityDiagnostic> closeFailures = new ArrayList<>();
        for (OwnedProvider owned : selectedProviders) {
            Throwable exception = owned.ownership().release();
            if (exception != null) {
                closeFailures.add(providerFailure(CapabilityErrorCode.PROVIDER_CLOSE_FAILURE,
                        owned.providerId(), ProviderLifecycleStage.CLOSE, exception));
            }
        }
        return closeFailures;
    }

    private synchronized void abandonTransition(long epoch, ProviderLifecycleStage stage) {
        if (transitionActive && transitionEpoch == epoch && activeStage == stage) {
            state = ProviderLifecycleState.FAILED;
            clearTransition();
        }
    }

    private synchronized ProviderLifecycleResult completeTransitionFailure(
            long epoch,
            ProviderLifecycleStage stage,
            List<CapabilityDiagnostic> failures) {
        verifyTransitionState(epoch, stage);
        diagnostics.addAll(failures);
        state = ProviderLifecycleState.FAILED;
        clearTransition();
        return new ProviderLifecycleResult(stage, false, failures);
    }

    private synchronized ProviderLifecycleResult completeTransitionSuccess(
            long epoch,
            ProviderLifecycleStage stage,
            ProviderLifecycleState successState,
            List<CapabilityDiagnostic> transitionDiagnostics) {
        verifyTransitionState(epoch, stage);
        validatePlan();
        state = successState;
        clearTransition();
        return new ProviderLifecycleResult(stage, true, transitionDiagnostics);
    }

    private synchronized void verifyTransition(long epoch, ProviderLifecycleStage stage) {
        verifyTransitionState(epoch, stage);
        validatePlan();
    }

    private void verifyTransitionState(long epoch, ProviderLifecycleStage stage) {
        if (!transitionActive || transitionEpoch != epoch || activeStage != stage) {
            throw transitionFailure(stage);
        }
    }

    private void clearTransition() {
        transitionActive = false;
        activeStage = null;
    }

    private void requireNoTransition(ProviderLifecycleStage stage) {
        if (transitionActive) {
            throw transitionFailure(stage);
        }
    }

    private void rejectMutationDuringCallback(ProviderLifecycleStage stage) {
        if (ExperimentalControlBoundary.inExternalCallback()) {
            throw transitionFailure(stage);
        }
    }

    private CapabilityNegotiationException transitionFailure(ProviderLifecycleStage stage) {
        return failure(CapabilityDiagnostic.unscoped(
                CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                BlendDiagnosticSeverity.ERROR,
                "Lifecycle mutation is unavailable during an active provider callback or transition at " + stage));
    }

    private CapabilityNegotiationException retirementObserverFailure(String message) {
        return failure(CapabilityDiagnostic.unscoped(
                CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                BlendDiagnosticSeverity.ERROR,
                message));
    }

    private void validatePlan() {
        plan.validateForLifecycle();
    }

    private void requireState(ProviderLifecycleState expected, ProviderLifecycleStage stage) {
        if (state != expected) {
            throw failure(CapabilityDiagnostic.unscoped(
                    CapabilityErrorCode.INVALID_LIFECYCLE_STATE,
                    BlendDiagnosticSeverity.ERROR,
                    stage + " requires state " + expected + " but current state is " + state));
        }
    }

    private static CapabilityDiagnostic providerFailure(
            CapabilityErrorCode code,
            BlendResourceId providerId,
            ProviderLifecycleStage stage,
            Throwable exception) {
        return CapabilityDiagnostic.provider(
                code,
                BlendDiagnosticSeverity.ERROR,
                providerId,
                "Provider " + providerId + " failed during " + stage + ": "
                        + CapabilityDiagnostic.causeSummary(exception));
    }

    private static BlendResourceId providerIdFor(
            BlendProvider provider,
            List<BlendProvider> providers,
            List<BlendResourceId> providerIds) {
        for (int index = 0; index < providers.size(); index++) {
            if (providers.get(index) == provider) {
                return providerIds.get(index);
            }
        }
        throw new IllegalStateException("Ownership conflict did not identify a selected provider");
    }

    private static CapabilityNegotiationException failure(CapabilityDiagnostic diagnostic) {
        return new CapabilityNegotiationException(diagnostic);
    }

    @FunctionalInterface
    private interface ProviderCallback {
        void invoke(BlendProvider provider, ProviderLifecycleContext context);
    }

    private record OwnedProvider(
            BlendResourceId providerId,
            BlendProvider provider,
            ProviderOwnership.Handle ownership) {
        private OwnedProvider {
            providerId = Objects.requireNonNull(providerId, "providerId");
            provider = Objects.requireNonNull(provider, "provider");
            ownership = Objects.requireNonNull(ownership, "ownership");
        }
    }

    private record RetireInvocation(
            List<CapabilityDiagnostic> diagnostics,
            Throwable fatalFailure) {
        private RetireInvocation {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static final class RetirementCompletion {
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile ProviderLifecycleResult result;
        private volatile Throwable fatalFailure;

        private void complete(ProviderLifecycleResult result, Throwable fatalFailure) {
            this.result = Objects.requireNonNull(result, "result");
            this.fatalFailure = fatalFailure;
            completed.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completed.await(timeout, unit);
        }

        private ProviderLifecycleResult result() {
            return Objects.requireNonNull(result, "result");
        }

        private Throwable fatalFailure() {
            return fatalFailure;
        }
    }

    private final class Lease implements ProviderLease {
        private final long leaseGeneration;
        private boolean closed;

        private Lease(long leaseGeneration) {
            this.leaseGeneration = leaseGeneration;
        }

        @Override
        public long generation() {
            return leaseGeneration;
        }

        @Override
        public boolean isClosed() {
            synchronized (ProviderLifecycleSession.this) {
                return closed;
            }
        }

        @Override
        public void close() {
            release(this);
        }
    }
}
