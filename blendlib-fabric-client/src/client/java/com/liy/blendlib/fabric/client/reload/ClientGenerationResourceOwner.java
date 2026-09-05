package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The sole D1 lifecycle owner for immutable model-registry generations.
 *
 * <p>Registry publication is legal only after this owner has recorded an explicit CPU-only payload or adopted the
 * exact complete aggregate into the same lifecycle record. The registry keeps the sole active reference; this class
 * owns retirement, exact leases, fencing, retry, and physical-close truth.</p>
 */
final class ClientGenerationResourceOwner {
    enum State {
        PENDING_RENDER_TRANSACTION,
        POLICY_FROZEN,
        PUBLISHED,
        RETIRING,
        FENCE_QUEUED,
        SHUTDOWN_FINAL_FENCE_ARMED,
        SHUTDOWN_FINAL_FENCE_WAIT,
        SHUTDOWN_NO_FINAL_PRESENT,
        SHUTDOWN_OUTSTANDING_CREATORS,
        SHUTDOWN_OUTSTANDING_TRANSACTIONS,
        SHUTDOWN_OUTSTANDING_LEASES,
        SHUTDOWN_HANDOFF_NOT_RUN,
        SHUTDOWN_EXTERNAL_QUEUE,
        SHUTDOWN_ADAPTER_MISMATCH,
        SHUTDOWN_FENCE_ARM_FAILED,
        SHUTDOWN_FENCE_TIMEOUT,
        SHUTDOWN_FENCE_WAIT_FAILED,
        SHUTDOWN_INTERNAL_FAILED,
        CLOSING,
        CLOSED,
        CLOSE_FAILED,
        ABORTED
    }

    enum ShutdownFailure {
        NO_FINAL_PRESENT,
        OUTSTANDING_CREATORS,
        OUTSTANDING_TRANSACTIONS,
        OUTSTANDING_LEASES,
        HANDOFF_NOT_RUN,
        EXTERNAL_QUEUE_PENDING,
        ADAPTER_MISMATCH,
        FENCE_ARM_FAILED,
        FENCE_TIMEOUT,
        FENCE_WAIT_FAILED,
        PHYSICAL_CLOSE_FAILED,
        INTERNAL_FAILURE
    }

    /** Package-private test port invoked only after the unique claim and before policy freezing. */
    @FunctionalInterface
    interface PolicyFreezeBarrier {
        void beforePolicyFreeze();
    }

    /** Package-private pre-CAS observation point. It cannot expose or mutate a raw physical resource. */
    @FunctionalInterface
    interface PublicationAdoptionBarrier {
        void beforePublicationCas(PublicationAdoptionProbe probe);
    }

    /**
     * Package-private pre-CAS test seam for materializing the immutable, read-only policy metrics value.
     *
     * <p>The lifecycle record invokes it during preallocation, while a candidate remains caller-owned. It must
     * never be invoked after the registry generation CAS.</p>
     */
    @FunctionalInterface
    interface PolicyMetricsMaterializer {
        X7PolicyMetricsView materialize(
                X7PublishedGenerationProjection projection, X7PolicyMetricsCollector collector);
    }

    static final PolicyFreezeBarrier NOOP_POLICY_FREEZE_BARRIER = () -> { };
    static final PublicationAdoptionBarrier NOOP_PUBLICATION_ADOPTION_BARRIER = probe -> { };
    static final PolicyMetricsMaterializer DEFAULT_POLICY_METRICS_MATERIALIZER =
            (projection, collector) -> X7PolicyMetricsView.unavailable(projection, collector.liveSnapshot());

    record PublicationAdoptionProbe(
            long recordId,
            long candidateGenerationId,
            int exactSetIdentity,
            PendingGenerationTransaction.PayloadMode payloadMode,
            int physicalResourceCount,
            long physicalByteCount,
            CompletedGenerationResourceSet.Ownership resourceOwnership,
            long activeGenerationId,
            boolean resourceReady,
            boolean publicationReady,
            boolean publicationPermitHeld) { }

    interface RenderOwnerCallbacks {
        void handoffToRenderThread(Runnable handoff);

        void assertOnRenderThread();

        /** Queues one callback which the platform invokes only after its accepted fence signals. */
        void queueFencedTask(Runnable callback);
    }

    record GenerationDiagnostics(
            long generationId,
            State state,
            int leaseCount,
            long activeCloseAttemptId,
            long lastCloseAttemptId,
            String closeFailureType,
            ShutdownFailure shutdownFailure,
            PendingGenerationTransaction.PayloadMode payloadMode,
            int physicalResourceCount,
            long physicalByteCount,
            boolean resourceReady,
            boolean publicationReady,
            CompletedGenerationResourceSet.Ownership resourceOwnership,
            boolean retryForbidden) { }

    record DrainDiagnostics(
            long activeGenerationId,
            int trackedGenerationCount,
            int outstandingPublicationTransactionCount,
            int publicationTransactionCountAtCutoff,
            int retiringGenerationCount,
            int outstandingLeaseCount,
            int fenceQueuedCount,
            int closeFailedCount,
            int closedGenerationCount,
            List<GenerationDiagnostics> generations) { }

    record ShutdownBatchArmResult(
            FinalCloseBatch batch,
            ShutdownFailure failure,
            int outstandingTransactionCount,
            int outstandingLeaseCount,
            int handoffNotRunCount,
            int externalQueueCount) {
        boolean armed() {
            return batch != null && failure == null;
        }
    }

    record ShutdownFailureSummary(
            ShutdownFailure requestedFailure,
            int outstandingCreatorCount,
            int outstandingTransactionCount,
            int outstandingLeaseCount,
            int handoffNotRunCount,
            int externalQueueCount) { }

    record ShutdownBatchCompletion(boolean accepted, int closedRecordCount, int failedRecordCount, Throwable failure) { }

    private final ClientModelRegistry registry;
    private final RenderOwnerCallbacks renderOwner;
    private final PolicyMetricsMaterializer policyMetricsMaterializer;
    private final Map<ModelRegistryGeneration, LifecycleRecord> records = new IdentityHashMap<>();
    /**
     * Pre-claim collision staging and post-claim detached cleanup share this owner-confined retention list.
     * A record enters either this list or the identity index before the transaction may claim its exact set, so a
     * claimed set never needs a newly allocated rescue record or list node.
     */
    private final List<LifecycleRecord> stagedOrDetachedRecords = new ArrayList<>();
    private boolean shutdown;
    private int inFlightPublicationTransactionCount;
    private int publicationTransactionCountAtCutoff;
    private long lastShutdownBatchAttemptId;
    private long lastLifecycleRecordId;
    private FinalCloseBatch activeShutdownBatch;
    private boolean shutdownFinalTerminal;
    private ShutdownFailure shutdownTerminalFailure;

    ClientGenerationResourceOwner(ClientModelRegistry registry) {
        this(registry, null);
    }

    ClientGenerationResourceOwner(ClientModelRegistry registry, RenderOwnerCallbacks renderOwner) {
        this(registry, renderOwner, DEFAULT_POLICY_METRICS_MATERIALIZER);
    }

    ClientGenerationResourceOwner(
            ClientModelRegistry registry,
            RenderOwnerCallbacks renderOwner,
            PolicyMetricsMaterializer policyMetricsMaterializer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.renderOwner = renderOwner;
        this.policyMetricsMaterializer = Objects.requireNonNull(policyMetricsMaterializer, "policyMetricsMaterializer");
    }

    synchronized void adoptInitialGeneration(ModelRegistryGeneration generation) {
        ModelRegistryGeneration checkedGeneration = Objects.requireNonNull(generation, "generation");
        if (!checkedGeneration.tryAttachResourceOwner(this)) {
            throw new IllegalStateException("A generation cannot belong to multiple resource owners");
        }
        X7PublishedGenerationProjection policyProjection = X7PublishedGenerationProjection.cpuOnly(checkedGeneration);
        LifecycleRecord record = new LifecycleRecord(
                nextLifecycleRecordIdLocked(),
                checkedGeneration,
                PendingGenerationTransaction.PayloadMode.CPU_ONLY,
                null,
                policyProjection,
                policyMetricsMaterializer,
                State.PUBLISHED,
                true,
                this);
        record.resourceReady = true;
        record.registeredInRecordIndex = true;
        records.put(checkedGeneration, record);
    }

    ModelRegistryGeneration offerPrepared(PendingGenerationTransaction transaction) {
        return offerPrepared(
                transaction,
                NOOP_POLICY_FREEZE_BARRIER,
                NOOP_PUBLICATION_ADOPTION_BARRIER);
    }

    /**
     * D1's only pre-publication adoption path. Test barriers observe a ready record but are never transaction
     * payloads and cannot move or close a resource.
     */
    ModelRegistryGeneration offerPrepared(
            PendingGenerationTransaction transaction,
            PolicyFreezeBarrier policyFreezeBarrier,
            PublicationAdoptionBarrier publicationAdoptionBarrier) {
        PendingGenerationTransaction checkedTransaction = Objects.requireNonNull(transaction, "transaction");
        PolicyFreezeBarrier checkedFreezeBarrier = Objects.requireNonNull(policyFreezeBarrier, "policyFreezeBarrier");
        PublicationAdoptionBarrier checkedAdoptionBarrier =
                Objects.requireNonNull(publicationAdoptionBarrier, "publicationAdoptionBarrier");

        PublicationTransactionPermit publicationPermit = tryAdmitPublicationTransaction();
        if (publicationPermit == null) {
            throw new IllegalStateException(
                    "Client model registry publication admission is closed; transaction remains caller-owned");
        }

        try (publicationPermit) {
            // The permit covers all work after admission, including record preallocation.  An allocation failure
            // before claim therefore releases admission without changing caller ownership.
            PendingGenerationTransaction.PreclaimPayload preclaimPayload = checkedTransaction.preclaimPayload();
            PreallocatedOffer preallocated = preallocateOffer(preclaimPayload);
            PendingGenerationTransaction.Claim claim;
            try {
                claim = checkedTransaction.claimForResourceOwner();
            } catch (Throwable failure) {
                discardUnclaimedPreallocatedRecord(preallocated.record);
                CompletedGenerationResourceSet.throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }
            LifecycleRecord record = preallocated.record;

            if (claim.abortedBeforeClaim()) {
                return abortClaimedAndReturnActive(
                        claim,
                        record,
                        nonNullAbortCause(claim),
                        false,
                        false,
                        preallocated.closeRequests);
            }

            try {
                checkedFreezeBarrier.beforePolicyFreeze();
                claim.freezePolicy();
            } catch (Throwable failure) {
                abortClaimedAndReturnActive(
                        claim, record, failure, false, false, preallocated.closeRequests);
                CompletedGenerationResourceSet.throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }

            Throwable publicationFailure = null;
            ModelRegistryGeneration activeResult;
            synchronized (this) {
                try {
                    activeResult = offerFrozenClaimLocked(
                            claim,
                            record,
                            publicationPermit,
                            checkedAdoptionBarrier,
                            preallocated.closeRequests);
                } catch (Throwable failure) {
                    publicationFailure = failure;
                    activeResult = abortClaimedLocked(
                            claim, record, failure, false, false, preallocated.closeRequests);
                }
            }
            dispatch(preallocated.closeRequests);
            if (publicationFailure != null) {
                CompletedGenerationResourceSet.throwUnchecked(publicationFailure);
            }
            return activeResult;
        }
    }

    boolean retire(ModelRegistryGeneration generation) {
        List<CloseRequest> closeRequests = new ArrayList<>(1);
        boolean transitioned;
        synchronized (this) {
            transitioned = retireLocked(generation, false, closeRequests);
        }
        dispatch(closeRequests);
        return transitioned;
    }

    GenerationRenderResourceLease acquire(ModelRegistryGeneration generation, ModelHandle handle) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(handle, "handle");
        synchronized (this) {
            LifecycleRecord record = records.get(generation);
            if (shutdown || record == null || record.state != State.PUBLISHED || registry.current() != generation) {
                throw new IllegalStateException("Generation " + generation.generationId() + " is not active for a render lease");
            }
            ModelHandle activeHandle = generation.handles().get(handle.key());
            if (activeHandle != handle || handle.generationId() != generation.generationId()) {
                throw new IllegalArgumentException("Render resource lease requires the exact active generation handle");
            }
            record.leaseCount++;
            return new GenerationRenderResourceLease(this, generation, handle);
        }
    }

    /**
     * Returns only the policy owner retained by this exact active D1 record.
     *
     * <p>The same monitor that serializes record publication gates this read, so an active CAS
     * cannot expose a projection before the owning record has reached {@link State#PUBLISHED}.
     * This is a D1-record lookup, not a side registry of X7 state.</p>
     */
    X7ProductionPolicyOwner policyOwnerForActive(
            ModelRegistryGeneration generation,
            BlendModelKey key,
            ModelHandle expectedHandle,
            ModelRenderHandle expectedRenderHandle) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expectedHandle, "expectedHandle");
        Objects.requireNonNull(expectedRenderHandle, "expectedRenderHandle");
        synchronized (this) {
            LifecycleRecord record = records.get(generation);
            if (shutdown || record == null || record.state != State.PUBLISHED || registry.current() != generation) {
                throw new IllegalStateException("Generation " + generation.generationId()
                        + " is not active for an X7 policy projection");
            }
            ModelHandle activeHandle = generation.handles().get(key);
            if (activeHandle != expectedHandle
                    || expectedHandle.renderHandle() != expectedRenderHandle
                    || activeHandle.missing()) {
                throw new IllegalStateException("X7 policy projection requires the exact active loaded D1 handle");
            }
            if (record.policyOwner.publishedProjection().generationId() != generation.generationId()) {
                throw new IllegalStateException("D1 policy owner retained another generation projection");
            }
            return record.policyOwner;
        }
    }

    GenerationRenderResourceLease acquireExact(
            ModelRegistryGeneration generation,
            BlendModelKey key,
            ModelHandle expectedHandle,
            ModelRenderHandle expectedRenderHandle) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(key, "key");
        if ((expectedHandle == null) != (expectedRenderHandle == null)) {
            throw new IllegalArgumentException("Render resource lease sample must retain handle and render-handle together");
        }
        synchronized (this) {
            LifecycleRecord record = records.get(generation);
            if (shutdown || record == null || record.state != State.PUBLISHED || registry.current() != generation) {
                throw new IllegalStateException("Generation " + generation.generationId()
                        + " is not active for an exact render-resource lease");
            }
            ModelHandle activeHandle = generation.handles().get(key);
            if (activeHandle != expectedHandle
                    || activeHandle != null && activeHandle.renderHandle() != expectedRenderHandle) {
                throw new IllegalStateException("Registry handle changed before exact render-resource lease admission");
            }
            if (activeHandle == null || activeHandle.missing()) {
                return null;
            }
            record.leaseCount++;
            return new GenerationRenderResourceLease(this, generation, activeHandle);
        }
    }

    void requireExactCurrentMissing(
            ModelRegistryGeneration generation,
            BlendModelKey key,
            ModelHandle expectedHandle,
            ModelRenderHandle expectedRenderHandle) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(key, "key");
        if ((expectedHandle == null) != (expectedRenderHandle == null)) {
            throw new IllegalArgumentException("Missing binding sample must retain handle and render-handle together");
        }
        synchronized (this) {
            LifecycleRecord record = records.get(generation);
            if (shutdown || record == null || record.state != State.PUBLISHED || registry.current() != generation) {
                throw new IllegalStateException("Generation " + generation.generationId()
                        + " is not active for an exact missing binding");
            }
            ModelHandle activeHandle = generation.handles().get(key);
            if (activeHandle != expectedHandle
                    || activeHandle != null && activeHandle.renderHandle() != expectedRenderHandle) {
                throw new IllegalStateException("Registry handle changed before exact missing binding admission");
            }
            if (activeHandle != null && !activeHandle.missing()) {
                throw new IllegalStateException("Exact missing binding requires an absent or map-owned missing handle");
            }
        }
    }

    /**
     * Mints one submitted-work child under the same D1 lifecycle-record count as its already-admitted parent.
     *
     * <p>This is deliberately a private lifecycle operation rather than a second submitted counter.  It is legal
     * only after canonical resource ownership exists, while the parent is open, and before shutdown/final-close
     * cutoff.  A retiring record remains valid because its parent/child counts still prevent physical close; a
     * closing or terminal record cannot be resurrected.</p>
     */
    SubmittedLease acquireSubmittedChild(
            GenerationRenderResourceLease parent,
            ModelRenderSnapshot exactSnapshot,
            X6DrawPrimitive exactDraw) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(exactSnapshot, "exactSnapshot");
        Objects.requireNonNull(exactDraw, "exactDraw");
        synchronized (this) {
            if (!parent.belongsTo(this) || parent.isReleased()) {
                throw new IllegalStateException("Submitted work requires one still-open exact D1 parent lease");
            }
            if (shutdown || activeShutdownBatch != null || shutdownFinalTerminal) {
                throw new IllegalStateException("Submitted work admission is closed during D1 shutdown");
            }
            LifecycleRecord record = records.get(parent.generation());
            if (record == null
                    || !record.resourceReady
                    || record.policyResourceRecord == null
                    || record.generation != parent.generation()
                    || record.state != State.PUBLISHED && record.state != State.RETIRING) {
                throw new IllegalStateException("Submitted work requires one live canonical D1 resource record");
            }
            X7PolicyResourceRecord policyRecord = record.policyResourceRecord;
            if (!policyRecord.ownsExactly(record.generation, record.resourceSet)) {
                throw new IllegalStateException("Submitted work lost its exact D1 policy-resource record identity");
            }
            X7PublishedSubmissionBridge.SubmissionProof proof = X7PublishedSubmissionBridge.requireExactSubmission(
                    policyRecord,
                    record.generation,
                    parent.handle(),
                    exactSnapshot,
                    exactDraw);
            // A submitted child must be fully materialized before it contributes to the D1 count.  In
            // particular, an allocation Error here cannot leave a positive count without a closeable child.
            X7DeferredSubmissionBridge.beforeAllocation(
                    X7DeferredSubmissionBridge.AllocationBoundary.OWNER_CHILD_LEASE);
            GenerationRenderResourceLease childLease = new GenerationRenderResourceLease(
                    this, record.generation, parent.handle(), true);
            X7DeferredSubmissionBridge.beforeAllocation(
                    X7DeferredSubmissionBridge.AllocationBoundary.OWNER_SUBMITTED_LEASE);
            SubmittedLease submitted = new SubmittedLease(childLease, policyRecord, proof);
            record.leaseCount++;
            return submitted;
        }
    }

    void release(GenerationRenderResourceLease lease) {
        Objects.requireNonNull(lease, "lease");
        CloseRequest closeRequest;
        synchronized (this) {
            LifecycleRecord record = records.get(lease.generation());
            if (record == null || record.leaseCount == 0) {
                throw new IllegalStateException("Render resource lease does not belong to a live generation");
            }
            record.leaseCount--;
            // Submitted-child rollback may run while handling an allocation Error.  The close request is
            // preallocated with the lifecycle record, so this decrement path must not need a successor list.
            closeRequest = scheduleCloseIfDrainedLocked(record);
        }
        if (closeRequest != null) {
            dispatch(closeRequest);
        }
    }

    boolean retryFailedClose(ModelRegistryGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        List<CloseRequest> closeRequests = new ArrayList<>(1);
        synchronized (this) {
            if (shutdownFinalTerminal) {
                return false;
            }
            LifecycleRecord record = records.get(generation);
            if (isRetryableCloseRecord(record)) {
                scheduleCloseIfDrainedLocked(record, closeRequests);
            }
            for (LifecycleRecord cleanupRecord : List.copyOf(stagedOrDetachedRecords)) {
                if (cleanupRecord.generation == generation && isRetryableCloseRecord(cleanupRecord)) {
                    scheduleCloseIfDrainedLocked(cleanupRecord, closeRequests);
                }
            }
        }
        dispatch(closeRequests);
        return !closeRequests.isEmpty();
    }

    synchronized ShutdownBatchArmResult armShutdownFinalBatch() {
        if (activeShutdownBatch != null) {
            return new ShutdownBatchArmResult(activeShutdownBatch, null, 0, 0, 0, 0);
        }
        if (shutdownFinalTerminal) {
            return new ShutdownBatchArmResult(
                    null, shutdownTerminalFailure, inFlightPublicationTransactionCount, 0, 0, 0);
        }

        freezeAllForShutdownLocked();
        publicationTransactionCountAtCutoff = inFlightPublicationTransactionCount;
        ShutdownCounts counts = shutdownCountsLocked();
        if (counts.hasBlocker()) {
            ShutdownFailure dominant = counts.dominantFailure();
            failAllShutdownRecordsLocked(dominant, null);
            shutdownFinalTerminal = true;
            shutdownTerminalFailure = dominant;
            return new ShutdownBatchArmResult(
                    null,
                    dominant,
                    counts.outstandingTransactions,
                    counts.outstandingLeases,
                    counts.handoffNotRun,
                    counts.externalQueue);
        }

        long batchAttemptId = nextShutdownBatchAttemptIdLocked();
        List<ShutdownCloseRequest> requests = new ArrayList<>();
        for (LifecycleRecord record : allRecordsSnapshotLocked()) {
            if (!hasPendingCloseWork(record)) {
                closeSuccessfullyLocked(record);
                continue;
            }
            long recordAttemptId = nextCloseAttemptIdLocked(record);
            record.activeCloseAttemptId = recordAttemptId;
            record.closeHandoffInFlight = false;
            record.pendingFencedCallback = false;
            record.shutdownBatchAttemptId = batchAttemptId;
            record.shutdownFailure = null;
            record.state = State.SHUTDOWN_FINAL_FENCE_ARMED;
            requests.add(new ShutdownCloseRequest(record, recordAttemptId));
        }
        FinalCloseBatch batch = new FinalCloseBatch(batchAttemptId, requests);
        activeShutdownBatch = batch;
        return new ShutdownBatchArmResult(batch, null, 0, 0, 0, 0);
    }

    synchronized boolean markShutdownFinalFenceWait(FinalCloseBatch batch) {
        if (!isActiveShutdownBatchLocked(batch) || shutdownFinalTerminal) {
            return false;
        }
        for (ShutdownCloseRequest request : batch.closeRequests) {
            if (!isCurrentShutdownRequestLocked(batch, request)
                    || request.record.state != State.SHUTDOWN_FINAL_FENCE_ARMED) {
                return false;
            }
        }
        for (ShutdownCloseRequest request : batch.closeRequests) {
            request.record.state = State.SHUTDOWN_FINAL_FENCE_WAIT;
        }
        return true;
    }

    ShutdownBatchCompletion completeShutdownFinalBatch(FinalCloseBatch batch) {
        List<ShutdownCloseRequest> requests;
        synchronized (this) {
            if (!isActiveShutdownBatchLocked(batch) || shutdownFinalTerminal) {
                return new ShutdownBatchCompletion(false, 0, 0, null);
            }
            requests = batch.closeRequests;
            for (ShutdownCloseRequest request : requests) {
                if (!isCurrentShutdownRequestLocked(batch, request)
                        || request.record.state != State.SHUTDOWN_FINAL_FENCE_WAIT) {
                    return new ShutdownBatchCompletion(false, 0, 0, null);
                }
            }
            for (ShutdownCloseRequest request : requests) {
                request.record.state = State.CLOSING;
            }
        }

        Throwable renderThreadFailure = null;
        try {
            if (renderOwner == null) {
                throw new IllegalStateException("No render owner is installed for the shutdown final batch");
            }
            renderOwner.assertOnRenderThread();
        } catch (Throwable failure) {
            renderThreadFailure = failure;
        }

        Map<LifecycleRecord, Throwable> failures = new IdentityHashMap<>();
        Throwable aggregateFailure = null;
        for (ShutdownCloseRequest request : requests) {
            Throwable recordFailure = renderThreadFailure == null ? runPhysicalClose(request.record) : renderThreadFailure;
            failures.put(request.record, recordFailure);
            aggregateFailure = appendFailure(aggregateFailure, recordFailure);
        }

        int closed = 0;
        int failed = 0;
        synchronized (this) {
            if (!isActiveShutdownBatchLocked(batch) || shutdownFinalTerminal) {
                return new ShutdownBatchCompletion(false, 0, 0, aggregateFailure);
            }
            for (ShutdownCloseRequest request : requests) {
                if (!isCurrentShutdownRequestLocked(batch, request) || request.record.state != State.CLOSING) {
                    return new ShutdownBatchCompletion(false, 0, 0, aggregateFailure);
                }
            }
            for (ShutdownCloseRequest request : requests) {
                Throwable failure = failures.get(request.record);
                if (failure == null) {
                    closeSuccessfullyLocked(request.record);
                    closed++;
                } else {
                    markShutdownFailureLocked(request.record, ShutdownFailure.PHYSICAL_CLOSE_FAILED, failure);
                    failed++;
                }
            }
            activeShutdownBatch = null;
            shutdownFinalTerminal = true;
            shutdownTerminalFailure = failed == 0 ? null : ShutdownFailure.PHYSICAL_CLOSE_FAILED;
        }
        return new ShutdownBatchCompletion(true, closed, failed, aggregateFailure);
    }

    synchronized boolean failShutdownFinalBatch(
            FinalCloseBatch batch, ShutdownFailure failure, Throwable cause) {
        Objects.requireNonNull(failure, "failure");
        if (!isActiveShutdownBatchLocked(batch) || shutdownFinalTerminal) {
            return false;
        }
        for (ShutdownCloseRequest request : batch.closeRequests) {
            if (isCurrentShutdownRequestLocked(batch, request)) {
                markShutdownFailureLocked(request.record, failure, cause);
            }
        }
        activeShutdownBatch = null;
        shutdownFinalTerminal = true;
        shutdownTerminalFailure = failure;
        return true;
    }

    synchronized ShutdownFailureSummary failShutdownWithoutFinalBatch(
            ShutdownFailure requestedFailure, int outstandingCreatorCount, Throwable cause) {
        Objects.requireNonNull(requestedFailure, "requestedFailure");
        if (outstandingCreatorCount < 0) {
            throw new IllegalArgumentException("Outstanding creator count cannot be negative");
        }
        if (shutdownFinalTerminal) {
            ShutdownCounts counts = shutdownCountsLocked();
            return new ShutdownFailureSummary(
                    shutdownTerminalFailure == null ? requestedFailure : shutdownTerminalFailure,
                    outstandingCreatorCount,
                    counts.outstandingTransactions,
                    counts.outstandingLeases,
                    counts.handoffNotRun,
                    counts.externalQueue);
        }
        freezeAllForShutdownLocked();
        publicationTransactionCountAtCutoff = inFlightPublicationTransactionCount;
        ShutdownCounts counts = shutdownCountsLocked();
        ShutdownFailure dominant = outstandingCreatorCount > 0
                ? ShutdownFailure.OUTSTANDING_CREATORS
                : counts.hasBlocker() ? counts.dominantFailure() : requestedFailure;
        failAllShutdownRecordsLocked(dominant, cause);
        activeShutdownBatch = null;
        shutdownFinalTerminal = true;
        shutdownTerminalFailure = dominant;
        return new ShutdownFailureSummary(
                dominant,
                outstandingCreatorCount,
                counts.outstandingTransactions,
                counts.outstandingLeases,
                counts.handoffNotRun,
                counts.externalQueue);
    }

    void closeRegistry() {
        List<CloseRequest> closeRequests = new ArrayList<>();
        synchronized (this) {
            shutdown = true;
            for (LifecycleRecord record : allRecordsSnapshotLocked()) {
                if (record.indexed) {
                    if (record.state == State.CLOSE_FAILED) {
                        scheduleCloseIfDrainedLocked(record, closeRequests);
                    } else {
                        retireLocked(record.generation, false, closeRequests);
                    }
                } else if (record.state == State.RETIRING || record.state == State.CLOSE_FAILED) {
                    scheduleCloseIfDrainedLocked(record, closeRequests);
                }
            }
        }
        dispatch(closeRequests);
    }

    synchronized DrainDiagnostics diagnostics() {
        int retiring = 0;
        int leases = 0;
        int fenced = 0;
        int failed = 0;
        int closed = 0;
        List<GenerationDiagnostics> generations = new ArrayList<>();
        for (LifecycleRecord record : allRecordsSnapshotLocked()) {
            if (record.state == State.RETIRING) {
                retiring++;
            }
            if (record.state == State.FENCE_QUEUED) {
                fenced++;
            }
            if (record.state == State.CLOSE_FAILED) {
                failed++;
            }
            if (record.state == State.CLOSED) {
                closed++;
            }
            leases += record.leaseCount;
            generations.add(new GenerationDiagnostics(
                    record.generation.generationId(),
                    record.state,
                    record.leaseCount,
                    record.activeCloseAttemptId,
                    record.lastCloseAttemptId,
                    record.closeFailure == null ? null : record.closeFailure.getClass().getName(),
                    record.shutdownFailure,
                    record.payloadMode,
                    record.physicalResourceCount,
                    record.physicalByteCount,
                    record.resourceReady,
                    record.publicationReady,
                    record.resourceSet == null ? null : record.resourceSet.ownership(),
                    record.retryForbidden));
        }
        return new DrainDiagnostics(
                registry.current().generationId(),
                generations.size(),
                inFlightPublicationTransactionCount,
                publicationTransactionCountAtCutoff,
                retiring,
                leases,
                fenced,
                failed,
                closed,
                List.copyOf(generations));
    }

    synchronized int retainedRetiredBackendHandleCount() {
        int retained = 0;
        ModelRegistryGeneration active = registry.current();
        for (LifecycleRecord record : allRecordsSnapshotLocked()) {
            if (record.resourceReady && record.generation != active && record.state != State.PUBLISHED) {
                retained += record.generation.backendHandleCount();
            }
        }
        return retained;
    }

    private PreallocatedOffer preallocateOffer(PendingGenerationTransaction.PreclaimPayload payload) {
        synchronized (this) {
            LifecycleRecord record = new LifecycleRecord(
                    nextLifecycleRecordIdLocked(),
                    payload.candidate(),
                    payload.payloadMode(),
                    payload.completeSet(),
                    payload.policyProjection(),
                    policyMetricsMaterializer,
                    State.PENDING_RENDER_TRANSACTION,
                    false,
                    this);
            List<CloseRequest> closeRequests = new ArrayList<>(1);
            record.preallocateCloseRequest(this);
            if (records.containsKey(record.generation)) {
                stagedOrDetachedRecords.add(record);
            } else {
                records.put(record.generation, record);
                record.registeredInRecordIndex = true;
            }
            return new PreallocatedOffer(record, closeRequests);
        }
    }

    /** Removes a record whose transaction never won the caller-to-claim move. */
    private synchronized void discardUnclaimedPreallocatedRecord(LifecycleRecord record) {
        if (record.resourceReady) {
            throw new IllegalStateException("A D1-ready record cannot return to pre-claim staging");
        }
        if (record.registeredInRecordIndex) {
            if (records.get(record.generation) == record) {
                records.remove(record.generation);
            }
        } else {
            stagedOrDetachedRecords.remove(record);
        }
        if (record.policyResourceRecord != null) {
            record.policyResourceRecord.clearAfterTerminalRecordRemoval();
            record.policyResourceRecord = null;
        }
        record.generation = null;
    }

    private ModelRegistryGeneration offerFrozenClaimLocked(
            PendingGenerationTransaction.Claim claim,
            LifecycleRecord record,
            PublicationTransactionPermit publicationPermit,
            PublicationAdoptionBarrier publicationAdoptionBarrier,
            List<CloseRequest> closeRequests) {
        ModelRegistryGeneration candidate = claim.candidate();
        if (record.generation != candidate
                || record.payloadMode != claim.payloadMode()
                || record.resourceSet != claim.completeSet()
                || record.policyOwner.publishedProjection() != claim.policyProjection()) {
            throw new IllegalStateException("Preallocated D1 record no longer matches the exact transaction payload");
        }
        if (shutdown || activeShutdownBatch != null || shutdownFinalTerminal) {
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException("Client model registry is closing"),
                    false,
                    false,
                    closeRequests);
        }
        if (registry.current() == candidate) {
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException("Generation " + candidate.generationId() + " is already active"),
                    false,
                    false,
                    closeRequests);
        }
        if (!record.registeredInRecordIndex) {
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException(
                            "Generation " + candidate.generationId()
                                    + " already has a pre-claim D1 lifecycle reservation"),
                    false,
                    false,
                    closeRequests);
        }
        if (!candidate.tryAttachResourceOwner(this)) {
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException(
                            "Generation " + candidate.generationId() + " belongs to another resource lifecycle owner"),
                    false,
                    false,
                    closeRequests);
        }
        if (candidate.isRetired()) {
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException("Generation " + candidate.generationId() + " is already retired"),
                    false,
                    false,
                    closeRequests);
        }

        ModelRegistryGeneration active = registry.current();
        if (candidate.generationId() <= active.generationId()) {
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException(
                            "Generation " + candidate.generationId() + " is stale behind " + active.generationId()),
                    true,
                    true,
                    closeRequests);
        }

        adoptRecordIntoD1Locked(record, true);
        // A cleanup-ready detached record is deliberately not a CAS authority.  This bit is set only for the
        // indexed, fully validated normal publication record after its aggregate has entered D1.
        record.publicationReady = true;
        record.state = State.POLICY_FROZEN;
        publicationAdoptionBarrier.beforePublicationCas(publicationProbeLocked(record, publicationPermit));
        ClientModelRegistry.ResourceOwnerPublication publication = registry.publishAtomicallyFromResourceOwner(
                candidate, record.publicationPermit);
        // The CAS proof is one-shot. Once the registry call returns, this record may remain cleanup-ready but no
        // longer has normal publication authority regardless of whether the CAS won.
        record.publicationReady = false;
        if (!publication.published()) {
            ModelRegistryGeneration newer = publication.activeGeneration();
            return abortClaimedLocked(
                    claim,
                    record,
                    new IllegalStateException(
                            "Generation " + candidate.generationId() + " is stale behind " + newer.generationId()),
                    true,
                    true,
                    closeRequests);
        }

        record.state = State.PUBLISHED;
        claim.completePublication();
        retireLocked(publication.displacedGeneration(), false, closeRequests);
        return candidate;
    }

    private ModelRegistryGeneration abortClaimedAndReturnActive(
            PendingGenerationTransaction.Claim claim,
            LifecycleRecord record,
            Throwable failure,
            boolean indexed,
            boolean stale,
            List<CloseRequest> closeRequests) {
        ModelRegistryGeneration active;
        synchronized (this) {
            active = abortClaimedLocked(claim, record, failure, indexed, stale, closeRequests);
        }
        dispatch(closeRequests);
        return active;
    }

    private ModelRegistryGeneration abortClaimedLocked(
            PendingGenerationTransaction.Claim claim,
            LifecycleRecord record,
            Throwable failure,
            boolean indexed,
            boolean stale,
            List<CloseRequest> closeRequests) {
        claim.abort(Objects.requireNonNull(failure, "failure"));
        if (!record.resourceReady) {
            try {
                adoptRecordIntoD1Locked(record, indexed);
            } catch (Throwable adoptionFailure) {
                CompletedGenerationResourceSet.CleanupFailureSelector selector =
                        new CompletedGenerationResourceSet.CleanupFailureSelector();
                selector.record(adoptionFailure);
                try {
                    claim.closeClaimOwnedCompleteAfterD1InsertionFailure();
                } catch (Throwable claimCloseFailure) {
                    selector.record(claimCloseFailure);
                }
                selector.rethrowWithPrimary(failure);
                throw new AssertionError("unreachable");
            }
        }
        abortD1RecordLocked(record, stale, closeRequests);
        return registry.current();
    }

    /** Inserts the exact preallocated record before the aggregate claim-to-D1 move and ready mark. */
    private void adoptRecordIntoD1Locked(LifecycleRecord record, boolean indexed) {
        if (record.resourceReady) {
            throw new IllegalStateException("A lifecycle record may become resource-ready only once");
        }
        if (record.registeredInRecordIndex) {
            if (records.get(record.generation) != record) {
                throw new IllegalStateException("Preallocated D1 index reservation no longer names this exact record");
            }
        } else if (!stagedOrDetachedRecords.contains(record)) {
            throw new IllegalStateException("Preallocated D1 detached reservation no longer names this exact record");
        }
        record.indexed = indexed;
        try {
            if (record.payloadMode == PendingGenerationTransaction.PayloadMode.COMPLETE_SET
                    && record.resourceSet.tryAdoptClaimOwnedCompleteByD1()
                            != CompletedGenerationResourceSet.D1Adoption.ADOPTED) {
                throw new IllegalStateException("The exact complete payload was not claim-owned for D1 adoption");
            }
            record.resourceReady = true;
        } catch (Throwable failure) {
            record.indexed = false;
            throw failure;
        }
    }

    private PublicationAdoptionProbe publicationProbeLocked(
            LifecycleRecord record, PublicationTransactionPermit publicationPermit) {
        return new PublicationAdoptionProbe(
                record.recordId,
                record.generation.generationId(),
                record.resourceSet == null ? 0 : System.identityHashCode(record.resourceSet),
                record.payloadMode,
                record.physicalResourceCount,
                record.physicalByteCount,
                record.resourceSet == null ? null : record.resourceSet.ownership(),
                registry.current().generationId(),
                record.resourceReady,
                record.publicationReady,
                publicationPermit.isHeldBy(this));
    }

    private void consumeResourceOwnerPublicationPermit(
            ResourceOwnerPublicationPermit proof,
            ClientModelRegistry expectedRegistry,
            ModelRegistryGeneration candidate) {
        synchronized (this) {
            if (proof.owner != this
                    || proof.registry != expectedRegistry
                    || proof.consumed
                    || proof.record.generation != candidate
                    || !proof.record.indexed
                    || !proof.record.resourceReady
                    || !proof.record.publicationReady
                    || proof.record.state != State.POLICY_FROZEN
                    || records.get(candidate) != proof.record
                    || !candidate.isAttachedToResourceOwner(this)) {
                throw new IllegalStateException("Resource-owner publication proof is not ready for this exact candidate");
            }
            proof.consumed = true;
        }
    }

    private void abortD1RecordLocked(LifecycleRecord record, boolean stale, List<CloseRequest> closeRequests) {
        record.publicationReady = false;
        record.state = State.ABORTED;
        if (record.indexed) {
            ModelRegistryGeneration generation = record.generation;
            if (generation.retireFromResourceOwner(this)) {
                registry.recordRetirementFromResourceOwner(generation, stale);
            }
        } else if (record.generation != registry.current()) {
            // A detached loser still needs a permanent no-republication terminal state. Claim the otherwise
            // unowned candidate only for this D1 cleanup record; a foreign winner makes the claim fail and is
            // deliberately left untouched.
            ModelRegistryGeneration generation = record.generation;
            if (generation.tryAttachResourceOwner(this) && generation.retireFromResourceOwner(this)) {
                registry.recordRetirementFromResourceOwner(generation, stale);
            }
        }
        record.state = State.RETIRING;
        scheduleCloseIfDrainedLocked(record, closeRequests);
    }

    private boolean retireLocked(
            ModelRegistryGeneration generation, boolean stale, List<CloseRequest> closeRequests) {
        LifecycleRecord record = records.get(generation);
        if (record == null) {
            return false;
        }
        if (!generation.retireFromResourceOwner(this)) {
            return false;
        }
        record.state = State.RETIRING;
        registry.recordRetirementFromResourceOwner(generation, stale);
        scheduleCloseIfDrainedLocked(record, closeRequests);
        return true;
    }

    private void freezeAllForShutdownLocked() {
        shutdown = true;
        for (LifecycleRecord record : allRecordsSnapshotLocked()) {
            if (record.indexed && isPublishedShutdownCandidate(record.state)) {
                ModelRegistryGeneration generation = record.generation;
                if (generation.retireFromResourceOwner(this)) {
                    registry.recordRetirementFromResourceOwner(generation, false);
                }
                record.state = State.RETIRING;
            } else if (!record.indexed && record.state == State.ABORTED) {
                record.state = State.RETIRING;
            }
        }
    }

    private static boolean isPublishedShutdownCandidate(State state) {
        return state == State.PENDING_RENDER_TRANSACTION
                || state == State.POLICY_FROZEN
                || state == State.PUBLISHED
                || state == State.ABORTED;
    }

    private ShutdownCounts shutdownCountsLocked() {
        int leases = 0;
        int handoffs = 0;
        int externalQueue = 0;
        for (LifecycleRecord record : allRecordsSnapshotLocked()) {
            leases += record.leaseCount;
            if (record.closeHandoffInFlight) {
                handoffs++;
            }
            if (record.pendingFencedCallback || record.state == State.FENCE_QUEUED || record.state == State.CLOSING) {
                externalQueue++;
            }
        }
        return new ShutdownCounts(inFlightPublicationTransactionCount, leases, handoffs, externalQueue);
    }

    private void failAllShutdownRecordsLocked(ShutdownFailure dominantFailure, Throwable cause) {
        for (LifecycleRecord record : allRecordsSnapshotLocked()) {
            if (!record.resourceReady && record.state == State.PENDING_RENDER_TRANSACTION) {
                // The admission permit is already visible to T1c, but the caller still owns this pre-claim payload.
                // Retain its preallocated record until the permit holder either loses claim or adopts this same record
                // into D1 cleanup after observing the cutoff.
                markShutdownFailureLocked(record, ShutdownFailure.OUTSTANDING_TRANSACTIONS, cause);
                continue;
            }
            boolean externalQueue = record.pendingFencedCallback
                    || record.state == State.FENCE_QUEUED
                    || record.state == State.CLOSING;
            if (!hasPendingCloseWork(record)
                    && record.leaseCount == 0
                    && !record.closeHandoffInFlight
                    && !externalQueue) {
                closeSuccessfullyLocked(record);
                continue;
            }
            ShutdownFailure recordFailure = record.leaseCount > 0
                    ? ShutdownFailure.OUTSTANDING_LEASES
                    : record.closeHandoffInFlight
                            ? ShutdownFailure.HANDOFF_NOT_RUN
                            : externalQueue ? ShutdownFailure.EXTERNAL_QUEUE_PENDING : dominantFailure;
            markShutdownFailureLocked(record, recordFailure, cause);
        }
    }

    private void markShutdownFailureLocked(
            LifecycleRecord record, ShutdownFailure failure, Throwable cause) {
        record.activeCloseAttemptId = 0L;
        record.closeHandoffInFlight = false;
        record.pendingFencedCallback = false;
        record.shutdownBatchAttemptId = 0L;
        record.shutdownFailure = failure;
        record.retryForbidden = true;
        if (cause != null) {
            record.closeFailure = cause;
        }
        record.state = stateForShutdownFailure(failure);
    }

    private static State stateForShutdownFailure(ShutdownFailure failure) {
        return switch (failure) {
            case NO_FINAL_PRESENT -> State.SHUTDOWN_NO_FINAL_PRESENT;
            case OUTSTANDING_CREATORS -> State.SHUTDOWN_OUTSTANDING_CREATORS;
            case OUTSTANDING_TRANSACTIONS -> State.SHUTDOWN_OUTSTANDING_TRANSACTIONS;
            case OUTSTANDING_LEASES -> State.SHUTDOWN_OUTSTANDING_LEASES;
            case HANDOFF_NOT_RUN -> State.SHUTDOWN_HANDOFF_NOT_RUN;
            case EXTERNAL_QUEUE_PENDING -> State.SHUTDOWN_EXTERNAL_QUEUE;
            case ADAPTER_MISMATCH -> State.SHUTDOWN_ADAPTER_MISMATCH;
            case FENCE_ARM_FAILED -> State.SHUTDOWN_FENCE_ARM_FAILED;
            case FENCE_TIMEOUT -> State.SHUTDOWN_FENCE_TIMEOUT;
            case FENCE_WAIT_FAILED -> State.SHUTDOWN_FENCE_WAIT_FAILED;
            case PHYSICAL_CLOSE_FAILED -> State.CLOSE_FAILED;
            case INTERNAL_FAILURE -> State.SHUTDOWN_INTERNAL_FAILED;
        };
    }

    private long nextShutdownBatchAttemptIdLocked() {
        long attemptId = ++lastShutdownBatchAttemptId;
        if (attemptId == 0L) {
            attemptId = ++lastShutdownBatchAttemptId;
        }
        return attemptId;
    }

    private long nextLifecycleRecordIdLocked() {
        long recordId = ++lastLifecycleRecordId;
        if (recordId == 0L) {
            recordId = ++lastLifecycleRecordId;
        }
        return recordId;
    }

    private static long nextCloseAttemptIdLocked(LifecycleRecord record) {
        long attemptId = ++record.lastCloseAttemptId;
        if (attemptId == 0L) {
            attemptId = ++record.lastCloseAttemptId;
        }
        return attemptId;
    }

    private boolean isActiveShutdownBatchLocked(FinalCloseBatch batch) {
        return batch != null
                && activeShutdownBatch == batch
                && activeShutdownBatch.attemptId == batch.attemptId;
    }

    private static boolean isCurrentShutdownRequestLocked(
            FinalCloseBatch batch, ShutdownCloseRequest request) {
        return request.record.shutdownBatchAttemptId == batch.attemptId
                && request.record.activeCloseAttemptId == request.recordAttemptId;
    }

    private static boolean hasPendingCloseWork(LifecycleRecord record) {
        return record.resourceSet != null;
    }

    private static Throwable runPhysicalClose(LifecycleRecord record) {
        if (record.resourceSet == null) {
            return null;
        }
        try {
            record.resourceSet.closeFromD1VerifiedCompletion();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void scheduleCloseIfDrainedLocked(LifecycleRecord record, List<CloseRequest> closeRequests) {
        CloseRequest request = scheduleCloseIfDrainedLocked(record);
        if (request != null) {
            closeRequests.add(request);
        }
    }

    /**
     * Allocation-free close scheduling used by submitted-child rollback.  The returned request was allocated when
     * the lifecycle record was reserved, so no child can become orphaned while an allocation Error is unwinding.
     */
    private CloseRequest scheduleCloseIfDrainedLocked(LifecycleRecord record) {
        if (record.leaseCount != 0
                || record.closeHandoffInFlight
                || record.state != State.RETIRING && record.state != State.CLOSE_FAILED) {
            return null;
        }
        if (!hasPendingCloseWork(record)) {
            closeSuccessfullyLocked(record);
            return null;
        }
        if (activeShutdownBatch != null || shutdownFinalTerminal) {
            ShutdownFailure failure = shutdownTerminalFailure == null
                    ? ShutdownFailure.OUTSTANDING_TRANSACTIONS
                    : shutdownTerminalFailure;
            markShutdownFailureLocked(record, failure, null);
            return null;
        }
        if (renderOwner == null) {
            record.state = State.CLOSE_FAILED;
            record.closeFailure = new IllegalStateException("No legal render owner is installed for generation close");
            record.activeCloseAttemptId = 0L;
            return null;
        }
        if (record.state == State.CLOSE_FAILED) {
            record.state = State.RETIRING;
        }
        long attemptId = nextCloseAttemptIdLocked(record);
        CloseRequest request = record.preallocatedCloseRequest;
        if (request == null) {
            throw new IllegalStateException("A claimed lifecycle record is missing its preallocated close request");
        }
        request.attemptId = attemptId;
        record.activeCloseAttemptId = attemptId;
        record.closeHandoffInFlight = true;
        return request;
    }

    private static boolean isRetryableCloseRecord(LifecycleRecord record) {
        return record != null
                && record.state == State.CLOSE_FAILED
                && record.leaseCount == 0
                && !record.retryForbidden;
    }

    private void dispatch(List<CloseRequest> closeRequests) {
        for (CloseRequest request : closeRequests) {
            dispatch(request);
        }
    }

    private void dispatch(CloseRequest request) {
        try {
            renderOwner.handoffToRenderThread(request.handoff);
        } catch (Throwable failure) {
            closeSchedulingFailed(request, failure);
        }
    }

    private void queueFenceOnRenderOwner(CloseRequest request) {
        try {
            renderOwner.assertOnRenderThread();
            boolean runDeferredCallback;
            synchronized (this) {
                if (request.record.activeCloseAttemptId != request.attemptId
                        || !request.record.closeHandoffInFlight) {
                    return;
                }
                if (activeShutdownBatch != null || shutdownFinalTerminal) {
                    ShutdownFailure failure = shutdownTerminalFailure == null
                            ? ShutdownFailure.HANDOFF_NOT_RUN
                            : shutdownTerminalFailure;
                    markShutdownFailureLocked(request.record, failure, null);
                    return;
                }
                renderOwner.queueFencedTask(request.fenced);
                request.record.closeHandoffInFlight = false;
                request.record.state = State.FENCE_QUEUED;
                runDeferredCallback = request.record.pendingFencedCallback;
                request.record.pendingFencedCallback = false;
            }
            if (runDeferredCallback) {
                runFencedClose(request);
            }
        } catch (Throwable failure) {
            closeSchedulingFailed(request, failure);
        }
    }

    private void runFencedClose(CloseRequest request) {
        synchronized (this) {
            if (request.record.activeCloseAttemptId != request.attemptId) {
                return;
            }
            if (request.record.closeHandoffInFlight) {
                request.record.pendingFencedCallback = true;
                return;
            }
            if (request.record.state != State.FENCE_QUEUED) {
                return;
            }
            request.record.state = State.CLOSING;
        }
        Throwable failure;
        try {
            renderOwner.assertOnRenderThread();
            failure = runPhysicalClose(request.record);
        } catch (Throwable assertionFailure) {
            failure = assertionFailure;
        }
        synchronized (this) {
            if (request.record.activeCloseAttemptId != request.attemptId || request.record.state != State.CLOSING) {
                return;
            }
            request.record.activeCloseAttemptId = 0L;
            request.record.closeFailure = failure;
            request.record.state = failure == null ? State.CLOSED : State.CLOSE_FAILED;
            if (failure == null) {
                closeSuccessfullyLocked(request.record);
            }
        }
    }

    private void closeSchedulingFailed(CloseRequest request, Throwable failure) {
        synchronized (this) {
            if (request.record.activeCloseAttemptId != request.attemptId || !request.record.closeHandoffInFlight) {
                return;
            }
            request.record.closeHandoffInFlight = false;
            request.record.pendingFencedCallback = false;
            request.record.activeCloseAttemptId = 0L;
            request.record.closeFailure = failure;
            request.record.state = State.CLOSE_FAILED;
        }
    }

    private static Throwable appendFailure(Throwable primary, Throwable additional) {
        if (additional == null) {
            return primary;
        }
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private void closeSuccessfullyLocked(LifecycleRecord record) {
        record.state = State.CLOSED;
        record.closeFailure = null;
        record.activeCloseAttemptId = 0L;
        record.closeHandoffInFlight = false;
        record.pendingFencedCallback = false;
        record.shutdownBatchAttemptId = 0L;
        record.shutdownFailure = null;
        record.retryForbidden = true;
        if (record.registeredInRecordIndex) {
            if (records.get(record.generation) == record) {
                records.remove(record.generation);
            }
        } else {
            stagedOrDetachedRecords.remove(record);
        }
        if (record.policyResourceRecord != null) {
            record.policyResourceRecord.clearAfterTerminalRecordRemoval();
            record.policyResourceRecord = null;
        }
        record.generation = null;
    }

    private List<LifecycleRecord> allRecordsSnapshotLocked() {
        List<LifecycleRecord> snapshot = new ArrayList<>(records.values());
        snapshot.addAll(stagedOrDetachedRecords);
        return snapshot;
    }

    private synchronized PublicationTransactionPermit tryAdmitPublicationTransaction() {
        if (shutdown || activeShutdownBatch != null || shutdownFinalTerminal) {
            return null;
        }
        inFlightPublicationTransactionCount++;
        return new PublicationTransactionPermit(this);
    }

    private synchronized void releasePublicationTransaction(PublicationTransactionPermit permit) {
        if (!permit.markReleased()) {
            return;
        }
        if (inFlightPublicationTransactionCount <= 0) {
            throw new IllegalStateException("Publication transaction permit count underflow");
        }
        inFlightPublicationTransactionCount--;
    }

    private static Throwable nonNullAbortCause(PendingGenerationTransaction.Claim claim) {
        Throwable cause = claim.abortCause();
        return cause == null ? new IllegalStateException("Aborted transaction did not retain a cause") : cause;
    }

    private static final class PreallocatedOffer {
        private final LifecycleRecord record;
        private final List<CloseRequest> closeRequests;

        private PreallocatedOffer(LifecycleRecord record, List<CloseRequest> closeRequests) {
            this.record = record;
            this.closeRequests = closeRequests;
        }
    }

    /** Package-private transfer unit; it exposes no owner, resource, or render handle outside reload. */
    record SubmittedLease(
            GenerationRenderResourceLease lease,
            X7PolicyResourceRecord policyResourceRecord,
            X7PublishedSubmissionBridge.SubmissionProof policyProof) {
        SubmittedLease {
            lease = Objects.requireNonNull(lease, "lease");
            policyResourceRecord = Objects.requireNonNull(policyResourceRecord, "policyResourceRecord");
            policyProof = Objects.requireNonNull(policyProof, "policyProof");
        }
    }

    private static final class LifecycleRecord {
        private final long recordId;
        private ModelRegistryGeneration generation;
        private final PendingGenerationTransaction.PayloadMode payloadMode;
        private final CompletedGenerationResourceSet resourceSet;
        /** The one immutable policy projection owner retained inside this same D1 lifecycle record. */
        private final X7ProductionPolicyOwner policyOwner;
        /** Constructed before resourceReady and retained only by this exact D1 lifecycle record. */
        private X7PolicyResourceRecord policyResourceRecord;
        private final int physicalResourceCount;
        private final long physicalByteCount;
        private final ResourceOwnerPublicationPermit publicationPermit;
        /** True when this record reserved the identity-index node before transaction claim. */
        private boolean registeredInRecordIndex;
        private boolean indexed;
        private boolean resourceReady;
        /** True only for the indexed normal record that may consume its one CAS permit. */
        private boolean publicationReady;
        private State state;
        private int leaseCount;
        private long activeCloseAttemptId;
        private long lastCloseAttemptId;
        private Throwable closeFailure;
        private boolean closeHandoffInFlight;
        private boolean pendingFencedCallback;
        private long shutdownBatchAttemptId;
        private ShutdownFailure shutdownFailure;
        private boolean retryForbidden;
        /** Allocated before claim and reused only after a completed/failed close attempt is no longer in flight. */
        private CloseRequest preallocatedCloseRequest;

        private LifecycleRecord(
                long recordId,
                ModelRegistryGeneration generation,
                PendingGenerationTransaction.PayloadMode payloadMode,
                CompletedGenerationResourceSet resourceSet,
                X7PublishedGenerationProjection policyProjection,
                PolicyMetricsMaterializer policyMetricsMaterializer,
                State state,
                boolean indexed,
                ClientGenerationResourceOwner owner) {
            this.recordId = recordId;
            this.generation = Objects.requireNonNull(generation, "generation");
            this.payloadMode = Objects.requireNonNull(payloadMode, "payloadMode");
            this.resourceSet = resourceSet;
            X7PublishedGenerationProjection checkedProjection = Objects.requireNonNull(policyProjection, "policyProjection");
            checkedProjection.requireExactCandidate(generation);
            this.policyOwner = new X7ProductionPolicyOwner(checkedProjection, policyMetricsMaterializer);
            if (payloadMode == PendingGenerationTransaction.PayloadMode.COMPLETE_SET) {
                CompletedGenerationResourceSet checkedSet = Objects.requireNonNull(resourceSet, "resourceSet");
                if (checkedSet.exactGeneration() != generation) {
                    throw new IllegalArgumentException("Lifecycle record must retain the exact complete generation set");
                }
                this.physicalResourceCount = checkedSet.physicalResourceCount();
                this.physicalByteCount = checkedSet.physicalByteCount();
            } else if (resourceSet != null) {
                throw new IllegalArgumentException("CPU-only lifecycle record cannot retain a physical resource set");
            } else {
                this.physicalResourceCount = 0;
                this.physicalByteCount = 0L;
            }
            this.policyResourceRecord = resourceSet == null
                    ? null
                    : X7PolicyResourceRecord.forCanonicalCompleteSet(generation, resourceSet);
            this.state = Objects.requireNonNull(state, "state");
            this.indexed = indexed;
            this.publicationPermit = new ResourceOwnerPublicationPermit(owner, owner.registry, this);
        }

        private void preallocateCloseRequest(ClientGenerationResourceOwner owner) {
            if (preallocatedCloseRequest != null) {
                throw new IllegalStateException("A lifecycle record may preallocate only one close request");
            }
            preallocatedCloseRequest = new CloseRequest(owner, this);
        }
    }

    /** The one-shot capability that lets only a ready record request the registry compare-and-set. */
    static final class ResourceOwnerPublicationPermit {
        private final ClientGenerationResourceOwner owner;
        private final ClientModelRegistry registry;
        private final LifecycleRecord record;
        private boolean consumed;

        private ResourceOwnerPublicationPermit(
                ClientGenerationResourceOwner owner, ClientModelRegistry registry, LifecycleRecord record) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.registry = Objects.requireNonNull(registry, "registry");
            this.record = Objects.requireNonNull(record, "record");
        }

        void consumeFor(ClientModelRegistry expectedRegistry, ModelRegistryGeneration candidate) {
            owner.consumeResourceOwnerPublicationPermit(this, expectedRegistry, candidate);
        }
    }

    /**
     * The handoff and fence callbacks are created before claim and reused only after the prior attempt has reached a
     * terminal callback result. This avoids manufacturing a cleanup callback/node after the exact set has left its
     * caller while retaining the existing attempt-id stale-callback guard.
     */
    private static final class CloseRequest {
        private final LifecycleRecord record;
        private final Runnable handoff;
        private final Runnable fenced;
        private long attemptId;

        private CloseRequest(ClientGenerationResourceOwner owner, LifecycleRecord record) {
            this.record = Objects.requireNonNull(record, "record");
            this.handoff = () -> owner.queueFenceOnRenderOwner(this);
            this.fenced = () -> owner.runFencedClose(this);
        }
    }

    private record ShutdownCloseRequest(LifecycleRecord record, long recordAttemptId) { }

    private record ShutdownCounts(
            int outstandingTransactions, int outstandingLeases, int handoffNotRun, int externalQueue) {
        private boolean hasBlocker() {
            return outstandingTransactions > 0
                    || outstandingLeases > 0
                    || handoffNotRun > 0
                    || externalQueue > 0;
        }

        private ShutdownFailure dominantFailure() {
            if (outstandingTransactions > 0) {
                return ShutdownFailure.OUTSTANDING_TRANSACTIONS;
            }
            if (externalQueue > 0) {
                return ShutdownFailure.EXTERNAL_QUEUE_PENDING;
            }
            if (handoffNotRun > 0) {
                return ShutdownFailure.HANDOFF_NOT_RUN;
            }
            return ShutdownFailure.OUTSTANDING_LEASES;
        }
    }

    private static final class PublicationTransactionPermit implements AutoCloseable {
        private final ClientGenerationResourceOwner owner;
        private boolean released;

        private PublicationTransactionPermit(ClientGenerationResourceOwner owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            owner.releasePublicationTransaction(this);
        }

        private synchronized boolean markReleased() {
            if (released) {
                return false;
            }
            released = true;
            return true;
        }

        private synchronized boolean isHeldBy(ClientGenerationResourceOwner expectedOwner) {
            return owner == expectedOwner && !released;
        }
    }

    static final class FinalCloseBatch {
        private final long attemptId;
        private final List<ShutdownCloseRequest> closeRequests;
        private final List<Long> generationIds;

        private FinalCloseBatch(long attemptId, List<ShutdownCloseRequest> closeRequests) {
            this.attemptId = attemptId;
            this.closeRequests = List.copyOf(closeRequests);
            this.generationIds = this.closeRequests.stream()
                    .map(request -> request.record.generation.generationId())
                    .toList();
        }

        long attemptId() {
            return attemptId;
        }

        int resourceRecordCount() {
            return closeRequests.size();
        }

        boolean isEmpty() {
            return closeRequests.isEmpty();
        }

        List<Long> generationIds() {
            return generationIds;
        }
    }
}
