package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding.DeferredSubmissionReceipt;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, value-only staging queue for future AFTER_SOLID submission execution.
 *
 * <p>T2a3 intentionally has no host wiring, device, target, pass, encoder, or draw call.  Its final admission
 * linearization is the receipt's caller-to-queue move. A full/closed queue leaves the child caller-owned so Stage A
 * can close it and keep the existing CPU path. Command-recorded work moves into a separate completion receipt and
 * is never force-released by cancellation or shutdown.</p>
 */
final class X7DeferredFrameQueue implements AutoCloseable {
    static final int MAX_REQUESTS_PER_AFTER_SOLID_FRAME = 256;

    private final long renderThreadId;
    private final int requestCapacity;
    private final Map<Long, ArrayDeque<QueuedSubmission>> queuedByEpoch = new java.util.HashMap<>();
    private final Map<Long, ArrayDeque<QueuedSubmission>> drainedNoCommandByEpoch = new java.util.HashMap<>();
    // This maximum is fixed by the queue contract, so command retention never has to resize its identity index
    // after a child has moved out of the drained no-command collection.
    private final Map<X7DeferredSubmissionCompletionReceipt, Boolean> completionReceipts =
            new IdentityHashMap<>(MAX_REQUESTS_PER_AFTER_SOLID_FRAME * 2);
    private long nextAdmissionEpoch = 1L;
    private long nextRequestId;
    private boolean admissionClosed;

    X7DeferredFrameQueue(long renderThreadId) {
        this(renderThreadId, MAX_REQUESTS_PER_AFTER_SOLID_FRAME);
    }

    X7DeferredFrameQueue(long renderThreadId, int requestCapacity) {
        if (renderThreadId <= 0L) {
            throw new IllegalArgumentException("renderThreadId must be positive");
        }
        if (requestCapacity <= 0 || requestCapacity > MAX_REQUESTS_PER_AFTER_SOLID_FRAME) {
            throw new IllegalArgumentException("requestCapacity must be between one and the frozen frame maximum");
        }
        this.renderThreadId = renderThreadId;
        this.requestCapacity = requestCapacity;
    }

    synchronized Admission tryAdmit(
            Object exactPlanIdentity,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw,
            DeferredSubmissionReceipt callerReceipt) {
        Objects.requireNonNull(exactPlanIdentity, "exactPlanIdentity");
        Objects.requireNonNull(exactSnapshot, "exactSnapshot");
        Objects.requireNonNull(exactDraw, "exactDraw");
        Objects.requireNonNull(callerReceipt, "callerReceipt");
        X7DeferredSubmissionBridge.QueueChild child = null;
        QueuedSubmission queued = null;
        ArrayDeque<QueuedSubmission> queue = null;
        try {
            if (admissionClosed) {
                return Admission.rejected(Rejection.CLOSED);
            }
            queue = queuedByEpoch.computeIfAbsent(nextAdmissionEpoch, ignored -> new ArrayDeque<>());
            if (queue.size() >= requestCapacity || inFlightSubmissionCountLocked() >= requestCapacity) {
                return Admission.rejected(Rejection.CAPACITY);
            }
            child = callerReceipt.transferToQueue();
            X7PreAdmittedFrameRequest request = X7PreAdmittedFrameRequest.create(
                    nextRequestId(),
                    new X7AfterSolidFrameIdentity(
                            nextAdmissionEpoch, renderThreadId, X7AfterSolidFrameIdentity.Phase.AFTER_SOLID_FEATURES),
                    exactPlanIdentity,
                    exactSnapshot,
                    exactDraw,
                    child);
            X7DeferredSubmissionBridge.beforeAllocation(
                    X7DeferredSubmissionBridge.AllocationBoundary.QUEUED_SUBMISSION);
            queued = new QueuedSubmission(this, request);
            // The result object must exist before the queue link commits; otherwise an Error after addLast would
            // make the queue own a child with no caller-visible admission result.
            Admission accepted = Admission.accepted(queued);
            X7DeferredSubmissionBridge.beforeAllocation(
                    X7DeferredSubmissionBridge.AllocationBoundary.QUEUE_APPEND);
            queue.addLast(queued);
            return accepted;
        } catch (Throwable failure) {
            if (queue != null && queued != null) {
                try {
                    queue.remove(queued);
                } catch (Throwable cleanupFailure) {
                    X7DeferredSubmissionBridge.appendSuppressedSafely(failure, cleanupFailure);
                }
            }
            if (child != null) {
                X7DeferredSubmissionBridge.closeQueueChildThenRethrow(child, failure);
            }
            X7DeferredSubmissionBridge.closeCallerReceiptThenRethrow(callerReceipt, failure);
            throw new AssertionError("unreachable");
        }
    }

    /** Seals the current target epoch and hands only its bounded queue to the later phase executor. */
    synchronized List<QueuedSubmission> sealAndDrainAfterSolid(long callbackThreadId) {
        requireRenderThread(callbackThreadId);
        if (admissionClosed) {
            return List.of();
        }
        long sealedEpoch = nextAdmissionEpoch;
        long nextEpoch = Math.addExact(sealedEpoch, 1L);
        ArrayDeque<QueuedSubmission> queued = queuedByEpoch.get(sealedEpoch);
        X7AfterSolidFrameIdentity frame = new X7AfterSolidFrameIdentity(
                sealedEpoch, renderThreadId, X7AfterSolidFrameIdentity.Phase.AFTER_SOLID_FEATURES);
        if (queued == null || queued.isEmpty()) {
            queuedByEpoch.remove(sealedEpoch);
            nextAdmissionEpoch = nextEpoch;
            return List.of();
        }
        for (QueuedSubmission submission : queued) {
            submission.requireTarget(frame);
        }
        ArrayDeque<QueuedSubmission> drained = new ArrayDeque<>(queued);
        X7DeferredSubmissionBridge.beforeAllocation(
                X7DeferredSubmissionBridge.AllocationBoundary.DRAINED_INDEX);
        try {
            drainedNoCommandByEpoch.put(sealedEpoch, drained);
        } catch (Throwable failure) {
            drainedNoCommandByEpoch.remove(sealedEpoch);
            CompletedGenerationResourceSet.throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
        queuedByEpoch.remove(sealedEpoch);
        nextAdmissionEpoch = nextEpoch;
        return List.copyOf(drained);
    }

    /** Cancels only unrecorded work for one exact frame; command-recorded receipts remain retained. */
    void cancelNoCommandForFrame(X7AfterSolidFrameIdentity frame) {
        Objects.requireNonNull(frame, "frame");
        List<QueuedSubmission> toCancel;
        synchronized (this) {
            requireExactFrame(frame);
            toCancel = removeNoCommandLocked(frame.afterSolidEpoch());
        }
        cancelAll(toCancel);
    }

    /** Reload/world-leave/T1c cutoff: stop admission and release only work with no recorded command. */
    void closeAdmissionAndCancelNoCommand() {
        List<QueuedSubmission> toCancel;
        synchronized (this) {
            toCancel = snapshotNoCommandLocked();
            admissionClosed = true;
            queuedByEpoch.clear();
            drainedNoCommandByEpoch.clear();
        }
        cancelAll(toCancel);
    }

    @Override
    public void close() {
        closeAdmissionAndCancelNoCommand();
    }

    synchronized int queuedRequestCount() {
        int count = 0;
        for (ArrayDeque<QueuedSubmission> queue : queuedByEpoch.values()) {
            count += queue.size();
        }
        for (ArrayDeque<QueuedSubmission> queue : drainedNoCommandByEpoch.values()) {
            count += queue.size();
        }
        return count;
    }

    synchronized int retainedCompletionCount() {
        return completionReceipts.size();
    }

    /** Includes queued, drained-no-command, and command-recorded/terminal retained work. */
    synchronized int inFlightSubmissionCount() {
        return inFlightSubmissionCountLocked();
    }

    private synchronized X7DeferredSubmissionCompletionReceipt recordCommand(QueuedSubmission submission) {
        return recordCommand(submission, null);
    }

    /**
     * Turns one drained child into its indexed completion owner and, when supplied, publishes that exact owner plus
     * the already-preallocated T4 transfer callback in the same synchronized final move. No allocation or map-key
     * operation occurs between the child detach and that callback.
     */
    private synchronized X7DeferredSubmissionCompletionReceipt recordCommand(
            QueuedSubmission submission, CompletionHandoff handoff) {
        long epoch = submission.request.targetFrame().afterSolidEpoch();
        // Retain the exact boxed key before any ownership mutation. A non-cached epoch may allocate here, while the
        // drained queue still owns its child; after removal this same object prevents a second boxing allocation.
        Long epochKey = Long.valueOf(epoch);
        ArrayDeque<QueuedSubmission> drained = drainedNoCommandByEpoch.get(epochKey);
        if (drained == null || !drained.contains(submission) || !submission.hasQueueChild()) {
            throw new IllegalStateException("Only one exact drained no-command request may record a command");
        }
        X7DeferredSubmissionBridge.beforeAllocation(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_RECEIPT);
        X7DeferredSubmissionCompletionReceipt receipt = new X7DeferredSubmissionCompletionReceipt(this);
        X7DeferredSubmissionBridge.beforeAllocation(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_INDEX);
        try {
            completionReceipts.put(receipt, Boolean.TRUE);
        } catch (Throwable failure) {
            completionReceipts.remove(receipt);
            CompletedGenerationResourceSet.throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
        drained.remove(submission);
        // The indexed completion receipt becomes the owner before any later map-key operation can run.
        X7DeferredSubmissionBridge.QueueChild child = submission.takeQueueChild();
        receipt.acceptQueueChild(child);
        if (handoff != null) {
            handoff.publishAndMarkMoved(receipt);
        }
        if (drained.isEmpty()) {
            X7DeferredSubmissionBridge.beforeAllocation(
                    X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE);
            drainedNoCommandByEpoch.remove(epochKey);
        }
        return receipt;
    }

    /**
     * Atomically turns one exact bounded static batch into completion-owned work before a native draw is permitted.
     *
     * <p>Every allocation-fallible operation, including each receipt object, the immutable result view, and each
     * completion-index insertion, happens while all children remain in the drained no-command collection. If one
     * fails, the index rollback is allocation-free and no child has moved. Once the loop begins moving children,
     * it uses only already-validated {@link ArrayDeque} removals, field swaps, and the pre-sized identity index.</p>
     */
    private synchronized List<X7DeferredSubmissionCompletionReceipt> recordCommandsAtomically(
            List<QueuedSubmission> submissions) {
        List<QueuedSubmission> checkedSubmissions = List.copyOf(Objects.requireNonNull(submissions, "submissions"));
        if (checkedSubmissions.isEmpty()) {
            throw new IllegalArgumentException("The direct-static command batch must contain at least one queued child");
        }
        QueuedSubmission first = checkedSubmissions.getFirst();
        long epoch = first.request.targetFrame().afterSolidEpoch();
        Long epochKey = Long.valueOf(epoch);
        ArrayDeque<QueuedSubmission> drained = drainedNoCommandByEpoch.get(epochKey);
        if (drained == null) {
            throw new IllegalStateException("Only exact drained no-command work may enter a direct-static command batch");
        }
        for (int index = 0; index < checkedSubmissions.size(); index++) {
            QueuedSubmission submission = checkedSubmissions.get(index);
            if (submission.owner != this
                    || submission.request.targetFrame().afterSolidEpoch() != epoch
                    || !drained.contains(submission)
                    || !submission.hasQueueChild()) {
                throw new IllegalStateException("The direct-static batch lost exact queue ownership before command recording");
            }
            for (int prior = 0; prior < index; prior++) {
                if (checkedSubmissions.get(prior) == submission) {
                    throw new IllegalArgumentException("The direct-static command batch contains one queued child twice");
                }
            }
        }

        X7DeferredSubmissionCompletionReceipt[] receipts =
                new X7DeferredSubmissionCompletionReceipt[checkedSubmissions.size()];
        for (int index = 0; index < receipts.length; index++) {
            X7DeferredSubmissionBridge.beforeAllocation(
                    X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_RECEIPT);
            receipts[index] = new X7DeferredSubmissionCompletionReceipt(this);
        }
        // Build the immutable exact receipt view before an ownership mutation; no List.copyOf can run after draw.
        List<X7DeferredSubmissionCompletionReceipt> result = List.of(receipts);
        int indexed = 0;
        try {
            for (; indexed < receipts.length; indexed++) {
                X7DeferredSubmissionBridge.beforeAllocation(
                        X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_INDEX);
                completionReceipts.put(receipts[indexed], Boolean.TRUE);
            }
        } catch (Throwable failure) {
            for (int rollback = 0; rollback < indexed; rollback++) {
                completionReceipts.remove(receipts[rollback]);
            }
            CompletedGenerationResourceSet.throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }

        // The remaining operations are allocation-free and cannot expose an intermediate child state while holding
        // this queue monitor. Every receipt already has its completion-index ownership before its child detaches.
        for (int index = 0; index < receipts.length; index++) {
            QueuedSubmission submission = checkedSubmissions.get(index);
            drained.remove(submission);
            receipts[index].acceptQueueChild(submission.takeQueueChild());
        }
        if (drained.isEmpty()) {
            drainedNoCommandByEpoch.remove(epochKey);
        }
        return result;
    }

    private void cancelNoCommand(QueuedSubmission submission) {
        boolean owned;
        synchronized (this) {
            owned = removeSubmissionLocked(submission);
        }
        if (owned) {
            submission.cancelOwnedChildAndReleasePayload();
        }
    }

    synchronized void onCompletionReleased(X7DeferredSubmissionCompletionReceipt receipt) {
        completionReceipts.remove(receipt);
    }

    private List<QueuedSubmission> removeNoCommandLocked(long epoch) {
        ArrayDeque<QueuedSubmission> queued = queuedByEpoch.get(epoch);
        ArrayDeque<QueuedSubmission> drained = drainedNoCommandByEpoch.get(epoch);
        List<QueuedSubmission> result = new ArrayList<>(noCommandCountLocked(queued, drained));
        if (queued != null) {
            result.addAll(queued);
        }
        if (drained != null) {
            result.addAll(drained);
        }
        queuedByEpoch.remove(epoch);
        drainedNoCommandByEpoch.remove(epoch);
        return result;
    }

    private List<QueuedSubmission> snapshotNoCommandLocked() {
        int count = 0;
        for (ArrayDeque<QueuedSubmission> queued : queuedByEpoch.values()) {
            count = Math.addExact(count, queued.size());
        }
        for (ArrayDeque<QueuedSubmission> drained : drainedNoCommandByEpoch.values()) {
            count = Math.addExact(count, drained.size());
        }
        List<QueuedSubmission> result = new ArrayList<>(count);
        for (ArrayDeque<QueuedSubmission> queued : queuedByEpoch.values()) {
            result.addAll(queued);
        }
        for (ArrayDeque<QueuedSubmission> drained : drainedNoCommandByEpoch.values()) {
            result.addAll(drained);
        }
        return result;
    }

    private static int noCommandCountLocked(
            ArrayDeque<QueuedSubmission> queued, ArrayDeque<QueuedSubmission> drained) {
        int count = queued == null ? 0 : queued.size();
        return Math.addExact(count, drained == null ? 0 : drained.size());
    }

    private boolean removeSubmissionLocked(QueuedSubmission submission) {
        long epoch = submission.request.targetFrame().afterSolidEpoch();
        ArrayDeque<QueuedSubmission> queued = queuedByEpoch.get(epoch);
        if (queued != null && queued.remove(submission)) {
            if (queued.isEmpty()) {
                queuedByEpoch.remove(epoch);
            }
            return true;
        }
        ArrayDeque<QueuedSubmission> drained = drainedNoCommandByEpoch.get(epoch);
        if (drained != null && drained.remove(submission)) {
            if (drained.isEmpty()) {
                drainedNoCommandByEpoch.remove(epoch);
            }
            return true;
        }
        return false;
    }

    private int inFlightSubmissionCountLocked() {
        int count = completionReceipts.size();
        for (ArrayDeque<QueuedSubmission> queue : queuedByEpoch.values()) {
            count += queue.size();
        }
        for (ArrayDeque<QueuedSubmission> queue : drainedNoCommandByEpoch.values()) {
            count += queue.size();
        }
        return count;
    }

    private void requireRenderThread(long callbackThreadId) {
        if (callbackThreadId != renderThreadId) {
            throw new IllegalArgumentException("AFTER_SOLID callback must use the queue's exact render-thread identity");
        }
    }

    private void requireExactFrame(X7AfterSolidFrameIdentity frame) {
        if (frame.renderThreadId() != renderThreadId
                || frame.phase() != X7AfterSolidFrameIdentity.Phase.AFTER_SOLID_FEATURES) {
            throw new IllegalArgumentException("Deferred queue operation requires an exact AFTER_SOLID frame identity");
        }
    }

    private long nextRequestId() {
        long requestId = ++nextRequestId;
        if (requestId == 0L) {
            requestId = ++nextRequestId;
        }
        return requestId;
    }

    private static void cancelAll(List<QueuedSubmission> submissions) {
        Throwable primary = null;
        for (QueuedSubmission submission : submissions) {
            try {
                submission.cancelOwnedChildAndReleasePayload();
            } catch (Throwable failure) {
                primary = appendFailure(primary, failure);
            }
        }
        if (primary != null) {
            CompletedGenerationResourceSet.throwUnchecked(primary);
        }
    }

    private static Throwable appendFailure(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            X7DeferredSubmissionBridge.appendSuppressedSafely(primary, additional);
        }
        return primary;
    }

    enum Rejection {
        CAPACITY,
        CLOSED,
        CHILD_NOT_CALLER_OWNED
    }

    static final class Admission {
        private final QueuedSubmission submission;
        private final Rejection rejection;
        private final Throwable failure;

        private Admission(QueuedSubmission submission, Rejection rejection, Throwable failure) {
            this.submission = submission;
            this.rejection = rejection;
            this.failure = failure;
        }

        static Admission accepted(QueuedSubmission submission) {
            return new Admission(Objects.requireNonNull(submission, "submission"), null, null);
        }

        static Admission rejected(Rejection rejection) {
            return new Admission(null, Objects.requireNonNull(rejection, "rejection"), null);
        }

        static Admission failed(Throwable failure) {
            return new Admission(null, Rejection.CHILD_NOT_CALLER_OWNED, Objects.requireNonNull(failure, "failure"));
        }

        boolean accepted() {
            return submission != null;
        }

        QueuedSubmission submission() {
            if (submission == null) {
                throw new IllegalStateException("Queue admission did not transfer a submitted child: " + rejection);
            }
            return submission;
        }

        Rejection rejection() {
            return rejection;
        }

        Throwable failure() {
            return failure;
        }
    }

    /** One queue-owned request that can either cancel before a command or transfer into a completion receipt. */
    static final class QueuedSubmission {
        private final X7DeferredFrameQueue owner;
        private final X7PreAdmittedFrameRequest request;
        private X7DeferredSubmissionBridge.QueueChild child;

        private QueuedSubmission(X7DeferredFrameQueue owner, X7PreAdmittedFrameRequest request) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.request = Objects.requireNonNull(request, "request");
            this.child = request.child();
        }

        X7PreAdmittedFrameRequest request() {
            return request;
        }

        X7DeferredSubmissionCompletionReceipt recordCommand() {
            return owner.recordCommand(this);
        }

        /**
         * The handoff is allocated and bound before the queue child moves. Its receipt becomes observable before the
         * supplied T4 transfer state is marked, both before the queue's post-move fault boundary.
         */
        X7DeferredSubmissionCompletionReceipt recordCommand(CompletionHandoff handoff) {
            return owner.recordCommand(this, Objects.requireNonNull(handoff, "handoff"));
        }

        List<X7DeferredSubmissionCompletionReceipt> recordCommandsAtomically(List<QueuedSubmission> submissions) {
            return owner.recordCommandsAtomically(submissions);
        }

        void cancelNoCommand() {
            owner.cancelNoCommand(this);
        }

        private void requireTarget(X7AfterSolidFrameIdentity frame) {
            if (!request.targetFrame().equals(frame)) {
                throw new IllegalStateException("Queue request crossed an AFTER_SOLID frame boundary");
            }
        }

        private X7DeferredSubmissionBridge.QueueChild takeQueueChild() {
            X7DeferredSubmissionBridge.QueueChild moved = child;
            child = null;
            return moved;
        }

        private boolean hasQueueChild() {
            return child != null;
        }

        private void cancelOwnedChildAndReleasePayload() {
            X7DeferredSubmissionBridge.QueueChild owned;
            synchronized (this) {
                owned = child;
                child = null;
            }
            if (owned != null) {
                owned.close();
            }
            if (request.exactPlanIdentity() instanceof NoCommandPayload payload) {
                payload.releaseAfterNoCommandCancellation();
            }
        }
    }

    /** Reload-private payload cleanup hook; it has no queue/receipt/D1 handle access. */
    interface NoCommandPayload {
        void releaseAfterNoCommandCancellation();
    }

    /**
     * Preallocated T3c/T4 completion handoff. It contains only the exact queue receipt and a one-shot allocation-free
     * state callback; it never exposes a D1 lease, resource leaf, or native handle.
     */
    static final class CompletionHandoff {
        private final Runnable markT4Transfer;
        private X7DeferredSubmissionCompletionReceipt receipt;
        private boolean published;

        CompletionHandoff(Runnable markT4Transfer) {
            this.markT4Transfer = Objects.requireNonNull(markT4Transfer, "markT4Transfer");
        }

        private synchronized void publishAndMarkMoved(X7DeferredSubmissionCompletionReceipt exactReceipt) {
            if (published || receipt != null) {
                throw new IllegalStateException("The exact queue completion handoff was published more than once");
            }
            receipt = Objects.requireNonNull(exactReceipt, "exactReceipt");
            published = true;
            markT4Transfer.run();
        }

        synchronized X7DeferredSubmissionCompletionReceipt receiptOrNull() {
            return receipt;
        }
    }
}
