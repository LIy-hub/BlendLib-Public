package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * Public migration handle for an externally implemented {@link X4HostAdapter}.
 *
 * <p>The handle is a versioned experimental SPI rather than an exposed registry-reservation SPI.
 * {@link #takeOwnership(X4HostAdapter)} transfers all subsequent lifecycle calls to the handle.
 * It installs the same internal reservation gate used by factory adapters, so a registry can revoke
 * an exact membership before this handle delegates a terminal transition. Retaining and calling the
 * raw delegate after transfer violates this explicit ownership contract and is deliberately not
 * treated as a safe registration path. A managed handle cannot be transferred again: nesting a
 * managed handle would split one delegate's lifecycle ownership across two terminal coordinators.</p>
 */
@ExperimentalBlendLibSpi(protocol = X4ManagedHostAdapter.REGISTRATION_PROTOCOL)
public final class X4ManagedHostAdapter<F extends X4HostFrame> implements X4HostAdapter<F> {
    /** Version of the public managed-registration migration protocol. */
    public static final String REGISTRATION_PROTOCOL = "1.0.0";

    private static final Object CLAIM_MONITOR = new Object();
    private static final IdentityHashMap<X4HostAdapter<?>, X4ManagedHostAdapter<?>> CLAIMED_DELEGATES = new IdentityHashMap<>();

    private final Object monitor = new Object();
    private final X4HostAdapter<F> delegate;
    private ManagedReservation activeReservation;
    private X4HostRegistrationMembership membership;
    private TerminalTransition terminalTransition;
    private Throwable terminalFailure;
    private boolean retired;
    private boolean closed;

    private X4ManagedHostAdapter(X4HostAdapter<F> delegate) {
        this.delegate = delegate;
    }

    /**
     * Transfers lifecycle ownership of a public legacy adapter to an X4-managed registration handle.
     *
     * @throws IllegalStateException when this delegate already has a managed owner or is itself a managed handle
     */
    public static <F extends X4HostFrame> X4ManagedHostAdapter<F> takeOwnership(X4HostAdapter<F> delegate) {
        X4HostAdapter<F> checked = Objects.requireNonNull(delegate, "delegate");
        if (checked instanceof X4ManagedHostAdapter<?>) {
            throw new IllegalStateException("An X4 managed host adapter cannot be transferred again");
        }
        X4ManagedHostAdapter<F> managed;
        synchronized (CLAIM_MONITOR) {
            if (CLAIMED_DELEGATES.containsKey(checked)) {
                throw new IllegalStateException("An X4 host adapter already has a managed registration owner");
            }
            managed = new X4ManagedHostAdapter<>(checked);
            CLAIMED_DELEGATES.put(checked, managed);
        }
        // This can invoke an externally implemented CompletionStage immediately, so it must stay
        // outside the global claim monitor. The put above makes concurrent ownership attempts fail
        // closed until a completed delegate releases its claim.
        try {
            managed.observeDelegateCompletion();
        } catch (Throwable failure) {
            managed.releaseClaim();
            X4HostFailureSelector.rethrow(failure, "Managed X4 ownership completion observation failed");
            throw new AssertionError("unreachable");
        }
        return managed;
    }

    private void observeDelegateCompletion() {
        delegate.drainCompletion().whenComplete((ignored, failure) -> releaseClaim());
    }

    @Override
    public X4HostSpec<F> configure() {
        return delegate.configure();
    }

    @Override
    public X4HostLifecycleState state() {
        return delegate.state();
    }

    @Override
    public X4HostLeaseDiagnostics leaseDiagnostics() {
        return delegate.leaseDiagnostics();
    }

    @Override
    public CompletionStage<X4HostLifecycleState> drainCompletion() {
        return delegate.drainCompletion();
    }

    @Override
    public void freeze() {
        synchronized (monitor) {
            requireNoReservationLocked("freeze");
            requireNoTerminalTransitionLocked("freeze");
        }
        delegate.freeze();
    }

    /**
     * Package-private registry bridge. The public managed-handle contract deliberately does not
     * expose an internal reservation type or lifecycle marker to external callers.
     */
    X4HostRegistrationReservation<F> freezeAndReserveRegistration() {
        ManagedReservation reservation;
        synchronized (monitor) {
            requireNoReservationLocked("register");
            requireNoTerminalTransitionLocked("register");
            if (membership != null || retired || closed) {
                throw new IllegalStateException("A managed X4 adapter cannot register after a terminal lifecycle request");
            }
            reservation = new ManagedReservation(Thread.currentThread());
            activeReservation = reservation;
        }
        try {
            // The delegate calls are outside this monitor. Concurrent managed close/retire sees the
            // reservation and waits, while an owner-thread callback fails closed instead of waiting.
            X4HostSpec<F> specification = Objects.requireNonNull(delegate.configure(), "delegate.configure");
            delegate.freeze();
            synchronized (monitor) {
                reservation.setSpecificationLocked(specification);
            }
            return reservation;
        } catch (Throwable failure) {
            Throwable cleanupFailure = null;
            try {
                reservation.abortAfterAcquisitionFailure();
            } catch (Throwable abortFailure) {
                cleanupFailure = abortFailure;
            }
            X4HostFailureSelector.rethrow(
                    X4HostFailureSelector.select(failure, cleanupFailure),
                    "Managed X4 host registration acquisition failed");
            throw new AssertionError("unreachable");
        }
    }

    @Override
    public X4PreparedSnapshot prepare(F frame) {
        synchronized (monitor) {
            requireNoReservationLocked("prepare");
            requireNoTerminalTransitionLocked("prepare");
        }
        return delegate.prepare(frame);
    }

    @Override
    public void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context) {
        synchronized (monitor) {
            requireNoReservationLocked("submit");
            requireNoTerminalTransitionLocked("submit");
        }
        delegate.submit(prepared, context);
    }

    @Override
    public void retire() {
        terminal(false);
    }

    @Override
    public void close() {
        terminal(true);
    }

    private void terminal(boolean closeRequest) {
        for (;;) {
            ManagedReservation reservation = null;
            TerminalTransition transition = null;
            Throwable publishedFailure = null;
            boolean owner = false;
            synchronized (monitor) {
                if (terminalFailure != null) {
                    publishedFailure = terminalFailure;
                } else {
                    reservation = activeReservation;
                    if (reservation != null) {
                        reservation.requireNonOwner(closeRequest ? "close" : "retire");
                    } else {
                        transition = terminalTransition;
                        if (transition == null) {
                            if ((closeRequest && closed) || (!closeRequest && (retired || closed))) {
                                return;
                            }
                            transition = new TerminalTransition(Thread.currentThread(), membership, closeRequest);
                            terminalTransition = transition;
                            owner = true;
                        } else if (!transition.completedLocked()) {
                            transition.requireNonOwnerLocked(closeRequest ? "close" : "retire");
                            transition.requestCloseLocked(closeRequest);
                        }
                    }
                }
            }
            if (publishedFailure != null) {
                X4HostFailureSelector.rethrow(
                        publishedFailure, "Managed X4 host terminal transition failed");
                return;
            }
            if (reservation != null) {
                awaitReservation(reservation, closeRequest ? "close" : "retire");
                continue;
            }
            if (owner) {
                executeTerminalTransition(Objects.requireNonNull(transition));
                return;
            }
            awaitTerminalTransition(Objects.requireNonNull(transition), closeRequest ? "close" : "retire");
            X4HostFailureSelector.rethrow(
                    transition.outcome(), "Managed X4 host terminal transition failed");
            return;
        }
    }

    private void executeTerminalTransition(TerminalTransition transition) {
        X4HostRegistrationMembership transitionMembership = transition.membership();
        Throwable outcome = null;
        boolean dispatchClose = false;
        try {
            if (transitionMembership != null) {
                transitionMembership.revoke();
            }
            synchronized (monitor) {
                if (membership == transitionMembership) {
                    membership = null;
                }
                dispatchClose = transition.sealDispatchLocked();
            }
            if (dispatchClose) {
                delegate.close();
            } else {
                delegate.retire();
            }
        } catch (Throwable failure) {
            outcome = canonicalTerminalOutcome(failure);
        } finally {
            synchronized (monitor) {
                if (outcome == null) {
                    retired = true;
                    if (dispatchClose) {
                        closed = true;
                    }
                } else {
                    terminalFailure = outcome;
                }
                transition.publishLocked(outcome);
                if (terminalTransition == transition) {
                    terminalTransition = null;
                }
            }
        }
        X4HostFailureSelector.rethrow(
                transition.outcome(), "Managed X4 host terminal transition failed");
    }

    /**
     * Converts the selector's checked-failure wrapper once, before it becomes the shared terminal
     * result. Re-running {@link X4HostFailureSelector#rethrow(Throwable, String)} per waiter would
     * otherwise allocate a different wrapper for the same checked delegate failure.
     */
    private static Throwable canonicalTerminalOutcome(Throwable failure) {
        try {
            X4HostFailureSelector.rethrow(failure, "Managed X4 host terminal transition failed");
            return null;
        } catch (Throwable observable) {
            return observable;
        }
    }

    private void requireNoReservationLocked(String operation) {
        if (activeReservation != null) {
            throw new IllegalStateException("Managed X4 host " + operation + " cannot cross an active registration reservation");
        }
    }

    private void requireNoTerminalTransitionLocked(String operation) {
        if (terminalTransition != null || terminalFailure != null || retired || closed) {
            throw new IllegalStateException("Managed X4 host " + operation + " cannot cross a terminal lifecycle transition");
        }
    }

    private void awaitReservation(ManagedReservation reservation, String operation) {
        try {
            reservation.awaitCompletion();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting to " + operation + " a managed X4 registration reservation", exception);
        }
    }

    private void awaitTerminalTransition(TerminalTransition transition, String operation) {
        try {
            transition.awaitCompletion();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting to " + operation + " a managed X4 terminal transition", exception);
        }
    }

    private void releaseClaim() {
        synchronized (CLAIM_MONITOR) {
            if (CLAIMED_DELEGATES.get(delegate) == this) {
                CLAIMED_DELEGATES.remove(delegate);
            }
        }
    }

    private final class ManagedReservation implements X4HostRegistrationReservation<F> {
        private final Thread ownerThread;
        private final CountDownLatch completed = new CountDownLatch(1);
        private X4HostSpec<F> specification;
        private boolean complete;

        private ManagedReservation(Thread ownerThread) {
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        }

        @Override
        public X4HostSpec<F> specification() {
            synchronized (monitor) {
                if (specification == null || activeReservation != this || complete) {
                    throw new IllegalStateException("Managed X4 registration reservation is not ready");
                }
                return specification;
            }
        }

        @Override
        public void commit(X4HostRegistrationMembership registrationMembership) {
            synchronized (monitor) {
                requireCurrentLocked();
                membership = Objects.requireNonNull(registrationMembership, "registrationMembership");
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

        private void setSpecificationLocked(X4HostSpec<F> checkedSpecification) {
            if (activeReservation != this || complete || specification != null) {
                throw new IllegalStateException("Managed X4 registration reservation is no longer active");
            }
            specification = checkedSpecification;
        }

        private void abortAfterAcquisitionFailure() {
            synchronized (monitor) {
                if (activeReservation == this && !complete) {
                    completeLocked();
                }
            }
        }

        private void requireCurrentLocked() {
            if (activeReservation != this || complete || specification == null) {
                throw new IllegalStateException("Managed X4 registration reservation is no longer active");
            }
        }

        private void completeLocked() {
            activeReservation = null;
            complete = true;
            completed.countDown();
        }

        private void requireNonOwner(String operation) {
            if (ownerThread == Thread.currentThread()) {
                throw new IllegalStateException(
                        "Managed X4 registration owner cannot " + operation + " while its reservation is active");
            }
        }

        private void awaitCompletion() throws InterruptedException {
            completed.await();
        }
    }

    private static final class TerminalTransition {
        private Thread ownerThread;
        private X4HostRegistrationMembership membership;
        private final CountDownLatch completed = new CountDownLatch(1);
        private boolean closeRequested;
        private boolean dispatchSealed;
        private boolean completedResult;
        private volatile Throwable outcome;

        private TerminalTransition(
                Thread ownerThread, X4HostRegistrationMembership membership, boolean closeRequest) {
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
            this.membership = membership;
            this.closeRequested = closeRequest;
        }

        private boolean completedLocked() {
            return completedResult;
        }

        private X4HostRegistrationMembership membership() {
            return membership;
        }

        private void requestCloseLocked(boolean closeRequest) {
            if (closeRequest && !dispatchSealed) {
                closeRequested = true;
            }
        }

        private void requireNonOwnerLocked(String operation) {
            if (ownerThread == Thread.currentThread()) {
                throw new IllegalStateException(
                        "Managed X4 terminal owner cannot " + operation + " while its transaction is active");
            }
        }

        private boolean sealDispatchLocked() {
            if (dispatchSealed) {
                throw new IllegalStateException("Managed X4 terminal transition dispatch is already sealed");
            }
            dispatchSealed = true;
            return closeRequested;
        }

        private void publishLocked(Throwable terminalOutcome) {
            if (completedResult) {
                throw new IllegalStateException("Managed X4 terminal transition outcome is already published");
            }
            outcome = terminalOutcome;
            completedResult = true;
            // The published result remains observable forever, but a completed transaction no
            // longer needs to retain its execution thread or a successfully revoked registry node.
            ownerThread = null;
            membership = null;
            completed.countDown();
        }

        private void awaitCompletion() throws InterruptedException {
            completed.await();
        }

        private Throwable outcome() {
            return outcome;
        }
    }
}
