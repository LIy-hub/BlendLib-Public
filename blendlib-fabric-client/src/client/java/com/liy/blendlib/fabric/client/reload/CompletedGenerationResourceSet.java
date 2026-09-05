package com.liy.blendlib.fabric.client.reload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One immutable, non-empty, exact-generation collection of completed physical resource leaves.
 *
 * <p>The aggregate is the only caller-owned resource atom that can later enter D1. Its totals are
 * always checked sums of its leaves; callers cannot provide a separate aggregate count, byte total,
 * or close callback. This T2a1 type deliberately retains no generation/lease/fence lifecycle
 * authority beyond caller-versus-D1 ownership of this already-complete aggregate.</p>
 */
final class CompletedGenerationResourceSet {
    enum Ownership {
        CALLER_OWNED_COMPLETE,
        CLAIM_OWNED_COMPLETE,
        D1_OWNED,
        D1_CLOSING,
        D1_CLOSE_FAILED,
        D1_CLOSED,
        CLAIM_CLOSING,
        CLAIM_CLOSE_FAILED,
        CLAIM_CLOSED,
        CALLER_CLOSING,
        CALLER_CLOSE_FAILED,
        CALLER_CLOSED
    }

    /** Result of the first, caller-to-transaction ownership hand-off. */
    enum ClaimMove {
        CLAIMED,
        NOT_CALLER_OWNED_COMPLETE
    }

    /** Result of the second, transaction-to-D1 ownership hand-off. */
    enum D1Adoption {
        ADOPTED,
        NOT_CLAIM_OWNED_COMPLETE
    }

    /** One complete key-owned physical leaf. A leaf never owns an independent generation lifecycle. */
    abstract static class ResourceLeaf {
        abstract X7GpuGenerationKey key();

        abstract int physicalResourceCount();

        abstract long physicalByteCount();

        abstract void closeSynchronouslyAfterVerifiedD1Completion();
    }

    @FunctionalInterface
    interface PhysicalLeafClose {
        void closeSynchronouslyAfterVerifiedD1Completion();
    }

    private ModelRegistryGeneration exactGeneration;
    private final List<ResourceLeaf> leaves;
    private List<ResourceLeaf> pendingCloseLeaves;
    private final int physicalResourceCount;
    private final long physicalByteCount;
    private Ownership ownership = Ownership.CALLER_OWNED_COMPLETE;

    private CompletedGenerationResourceSet(ModelRegistryGeneration exactGeneration, List<ResourceLeaf> callerOwnedLeaves) {
        this.exactGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
        if (callerOwnedLeaves.isEmpty()) {
            throw new IllegalArgumentException("A completed generation resource set must be non-empty");
        }

        TreeMap<X7GpuGenerationKey, ResourceLeaf> byKey = new TreeMap<>(X7GpuGenerationKey.deterministicOrder());
        int derivedResourceCount = 0;
        long derivedByteCount = 0L;
        for (ResourceLeaf leaf : callerOwnedLeaves) {
            ResourceLeaf checkedLeaf = Objects.requireNonNull(leaf, "resource leaf");
            X7GpuGenerationKey key = Objects.requireNonNull(checkedLeaf.key(), "resource leaf key");
            if (key.generation() != exactGeneration.generationId()) {
                throw new IllegalArgumentException("A resource leaf must belong to the exact aggregate generation id");
            }
            if (byKey.putIfAbsent(key, checkedLeaf) != null) {
                throw new IllegalArgumentException("A completed generation resource set cannot contain duplicate keys");
            }
            int leafCount = checkedLeaf.physicalResourceCount();
            long leafBytes = checkedLeaf.physicalByteCount();
            if (leafCount <= 0 || leafBytes < 0L) {
                throw new IllegalArgumentException("A completed generation resource leaf must have positive resources and non-negative bytes");
            }
            derivedResourceCount = Math.addExact(derivedResourceCount, leafCount);
            derivedByteCount = Math.addExact(derivedByteCount, leafBytes);
        }
        this.leaves = List.copyOf(byKey.values());
        this.pendingCloseLeaves = new ArrayList<>(leaves);
        this.physicalResourceCount = derivedResourceCount;
        this.physicalByteCount = derivedByteCount;
    }

    static CompletedGenerationResourceSet complete(
            ModelRegistryGeneration exactGeneration, List<? extends ResourceLeaf> callerOwnedLeaves) {
        Objects.requireNonNull(exactGeneration, "exactGeneration");
        List<ResourceLeaf> suppliedLeaves = new ArrayList<>();
        try {
            for (ResourceLeaf leaf : Objects.requireNonNull(callerOwnedLeaves, "callerOwnedLeaves")) {
                suppliedLeaves.add(leaf);
            }
            return new CompletedGenerationResourceSet(exactGeneration, suppliedLeaves);
        } catch (Throwable failure) {
            throw rethrowAfterClosingCallerOwnedLeaves(failure, suppliedLeaves);
        }
    }

    /** Package-private fake-leaf seam for D1 ownership tests; aggregate totals still derive from it. */
    static ResourceLeaf leaf(
            X7GpuGenerationKey key,
            int physicalResourceCount,
            long physicalByteCount,
            PhysicalLeafClose physicalClose) {
        return new CallbackLeaf(key, physicalResourceCount, physicalByteCount, physicalClose);
    }

    synchronized ModelRegistryGeneration exactGeneration() {
        return exactGeneration;
    }

    int physicalResourceCount() {
        return physicalResourceCount;
    }

    long physicalByteCount() {
        return physicalByteCount;
    }

    List<X7GpuGenerationKey> keysInDeterministicOrder() {
        return leaves.stream().map(ResourceLeaf::key).toList();
    }

    /**
     * Package-private deterministic identity view used only while D1 creates its one policy-resource record.
     *
     * <p>The returned list is immutable and preserves the aggregate's canonical key order. It is deliberately a
     * view, not an attachment seam: callers cannot add, replace, close, or otherwise resurrect a resource after
     * the aggregate has entered its claim/D1 ownership path.</p>
     */
    List<ResourceLeaf> leavesInDeterministicOrder() {
        return leaves;
    }

    synchronized Ownership ownership() {
        return ownership;
    }

    /**
     * Moves the completed aggregate from its creator to the in-flight D1 transaction claim.
     *
     * <p>The claim is intentionally one-way: after it wins, the creator can no longer close the
     * set or replay its close. The claimant must either adopt it into D1 or explicitly close it
     * after its own pre-allocation/insertion failure.</p>
     */
    synchronized ClaimMove tryClaimCallerOwnedCompleteForD1() {
        if (ownership != Ownership.CALLER_OWNED_COMPLETE) {
            return ClaimMove.NOT_CALLER_OWNED_COMPLETE;
        }
        ownership = Ownership.CLAIM_OWNED_COMPLETE;
        return ClaimMove.CLAIMED;
    }

    /** Moves a previously claimed complete aggregate into D1 ownership exactly once. */
    synchronized D1Adoption tryAdoptClaimOwnedCompleteByD1() {
        if (ownership != Ownership.CLAIM_OWNED_COMPLETE) {
            return D1Adoption.NOT_CLAIM_OWNED_COMPLETE;
        }
        ownership = Ownership.D1_OWNED;
        return D1Adoption.ADOPTED;
    }

    /**
     * Closes a claimed aggregate when D1 transaction pre-allocation or insertion fails before
     * adoption. This is deliberately unavailable to the former caller.
     */
    void closeFromClaimOwnerAfterD1InsertionFailure() {
        List<ResourceLeaf> closeAttempt;
        synchronized (this) {
            if (ownership != Ownership.CLAIM_OWNED_COMPLETE && ownership != Ownership.CLAIM_CLOSE_FAILED) {
                throw new IllegalStateException("Only the claim owner may close before D1 adoption");
            }
            ownership = Ownership.CLAIM_CLOSING;
            closeAttempt = List.copyOf(pendingCloseLeaves);
        }
        closeAttempt(closeAttempt, Ownership.CLAIM_CLOSE_FAILED, Ownership.CLAIM_CLOSED);
    }

    void closeFromD1VerifiedCompletion() {
        List<ResourceLeaf> closeAttempt;
        synchronized (this) {
            if (ownership != Ownership.D1_OWNED && ownership != Ownership.D1_CLOSE_FAILED) {
                throw new IllegalStateException("Only D1 may close a transferred generation resource set");
            }
            ownership = Ownership.D1_CLOSING;
            closeAttempt = List.copyOf(pendingCloseLeaves);
        }
        closeAttempt(closeAttempt, Ownership.D1_CLOSE_FAILED, Ownership.D1_CLOSED);
    }

    boolean closeIfStillCallerOwned() {
        List<ResourceLeaf> closeAttempt;
        synchronized (this) {
            if (ownership != Ownership.CALLER_OWNED_COMPLETE && ownership != Ownership.CALLER_CLOSE_FAILED) {
                return false;
            }
            ownership = Ownership.CALLER_CLOSING;
            closeAttempt = List.copyOf(pendingCloseLeaves);
        }
        closeAttempt(closeAttempt, Ownership.CALLER_CLOSE_FAILED, Ownership.CALLER_CLOSED);
        return true;
    }

    private void closeAttempt(List<ResourceLeaf> closeAttempt, Ownership failedOwnership, Ownership closedOwnership) {
        List<ResourceLeaf> failedLeaves = new ArrayList<>();
        CleanupFailureSelector failures = new CleanupFailureSelector();
        for (ResourceLeaf leaf : closeAttempt) {
            try {
                leaf.closeSynchronouslyAfterVerifiedD1Completion();
            } catch (Throwable leafFailure) {
                failedLeaves.add(leaf);
                failures.record(leafFailure);
            }
        }
        Throwable failure = failures.failureOrNull();
        synchronized (this) {
            pendingCloseLeaves = failedLeaves;
            if (failure == null) {
                ownership = closedOwnership;
                exactGeneration = null;
            } else {
                ownership = failedOwnership;
            }
        }
        if (failure != null) {
            throw propagatePhysicalCloseFailure(failure);
        }
    }

    private static RuntimeException propagatePhysicalCloseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Synchronous physical resource close failed", failure);
    }

    private static RuntimeException rethrowAfterClosingCallerOwnedLeaves(
            Throwable failure, List<? extends ResourceLeaf> callerOwnedLeaves) {
        CleanupFailureSelector cleanupFailures = new CleanupFailureSelector();
        IdentityHashMap<ResourceLeaf, Boolean> closed = new IdentityHashMap<>();
        for (ResourceLeaf leaf : callerOwnedLeaves) {
            if (leaf == null || closed.put(leaf, Boolean.TRUE) != null) {
                continue;
            }
            try {
                leaf.closeSynchronouslyAfterVerifiedD1Completion();
            } catch (Throwable closeFailure) {
                cleanupFailures.record(closeFailure);
            }
        }
        cleanupFailures.rethrowWithPrimary(failure);
        throw new AssertionError("unreachable");
    }

    static void addSuppressed(Throwable target, Throwable suppressed) {
        if (target != null && suppressed != null && target != suppressed) {
            target.addSuppressed(suppressed);
        }
    }

    static void throwUnchecked(Throwable failure) {
        CompletedGenerationResourceSet.<RuntimeException>throwUnchecked0(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked0(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * One cleanup selector used by aggregate construction, aggregate close retry, and bridge
     * composition. A fatal cleanup failure outranks a non-fatal primary while retaining the exact
     * original objects and deterministic suppression order.
     */
    static final class CleanupFailureSelector {
        private final List<Throwable> cleanupFailures = new ArrayList<>();

        void record(Throwable failure) {
            cleanupFailures.add(Objects.requireNonNull(failure, "cleanup failure"));
        }

        Throwable failureOrNull() {
            return select(null);
        }

        void rethrowWithPrimary(Throwable primary) {
            Throwable selected = select(Objects.requireNonNull(primary, "primary"));
            throwUnchecked(selected);
        }

        private Throwable select(Throwable primary) {
            Throwable selected = primary instanceof Error ? primary : null;
            if (selected == null) {
                for (Throwable cleanupFailure : cleanupFailures) {
                    if (cleanupFailure instanceof Error) {
                        selected = cleanupFailure;
                        break;
                    }
                }
            }
            if (selected == null) {
                if (primary != null) {
                    selected = primary;
                } else if (!cleanupFailures.isEmpty()) {
                    selected = cleanupFailures.getFirst();
                } else {
                    return null;
                }
            }
            if (primary != null && primary != selected) {
                addSuppressed(selected, primary);
            }
            for (Throwable cleanupFailure : cleanupFailures) {
                if (cleanupFailure != selected) {
                    addSuppressed(selected, cleanupFailure);
                }
            }
            return selected;
        }
    }

    private static final class CallbackLeaf extends ResourceLeaf {
        private final X7GpuGenerationKey key;
        private final int physicalResourceCount;
        private final long physicalByteCount;
        private final PhysicalLeafClose physicalClose;

        private CallbackLeaf(
                X7GpuGenerationKey key,
                int physicalResourceCount,
                long physicalByteCount,
                PhysicalLeafClose physicalClose) {
            this.key = Objects.requireNonNull(key, "key");
            if (physicalResourceCount <= 0 || physicalByteCount < 0L) {
                throw new IllegalArgumentException("A physical resource leaf must be non-empty and non-negative in bytes");
            }
            this.physicalResourceCount = physicalResourceCount;
            this.physicalByteCount = physicalByteCount;
            this.physicalClose = Objects.requireNonNull(physicalClose, "physicalClose");
        }

        @Override
        X7GpuGenerationKey key() {
            return key;
        }

        @Override
        int physicalResourceCount() {
            return physicalResourceCount;
        }

        @Override
        long physicalByteCount() {
            return physicalByteCount;
        }

        @Override
        void closeSynchronouslyAfterVerifiedD1Completion() {
            physicalClose.closeSynchronouslyAfterVerifiedD1Completion();
        }
    }
}
