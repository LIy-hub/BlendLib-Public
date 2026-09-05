package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * One fully prepared candidate held from reload composition through one owner-controlled publication attempt.
 *
 * <p>The payload is deliberately closed: a candidate is either explicitly CPU-only or carries the one complete,
 * exact-generation resource aggregate that must enter D1 before the registry compare-and-set. It has no callback
 * bag, mutable resource slot, or post-publication extension point.</p>
 */
final class PendingGenerationTransaction {
    enum PayloadMode {
        CPU_ONLY,
        COMPLETE_SET
    }

    enum State {
        PREPARED_CALLER_OWNED,
        CLAIMED,
        POLICY_FROZEN,
        PUBLISHED,
        ABORTED_CALLER_OWNED,
        CLAIMED_ABORT,
        ABORTED
    }

    private final long generationId;
    private final ModelRegistryGeneration candidate;
    private final PayloadMode payloadMode;
    /** Immutable exact payload; state gates all access after its one ownership move. */
    private final CompletedGenerationResourceSet completeSet;
    /** Immutable CPU/LOD0 policy state that must enter the same D1 record before the one CAS. */
    private final X7PublishedGenerationProjection policyProjection;
    private State state;
    private Throwable abortCause;
    private boolean ownershipClaimed;

    private PendingGenerationTransaction(
            ModelRegistryGeneration candidate,
            PayloadMode payloadMode,
            CompletedGenerationResourceSet completeSet,
            X7PublishedGenerationProjection policyProjection) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.generationId = candidate.generationId();
        this.payloadMode = Objects.requireNonNull(payloadMode, "payloadMode");
        this.completeSet = completeSet;
        this.policyProjection = Objects.requireNonNull(policyProjection, "policyProjection");
        this.policyProjection.requireExactCandidate(candidate);
        if (payloadMode == PayloadMode.COMPLETE_SET) {
            CompletedGenerationResourceSet checkedSet = Objects.requireNonNull(completeSet, "complete set");
            if (checkedSet.exactGeneration() != candidate) {
                throw new IllegalArgumentException("A complete transaction payload must retain the exact candidate identity");
            }
        } else if (completeSet != null) {
            throw new IllegalArgumentException("A CPU-only transaction cannot retain a physical resource set");
        }
        this.state = State.PREPARED_CALLER_OWNED;
    }

    static PendingGenerationTransaction cpuOnly(ModelRegistryGeneration candidate) {
        ModelRegistryGeneration checkedCandidate = Objects.requireNonNull(candidate, "candidate");
        return cpuOnly(checkedCandidate, X7PublishedGenerationProjection.cpuOnly(checkedCandidate));
    }

    /** Package-private construction seam for a transaction-owned immutable projection test candidate. */
    static PendingGenerationTransaction cpuOnly(
            ModelRegistryGeneration candidate, X7PublishedGenerationProjection policyProjection) {
        return new PendingGenerationTransaction(candidate, PayloadMode.CPU_ONLY, null, policyProjection);
    }

    static PendingGenerationTransaction complete(
            ModelRegistryGeneration candidate, CompletedGenerationResourceSet completeSet) {
        ModelRegistryGeneration checkedCandidate = Objects.requireNonNull(candidate, "candidate");
        return complete(checkedCandidate, completeSet, X7PublishedGenerationProjection.cpuOnly(checkedCandidate));
    }

    /** Package-private handoff seam; the projection remains immutable and candidate-bound before D1 adoption. */
    static PendingGenerationTransaction complete(
            ModelRegistryGeneration candidate,
            CompletedGenerationResourceSet completeSet,
            X7PublishedGenerationProjection policyProjection) {
        return new PendingGenerationTransaction(candidate, PayloadMode.COMPLETE_SET, completeSet, policyProjection);
    }

    synchronized long generationId() {
        return generationId;
    }

    synchronized State state() {
        return state;
    }

    synchronized PayloadMode payloadMode() {
        return payloadMode;
    }

    synchronized Throwable abortCause() {
        return abortCause;
    }

    /** Snapshot used only to preallocate D1 bookkeeping before the one ownership claim. */
    synchronized PreclaimPayload preclaimPayload() {
        if (ownershipClaimed
                || state != State.PREPARED_CALLER_OWNED && state != State.ABORTED_CALLER_OWNED) {
            throw new IllegalStateException("Generation transaction cannot be preallocated from " + state);
        }
        return new PreclaimPayload(candidate, payloadMode, completeSet, policyProjection);
    }

    /**
     * Lets the caller stop an unclaimed transaction without moving a complete set out of caller ownership.
     * The owner may later take the one cleanup claim, but cannot publish it.
     */
    synchronized void abort(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        if (state != State.PREPARED_CALLER_OWNED) {
            throw new IllegalStateException("Generation transaction cannot abort from " + state);
        }
        state = State.ABORTED_CALLER_OWNED;
        abortCause = cause;
    }

    /**
     * The sole transaction/aggregate ownership linearization point.
     *
     * <p>For a complete payload, the aggregate's caller-to-claim transition happens while this transaction monitor
     * is held, before the immutable payload is exposed to the returned claim. A caller close that wins first makes
     * this method reject without changing the transaction state.</p>
     */
    synchronized Claim claimForResourceOwner() {
        if (ownershipClaimed
                || state != State.PREPARED_CALLER_OWNED && state != State.ABORTED_CALLER_OWNED) {
            throw new IllegalStateException("Generation transaction has already been claimed or finalized from " + state);
        }

        CompletedGenerationResourceSet claimedSet = null;
        if (payloadMode == PayloadMode.COMPLETE_SET) {
            claimedSet = completeSet;
            if (claimedSet == null
                    || claimedSet.tryClaimCallerOwnedCompleteForD1()
                            != CompletedGenerationResourceSet.ClaimMove.CLAIMED) {
                throw new IllegalStateException("The exact complete payload is no longer caller-owned");
            }
        }

        ownershipClaimed = true;
        boolean abortedBeforeClaim = state == State.ABORTED_CALLER_OWNED;
        state = abortedBeforeClaim ? State.CLAIMED_ABORT : State.CLAIMED;
        return new Claim(this, candidate, payloadMode, claimedSet, policyProjection, abortedBeforeClaim);
    }

    private synchronized ModelRegistryGeneration freezePolicy(Claim claim) {
        claim.requireOwner(this);
        if (state != State.CLAIMED) {
            throw new IllegalStateException("Generation transaction cannot freeze from " + state);
        }
        if (candidate.isRetired() || candidate.generationId() != generationId) {
            throw new IllegalStateException("Candidate is not publishable for generation " + generationId);
        }
        state = State.POLICY_FROZEN;
        return candidate;
    }

    private synchronized void abortFromClaim(Claim claim, Throwable cause) {
        claim.requireOwner(this);
        Objects.requireNonNull(cause, "cause");
        if (state != State.CLAIMED
                && state != State.POLICY_FROZEN
                && state != State.CLAIMED_ABORT
                && state != State.ABORTED) {
            throw new IllegalStateException("Claimed generation transaction cannot abort from " + state);
        }
        state = State.ABORTED;
        if (abortCause == null) {
            abortCause = cause;
        }
    }

    private synchronized void completePublication(Claim claim) {
        claim.requireOwner(this);
        if (state != State.POLICY_FROZEN) {
            throw new IllegalStateException("Generation transaction cannot publish from " + state);
        }
        state = State.PUBLISHED;
    }

    static final class Claim {
        private final PendingGenerationTransaction transaction;
        private final ModelRegistryGeneration candidate;
        private final PayloadMode payloadMode;
        private final CompletedGenerationResourceSet completeSet;
        private final X7PublishedGenerationProjection policyProjection;
        private final boolean abortedBeforeClaim;

        private Claim(
                PendingGenerationTransaction transaction,
                ModelRegistryGeneration candidate,
                PayloadMode payloadMode,
                CompletedGenerationResourceSet completeSet,
                X7PublishedGenerationProjection policyProjection,
                boolean abortedBeforeClaim) {
            this.transaction = transaction;
            this.candidate = candidate;
            this.payloadMode = payloadMode;
            this.completeSet = completeSet;
            this.policyProjection = policyProjection;
            this.abortedBeforeClaim = abortedBeforeClaim;
        }

        ModelRegistryGeneration candidate() {
            return candidate;
        }

        PayloadMode payloadMode() {
            return payloadMode;
        }

        CompletedGenerationResourceSet completeSet() {
            return completeSet;
        }

        X7PublishedGenerationProjection policyProjection() {
            return policyProjection;
        }

        boolean abortedBeforeClaim() {
            return abortedBeforeClaim;
        }

        Throwable abortCause() {
            return transaction.abortCause();
        }

        ModelRegistryGeneration freezePolicy() {
            return transaction.freezePolicy(this);
        }

        void abort(Throwable cause) {
            transaction.abortFromClaim(this, cause);
        }

        void completePublication() {
            transaction.completePublication(this);
        }

        /** Only the sole claimant may close an aggregate that D1 could not adopt after preallocation/insertion. */
        void closeClaimOwnedCompleteAfterD1InsertionFailure() {
            if (completeSet != null) {
                completeSet.closeFromClaimOwnerAfterD1InsertionFailure();
            }
        }

        private void requireOwner(PendingGenerationTransaction expectedTransaction) {
            if (transaction != expectedTransaction) {
                throw new IllegalStateException("Generation transaction claim does not own this transaction");
            }
        }
    }

    record PreclaimPayload(
            ModelRegistryGeneration candidate,
            PayloadMode payloadMode,
            CompletedGenerationResourceSet completeSet,
            X7PublishedGenerationProjection policyProjection) { }
}
