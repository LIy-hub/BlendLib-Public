package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/** Shared implementation of the frozen, real-generation-pinned X4 host lifecycle. */
final class DefaultX4HostAdapter<F extends X4HostFrame>
        implements X4HostAdapter<F>, X4HostRegistrationLifecycle<F> {
    private enum TerminalEpochPhase {
        NONE, OPEN, SEALING, SEALED
    }

    private final Object monitor = new Object();
    private final X4HostSpec<F> specification;
    private final ClientModelLookup models;
    private final BlendRenderer renderer;
    private final ProviderLifecycleSession generationSession;
    private final IdentityHashMap<X4PreparedSnapshot, Boolean> activeSnapshots = new IdentityHashMap<>();
    private final CompletableFuture<X4HostLifecycleState> drainCompletion = new CompletableFuture<>();

    private X4HostLifecycleState state = X4HostLifecycleState.CONFIGURING;
    private RegistrationReservation activeRegistrationReservation;
    private X4HostRegistrationMembership registrationMembership;
    private TerminalTransition terminalTransition;
    private int inFlightSubmissions;
    // Existing submit holds, linked only while their renderer source is active. This supports
    // exact renderer-callback reentry without a global unresolved-terminal-H slot.
    private X4PreparedSnapshot.SubmissionHold activeSubmissionHead;
    private int pendingPinReleases;
    private long nextSubmissionEpoch;
    private TerminalEpochPhase terminalEpochPhase = TerminalEpochPhase.NONE;
    private long terminalEpochId;
    private boolean terminalCloseRequested;
    private boolean epochPhysicalResolved;
    // A constructed-T failure is an exact pre-seal D source before it can acquire the contribution
    // gate. This blocks a second T and sealing without allocating a fallback reservation.
    private Throwable pendingInitialPhysicalAllocationFailure;
    private Thread pendingInitialPhysicalAllocationOwnerThread;
    private boolean pendingInitialPhysicalAllocationInterruptReceipt;
    private Throwable epochRendererFailure;
    private Throwable epochProviderFailure;
    private Throwable epochMembershipFailure;
    private Throwable epochBookkeepingFailure;
    private Object terminalContributionOwner;
    private Thread terminalContributionOwnerThread;
    private long nextTerminalContributionSequence;
    private int pendingTerminalSubmissionCommits;
    // Existing snapshot chain for exact provider-callback owner lookup only.
    private X4PreparedSnapshot releaseInProgressHead;
    private int releaseInProgressCount;
    private boolean drainResultClaimed;
    private boolean drainResultPending;
    private X4HostLifecycleState pendingDrainState;
    private Throwable pendingDrainFailure;
    // Assigned only by the SEALED commit; null is a successful sealed result.
    private Throwable terminalFailure;

    private Error postSealTerminalTransitionAllocationFailureForTesting;
    private Error preSealTerminalEpochAllocationFailureForTesting;
    private Runnable directReleasePrePendingPinDecrementActionForTesting;
    private Runnable terminalTransitionPreMembershipRevokeActionForTesting;
    private Runnable terminalContributionBeforeCommitActionForTesting;
    private volatile Runnable terminalContributionAcceptedActionForTesting;
    private Runnable terminalPhysicalOutcomeBeforePublicationActionForTesting;
    private Runnable initialPhysicalAllocationFailureAfterPendingActionForTesting;
    private volatile Runnable afterProviderLeaseCloseActionForTesting;

    DefaultX4HostAdapter(
            X4HostSpec<F> specification,
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession) {
        this.specification = Objects.requireNonNull(specification, "specification");
        this.models = Objects.requireNonNull(models, "models");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.generationSession = Objects.requireNonNull(generationSession, "generationSession");
    }

    void failNextPostSealTerminalTransitionAllocationForTesting(Error failure) {
        synchronized (monitor) {
            if (postSealTerminalTransitionAllocationFailureForTesting != null) {
                throw new IllegalStateException("X4 post-seal terminal allocation probe is already armed");
            }
            postSealTerminalTransitionAllocationFailureForTesting = Objects.requireNonNull(failure, "failure");
        }
    }

    void failNextPreSealTerminalEpochAllocationForTesting(Error failure) {
        synchronized (monitor) {
            if (preSealTerminalEpochAllocationFailureForTesting != null) {
                throw new IllegalStateException("X4 pre-seal terminal allocation probe is already armed");
            }
            preSealTerminalEpochAllocationFailureForTesting = Objects.requireNonNull(failure, "failure");
        }
    }

    void runBeforeDirectReleasePendingPinDecrementForTesting(Runnable action) {
        synchronized (monitor) {
            if (directReleasePrePendingPinDecrementActionForTesting != null) {
                throw new IllegalStateException("X4 direct release pre-decrement probe is already armed");
            }
            directReleasePrePendingPinDecrementActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    void runBeforeTerminalTransitionMembershipRevokeForTesting(Runnable action) {
        synchronized (monitor) {
            if (terminalTransitionPreMembershipRevokeActionForTesting != null) {
                throw new IllegalStateException("X4 terminal pre-revoke probe is already armed");
            }
            terminalTransitionPreMembershipRevokeActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    void runBeforeTerminalContributionCommitForTesting(Runnable action) {
        synchronized (monitor) {
            if (terminalContributionBeforeCommitActionForTesting != null) {
                throw new IllegalStateException("X4 terminal contribution probe is already armed");
            }
            terminalContributionBeforeCommitActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    void runAfterTerminalContributionAcceptedForTesting(Runnable action) {
        synchronized (monitor) {
            if (terminalContributionAcceptedActionForTesting != null) {
                throw new IllegalStateException("X4 terminal contribution-accepted probe is already armed");
            }
            terminalContributionAcceptedActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    void runAfterTerminalPhysicalOutcomeBeforePublicationForTesting(Runnable action) {
        synchronized (monitor) {
            if (terminalPhysicalOutcomeBeforePublicationActionForTesting != null) {
                throw new IllegalStateException("X4 terminal physical-outcome probe is already armed");
            }
            terminalPhysicalOutcomeBeforePublicationActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    void runAfterInitialPhysicalAllocationFailurePendingForTesting(Runnable action) {
        synchronized (monitor) {
            if (initialPhysicalAllocationFailureAfterPendingActionForTesting != null) {
                throw new IllegalStateException("X4 initial physical-allocation pending probe is already armed");
            }
            initialPhysicalAllocationFailureAfterPendingActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    void runAfterProviderLeaseCloseForTesting(Runnable action) {
        synchronized (monitor) {
            if (afterProviderLeaseCloseActionForTesting != null) {
                throw new IllegalStateException("X4 provider-lease-close probe is already armed");
            }
            afterProviderLeaseCloseActionForTesting = Objects.requireNonNull(action, "action");
        }
    }

    private void runDirectReleasePrePendingPinDecrementForTesting() {
        Runnable action;
        synchronized (monitor) {
            action = directReleasePrePendingPinDecrementActionForTesting;
            directReleasePrePendingPinDecrementActionForTesting = null;
        }
        if (action != null) action.run();
    }

    private void runBeforeTerminalTransitionMembershipRevokeForTesting() {
        Runnable action;
        synchronized (monitor) {
            action = terminalTransitionPreMembershipRevokeActionForTesting;
            terminalTransitionPreMembershipRevokeActionForTesting = null;
        }
        if (action != null) action.run();
    }

    private void runBeforeTerminalContributionCommitForTesting() {
        Runnable action;
        synchronized (monitor) {
            action = terminalContributionBeforeCommitActionForTesting;
            terminalContributionBeforeCommitActionForTesting = null;
        }
        if (action != null) action.run();
    }

    private void runAfterTerminalContributionAcceptedForTesting() {
        Runnable action = terminalContributionAcceptedActionForTesting;
        if (action != null) action.run();
    }

    private void runAfterTerminalPhysicalOutcomeBeforePublicationForTesting() {
        Runnable action;
        synchronized (monitor) {
            action = terminalPhysicalOutcomeBeforePublicationActionForTesting;
            terminalPhysicalOutcomeBeforePublicationActionForTesting = null;
        }
        if (action != null) action.run();
    }

    private void runAfterInitialPhysicalAllocationFailurePendingForTesting() {
        Runnable action;
        synchronized (monitor) {
            action = initialPhysicalAllocationFailureAfterPendingActionForTesting;
            initialPhysicalAllocationFailureAfterPendingActionForTesting = null;
        }
        if (action != null) action.run();
    }

    private void runAfterProviderLeaseCloseForTesting() {
        Runnable action = afterProviderLeaseCloseActionForTesting;
        if (action != null) action.run();
    }

    @Override
    public X4HostSpec<F> configure() {
        return specification;
    }

    @Override
    public X4HostLifecycleState state() {
        synchronized (monitor) {
            return state;
        }
    }

    @Override
    public X4HostLeaseDiagnostics leaseDiagnostics() {
        synchronized (monitor) {
            boolean activeEpoch = terminalEpochPhase != TerminalEpochPhase.NONE;
            boolean retire = (activeEpoch && !terminalCloseRequested)
                    || state == X4HostLifecycleState.RETIRING || state == X4HostLifecycleState.RETIRED;
            boolean close = (activeEpoch && terminalCloseRequested)
                    || state == X4HostLifecycleState.CLOSING || state == X4HostLifecycleState.CLOSED;
            return new X4HostLeaseDiagnostics(
                    state, generationSession.generation(), activeSnapshots.size() + pendingPinReleases,
                    inFlightSubmissions, retire, close,
                    terminalEpochPhase == TerminalEpochPhase.SEALED && terminalFailure != null
                            ? terminalFailure.getClass().getName() : "");
        }
    }

    @Override
    public CompletionStage<X4HostLifecycleState> drainCompletion() {
        return drainCompletion;
    }

    @Override
    public void freeze() {
        synchronized (monitor) {
            if (state != X4HostLifecycleState.CONFIGURING || terminalEpochPhase != TerminalEpochPhase.NONE) {
                throw new IllegalStateException("X4 host configuration can only be frozen once before extraction");
            }
            state = X4HostLifecycleState.FROZEN;
        }
    }

    @Override
    public X4HostRegistrationReservation<F> freezeAndReserveRegistration() {
        synchronized (monitor) {
            if (state != X4HostLifecycleState.CONFIGURING || activeRegistrationReservation != null
                    || terminalEpochPhase != TerminalEpochPhase.NONE) {
                throw new IllegalStateException("X4 host registration can freeze only a configuring adapter");
            }
            RegistrationReservation reservation = new RegistrationReservation(Thread.currentThread());
            state = X4HostLifecycleState.FROZEN;
            activeRegistrationReservation = reservation;
            return reservation;
        }
    }

    @Override
    public X4PreparedSnapshot prepare(F frame) {
        F checkedFrame = Objects.requireNonNull(frame, "frame");
        X4SnapshotFrame commonFrame = Objects.requireNonNull(checkedFrame.snapshotFrame(), "frame.snapshotFrame");
        if (!specification.identity().equals(commonFrame.identity())) {
            throw new IllegalArgumentException("An X4 host frame must use the configured scoped identity");
        }
        synchronized (monitor) {
            requirePreparationStateLocked();
        }
        specification.configuration().validateFrame(specification, checkedFrame);
        PreparedInputs inputs = prepareInputs(commonFrame);
        specification.configuration().validatePreparedHandle(inputs.snapshot().handle());
        if (inputs.snapshot().generation() != generationSession.generation()) {
            throw new IllegalStateException("X4 snapshot generation must match the explicitly injected generation session");
        }
        ClientGenerationLeaseBinding sharedGenerationBinding = inputs.acquireSharedGenerationBinding(models);
        ProviderLease generationLease = null;
        try {
            boolean permitsFrozenCpuSubmission = sharedGenerationBinding.permitsFrozenCpuRoute(inputs.snapshot())
                    && inputs.snapshot().visibility() != RenderVisibility.CULLED;
            generationLease = generationSession.pin();
            X4PreparedSnapshot prepared = new X4PreparedSnapshot(
                    specification.hostKind(), specification.identity(), specification.modelKey(), inputs.snapshot(),
                    inputs.missingDiagnostic(), generationLease, sharedGenerationBinding, permitsFrozenCpuSubmission,
                    this::releasePreparedSnapshot);
            synchronized (monitor) {
                requirePreparationStateLocked();
                activeSnapshots.put(prepared, Boolean.TRUE);
                state = X4HostLifecycleState.PREPARED;
            }
            return prepared;
        } catch (Throwable failure) {
            Throwable cleanup = closeFailedPreparationLeases(generationLease, sharedGenerationBinding);
            X4HostFailureSelector.rethrow(X4HostFailureSelector.select(failure, cleanup),
                    "X4 host preparation failed while releasing its generation leases");
            throw new AssertionError("unreachable");
        }
    }

    private PreparedInputs prepareInputs(X4SnapshotFrame frame) {
        if (frame.extractedSnapshot().isPresent()) {
            ModelRenderSnapshot snapshot = frame.extractedSnapshot().orElseThrow();
            validateCapturedSnapshot(frame, snapshot);
            return new PreparedInputs(snapshot, frame.capturedMissingDiagnostic(), Optional.empty());
        }
        ClientModelView view = models.resolve(specification.modelKey());
        ModelRenderHandle handle = view.renderHandle();
        if (!specification.modelKey().equals(handle.modelKey()) || view.generationId() != handle.generation()) {
            throw new IllegalStateException("Client model lookup returned a handle outside the requested model/generation");
        }
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle, frame.transform().toCoreTransform(), frame.packedLight(), frame.packedOverlay(),
                frame.tintArgb(), frame.visible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED,
                new CullingMetadata(handle.bounds().transformed(frame.transform().toCoreTransform()), true));
        Optional<X4MissingModelDiagnostic> diagnostic = handle.missingModel()
                ? Optional.of(new X4MissingModelDiagnostic(view.generationId(), view.primaryDiagnostic().orElseThrow(
                        () -> new IllegalStateException("A missing client model view must provide structured diagnostic evidence"))))
                : Optional.empty();
        return new PreparedInputs(snapshot, diagnostic, Optional.of(view));
    }

    private void validateCapturedSnapshot(X4SnapshotFrame frame, ModelRenderSnapshot snapshot) {
        if (!specification.modelKey().equals(snapshot.handle().modelKey())) {
            throw new IllegalArgumentException("An extracted X4 snapshot must match the configured model key");
        }
        if (!snapshot.rootTransform().equals(frame.transform().toCoreTransform())
                || snapshot.packedLight() != frame.packedLight()
                || snapshot.packedOverlay() != frame.packedOverlay()
                || snapshot.tintArgb() != frame.tintArgb()
                || snapshot.visibility() != (frame.visible() ? RenderVisibility.VISIBLE : RenderVisibility.CULLED)) {
            throw new IllegalArgumentException("An extracted X4 snapshot must match its immutable frame metadata");
        }
    }

    @Override
    public void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context) {
        X4PreparedSnapshot checkedPrepared = Objects.requireNonNull(prepared, "prepared");
        RenderSubmissionContext checkedContext = Objects.requireNonNull(context, "context");
        X4PreparedSnapshot.SubmissionHold hold;
        ModelRenderSnapshot submittedSnapshot;
        synchronized (monitor) {
            requireSubmitStateLocked();
            if (!activeSnapshots.containsKey(checkedPrepared)) {
                throw new IllegalStateException("X4 host submit accepts only a live lease created by this adapter");
            }
            hold = checkedPrepared.acquireSubmitHold();
            hold.admit(++nextSubmissionEpoch, Thread.currentThread());
            linkActiveSubmissionLocked(hold);
            submittedSnapshot = hold.snapshot();
            inFlightSubmissions++;
        }
        Throwable rendererFailure = null;
        try {
            if (checkedPrepared.permitsFrozenCpuSubmission()) {
                renderer.submit(submittedSnapshot, checkedContext);
            }
        } catch (Throwable failure) {
            rendererFailure = failure;
        }
        synchronized (monitor) {
            hold.recordRendererFailure(rendererFailure);
        }
        Throwable bookkeepingFailure = null;
        try {
            hold.release();
        } catch (Throwable failure) {
            bookkeepingFailure = failure;
        }
        synchronized (monitor) {
            hold.recordBookkeepingFailure(bookkeepingFailure);
        }
        X4HostFailureSelector.rethrow(finishSubmission(hold), "X4 host submit failed");
    }

    @Override
    public void retire() {
        requestTerminalTransition(false);
    }

    @Override
    public void close() {
        requestTerminalTransition(true);
    }

    private void releasePreparedSnapshot(X4PreparedSnapshot released, X4PreparedSnapshot.SubmissionHold releasingHold) {
        synchronized (monitor) {
            if (activeSnapshots.remove(released) == null) return;
            pendingPinReleases++;
            released.beginProviderRelease(releasingHold, Thread.currentThread());
            linkReleaseInProgressLocked(released);
            if (releasingHold != null) {
                if (releasingHold.submissionEpoch() <= 0L) {
                    throw new IllegalStateException("X4 terminal submit carrier has no admission epoch");
                }
                pendingTerminalSubmissionCommits++;
            }
        }
        // A provider callback is an external boundary. Preserve a caller's interrupted status on
        // its already-admitted source while clearing it before the callback can observe it.
        recordReleaseInterruptReceipt(released, releasingHold);
        Throwable providerFailure = null;
        try {
            released.generationLease().close();
            runAfterProviderLeaseCloseForTesting();
        } catch (Throwable failure) {
            providerFailure = failure;
        }
        Throwable sharedLeaseFailure = closeSharedGenerationBinding(released.sharedGenerationBinding());
        providerFailure = X4HostFailureSelector.select(providerFailure, sharedLeaseFailure);
        // Provider callbacks may set the status asynchronously as well. Keep it on the same
        // existing carrier until its terminal replay boundary rather than letting C observe it.
        recordReleaseInterruptReceipt(released, releasingHold);
        boolean submissionRelease = releasingHold != null;
        synchronized (monitor) {
            released.recordProviderReleaseFailure(providerFailure);
            unlinkReleaseInProgressLocked(released);
            if (submissionRelease) {
                releasingHold.recordProviderReleaseFailure(providerFailure);
                decrementPendingPinReleaseLocked();
                if (providerFailure != null) {
                    releasingHold.joinTerminalEpoch(openTerminalEpochLocked(true));
                } else if (terminalEpochPhase == TerminalEpochPhase.OPEN) {
                    releasingHold.joinTerminalEpoch(terminalEpochId);
                }
                monitor.notifyAll();
            }
        }
        if (submissionRelease) {
            publishDrainIfPending();
            return;
        }
        // B is stored above before this monitor-out seam or any contribution wait.
        runDirectReleasePrePendingPinDecrementForTesting();
        X4HostFailureSelector.rethrow(finishDirectRelease(released), "X4 host terminal release failed");
    }

    private void recordReleaseInterruptReceipt(
            X4PreparedSnapshot released, X4PreparedSnapshot.SubmissionHold releasingHold) {
        if (!Thread.interrupted()) return;
        synchronized (monitor) {
            if (releasingHold != null) releasingHold.recordTerminalInterruptReceipt();
            else released.recordTerminalInterruptReceipt();
        }
    }

    private Throwable closeFailedPreparationLeases(
            ProviderLease generationLease, ClientGenerationLeaseBinding sharedGenerationBinding) {
        Throwable providerFailure = null;
        if (generationLease != null) {
            try {
                generationLease.close();
            } catch (Throwable failure) {
                providerFailure = failure;
            }
        }
        return X4HostFailureSelector.select(providerFailure, closeSharedGenerationBinding(sharedGenerationBinding));
    }

    private static Throwable closeSharedGenerationBinding(ClientGenerationLeaseBinding sharedGenerationBinding) {
        try {
            sharedGenerationBinding.close();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Throwable finishSubmission(X4PreparedSnapshot.SubmissionHold hold) {
        boolean interrupted = Thread.interrupted();
        try {
            Throwable rendererFailure;
            Throwable providerFailure;
            Throwable bookkeepingFailure;
            boolean terminalRenderer;
            boolean rendererContributes;
            boolean locallyCompleteTerminalError = false;
            long epoch = 0L;
            synchronized (monitor) {
                interrupted |= hold.takeTerminalInterruptReceipt();
                rendererFailure = hold.rendererFailure();
                providerFailure = hold.providerReleaseFailureRecorded() ? hold.providerReleaseFailure() : null;
                bookkeepingFailure = hold.bookkeepingFailure();
                terminalRenderer = rendererFailure instanceof Error;
                // Ordinary A remains local only when it is alone. A same-H B/D must still fold A
                // through the selector so an Error cleanup can suppress it without losing identity.
                rendererContributes = terminalRenderer || providerFailure != null || bookkeepingFailure != null;
                if (terminalEpochPhase == TerminalEpochPhase.NONE && rendererContributes) {
                    epoch = openTerminalEpochLocked(true);
                    // Error A is the source that opened E. With no same-H B/D it is a stable
                    // local result even if this was the only in-flight H; if it is the last
                    // source below, the same caller still installs C before returning A.
                    locallyCompleteTerminalError = terminalRenderer
                            && providerFailure == null && bookkeepingFailure == null;
                } else if (terminalEpochPhase == TerminalEpochPhase.OPEN) {
                    epoch = terminalEpochId;
                } else if (terminalEpochPhase == TerminalEpochPhase.SEALING || terminalEpochPhase == TerminalEpochPhase.SEALED) {
                    throw new IllegalStateException("X4 submit reached finalization after terminal sealing");
                }
                if (epoch == 0L) {
                    releaseSubmissionCountersLocked(hold);
                    finishIfDrainedLocked();
                    monitor.notifyAll();
                } else {
                    hold.joinTerminalEpoch(epoch);
                }
            }
            if (epoch == 0L) {
                boolean nestedExactSource;
                boolean transferredInterrupt = false;
                synchronized (monitor) {
                    // This H has already unlinked above. A same-thread outer renderer/provider/T
                    // carrier may still be live even though the nested H did not open E; do not
                    // restore its receipt into that callback before the outer source is finished.
                    nestedExactSource = isExactTerminalOwnerLocked(Thread.currentThread());
                    if (nestedExactSource && interrupted) {
                        transferredInterrupt = transferInterruptReceiptToExactOuterSourceLocked(Thread.currentThread());
                    }
                }
                if (nestedExactSource) {
                    if (interrupted && !transferredInterrupt) {
                        throw new IllegalStateException("X4 nested local submit lost its exact interrupt carrier");
                    }
                    interrupted = false;
                }
                publishDrainIfPending();
                return selectFailures(rendererFailure, selectFailures(providerFailure, bookkeepingFailure));
            }
            interrupted |= commitSubmissionContribution(hold, rendererContributes, epoch);
            boolean ownsOpenSnapshot;
            boolean nestedExactSource;
            boolean lastSourceNeedsPhysicalDriver;
            boolean transferredInterrupt = false;
            synchronized (monitor) {
                ownsOpenSnapshot = activeSnapshots.containsKey(hold.ownerSnapshot());
                nestedExactSource = isExactTerminalOwnerLocked(Thread.currentThread());
                lastSourceNeedsPhysicalDriver = requiresInitialPhysicalDriverLocked();
                if (nestedExactSource && interrupted) {
                    transferredInterrupt = transferInterruptReceiptToExactOuterSourceLocked(Thread.currentThread());
                }
            }
            Throwable sameSubmissionOutcome = selectSameSubmissionOutcome(
                    rendererFailure, providerFailure, bookkeepingFailure);
            if (nestedExactSource) {
                // An outer renderer/provider/revoke callback still owns an exact source. It will
                // drive E after this H is committed; this nested H must not wait on that owner.
                if (interrupted && !transferredInterrupt) {
                    throw new IllegalStateException("X4 nested submit lost its exact interrupt carrier");
                }
                interrupted = false;
                publishDrainIfPending();
                return sameSubmissionOutcome;
            }
            boolean locallyCompleteOrdinaryRenderer = !rendererContributes && rendererFailure != null;
            if (locallyCompleteTerminalError || ownsOpenSnapshot || locallyCompleteOrdinaryRenderer) {
                // A last source cannot leave an already-open E with no actor able to install C.
                // Do not do this for an open caller-owned snapshot: that source is the intentional
                // future B gate and its submit must retain its local bounded return.
                if (!ownsOpenSnapshot && lastSourceNeedsPhysicalDriver) {
                    interrupted |= driveEpochPhysicalIfRequired();
                }
                publishDrainIfPending();
                interrupted |= Thread.interrupted();
                return sameSubmissionOutcome;
            }
            interrupted |= driveEpochPhysicalIfRequired();
            interrupted |= awaitTerminalEpochUninterruptibly();
            Throwable terminal = sealedTerminalFailure();
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            return terminal == null && rendererFailure != null ? rendererFailure : terminal;
        } finally {
            restoreInterruptIfNeeded(interrupted);
        }
    }

    private Throwable finishDirectRelease(X4PreparedSnapshot released) {
        boolean interrupted = Thread.interrupted();
        try {
            Throwable providerFailure;
            long epoch = 0L;
            synchronized (monitor) {
                interrupted |= released.takeTerminalInterruptReceipt();
                providerFailure = released.providerReleaseFailure();
                if (terminalEpochPhase == TerminalEpochPhase.NONE && providerFailure != null) {
                    epoch = openTerminalEpochLocked(true);
                } else if (terminalEpochPhase == TerminalEpochPhase.OPEN) {
                    epoch = terminalEpochId;
                } else if (terminalEpochPhase == TerminalEpochPhase.SEALING || terminalEpochPhase == TerminalEpochPhase.SEALED) {
                    throw new IllegalStateException("X4 direct provider release arrived after terminal sealing");
                }
                if (epoch == 0L) {
                    released.commitTerminalContribution(0L);
                    decrementPendingPinReleaseLocked();
                    finishIfDrainedLocked();
                    monitor.notifyAll();
                } else {
                    released.joinTerminalEpoch(epoch);
                }
            }
            if (epoch == 0L) {
                boolean nestedExactSource;
                boolean transferredInterrupt = false;
                synchronized (monitor) {
                    // S2 has unlinked above. Preserve an interrupt on a still-active S1/H/T
                    // callback carrier rather than restoring it before that outer boundary.
                    nestedExactSource = isExactTerminalOwnerLocked(Thread.currentThread());
                    if (nestedExactSource && interrupted) {
                        transferredInterrupt = transferInterruptReceiptToExactOuterSourceLocked(Thread.currentThread());
                    }
                }
                if (nestedExactSource) {
                    if (interrupted && !transferredInterrupt) {
                        throw new IllegalStateException("X4 nested local direct release lost its exact interrupt carrier");
                    }
                    interrupted = false;
                }
                publishDrainIfPending();
                return null;
            }
            interrupted |= commitDirectContribution(released, epoch);
            boolean nestedExactSource;
            boolean transferredInterrupt = false;
            synchronized (monitor) {
                nestedExactSource = isExactTerminalOwnerLocked(Thread.currentThread());
                if (nestedExactSource && interrupted) {
                    transferredInterrupt = transferInterruptReceiptToExactOuterSourceLocked(Thread.currentThread());
                }
            }
            if (nestedExactSource) {
                // A nested direct close has committed B to E. Returning to its exact outer source
                // is the only non-self-waiting outcome; that outer source will drive/replay F.
                if (interrupted && !transferredInterrupt) {
                    throw new IllegalStateException("X4 nested direct release lost its exact interrupt carrier");
                }
                interrupted = false;
                publishDrainIfPending();
                return null;
            }
            interrupted |= driveEpochPhysicalIfRequired();
            interrupted |= awaitTerminalEpochUninterruptibly();
            Throwable terminal = sealedTerminalFailure();
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            return terminal;
        } finally {
            restoreInterruptIfNeeded(interrupted);
        }
    }

    private boolean commitSubmissionContribution(
            X4PreparedSnapshot.SubmissionHold hold, boolean rendererContributes, long epoch) {
        boolean interrupted = claimTerminalContribution(hold, epoch);
        boolean handedOff = false;
        try {
            Throwable oldRenderer;
            Throwable oldProvider;
            Throwable oldBookkeeping;
            Throwable rendererFailure;
            Throwable providerFailure;
            Throwable bookkeepingFailure;
            synchronized (monitor) {
                requireContributionOwnerLocked(hold, epoch);
                oldRenderer = epochRendererFailure;
                oldProvider = epochProviderFailure;
                oldBookkeeping = epochBookkeepingFailure;
                rendererFailure = hold.rendererFailure();
                providerFailure = hold.providerReleaseFailureRecorded() ? hold.providerReleaseFailure() : null;
                bookkeepingFailure = hold.bookkeepingFailure();
            }
            interrupted |= Thread.interrupted();
            Throwable renderer = rendererContributes ? selectFailures(oldRenderer, rendererFailure) : oldRenderer;
            Throwable provider = providerFailure == null ? oldProvider : selectFailures(oldProvider, providerFailure);
            Throwable bookkeeping = bookkeepingFailure == null ? oldBookkeeping : selectFailures(oldBookkeeping, bookkeepingFailure);
            interrupted |= Thread.interrupted();
            runBeforeTerminalContributionCommitForTesting();
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                requireContributionOwnerLocked(hold, epoch);
                interrupted |= hold.takeTerminalInterruptReceipt();
                if (rendererContributes) epochRendererFailure = renderer;
                if (providerFailure != null) epochProviderFailure = provider;
                if (bookkeepingFailure != null) epochBookkeepingFailure = bookkeeping;
                if (rendererContributes) requireTerminalCloseLocked();
                releaseSubmissionCountersLocked(hold);
                hold.commitTerminalContribution(epoch, ++nextTerminalContributionSequence);
                releaseTerminalContributionLocked(hold);
                monitor.notifyAll();
            }
            runAfterTerminalContributionAcceptedForTesting();
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                interrupted |= hold.takeTerminalInterruptReceipt();
            }
            interrupted |= tryClaimEpochSeal(hold);
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private boolean commitDirectContribution(X4PreparedSnapshot released, long epoch) {
        boolean interrupted = claimTerminalContribution(released, epoch);
        boolean handedOff = false;
        try {
            Throwable oldProvider;
            Throwable providerFailure;
            synchronized (monitor) {
                requireContributionOwnerLocked(released, epoch);
                oldProvider = epochProviderFailure;
                providerFailure = released.providerReleaseFailure();
            }
            interrupted |= Thread.interrupted();
            Throwable provider = providerFailure == null ? oldProvider : selectFailures(oldProvider, providerFailure);
            interrupted |= Thread.interrupted();
            runBeforeTerminalContributionCommitForTesting();
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                requireContributionOwnerLocked(released, epoch);
                interrupted |= released.takeTerminalInterruptReceipt();
                if (providerFailure != null) {
                    epochProviderFailure = provider;
                    requireTerminalCloseLocked();
                }
                released.commitTerminalContribution(epoch);
                nextTerminalContributionSequence++;
                decrementPendingPinReleaseLocked();
                releaseTerminalContributionLocked(released);
                monitor.notifyAll();
            }
            runAfterTerminalContributionAcceptedForTesting();
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                interrupted |= released.takeTerminalInterruptReceipt();
            }
            interrupted |= tryClaimEpochSeal(released);
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private void requestTerminalTransition(boolean closeRequest) {
        boolean interrupted = false;
        boolean terminalReceiptCaptured = false;
        try {
            for (;;) {
                RegistrationReservation reservation = null;
                boolean openedHere = false;
                boolean driveOpen = false;
                boolean waitOpen = false;
                boolean coalesced = false;
                TerminalTransition retry = null;
                TerminalTransition waitingRetry = null;
                Throwable allocationFailure = null;
                Throwable sealed = null;
                synchronized (monitor) {
                    reservation = activeRegistrationReservation;
                    if (reservation != null) {
                        reservation.requireNonOwner(closeRequest ? "close" : "retire");
                    } else if (terminalEpochPhase == TerminalEpochPhase.NONE) {
                        openTerminalEpochLocked(closeRequest);
                        if (isExactTerminalOwnerLocked(Thread.currentThread())) {
                            coalesced = true;
                        } else {
                            openedHere = true;
                            driveOpen = true;
                            waitOpen = true;
                        }
                    } else if (terminalEpochPhase == TerminalEpochPhase.OPEN
                            || terminalEpochPhase == TerminalEpochPhase.SEALING) {
                        upgradeTerminalIntentLocked(closeRequest);
                        if (isExactTerminalOwnerLocked(Thread.currentThread())) {
                            coalesced = true;
                        } else {
                            driveOpen = terminalEpochPhase == TerminalEpochPhase.OPEN;
                            waitOpen = true;
                        }
                    } else {
                        if (closeRequest && state == X4HostLifecycleState.RETIRED) {
                            // A post-retirement close upgrades intent only; its existing retirement
                            // drain receipt stays one-shot while diagnostics report close, not both.
                            terminalCloseRequested = true;
                            state = X4HostLifecycleState.CLOSED;
                            finishIfDrainedLocked();
                        }
                        if (registrationMembership == null) {
                            sealed = terminalFailure;
                        } else if (isExactTerminalOwnerLocked(Thread.currentThread())) {
                            coalesced = true;
                        } else if (terminalTransition != null) {
                            waitingRetry = terminalTransition;
                        } else {
                            try {
                                beforePostSealTerminalTransitionAllocationLocked();
                                retry = new TerminalTransition(Thread.currentThread(), registrationMembership,
                                        terminalEpochId, true, true);
                                terminalTransition = retry;
                            } catch (Throwable failure) {
                                allocationFailure = failure;
                            }
                        }
                    }
                }
                if (reservation != null) {
                    awaitRegistrationReservation(reservation, closeRequest ? "close" : "retire");
                    continue;
                }
                // Reservation waiting retains its established interrupted-failure contract. Only
                // after a caller has reached E (or a sealed receipt retry) do we capture/clear
                // status into the terminal receipt that crosses physical callback boundaries.
                if (!terminalReceiptCaptured) {
                    interrupted = Thread.interrupted();
                    terminalReceiptCaptured = true;
                }
                if (coalesced) {
                    boolean transferred;
                    synchronized (monitor) {
                        interrupted |= Thread.interrupted();
                        transferred = !interrupted
                                || transferInterruptReceiptToExactOuterSourceLocked(Thread.currentThread());
                    }
                    if (!transferred) {
                        throw new IllegalStateException("X4 coalesced terminal request lost its exact interrupt carrier");
                    }
                    interrupted = false;
                    return;
                }
                if (driveOpen) interrupted |= driveEpochPhysicalIfRequired();
                if (waitOpen) {
                    interrupted |= awaitTerminalEpochUninterruptibly();
                    Throwable observed = sealedTerminalFailure();
                    if (!openedHere && hasRetainedMembership()) continue;
                    publishDrainIfPending();
                    interrupted |= Thread.interrupted();
                    X4HostFailureSelector.rethrow(observed, "X4 host terminal release failed");
                    return;
                }
                if (retry != null) {
                    interrupted |= executeTerminalTransition(retry);
                    Throwable observed = sealedTerminalFailure();
                    publishDrainIfPending();
                    interrupted |= Thread.interrupted();
                    X4HostFailureSelector.rethrow(observed, "X4 host terminal release failed");
                    return;
                }
                if (waitingRetry != null) {
                    interrupted |= awaitTerminalTransitionUninterruptibly(waitingRetry);
                    Throwable observed = sealedTerminalFailure();
                    publishDrainIfPending();
                    interrupted |= Thread.interrupted();
                    X4HostFailureSelector.rethrow(observed, "X4 host terminal release failed");
                    return;
                }
                if (allocationFailure != null) {
                    Throwable observed = sealedTerminalFailure();
                    publishDrainIfPending();
                    interrupted |= Thread.interrupted();
                    X4HostFailureSelector.rethrow(observed, "X4 host terminal release failed");
                    return;
                }
                publishDrainIfPending();
                interrupted |= Thread.interrupted();
                X4HostFailureSelector.rethrow(sealed, "X4 host terminal release failed");
                return;
            }
        } finally {
            restoreInterruptIfNeeded(interrupted);
        }
    }

    private long openTerminalEpochLocked(boolean closeRequest) {
        if (terminalEpochPhase == TerminalEpochPhase.NONE) {
            terminalEpochPhase = TerminalEpochPhase.OPEN;
            terminalEpochId++;
            terminalCloseRequested = closeRequest;
            epochPhysicalResolved = registrationMembership == null;
            pendingInitialPhysicalAllocationFailure = null;
            pendingInitialPhysicalAllocationOwnerThread = null;
            pendingInitialPhysicalAllocationInterruptReceipt = false;
            epochRendererFailure = null;
            epochProviderFailure = null;
            epochMembershipFailure = null;
            epochBookkeepingFailure = null;
            terminalFailure = null;
            state = closeRequest ? X4HostLifecycleState.CLOSING : X4HostLifecycleState.RETIRING;
            monitor.notifyAll();
            return terminalEpochId;
        }
        if (terminalEpochPhase == TerminalEpochPhase.OPEN || terminalEpochPhase == TerminalEpochPhase.SEALING) {
            upgradeTerminalIntentLocked(closeRequest);
            return terminalEpochId;
        }
        throw new IllegalStateException("X4 cannot open a second terminal epoch after F is sealed");
    }

    private void upgradeTerminalIntentLocked(boolean closeRequest) {
        if (closeRequest && !terminalCloseRequested) {
            terminalCloseRequested = true;
            if (terminalEpochPhase == TerminalEpochPhase.OPEN || terminalEpochPhase == TerminalEpochPhase.SEALING) {
                state = X4HostLifecycleState.CLOSING;
            }
            if (terminalTransition != null) terminalTransition.requestClose();
        }
    }

    private void requireTerminalCloseLocked() {
        terminalCloseRequested = true;
        if (terminalEpochPhase == TerminalEpochPhase.OPEN || terminalEpochPhase == TerminalEpochPhase.SEALING) {
            state = X4HostLifecycleState.CLOSING;
        }
        if (terminalTransition != null) terminalTransition.requestClose();
    }

    private boolean driveEpochPhysicalIfRequired() {
        boolean interrupted = Thread.interrupted();
        boolean handedOff = false;
        try {
            TerminalTransition transition = startInitialPhysicalAttempt();
            if (transition != null) {
                interrupted |= executeTerminalTransition(transition);
            } else {
                Throwable allocationFailure = null;
                synchronized (monitor) {
                    if (pendingInitialPhysicalAllocationOwnerThread == Thread.currentThread()) {
                        allocationFailure = pendingInitialPhysicalAllocationFailure;
                    }
                }
                if (allocationFailure != null) {
                    runAfterInitialPhysicalAllocationFailurePendingForTesting();
                    interrupted |= commitInitialAllocationFailure(allocationFailure);
                }
            }
            interrupted |= tryClaimEpochSeal(this);
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private TerminalTransition startInitialPhysicalAttempt() {
        TerminalTransition transition = null;
        synchronized (monitor) {
            if (terminalEpochPhase != TerminalEpochPhase.OPEN || epochPhysicalResolved || terminalTransition != null
                    || pendingInitialPhysicalAllocationFailure != null
                    || pendingInitialPhysicalAllocationOwnerThread != null) return null;
            if (registrationMembership == null) {
                epochPhysicalResolved = true;
                monitor.notifyAll();
                return null;
            }
            try {
                beforePreSealTerminalEpochAllocationLocked();
                transition = new TerminalTransition(Thread.currentThread(), registrationMembership,
                        terminalEpochId, terminalCloseRequested, false);
                terminalTransition = transition;
            } catch (Throwable failure) {
                pendingInitialPhysicalAllocationFailure = failure;
                pendingInitialPhysicalAllocationOwnerThread = Thread.currentThread();
            }
        }
        return transition;
    }

    private boolean commitInitialAllocationFailure(Throwable failure) {
        long epoch;
        synchronized (monitor) {
            if (terminalEpochPhase != TerminalEpochPhase.OPEN || epochPhysicalResolved || terminalTransition != null
                    || pendingInitialPhysicalAllocationFailure != failure
                    || pendingInitialPhysicalAllocationOwnerThread != Thread.currentThread()) {
                throw new IllegalStateException("X4 initial terminal allocation failure lost its epoch");
            }
            epoch = terminalEpochId;
        }
        boolean interrupted = claimTerminalContribution(this, epoch);
        boolean handedOff = false;
        try {
            Throwable oldBookkeeping;
            synchronized (monitor) {
                requireContributionOwnerLocked(this, epoch);
                oldBookkeeping = epochBookkeepingFailure;
            }
            interrupted |= Thread.interrupted();
            Throwable bookkeeping = selectFailures(oldBookkeeping, failure);
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                requireContributionOwnerLocked(this, epoch);
                if (pendingInitialPhysicalAllocationFailure != failure
                        || pendingInitialPhysicalAllocationOwnerThread != Thread.currentThread()) {
                    throw new IllegalStateException("X4 initial terminal allocation failure lost its pending carrier");
                }
                epochBookkeepingFailure = bookkeeping;
                pendingInitialPhysicalAllocationFailure = null;
                pendingInitialPhysicalAllocationOwnerThread = null;
                interrupted |= pendingInitialPhysicalAllocationInterruptReceipt;
                pendingInitialPhysicalAllocationInterruptReceipt = false;
                epochPhysicalResolved = true;
                requireTerminalCloseLocked();
                releaseTerminalContributionLocked(this);
                monitor.notifyAll();
            }
            interrupted |= tryClaimEpochSeal(this);
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private boolean executeTerminalTransition(TerminalTransition transition) {
        boolean interrupted = Thread.interrupted();
        boolean handedOff = false;
        try {
            synchronized (monitor) {
                if (terminalTransition != transition) {
                    throw new IllegalStateException("X4 terminal transition ownership changed before execution");
                }
            }
            runBeforeTerminalTransitionMembershipRevokeForTesting();
            interrupted |= Thread.interrupted();
            Throwable revokeFailure = null;
            try {
                transition.membership().revoke();
            } catch (Throwable failure) {
                revokeFailure = failure;
            }
            interrupted |= Thread.interrupted();
            runAfterTerminalPhysicalOutcomeBeforePublicationForTesting();
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                interrupted |= transition.takeTerminalInterruptReceipt();
            }
            if (transition.postSealRetry()) completePostSealPhysicalRetry(transition, revokeFailure);
            else interrupted |= commitInitialPhysicalOutcome(transition, revokeFailure);
            synchronized (monitor) {
                interrupted |= transition.takeTerminalInterruptReceipt();
            }
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private boolean commitInitialPhysicalOutcome(TerminalTransition transition, Throwable revokeFailure) {
        long epoch = transition.epochId();
        boolean interrupted = claimTerminalContribution(transition, epoch);
        boolean handedOff = false;
        try {
            Throwable oldMembership;
            synchronized (monitor) {
                requireContributionOwnerLocked(transition, epoch);
                oldMembership = epochMembershipFailure;
            }
            interrupted |= Thread.interrupted();
            Throwable membership = revokeFailure == null ? oldMembership : selectFailures(oldMembership, revokeFailure);
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                requireContributionOwnerLocked(transition, epoch);
                if (terminalTransition != transition) {
                    throw new IllegalStateException("X4 physical terminal owner changed during outcome commit");
                }
                if (revokeFailure == null && registrationMembership == transition.membership()) {
                    registrationMembership = null;
                } else if (revokeFailure != null) {
                    epochMembershipFailure = membership;
                    requireTerminalCloseLocked();
                }
                epochPhysicalResolved = true;
                terminalTransition = null;
                releaseTerminalContributionLocked(transition);
                monitor.notifyAll();
            }
            transition.complete(revokeFailure);
            interrupted |= Thread.interrupted();
            interrupted |= tryClaimEpochSeal(transition);
            publishDrainIfPending();
            synchronized (monitor) {
                interrupted |= transition.takeTerminalInterruptReceipt();
            }
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private void completePostSealPhysicalRetry(TerminalTransition transition, Throwable revokeFailure) {
        synchronized (monitor) {
            if (terminalEpochPhase != TerminalEpochPhase.SEALED || terminalTransition != transition) {
                throw new IllegalStateException("X4 post-seal physical retry lost its immutable epoch");
            }
            if (revokeFailure == null && registrationMembership == transition.membership()) {
                registrationMembership = null;
                state = X4HostLifecycleState.CLOSING;
            }
            terminalTransition = null;
            finishIfDrainedLocked();
            monitor.notifyAll();
        }
        transition.complete(revokeFailure);
        publishDrainIfPending();
    }

    private boolean tryClaimEpochSeal(Object claimant) {
        boolean interrupted = Thread.interrupted();
        boolean handedOff = false;
        try {
            long epoch;
            Throwable rendererFailure;
            Throwable providerFailure;
            Throwable membershipFailure;
            Throwable bookkeepingFailure;
            synchronized (monitor) {
                if (!canSealEpochLocked()) {
                    handedOff = true;
                    return interrupted;
                }
                epoch = terminalEpochId;
                terminalContributionOwner = claimant;
                terminalContributionOwnerThread = Thread.currentThread();
                terminalEpochPhase = TerminalEpochPhase.SEALING;
                rendererFailure = epochRendererFailure;
                providerFailure = epochProviderFailure;
                membershipFailure = epochMembershipFailure;
                bookkeepingFailure = epochBookkeepingFailure;
            }
            // Selector execution is monitor-out and must not receive an asynchronously-set flag.
            interrupted |= Thread.interrupted();
            Throwable finalFailure = selectFailures(rendererFailure,
                    selectFailures(selectFailures(providerFailure, membershipFailure), bookkeepingFailure));
            interrupted |= Thread.interrupted();
            synchronized (monitor) {
                if (terminalEpochPhase != TerminalEpochPhase.SEALING || terminalEpochId != epoch
                        || terminalContributionOwner != claimant) {
                    throw new IllegalStateException("X4 terminal epoch changed during final failure selection");
                }
                interrupted |= takeInterruptReceiptFromOwner(claimant);
                terminalFailure = finalFailure;
                if (finalFailure != null) terminalCloseRequested = true;
                terminalEpochPhase = TerminalEpochPhase.SEALED;
                releaseTerminalContributionLocked(claimant);
                if (registrationMembership != null) state = X4HostLifecycleState.CLOSING;
                else state = terminalCloseRequested || finalFailure != null
                        ? X4HostLifecycleState.CLOSED : X4HostLifecycleState.RETIRED;
                finishIfDrainedLocked();
                monitor.notifyAll();
            }
            publishDrainIfPending();
            interrupted |= Thread.interrupted();
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private boolean canSealEpochLocked() {
        return terminalEpochPhase == TerminalEpochPhase.OPEN
                && (epochPhysicalResolved || registrationMembership == null)
                && pendingInitialPhysicalAllocationFailure == null
                && pendingInitialPhysicalAllocationOwnerThread == null
                && terminalTransition == null && terminalContributionOwner == null
                && activeSnapshots.isEmpty() && inFlightSubmissions == 0 && pendingPinReleases == 0
                && pendingTerminalSubmissionCommits == 0 && releaseInProgressCount == 0;
    }

    /** Whether the just-committed final source must install the first physical owner itself. */
    private boolean requiresInitialPhysicalDriverLocked() {
        return terminalEpochPhase == TerminalEpochPhase.OPEN && !epochPhysicalResolved
                && terminalTransition == null && terminalContributionOwner == null
                && pendingInitialPhysicalAllocationOwnerThread == null
                && activeSnapshots.isEmpty() && inFlightSubmissions == 0 && pendingPinReleases == 0
                && pendingTerminalSubmissionCommits == 0 && releaseInProgressCount == 0;
    }

    private boolean claimTerminalContribution(Object owner, long epoch) {
        boolean interrupted = Thread.interrupted();
        boolean handedOff = false;
        try {
            synchronized (monitor) {
                for (;;) {
                    if (terminalEpochPhase != TerminalEpochPhase.OPEN || terminalEpochId != epoch) {
                        throw new IllegalStateException("X4 terminal contribution cannot enter a sealed epoch");
                    }
                    if (terminalContributionOwner == null) {
                        terminalContributionOwner = owner;
                        terminalContributionOwnerThread = Thread.currentThread();
                        break;
                    }
                    if (terminalContributionOwner == owner && terminalContributionOwnerThread == Thread.currentThread()) {
                        throw new IllegalStateException("X4 terminal contribution owner attempted a recursive commit");
                    }
                    try {
                        monitor.wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private static void restoreInterruptIfNeeded(boolean interrupted) {
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void requireContributionOwnerLocked(Object owner, long epoch) {
        if (terminalEpochPhase != TerminalEpochPhase.OPEN || terminalEpochId != epoch || terminalContributionOwner != owner) {
            throw new IllegalStateException("X4 terminal contribution ownership changed before commit");
        }
    }

    private void releaseTerminalContributionLocked(Object owner) {
        if (terminalContributionOwner != owner) {
            throw new IllegalStateException("X4 terminal contribution owner changed before release");
        }
        terminalContributionOwner = null;
        terminalContributionOwnerThread = null;
        monitor.notifyAll();
    }

    private void releaseSubmissionCountersLocked(X4PreparedSnapshot.SubmissionHold hold) {
        unlinkActiveSubmissionLocked(hold);
        inFlightSubmissions--;
        if (inFlightSubmissions < 0) throw new IllegalStateException("X4 adapter submit count underflow");
        if (hold.providerReleaseFailureRecorded()) {
            pendingTerminalSubmissionCommits--;
            if (pendingTerminalSubmissionCommits < 0) {
                throw new IllegalStateException("X4 terminal submit contribution count underflow");
            }
        }
    }

    private void decrementPendingPinReleaseLocked() {
        pendingPinReleases--;
        if (pendingPinReleases < 0) throw new IllegalStateException("X4 adapter pending generation-release count underflow");
    }

    private void linkActiveSubmissionLocked(X4PreparedSnapshot.SubmissionHold hold) {
        hold.linkAdmittedSubmission(activeSubmissionHead);
        activeSubmissionHead = hold;
    }

    private void unlinkActiveSubmissionLocked(X4PreparedSnapshot.SubmissionHold hold) {
        X4PreparedSnapshot.SubmissionHold previous = null;
        X4PreparedSnapshot.SubmissionHold current = activeSubmissionHead;
        while (current != null && current != hold) {
            previous = current;
            current = current.nextAdmittedSubmission();
        }
        if (current == null) {
            throw new IllegalStateException("X4 active submit source vanished before terminal commit");
        }
        X4PreparedSnapshot.SubmissionHold next = current.nextAdmittedSubmission();
        if (previous == null) activeSubmissionHead = next;
        else {
            previous.unlinkAdmittedSubmission();
            previous.linkAdmittedSubmission(next);
        }
        current.unlinkAdmittedSubmission();
    }

    private void linkReleaseInProgressLocked(X4PreparedSnapshot snapshot) {
        snapshot.linkReleaseInProgress(releaseInProgressHead);
        releaseInProgressHead = snapshot;
        releaseInProgressCount++;
    }

    private void unlinkReleaseInProgressLocked(X4PreparedSnapshot snapshot) {
        X4PreparedSnapshot previous = null;
        X4PreparedSnapshot current = releaseInProgressHead;
        while (current != null && current != snapshot) {
            previous = current;
            current = current.nextReleaseInProgress();
        }
        if (current == null) throw new IllegalStateException("X4 provider-release carrier vanished before result recording");
        X4PreparedSnapshot next = current.nextReleaseInProgress();
        if (previous == null) releaseInProgressHead = next;
        else {
            previous.unlinkReleaseInProgress();
            previous.linkReleaseInProgress(next);
        }
        current.unlinkReleaseInProgress();
        releaseInProgressCount--;
        if (releaseInProgressCount < 0) throw new IllegalStateException("X4 provider-release callback count underflow");
    }

    private boolean isExactTerminalOwnerLocked(Thread candidate) {
        if (terminalContributionOwnerThread == candidate) return true;
        if (pendingInitialPhysicalAllocationFailure != null
                && pendingInitialPhysicalAllocationOwnerThread == candidate) return true;
        if (terminalTransition != null && terminalTransition.ownedBy(candidate)) return true;
        for (X4PreparedSnapshot.SubmissionHold current = activeSubmissionHead;
                current != null; current = current.nextAdmittedSubmission()) {
            if (current.ownedBy(candidate)) return true;
        }
        for (X4PreparedSnapshot current = releaseInProgressHead; current != null; current = current.nextReleaseInProgress()) {
            if (current.providerReleaseOwnerThread() == candidate) return true;
        }
        return false;
    }

    /**
     * Transfers an interrupted contribution/coalesced request to the exact already-admitted outer
     * source. The carrier stays intrusive and allocation-free; a nested source must never restore
     * the flag before that outer source has crossed its final physical/replay boundary.
     */
    private boolean transferInterruptReceiptToExactOuterSourceLocked(Thread candidate) {
        if (pendingInitialPhysicalAllocationFailure != null
                && pendingInitialPhysicalAllocationOwnerThread == candidate) {
            pendingInitialPhysicalAllocationInterruptReceipt = true;
            return true;
        }
        Object owner = terminalContributionOwner;
        if (terminalContributionOwnerThread == candidate && recordInterruptReceiptOnOwner(owner)) return true;
        if (terminalTransition != null && terminalTransition.ownedBy(candidate)) {
            terminalTransition.recordTerminalInterruptReceipt();
            return true;
        }
        for (X4PreparedSnapshot.SubmissionHold current = activeSubmissionHead;
                current != null; current = current.nextAdmittedSubmission()) {
            if (current.ownedBy(candidate)) {
                current.recordTerminalInterruptReceipt();
                return true;
            }
        }
        for (X4PreparedSnapshot current = releaseInProgressHead;
                current != null; current = current.nextReleaseInProgress()) {
            if (current.providerReleaseOwnerThread() == candidate) {
                current.recordTerminalInterruptReceipt();
                return true;
            }
        }
        return false;
    }

    private static boolean recordInterruptReceiptOnOwner(Object owner) {
        if (owner instanceof TerminalTransition transition) {
            transition.recordTerminalInterruptReceipt();
            return true;
        }
        if (owner instanceof X4PreparedSnapshot.SubmissionHold hold) {
            hold.recordTerminalInterruptReceipt();
            return true;
        }
        if (owner instanceof X4PreparedSnapshot snapshot) {
            snapshot.recordTerminalInterruptReceipt();
            return true;
        }
        return false;
    }

    private static boolean takeInterruptReceiptFromOwner(Object owner) {
        if (owner instanceof TerminalTransition transition) return transition.takeTerminalInterruptReceipt();
        if (owner instanceof X4PreparedSnapshot.SubmissionHold hold) return hold.takeTerminalInterruptReceipt();
        if (owner instanceof X4PreparedSnapshot snapshot) return snapshot.takeTerminalInterruptReceipt();
        return false;
    }

    private boolean hasRetainedMembership() {
        synchronized (monitor) {
            return registrationMembership != null;
        }
    }

    private void beforePostSealTerminalTransitionAllocationLocked() {
        Error failure = postSealTerminalTransitionAllocationFailureForTesting;
        if (failure != null) {
            postSealTerminalTransitionAllocationFailureForTesting = null;
            throw failure;
        }
    }

    private void beforePreSealTerminalEpochAllocationLocked() {
        Error failure = preSealTerminalEpochAllocationFailureForTesting;
        if (failure != null) {
            preSealTerminalEpochAllocationFailureForTesting = null;
            throw failure;
        }
    }

    private void requirePreparationStateLocked() {
        if (activeRegistrationReservation != null) {
            throw new IllegalStateException("X4 host extraction cannot cross an active registration reservation");
        }
        if (terminalEpochPhase != TerminalEpochPhase.NONE) {
            throw new IllegalStateException("X4 host extraction cannot cross a terminal lifecycle transition");
        }
        if (state != X4HostLifecycleState.FROZEN && state != X4HostLifecycleState.PREPARED) {
            throw new IllegalStateException("X4 host extraction requires a frozen, non-retiring adapter");
        }
    }

    private void requireSubmitStateLocked() {
        if (terminalEpochPhase != TerminalEpochPhase.NONE) {
            throw new IllegalStateException("X4 host submit cannot cross a terminal lifecycle transition");
        }
        if (state != X4HostLifecycleState.PREPARED) {
            throw new IllegalStateException("X4 host submit requires a live prepared lease");
        }
    }

    private boolean awaitTerminalEpochUninterruptibly() {
        boolean interrupted = Thread.interrupted();
        boolean handedOff = false;
        try {
            synchronized (monitor) {
                while (terminalEpochPhase != TerminalEpochPhase.SEALED) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private Throwable sealedTerminalFailure() {
        synchronized (monitor) {
            if (terminalEpochPhase != TerminalEpochPhase.SEALED) {
                throw new IllegalStateException("X4 terminal failure was read before the epoch sealed");
            }
            return terminalFailure;
        }
    }

    private boolean awaitTerminalTransitionUninterruptibly(TerminalTransition transition) {
        boolean interrupted = Thread.interrupted();
        boolean handedOff = false;
        try {
            for (;;) {
                try {
                    transition.awaitCompletion();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            handedOff = true;
            return interrupted;
        } finally {
            if (!handedOff) restoreInterruptIfNeeded(interrupted);
        }
    }

    private static Throwable selectFailures(Throwable primary, Throwable cleanup) {
        try {
            return X4HostFailureSelector.select(primary, cleanup);
        } catch (Throwable selectorFailure) {
            return selectorFailure;
        }
    }

    /** Selects the same-H replay identity without adding duplicate suppression metadata before F seals. */
    private static Throwable selectSameSubmissionOutcome(
            Throwable rendererFailure, Throwable providerFailure, Throwable bookkeepingFailure) {
        return selectFailureIdentity(rendererFailure, selectFailureIdentity(providerFailure, bookkeepingFailure));
    }

    private static Throwable selectFailureIdentity(Throwable primary, Throwable cleanup) {
        if (primary == null || cleanup == null || cleanup == primary) return primary == null ? cleanup : primary;
        return primary instanceof Error || !(cleanup instanceof Error) ? primary : cleanup;
    }

    private void finishIfDrainedLocked() {
        if (terminalTransition != null || terminalContributionOwner != null || !activeSnapshots.isEmpty()
                || inFlightSubmissions != 0 || pendingPinReleases != 0 || pendingTerminalSubmissionCommits != 0
                || releaseInProgressCount != 0) return;
        if (terminalEpochPhase == TerminalEpochPhase.NONE) {
            if (state == X4HostLifecycleState.PREPARED) state = X4HostLifecycleState.FROZEN;
            return;
        }
        if (terminalEpochPhase != TerminalEpochPhase.SEALED || registrationMembership != null) return;
        if (state == X4HostLifecycleState.RETIRING) state = X4HostLifecycleState.RETIRED;
        else if (state == X4HostLifecycleState.CLOSING) state = X4HostLifecycleState.CLOSED;
        if (state == X4HostLifecycleState.RETIRED || state == X4HostLifecycleState.CLOSED) {
            claimDrainResultLocked(state, terminalFailure);
        }
    }

    private void claimDrainResultLocked(X4HostLifecycleState completionState, Throwable completionFailure) {
        if (drainResultClaimed) return;
        drainResultClaimed = true;
        pendingDrainState = completionState;
        pendingDrainFailure = completionFailure;
        drainResultPending = true;
    }

    private void publishDrainIfPending() {
        X4HostLifecycleState completionState;
        Throwable completionFailure;
        synchronized (monitor) {
            if (!drainResultPending) return;
            drainResultPending = false;
            completionState = pendingDrainState;
            completionFailure = pendingDrainFailure;
        }
        if (completionFailure == null) drainCompletion.complete(completionState);
        else drainCompletion.completeExceptionally(completionFailure);
    }

    private void awaitRegistrationReservation(RegistrationReservation reservation, String transition) {
        try {
            reservation.awaitCompletion();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to " + transition
                    + " an X4 host adapter registration reservation", exception);
        }
    }

    private final class RegistrationReservation implements X4HostRegistrationReservation<F> {
        private final Thread ownerThread;
        private final CountDownLatch completed = new CountDownLatch(1);
        private boolean complete;

        private RegistrationReservation(Thread ownerThread) {
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        }

        @Override
        public X4HostSpec<F> specification() {
            return specification;
        }

        @Override
        public void commit(X4HostRegistrationMembership membership) {
            synchronized (monitor) {
                requireCurrentLocked();
                if (state != X4HostLifecycleState.FROZEN) {
                    throw new IllegalStateException("X4 host registration reservation lost its frozen lifecycle state");
                }
                registrationMembership = Objects.requireNonNull(membership, "membership");
                completeLocked();
            }
        }

        @Override
        public void abort() {
            synchronized (monitor) {
                requireCurrentLocked();
                completeLocked();
            }
        }

        private void requireCurrentLocked() {
            if (activeRegistrationReservation != this || complete) {
                throw new IllegalStateException("X4 host registration reservation is no longer active");
            }
        }

        private void completeLocked() {
            activeRegistrationReservation = null;
            complete = true;
            completed.countDown();
        }

        private void requireNonOwner(String transition) {
            if (ownerThread == Thread.currentThread()) {
                throw new IllegalStateException("X4 host registration owner cannot " + transition
                        + " while its reservation is active");
            }
        }

        private void awaitCompletion() throws InterruptedException {
            completed.await();
        }
    }

    /** One physical membership receipt attempt; all terminal publication remains in E. */
    private static final class TerminalTransition {
        private final Thread ownerThread;
        private final X4HostRegistrationMembership membership;
        private final long epochId;
        private final boolean postSealRetry;
        private volatile boolean closeRequested;
        private volatile Throwable physicalOutcome;
        private volatile boolean terminalInterruptReceiptPending;
        private boolean completed;

        private TerminalTransition(Thread ownerThread, X4HostRegistrationMembership membership,
                long epochId, boolean closeRequested, boolean postSealRetry) {
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
            this.membership = Objects.requireNonNull(membership, "membership");
            if (epochId <= 0L) throw new IllegalArgumentException("X4 terminal physical attempt requires an epoch");
            this.epochId = epochId;
            this.closeRequested = closeRequested;
            this.postSealRetry = postSealRetry;
        }

        private boolean ownedBy(Thread candidate) { return ownerThread == candidate; }
        private X4HostRegistrationMembership membership() { return membership; }
        private long epochId() { return epochId; }
        private boolean postSealRetry() { return postSealRetry; }
        private void requestClose() { closeRequested = true; }
        private void recordTerminalInterruptReceipt() { terminalInterruptReceiptPending = true; }
        private boolean takeTerminalInterruptReceipt() {
            boolean pending = terminalInterruptReceiptPending;
            terminalInterruptReceiptPending = false;
            return pending;
        }
        @SuppressWarnings("unused") private boolean closeRequested() { return closeRequested; }
        @SuppressWarnings("unused") private Throwable physicalOutcome() { return physicalOutcome; }

        private synchronized void complete(Throwable outcome) {
            physicalOutcome = outcome;
            completed = true;
            notifyAll();
        }

        private synchronized void awaitCompletion() throws InterruptedException {
            while (!completed) wait();
        }
    }

    private record PreparedInputs(
            ModelRenderSnapshot snapshot,
            Optional<X4MissingModelDiagnostic> missingDiagnostic,
            Optional<ClientModelView> registryBackedView) {
        private PreparedInputs {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            missingDiagnostic = Objects.requireNonNull(missingDiagnostic, "missingDiagnostic");
            registryBackedView = Objects.requireNonNull(registryBackedView, "registryBackedView");
        }

        private ClientGenerationLeaseBinding acquireSharedGenerationBinding(ClientModelLookup models) {
            Objects.requireNonNull(models, "models");
            if (registryBackedView.isEmpty()) {
                return ClientGenerationLeaseBinding.unavailable(snapshot);
            }
            ClientModelView view = registryBackedView.orElseThrow();
            ClientGenerationLeaseBinding binding = models.acquireGenerationLeaseBinding(view.key(), snapshot);
            try {
                binding.requireCompatible(models);
                binding.requireExactSnapshot(snapshot);
                return binding;
            } catch (Throwable failure) {
                Throwable cleanup = closeSharedGenerationBinding(binding);
                X4HostFailureSelector.rethrow(X4HostFailureSelector.select(failure, cleanup),
                        "X4 host shared generation admission raced its resolved view");
                throw new AssertionError("unreachable");
            }
        }
    }
}
