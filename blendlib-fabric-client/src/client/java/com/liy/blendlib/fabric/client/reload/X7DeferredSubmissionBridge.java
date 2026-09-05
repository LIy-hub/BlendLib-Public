package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.Objects;

/** Reload-private bridge that turns one open plan parent into one exact submitted-work child. */
final class X7DeferredSubmissionBridge {
    /** Package-private deterministic allocation probes used only by the focused ownership tests. */
    enum AllocationBoundary {
        OWNER_CHILD_LEASE,
        OWNER_SUBMITTED_LEASE,
        BRIDGE_SUBMISSION_CHILD,
        PUBLIC_RECEIPT,
        QUEUE_CHILD,
        QUEUED_SUBMISSION,
        QUEUE_APPEND,
        COMPLETION_RECEIPT,
        COMPLETION_INDEX,
        COMPLETION_POST_DRAINED_REMOVE,
        DRAINED_INDEX
    }

    private static volatile AllocationBoundary injectedAllocationFailureBoundary;
    private static volatile Error injectedAllocationFailure;
    private static volatile int injectedAllocationFailureOccurrence = 1;
    private static volatile int observedAllocationFailureOccurrences;

    private X7DeferredSubmissionBridge() {
    }

    static void failAllocationAtForTest(AllocationBoundary boundary, Error failure) {
        failAllocationAtForTest(boundary, 1, failure);
    }

    /** Injects one deterministic occurrence of an otherwise allocation-fallible ownership boundary. */
    static void failAllocationAtForTest(AllocationBoundary boundary, int occurrence, Error failure) {
        if (occurrence <= 0) {
            throw new IllegalArgumentException("allocation failure occurrence must be positive");
        }
        injectedAllocationFailure = Objects.requireNonNull(failure, "failure");
        injectedAllocationFailureBoundary = Objects.requireNonNull(boundary, "boundary");
        injectedAllocationFailureOccurrence = occurrence;
        observedAllocationFailureOccurrences = 0;
    }

    static void clearAllocationFailureForTest() {
        injectedAllocationFailureBoundary = null;
        injectedAllocationFailure = null;
        injectedAllocationFailureOccurrence = 1;
        observedAllocationFailureOccurrences = 0;
    }

    static void beforeAllocation(AllocationBoundary boundary) {
        if (injectedAllocationFailureBoundary == boundary
                && ++observedAllocationFailureOccurrences == injectedAllocationFailureOccurrence) {
            throw injectedAllocationFailure;
        }
    }

    static SubmissionChild begin(
            GenerationRenderResourceLease parent,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw) {
        ClientGenerationResourceOwner.SubmittedLease submitted = Objects.requireNonNull(parent, "parent")
                .beginSubmittedChild(
                        Objects.requireNonNull(exactSnapshot, "exactSnapshot"),
                        Objects.requireNonNull(exactDraw, "exactDraw"));
        try {
            beforeAllocation(AllocationBoundary.BRIDGE_SUBMISSION_CHILD);
            return new SubmissionChild(submitted.lease(), submitted.policyResourceRecord(), submitted.policyProof());
        } catch (Throwable failure) {
            closeLeaseThenRethrow(submitted.lease(), failure);
            throw new AssertionError("unreachable");
        }
    }

    /** Immutable internal payload; only the child receipt/queue may move or close its lease. */
    static final class SubmissionChild {
        private GenerationRenderResourceLease lease;
        private final X7PolicyResourceRecord policyResourceRecord;
        private final X7PublishedSubmissionBridge.SubmissionProof policyProof;

        private SubmissionChild(
                GenerationRenderResourceLease lease,
                X7PolicyResourceRecord policyResourceRecord,
                X7PublishedSubmissionBridge.SubmissionProof policyProof) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.policyResourceRecord = Objects.requireNonNull(policyResourceRecord, "policyResourceRecord");
            this.policyProof = Objects.requireNonNull(policyProof, "policyProof");
        }

        synchronized QueueChild takeForQueue() {
            GenerationRenderResourceLease moved = lease;
            if (moved == null) {
                throw new IllegalStateException("Submitted child is no longer caller-owned");
            }
            beforeAllocation(AllocationBoundary.QUEUE_CHILD);
            QueueChild successor = new QueueChild(moved, policyResourceRecord, policyProof);
            lease = null;
            return successor;
        }

        synchronized void closeCallerOwned() {
            GenerationRenderResourceLease owned = lease;
            lease = null;
            if (owned != null) {
                owned.close();
            }
        }
    }

    /** Queue/completion-only ownership after the public receipt has transferred exactly once. */
    static final class QueueChild implements AutoCloseable {
        private GenerationRenderResourceLease lease;
        private final X7PolicyResourceRecord policyResourceRecord;
        private final X7PublishedSubmissionBridge.SubmissionProof policyProof;

        private QueueChild(
                GenerationRenderResourceLease lease,
                X7PolicyResourceRecord policyResourceRecord,
                X7PublishedSubmissionBridge.SubmissionProof policyProof) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.policyResourceRecord = Objects.requireNonNull(policyResourceRecord, "policyResourceRecord");
            this.policyProof = Objects.requireNonNull(policyProof, "policyProof");
        }

        X7PolicyResourceRecord policyResourceRecord() {
            return policyResourceRecord;
        }

        X7PublishedSubmissionBridge.SubmissionProof policyProof() {
            return policyProof;
        }

        @Override
        public synchronized void close() {
            GenerationRenderResourceLease owned = lease;
            lease = null;
            if (owned != null) {
                owned.close();
            }
        }
    }

    static void closeLeaseThenRethrow(GenerationRenderResourceLease lease, Throwable primary) {
        try {
            lease.close();
        } catch (Throwable cleanupFailure) {
            appendSuppressedSafely(primary, cleanupFailure);
        }
        CompletedGenerationResourceSet.throwUnchecked(primary);
    }

    static void closeCallerReceiptThenRethrow(
            ClientGenerationLeaseBinding.DeferredSubmissionReceipt receipt, Throwable primary) {
        try {
            receipt.close();
        } catch (Throwable cleanupFailure) {
            appendSuppressedSafely(primary, cleanupFailure);
        }
        CompletedGenerationResourceSet.throwUnchecked(primary);
    }

    static void closeQueueChildThenRethrow(QueueChild child, Throwable primary) {
        try {
            child.close();
        } catch (Throwable cleanupFailure) {
            appendSuppressedSafely(primary, cleanupFailure);
        }
        CompletedGenerationResourceSet.throwUnchecked(primary);
    }

    static void appendSuppressedSafely(Throwable primary, Throwable cleanupFailure) {
        if (primary == cleanupFailure) {
            return;
        }
        try {
            primary.addSuppressed(cleanupFailure);
        } catch (Throwable ignored) {
            // A suppressed-exception allocation failure must never replace the original fatal failure.
        }
    }
}
