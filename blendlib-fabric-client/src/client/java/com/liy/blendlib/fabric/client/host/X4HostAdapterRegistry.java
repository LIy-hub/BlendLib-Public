package com.liy.blendlib.fabric.client.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Client-only X4 registration registry with exact opaque retirement receipts.
 *
 * <p>Registration uses an internal two-phase membership transaction. The registry first reserves
 * a target key as pending, then invokes the adapter-owned reservation without holding the registry
 * monitor, and finally activates the exact membership only if the operation is still current. A
 * direct adapter terminal transition revokes that exact membership before the adapter may become
 * terminal; the revocation removes the physical registry record rather than filtering a stale
 * adapter state at inspection time.</p>
 */
public final class X4HostAdapterRegistry implements AutoCloseable {
    private final Object monitor = new Object();
    private final Object receiptOwnerToken = new Object();
    private final List<Registration> registrations = new ArrayList<>();
    private boolean closed;
    private long revision;
    private long operationEpoch;
    private RegistrationOperation activeRegistration;
    private CloseCompletion closeCompletion;

    /**
     * Freezes and records one configuring adapter through an X4-owned lifecycle participant.
     *
     * <p>Ordinary public adapters can migrate without implementing package-private mechanics by
     * transferring their lifecycle calls to {@link X4ManagedHostAdapter#takeOwnership(X4HostAdapter)}.
     * A raw adapter cannot safely participate because the registry cannot intercept its independent
     * terminal transition.</p>
     */
    public <F extends X4HostFrame> X4HostRegistrationReceipt<F> register(X4HostAdapter<F> adapter) {
        X4HostAdapter<F> checked = Objects.requireNonNull(adapter, "adapter");
        RegistrationOperation operation;
        synchronized (monitor) {
            requireOpen();
            if (activeRegistration != null) {
                activeRegistration.invalidated = true;
                throw new IllegalStateException("X4 host registration cannot re-enter an active registration operation");
            }
            operation = new RegistrationOperation(++operationEpoch);
            activeRegistration = operation;
        }

        X4HostRegistrationReservation<F> reservation = null;
        Membership membership = null;
        boolean reservationCommitted = false;
        X4HostRegistrationReceipt<F> result = null;
        Throwable outcome = null;
        try {
            // Every adapter-owned operation is outside the registry monitor. This preserves the
            // no-ABBA rule when terminal lifecycle code revokes membership through this registry.
            reservation = freezeAndReserveRegistration(checked);
            X4HostSpec<F> candidate = Objects.requireNonNull(reservation.specification(), "reservation.specification");
            synchronized (monitor) {
                requireOpen();
                requireCurrentRegistration(operation);
                if (operation.invalidated) {
                    throw new IllegalStateException("X4 host registration operation was invalidated before commit");
                }
                requireNoDuplicateLocked(candidate);
                membership = new Membership(++revision);
                registrations.add(new Registration(checked, candidate, membership.revision(), membership));
            }

            // Commit stores the registry-owned membership on the adapter's own lifecycle side. It
            // must not invoke user code or retain a registry monitor.
            reservation.commit(membership);
            reservationCommitted = true;

            synchronized (monitor) {
                requireOpen();
                requireCurrentRegistration(operation);
                if (operation.invalidated || !activateMembershipLocked(membership)) {
                    throw new IllegalStateException("X4 host registration was revoked before activation");
                }
                result = new X4HostRegistrationReceipt<>(
                        checked, membership.revision(), receiptOwnerToken, membership);
            }
        } catch (Throwable failure) {
            outcome = failure;
        } finally {
            try {
                if (result == null) {
                    if (membership != null) {
                        try {
                            membership.revoke();
                        } catch (Throwable revokeFailure) {
                            outcome = X4HostFailureSelector.select(outcome, revokeFailure);
                        }
                    }
                    if (!reservationCommitted && reservation != null) {
                        try {
                            reservation.abort();
                        } catch (Throwable abortFailure) {
                            outcome = X4HostFailureSelector.select(outcome, abortFailure);
                        }
                    }
                }
            } finally {
                // This nested finally is deliberately unconditional: an adapter abort failure
                // must never retain the registry operation sentinel and poison future attempts.
                synchronized (monitor) {
                    if (activeRegistration == operation) {
                        activeRegistration = null;
                    }
                }
            }
        }
        X4HostFailureSelector.rethrow(outcome, "X4 host registration failed");
        return Objects.requireNonNull(result, "successful X4 registration must produce a receipt");
    }

    @SuppressWarnings("unchecked")
    private static <F extends X4HostFrame> X4HostRegistrationReservation<F> freezeAndReserveRegistration(
            X4HostAdapter<F> adapter) {
        if (adapter instanceof X4HostRegistrationLifecycle<?> lifecycle) {
            return ((X4HostRegistrationLifecycle<F>) lifecycle).freezeAndReserveRegistration();
        }
        if (adapter instanceof X4ManagedHostAdapter<?> managed) {
            return ((X4ManagedHostAdapter<F>) managed).freezeAndReserveRegistration();
        }
        throw new IllegalStateException(
                "X4 host registration requires a factory lifecycle or "
                        + "X4ManagedHostAdapter.takeOwnership(adapter) public migration handle");
    }

    /** Immutable inspection snapshot; this does not expose a mutable registry collection. */
    public List<X4HostSpec<?>> registrations() {
        synchronized (monitor) {
            return specificationsLocked();
        }
    }

    /**
     * Atomically removes and retires only the exact registration represented by {@code receipt}.
     *
     * @return {@code true} when this receipt still owns a live registration, otherwise false
     */
    public boolean retire(X4HostRegistrationReceipt<?> receipt) {
        X4HostRegistrationReceipt<?> checked = Objects.requireNonNull(receipt, "receipt");
        X4HostAdapter<?> removed = null;
        synchronized (monitor) {
            requireOpen();
            if (checked.ownerToken() != receiptOwnerToken) {
                return false;
            }
            for (Registration registration : List.copyOf(registrations)) {
                if (registration.revision() == checked.revision()
                        && registration.adapter() == checked.adapter()
                        && registration.membership() == checked.membershipToken()
                        && revokeMembershipLocked(registration.membership())) {
                    removed = registration.adapter();
                    break;
                }
            }
        }
        if (removed != null) {
            removed.retire();
            return true;
        }
        return false;
    }

    /**
     * Idempotently closes every adapter through one shared completion.
     *
     * <p>The first caller revokes every pending or active membership while holding only this
     * registry monitor, releases it, and then attempts every adapter close. Concurrent and later
     * callers await the same completion and receive the same aggregate or fatal identity.</p>
     */
    @Override
    public void close() {
        CloseCompletion completion;
        List<X4HostAdapter<?>> toClose = null;
        boolean owner = false;
        synchronized (monitor) {
            if (closeCompletion == null) {
                closed = true;
                if (activeRegistration != null) {
                    activeRegistration.invalidated = true;
                }
                toClose = revokeAllLocked();
                completion = new CloseCompletion(Thread.currentThread());
                closeCompletion = completion;
                owner = true;
            } else {
                completion = closeCompletion;
            }
        }

        if (!owner) {
            if (completion.ownerThread == Thread.currentThread()) {
                return;
            }
            awaitCloseCompletion(completion);
            return;
        }

        Throwable outcome = null;
        try {
            outcome = closeEveryAdapter(toClose);
        } catch (Throwable unexpected) {
            outcome = unexpected;
        } finally {
            completion.complete(outcome);
        }
        rethrowCloseOutcome(outcome);
    }

    private void requireNoDuplicateLocked(X4HostSpec<?> candidate) {
        for (Registration registered : registrations) {
            X4HostSpec<?> registeredSpec = registered.specification();
            if (registeredSpec.hostKind() == candidate.hostKind()
                    && registeredSpec.identity().equals(candidate.identity())) {
                throw new IllegalStateException("Duplicate or conflicting X4 host target: "
                        + candidate.hostKind() + " " + candidate.identity());
            }
        }
    }

    private boolean activateMembershipLocked(Membership membership) {
        if (closed || membership.revoked || membership.active) {
            return false;
        }
        for (Registration registration : registrations) {
            if (registration.membership() == membership) {
                membership.active = true;
                return true;
            }
        }
        return false;
    }

    private boolean revokeMembershipLocked(Membership membership) {
        if (membership.revoked) {
            return false;
        }
        membership.revoked = true;
        membership.active = false;
        for (int index = 0; index < registrations.size(); index++) {
            if (registrations.get(index).membership() == membership) {
                registrations.remove(index);
                return true;
            }
        }
        return false;
    }

    private List<X4HostAdapter<?>> revokeAllLocked() {
        List<X4HostAdapter<?>> adapters = new ArrayList<>(registrations.size());
        for (Registration registration : List.copyOf(registrations)) {
            if (revokeMembershipLocked(registration.membership())) {
                adapters.add(registration.adapter());
            }
        }
        return List.copyOf(adapters);
    }

    private Throwable closeEveryAdapter(List<X4HostAdapter<?>> toClose) {
        Throwable firstNormal = null;
        Throwable firstFatal = null;
        List<Throwable> failures = new ArrayList<>();
        for (X4HostAdapter<?> adapter : toClose) {
            try {
                adapter.close();
            } catch (Throwable failure) {
                failures.add(failure);
                if (X4HostFailureSelector.isFatal(failure)) {
                    if (firstFatal == null) {
                        firstFatal = failure;
                    }
                } else if (firstNormal == null) {
                    firstNormal = failure;
                }
            }
        }
        if (firstFatal != null) {
            suppressOthers(firstFatal, failures);
            return firstFatal;
        }
        if (firstNormal != null) {
            suppressOthers(firstNormal, failures);
            return new IllegalStateException("One or more X4 host adapters failed during registry close", firstNormal);
        }
        return null;
    }

    private void awaitCloseCompletion(CloseCompletion completion) {
        try {
            completion.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for active X4 host registry close", exception);
        }
        rethrowCloseOutcome(completion.outcome());
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The X4 host adapter registry is closed");
        }
    }

    private void requireCurrentRegistration(RegistrationOperation operation) {
        if (activeRegistration != operation || operation.epoch != operationEpoch) {
            throw new IllegalStateException("X4 host registration operation identity changed before commit");
        }
    }

    private List<X4HostSpec<?>> specificationsLocked() {
        List<X4HostSpec<?>> specifications = new ArrayList<>(registrations.size());
        for (Registration registration : registrations) {
            if (registration.membership().active) {
                specifications.add(registration.specification());
            }
        }
        return List.copyOf(specifications);
    }

    private static void suppressOthers(Throwable primary, List<Throwable> failures) {
        for (Throwable failure : failures) {
            if (failure != primary) {
                try {
                    primary.addSuppressed(failure);
                } catch (Throwable ignored) {
                    // Completion identity and all close attempts matter more than best-effort metadata.
                }
            }
        }
    }

    private static void rethrowCloseOutcome(Throwable outcome) {
        if (outcome == null) {
            return;
        }
        X4HostFailureSelector.rethrow(outcome, "X4 host registry close failed");
    }

    private record Registration(
            X4HostAdapter<?> adapter,
            X4HostSpec<?> specification,
            long revision,
            Membership membership) {
        private Registration {
            adapter = Objects.requireNonNull(adapter, "adapter");
            specification = Objects.requireNonNull(specification, "specification");
            if (revision <= 0L) {
                throw new IllegalArgumentException("registration revision must be positive");
            }
            membership = Objects.requireNonNull(membership, "membership");
        }
    }

    private final class Membership implements X4HostRegistrationMembership {
        private final long membershipRevision;
        private boolean active;
        private boolean revoked;

        private Membership(long membershipRevision) {
            if (membershipRevision <= 0L) {
                throw new IllegalArgumentException("membership revision must be positive");
            }
            this.membershipRevision = membershipRevision;
        }

        @Override
        public long revision() {
            return membershipRevision;
        }

        @Override
        public void revoke() {
            synchronized (monitor) {
                revokeMembershipLocked(this);
            }
        }
    }

    private static final class RegistrationOperation {
        private final long epoch;
        private boolean invalidated;

        private RegistrationOperation(long epoch) {
            this.epoch = epoch;
        }
    }

    private static final class CloseCompletion {
        private final Thread ownerThread;
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile Throwable outcome;

        private CloseCompletion(Thread ownerThread) {
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        }

        private void complete(Throwable closeOutcome) {
            outcome = closeOutcome;
            completed.countDown();
        }

        private void await() throws InterruptedException {
            completed.await();
        }

        private Throwable outcome() {
            return outcome;
        }
    }
}
