package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.spi.experimental.ProviderLease;
import java.util.Objects;

/** Package-private, prepare-time owner of exactly one admitted successful-plan provider lease. */
final class X6LifecycleDrainBridge implements Runnable {
    private static final X6PreparedRenderPlanFactory.SuppressionAppender SUPPRESSION_APPENDER = Throwable::addSuppressed;

    private enum State {
        NEW,
        REGISTERING,
        ADMITTED,
        LEASE_ATTACHED,
        REQUESTING,
        REQUESTED,
        DRAINING,
        TERMINAL,
        CANCELLED,
        ADMISSION_VIOLATION
    }

    /** One bounded prepare-time completion object; it never owns or exposes the raw lease. */
    static final class Completion {
        private volatile Throwable admissionFailure;
        private volatile Throwable requestFailure;
        private volatile Throwable cancellationFailure;
        private volatile Throwable ownerContractFailure;
        private volatile Throwable terminalFailure;
        private volatile Throwable ownerFailure;
        private volatile boolean inlineViolation;
        private volatile boolean terminal;

        private void recordAdmissionFailure(Throwable failure) {
            admissionFailure = failure;
        }

        private void recordRequestFailure(Throwable failure) {
            requestFailure = failure;
        }

        private void recordCancellationFailure(Throwable failure) {
            cancellationFailure = failure;
        }

        private void recordOwnerContractViolation(Throwable failure) {
            inlineViolation = true;
            if (ownerContractFailure == null) {
                ownerContractFailure = failure;
            }
        }

        private void recordTerminal(Throwable failure) {
            terminalFailure = failure;
            terminal = true;
        }

        private void recordOwnerFailure(Throwable failure) {
            ownerFailure = failure;
        }

        Throwable admissionFailure() {
            return admissionFailure;
        }

        Throwable requestFailure() {
            return requestFailure;
        }

        Throwable cancellationFailure() {
            return cancellationFailure;
        }

        Throwable ownerContractFailure() {
            return ownerContractFailure;
        }

        Throwable terminalFailure() {
            return terminalFailure;
        }

        Throwable ownerFailure() {
            return ownerFailure;
        }

        boolean inlineViolation() {
            return inlineViolation;
        }

        boolean terminal() {
            return terminal;
        }
    }

    private final long generation;
    private final Completion completion = new Completion();
    private ProviderLease lease;
    private ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceipt;
    private X6LifecycleDrainDispatcher.Admission admission;
    private Thread requestingThread;
    private State state = State.NEW;

    X6LifecycleDrainBridge(long generation) {
        this.generation = generation;
    }

    long generation() {
        return generation;
    }

    Completion completion() {
        return completion;
    }

    /**
     * Completes the pre-pin host admission handshake. A registered task is retained by the host
     * before X1 can create the lease, so a later request failure cannot strand a raw lease.
     */
    boolean admit(X6LifecycleDrainDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException("X6 lifecycle bridge admission may occur only once");
            }
            state = State.REGISTERING;
        }

        X6LifecycleDrainDispatcher.Admission candidate = null;
        Throwable registrationFailure = null;
        try {
            candidate = Objects.requireNonNull(dispatcher.register(this), "dispatcher.register returned null");
        } catch (Throwable failure) {
            registrationFailure = failure;
        }
        if (registrationFailure != null) {
            synchronized (this) {
                completion.recordAdmissionFailure(registrationFailure);
                if (state == State.REGISTERING) {
                    state = State.CANCELLED;
                }
            }
            return false;
        }

        boolean admitted = false;
        synchronized (this) {
            if (state == State.REGISTERING) {
                admission = candidate;
                state = State.ADMITTED;
                admitted = true;
            } else {
                completion.recordAdmissionFailure(new IllegalStateException(
                        "X6 lifecycle dispatcher invoked its bridge before pre-pin admission completed"));
            }
        }
        if (!admitted) {
            Throwable cancellationFailure = cancel(candidate);
            if (cancellationFailure != null) {
                completion.recordCancellationFailure(cancellationFailure);
            }
        }
        return admitted;
    }

    /** Attaches provider and opaque plan-owned shared-generation ownership only after admission. */
    boolean attachLeases(
            ProviderLease candidateLease,
            ClientGenerationLeaseBinding.PlanTransferReceipt candidateSharedGenerationReceipt) {
        candidateLease = Objects.requireNonNull(candidateLease, "candidateLease");
        synchronized (this) {
            if (state != State.ADMITTED) {
                completion.recordAdmissionFailure(new IllegalStateException(
                        "X6 lifecycle dispatcher lost pre-pin admission before the provider lease attached"));
                return false;
            }
            if (candidateLease.generation() != generation) {
                completion.recordAdmissionFailure(new IllegalArgumentException(
                        "X6 lifecycle bridge lease generation must match its admitted plan generation"));
                return false;
            }
            lease = candidateLease;
            sharedGenerationReceipt = candidateSharedGenerationReceipt;
            state = State.LEASE_ATTACHED;
            return true;
        }
    }

    /** Rechecks that no owner task ran between lease attachment and plan publication. */
    boolean publicationReady() {
        synchronized (this) {
            return state == State.LEASE_ATTACHED && !completion.inlineViolation();
        }
    }

    /** Fast fail-closed probe for the irreducible return-after-publication hostile-owner race. */
    boolean permitsSubmission() {
        return !completion.inlineViolation();
    }

    /**
     * Narrow X6-only delegate for minting one close-only deferred child from the live plan parent.
     *
     * <p>The receipt itself remains opaque; this bridge never exposes the D1 lease, owner, policy
     * record, resource set, or queue child. The caller must still atomically transfer the child to
     * the T2a3 queue before it can suppress CPU.</p>
     */
    ClientGenerationLeaseBinding.DeferredSubmissionReceipt beginDeferredSubmission(
            ModelRenderSnapshot exactSnapshot, X6DrawPrimitive exactDraw) {
        ClientGenerationLeaseBinding.PlanTransferReceipt parent;
        synchronized (this) {
            if (state != State.LEASE_ATTACHED || sharedGenerationReceipt == null) {
                throw new IllegalStateException("X6 deferred submission requires one live attached plan receipt");
            }
            parent = sharedGenerationReceipt;
        }
        return parent.beginDeferredSubmission(
                Objects.requireNonNull(exactSnapshot, "exactSnapshot"), Objects.requireNonNull(exactDraw, "exactDraw"));
    }

    /**
     * Coalesces a close request into its already admitted host capability.
     *
     * <p>Request failure and malformed inline execution are completion-only owner diagnostics; no
     * caller catches a provider failure and no caller-inline fallback closes the lease.</p>
     */
    void requestDrain() {
        X6LifecycleDrainDispatcher.Admission localAdmission;
        synchronized (this) {
            if (state != State.LEASE_ATTACHED) {
                return;
            }
            state = State.REQUESTING;
            requestingThread = Thread.currentThread();
            localAdmission = admission;
        }

        Throwable requestFailure = null;
        try {
            localAdmission.requestDrain();
        } catch (Throwable failure) {
            requestFailure = failure;
        }
        synchronized (this) {
            if (requestFailure != null) {
                completion.recordRequestFailure(requestFailure);
                if (state == State.TERMINAL) {
                    // A distinct lifecycle owner is allowed to complete between the request call
                    // and this acknowledgement. That already-returned owner call cannot observe
                    // a later host request failure, but the bounded completion and every later
                    // terminal replay must use the same every-Error selection as an ordinary
                    // owner drain that observed both outcomes in one pass.
                    completion.recordOwnerFailure(selectOwnerFailure(
                            completion.ownerContractFailure(), requestFailure, completion.terminalFailure()));
                }
            }
            if (state == State.REQUESTING) {
                state = State.REQUESTED;
                requestingThread = null;
            }
        }
    }

    /**
     * Atomically detaches pre-publication ownership back to the factory before cancellation.
     *
     * <p>When a provider/receipt pair has attached, only this method may recover it for a failed
     * assembly/publication path. The factory therefore never races the bridge by closing stale
     * aliases after attachment. A successfully published plan never invokes this method; its
     * lifecycle owner remains the sole physical-close boundary.</p>
     */
    DetachedLeases detachBeforePublication() {
        X6LifecycleDrainDispatcher.Admission localAdmission;
        ProviderLease detachedLease;
        ClientGenerationLeaseBinding.PlanTransferReceipt detachedSharedGenerationReceipt;
        synchronized (this) {
            if (state == State.TERMINAL || state == State.CANCELLED || state == State.DRAINING) {
                return new DetachedLeases(null, null, null);
            }
            localAdmission = admission;
            admission = null;
            detachedLease = lease;
            detachedSharedGenerationReceipt = sharedGenerationReceipt;
            lease = null;
            sharedGenerationReceipt = null;
            requestingThread = null;
            state = State.CANCELLED;
        }
        Throwable cancellationFailure = null;
        try {
            if (localAdmission != null) {
                localAdmission.cancel();
            }
        } catch (Throwable failure) {
            cancellationFailure = failure;
            completion.recordCancellationFailure(failure);
        }
        return new DetachedLeases(detachedLease, detachedSharedGenerationReceipt, cancellationFailure);
    }

    /** Package-private exact pair recovered only by {@link #detachBeforePublication()}. */
    static final class DetachedLeases {
        private final ProviderLease providerLease;
        private final ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceipt;
        private final Throwable cancellationFailure;

        private DetachedLeases(
                ProviderLease providerLease,
                ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceipt,
                Throwable cancellationFailure) {
            this.providerLease = providerLease;
            this.sharedGenerationReceipt = sharedGenerationReceipt;
            this.cancellationFailure = cancellationFailure;
        }

        ProviderLease providerLease() {
            return providerLease;
        }

        ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceipt() {
            return sharedGenerationReceipt;
        }

        Throwable cancellationFailure() {
            return cancellationFailure;
        }
    }

    /**
     * Runs only from the host lifecycle owner. Provider callbacks occur outside this monitor.
     */
    @Override
    public void run() {
        ProviderLease leaseToClose = null;
        ClientGenerationLeaseBinding.PlanTransferReceipt sharedGenerationReceiptToClose = null;
        Throwable terminalReplay = null;
        synchronized (this) {
            switch (state) {
                case REQUESTING -> {
                    if (Thread.currentThread() == requestingThread) {
                        // A malformed dispatcher can call the retained bridge repeatedly on the
                        // request caller's own stack. Every such reentry is a violation, but it
                        // must leave REQUESTING intact: only requestDrain may acknowledge that
                        // state after it returns, and no requester-stack invocation may close.
                        completion.recordOwnerContractViolation(new IllegalStateException(
                                "X6 lifecycle dispatcher invoked drain inline from requestDrain"));
                        return;
                    }
                    // A real lifecycle-owner worker is allowed to win this race. It may start
                    // after the host receives the request but before requestDrain returns; the
                    // acknowledgement below deliberately advances only if REQUESTING still owns
                    // the state, so it cannot overwrite this DRAINING/TERMINAL transition.
                    state = State.DRAINING;
                    requestingThread = null;
                    leaseToClose = lease;
                    sharedGenerationReceiptToClose = sharedGenerationReceipt;
                }
                case REQUESTED -> {
                    state = State.DRAINING;
                    leaseToClose = lease;
                    sharedGenerationReceiptToClose = sharedGenerationReceipt;
                }
                case TERMINAL -> {
                    terminalReplay = completion.ownerFailure();
                    if (terminalReplay == null) {
                        return;
                    }
                }
                case NEW, REGISTERING, ADMITTED -> {
                    completion.recordOwnerContractViolation(new IllegalStateException(
                            "X6 lifecycle dispatcher invoked drain before an owner request"));
                    state = State.ADMISSION_VIOLATION;
                    return;
                }
                case LEASE_ATTACHED -> {
                    completion.recordOwnerContractViolation(new IllegalStateException(
                            "X6 lifecycle dispatcher invoked drain before an owner request"));
                    return;
                }
                case DRAINING, CANCELLED, ADMISSION_VIOLATION -> {
                    return;
                }
            }
        }
        if (terminalReplay != null) {
            rethrowOnLifecycleOwner(terminalReplay);
            return;
        }

        Throwable providerFailure = null;
        try {
            Objects.requireNonNull(leaseToClose, "admitted drain requires one attached provider lease").close();
        } catch (Throwable failure) {
            providerFailure = failure;
        }
        Throwable sharedGenerationReceiptFailure = null;
        if (sharedGenerationReceiptToClose != null) {
            try {
                sharedGenerationReceiptToClose.close();
            } catch (Throwable failure) {
                sharedGenerationReceiptFailure = failure;
            }
        }
        Throwable observedFailure = selectFailure(providerFailure, sharedGenerationReceiptFailure);
        synchronized (this) {
            lease = null;
            sharedGenerationReceipt = null;
            completion.recordTerminal(observedFailure);
            completion.recordOwnerFailure(selectOwnerFailure(
                    completion.ownerContractFailure(), completion.requestFailure(), observedFailure));
            state = State.TERMINAL;
        }
        Throwable ownerFailure = completion.ownerFailure();
        if (ownerFailure != null) {
            rethrowOnLifecycleOwner(ownerFailure);
        }
    }

    private static Throwable cancel(X6LifecycleDrainDispatcher.Admission admission) {
        try {
            admission.cancel();
        } catch (Throwable ignored) {
            return ignored;
        }
        return null;
    }

    private static Throwable selectOwnerFailure(
            Throwable ownerContractFailure, Throwable requestFailure, Throwable providerFailure) {
        return selectFailure(selectFailure(ownerContractFailure, requestFailure), providerFailure);
    }

    private static Throwable selectFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) {
            return cleanup;
        }
        if (cleanup == null || cleanup == primary) {
            return primary;
        }
        Throwable selected = primary instanceof Error || !(cleanup instanceof Error) ? primary : cleanup;
        Throwable suppressed = selected == primary ? cleanup : primary;
        X6PreparedRenderPlanFactory.addSuppressedSafely(selected, suppressed, SUPPRESSION_APPENDER);
        return selected;
    }

    private static void rethrowOnLifecycleOwner(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("X6 lifecycle-owner lease drain failed", failure);
    }
}
