package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.Objects;

/**
 * Opaque source-bound handoff joining one trusted lookup sample, immutable render snapshot, and
 * its exact D1 parent lease.
 *
 * <p>Only the reload-private lookup can construct a source-bound instance. External lookup
 * implementations receive {@link #unavailable(ModelRenderSnapshot)} from the public default and
 * cannot upgrade it to managed ownership. The composite deliberately owns no render-pass or
 * deferred-submission completion state.</p>
 */
public final class ClientGenerationLeaseBinding implements AutoCloseable {
    private enum Ownership {
        CALLER_OWNED,
        PLAN_TRANSFERRED,
        CALLER_CLOSED
    }

    private final ClientModelLookup sourceLookup;
    private final ClientModelView sourceView;
    private final ModelRenderSnapshot snapshot;
    private final X7PreparedFrameProjection preparedFrameProjection;
    private ClientGenerationLease lease;
    private Ownership ownership = Ownership.CALLER_OWNED;

    private ClientGenerationLeaseBinding(
            ClientModelLookup sourceLookup,
            ClientModelView sourceView,
            ModelRenderSnapshot snapshot,
            ClientGenerationLease lease,
            X7PreparedFrameProjection preparedFrameProjection) {
        this.sourceLookup = sourceLookup;
        this.sourceView = sourceView;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.preparedFrameProjection = Objects.requireNonNull(preparedFrameProjection, "preparedFrameProjection");
    }

    /** Explicit no-resource fallback for a compatible external lookup implementation. */
    public static ClientGenerationLeaseBinding unavailable(ModelRenderSnapshot snapshot) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        return new ClientGenerationLeaseBinding(
                null,
                null,
                checkedSnapshot,
                ClientGenerationLease.unavailable(),
                X7PreparedFrameProjection.externalFallback(checkedSnapshot));
    }

    static ClientGenerationLeaseBinding sourceBound(
            ClientModelLookup sourceLookup,
            ClientModelView sourceView,
            ModelRenderSnapshot snapshot,
            ClientGenerationLease lease,
            X7ProductionPolicyOwner policyOwner) {
        ClientModelLookup checkedSourceLookup = Objects.requireNonNull(sourceLookup, "sourceLookup");
        ClientModelView checkedSourceView = Objects.requireNonNull(sourceView, "sourceView");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        ClientGenerationLease checkedLease = Objects.requireNonNull(lease, "lease");
        X7ProductionPolicyOwner checkedPolicyOwner = Objects.requireNonNull(policyOwner, "policyOwner");
        checkedLease.requireCompatible(checkedSourceLookup, checkedSourceView);
        if (checkedSourceView.renderHandle() != checkedSnapshot.handle()) {
            throw new IllegalArgumentException(
                    "Source-bound generation ownership requires the exact source-render-handle identity");
        }
        return new ClientGenerationLeaseBinding(
                checkedSourceLookup,
                checkedSourceView,
                checkedSnapshot,
                checkedLease,
                checkedPolicyOwner.prepareUnavailableFrame(checkedSourceView, checkedSnapshot));
    }

    /** The immutable snapshot sampled and source-bound before managed X6 admission. */
    public ModelRenderSnapshot snapshot() {
        return snapshot;
    }

    /** Whether this caller-owned composite still retains an actual D1 parent. */
    public synchronized boolean managed() {
        return ownership == Ownership.CALLER_OWNED && lease != null && lease.managed();
    }

    /**
     * Fails closed when a wrapper tries to present this source-bound composite as its own.
     * The source view is retained privately and never supplied by the caller.
     */
    public synchronized void requireCompatible(ClientModelLookup expectedSourceLookup) {
        Objects.requireNonNull(expectedSourceLookup, "expectedSourceLookup");
        ClientGenerationLease callerLease = requireCallerOwnedLease();
        if (sourceLookup == null) {
            return;
        }
        if (sourceLookup != expectedSourceLookup) {
            throw new IllegalArgumentException("Shared generation binding belongs to another concrete model lookup source");
        }
        callerLease.requireCompatible(sourceLookup, sourceView);
        if (sourceView.renderHandle() != snapshot.handle()) {
            throw new IllegalArgumentException(
                    "Shared generation binding no longer carries its exact source-render-handle identity");
        }
    }

    /**
     * Requires this composite to still carry the exact immutable snapshot it sampled.
     *
     * <p>The trusted lookup resolves and retains its own source view before it accepts the caller
     * snapshot. That snapshot is admitted only when its handle is the exact source-view handle;
     * this check then prevents a caller from later splicing a view from another lookup onto the
     * source-bound composite.</p>
     */
    public synchronized void requireExactSnapshot(ModelRenderSnapshot expectedSnapshot) {
        if (snapshot != Objects.requireNonNull(expectedSnapshot, "expectedSnapshot")) {
            throw new IllegalArgumentException("Shared generation binding belongs to another immutable render snapshot");
        }
        ClientGenerationLease callerLease = requireCallerOwnedLease();
        if (sourceLookup != null) {
            callerLease.requireCompatible(sourceLookup, sourceView);
            callerLease.requireExactHandle(snapshot.handle());
        }
    }

    /**
     * Returns the immutable CPU-route permission sampled with this exact source-bound snapshot.
     *
     * <p>This additive primitive is deliberately not a resource lease, backend object, policy
     * type, or render-pass receipt. It only confirms that the preparation-time route remains the
     * reliable CPU route for the exact immutable snapshot already retained by this binding. The
     * per-frame visibility decision remains on {@link ModelRenderSnapshot}; callers must not
     * derive a second visibility truth from this method.</p>
     */
    public synchronized boolean permitsFrozenCpuRoute(ModelRenderSnapshot expectedSnapshot) {
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(expectedSnapshot, "expectedSnapshot");
        requireExactSnapshot(checkedSnapshot);
        return preparedFrameProjection.permitsCpuRoute(checkedSnapshot, sourceView);
    }

    /** Fails closed before X6 admission/pin unless this is a live source-bound managed composite. */
    public synchronized void requireManagedForPlan() {
        ClientGenerationLease callerLease = requireCallerOwnedLease();
        if (sourceLookup == null || !callerLease.managed()) {
            throw new IllegalStateException("Managed X6 ownership requires a source-bound registry lease composite");
        }
        callerLease.requireCompatible(sourceLookup, sourceView);
        callerLease.requireExactHandle(snapshot.handle());
    }

    /**
     * Atomically moves this caller-owned parent into one opaque plan-owned close receipt.
     *
     * <p>This is the only caller-to-plan ownership transfer. It linearizes with {@link #close()}
     * on this binding's monitor: a close that wins leaves no transferable parent, while a transfer
     * that wins makes later caller closes no-ops. The returned receipt exposes only idempotent
     * close; it never exposes the raw D1 lease, its registry owner, or a render handle.</p>
     */
    public synchronized PlanTransferReceipt transferToPlan() {
        requireManagedForPlan();
        PlanTransferReceipt receipt = new PlanTransferReceipt();
        receipt.acceptMovedLease(requireCallerOwnedLease());
        lease = null;
        ownership = Ownership.PLAN_TRANSFERRED;
        return receipt;
    }

    /** Releases this caller-owned exact D1 parent; after transfer this is deliberately a no-op. */
    @Override
    public void close() {
        ClientGenerationLease callerLease;
        synchronized (this) {
            if (ownership != Ownership.CALLER_OWNED) {
                return;
            }
            callerLease = lease;
            lease = null;
            ownership = Ownership.CALLER_CLOSED;
        }
        if (callerLease != null) {
            callerLease.close();
        }
    }

    private ClientGenerationLease requireCallerOwnedLease() {
        if (ownership != Ownership.CALLER_OWNED || lease == null) {
            throw new IllegalStateException("Shared generation binding is no longer caller-owned");
        }
        return lease;
    }

    /** Close-only plan ownership returned by one successful {@link #transferToPlan()} call. */
    public static final class PlanTransferReceipt implements AutoCloseable {
        private ClientGenerationLease movedLease;

        private PlanTransferReceipt() {
        }

        private synchronized void acceptMovedLease(ClientGenerationLease candidateLease) {
            if (movedLease != null) {
                throw new IllegalStateException("Shared generation plan receipt already owns a lease");
            }
            movedLease = Objects.requireNonNull(candidateLease, "candidateLease");
        }

        /**
         * Mints one close-only submitted-work child while this exact plan parent is still open.
         *
         * <p>The returned receipt deliberately reveals no owner, generation lease, policy, resource, or render
         * handle. A later queue can atomically take its private child; a caller close before that transfer releases
         * it, while a parent close race is serialized on this receipt's monitor.</p>
         */
        public synchronized DeferredSubmissionReceipt beginDeferredSubmission(
                ModelRenderSnapshot exactSnapshot, X6DrawPrimitive exactDraw) {
            ClientGenerationLease parent = movedLease;
            if (parent == null) {
                throw new IllegalStateException("Shared generation plan receipt is no longer open for deferred submission");
            }
            X7DeferredSubmissionBridge.SubmissionChild child = parent.beginDeferredSubmission(
                    Objects.requireNonNull(exactSnapshot, "exactSnapshot"),
                    Objects.requireNonNull(exactDraw, "exactDraw"));
            try {
                X7DeferredSubmissionBridge.beforeAllocation(
                        X7DeferredSubmissionBridge.AllocationBoundary.PUBLIC_RECEIPT);
                return new DeferredSubmissionReceipt(child);
            } catch (Throwable failure) {
                try {
                    child.closeCallerOwned();
                } catch (Throwable cleanupFailure) {
                    X7DeferredSubmissionBridge.appendSuppressedSafely(failure, cleanupFailure);
                }
                CompletedGenerationResourceSet.throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }
        }

        /** Releases the transferred parent at most once; no raw ownership detail is exposed. */
        @Override
        public void close() {
            ClientGenerationLease leaseToClose;
            synchronized (this) {
                leaseToClose = movedLease;
                movedLease = null;
            }
            if (leaseToClose != null) {
                leaseToClose.close();
            }
        }
    }

    /**
     * Opaque close-only ownership of one submitted child lease.
     *
     * <p>Its constructor and queue-transfer operation are reload-private.  Consumers can only release a child they
     * still own; once queue ownership wins, a later public close is intentionally a no-op and cannot double-release
     * the D1 record.</p>
     */
    public static final class DeferredSubmissionReceipt implements AutoCloseable {
        private X7DeferredSubmissionBridge.SubmissionChild child;

        private DeferredSubmissionReceipt(X7DeferredSubmissionBridge.SubmissionChild child) {
            this.child = Objects.requireNonNull(child, "child");
        }

        synchronized X7DeferredSubmissionBridge.QueueChild transferToQueue() {
            X7DeferredSubmissionBridge.SubmissionChild callerOwned = child;
            if (callerOwned == null) {
                throw new IllegalStateException("Deferred submission child is no longer caller-owned");
            }
            X7DeferredSubmissionBridge.QueueChild moved = callerOwned.takeForQueue();
            child = null;
            return moved;
        }

        @Override
        public void close() {
            X7DeferredSubmissionBridge.SubmissionChild callerOwned;
            synchronized (this) {
                callerOwned = child;
                child = null;
            }
            if (callerOwned != null) {
                callerOwned.closeCallerOwned();
            }
        }
    }
}
