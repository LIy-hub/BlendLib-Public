package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * Package-private admission and final-present shutdown coordinator for the trusted 26.1.2 client.
 *
 * <p>This coordinator never drains RenderSystem's private fenced-task FIFO. It owns only the one D1 final batch and
 * one fence created after admission is quiescent and before vanilla's original final present.</p>
 */
final class ClientFinalFrameShutdownCoordinator {
    private static final System.Logger LOGGER = System.getLogger("BlendLib");
    enum State {
        RUNNING,
        QUIESCING,
        FINAL_BATCH_SEALED,
        FINAL_FENCE_ARMING,
        FINAL_FENCE_ARMED,
        FINAL_PRESENT_DONE,
        FINAL_FENCE_WAIT,
        PHYSICAL_CLOSE,
        DRAINED,
        FAILED_NO_FINAL_PRESENT,
        FAILED_OUTSTANDING_CREATORS,
        FAILED_OUTSTANDING_TRANSACTIONS,
        FAILED_OUTSTANDING_LEASES,
        FAILED_HANDOFF_NOT_RUN,
        FAILED_EXTERNAL_QUEUE,
        FAILED_ADAPTER_MISMATCH,
        FAILED_FENCE_ARM,
        FAILED_FENCE_TIMEOUT,
        FAILED_FENCE_WAIT,
        FAILED_PHYSICAL_CLOSE,
        FAILED_INTERNAL
    }

    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    interface OwnedFenceAdapter {
        boolean pinnedAdapterAvailable();

        void assertOnRenderThread();

        OwnedFence createOwnedFence();
    }

    interface OwnedFence {
        boolean awaitCompletionZeroTimeout();

        void closeOnRenderThread();
    }

    record Diagnostics(
            State state,
            boolean creatorAdmissionOpen,
            int inFlightCreatorCount,
            int creatorCountAtCutoff,
            long shutdownAttemptId,
            long finalBatchAttemptId,
            int finalBatchResourceRecordCount,
            int finalFencePollCount,
            String failureType,
            String ownedFenceCloseFailureType,
            ClientGenerationResourceOwner.DrainDiagnostics d1) { }

    private final ClientGenerationResourceOwner resourceOwner;
    private final OwnedFenceAdapter fenceAdapter;
    private final NanoClock clock;
    private final long fenceWaitBudgetNanos;
    private final int maximumZeroPolls;

    private State state = State.RUNNING;
    private boolean creatorAdmissionOpen = true;
    private int inFlightCreatorCount;
    private int creatorCountAtCutoff;
    private long lastShutdownAttemptId;
    private long shutdownAttemptId;
    private ClientGenerationResourceOwner.FinalCloseBatch finalBatch;
    private OwnedFence ownedFence;
    private int finalFencePollCount;
    private Throwable failure;
    private Throwable ownedFenceCloseFailure;
    private boolean stoppingDiagnosticPublished;

    ClientFinalFrameShutdownCoordinator(
            ClientGenerationResourceOwner resourceOwner,
            OwnedFenceAdapter fenceAdapter,
            NanoClock clock,
            long fenceWaitBudgetNanos,
            int maximumZeroPolls) {
        this.resourceOwner = Objects.requireNonNull(resourceOwner, "resourceOwner");
        this.fenceAdapter = Objects.requireNonNull(fenceAdapter, "fenceAdapter");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (fenceWaitBudgetNanos <= 0L) {
            throw new IllegalArgumentException("Final-fence wait budget must be positive");
        }
        if (maximumZeroPolls <= 0) {
            throw new IllegalArgumentException("Final-fence maximum poll count must be positive");
        }
        this.fenceWaitBudgetNanos = fenceWaitBudgetNanos;
        this.maximumZeroPolls = maximumZeroPolls;
    }

    synchronized CreatorPermit tryAdmitCreatorBeforeDeviceAccess() {
        if (!creatorAdmissionOpen || state != State.RUNNING) {
            return null;
        }
        inFlightCreatorCount++;
        return new CreatorPermit(this);
    }

    /** Called by the BEFORE injection; returns true only for the one final-frame attempt. */
    boolean beforeOriginalPresent(boolean clientRunning) {
        if (clientRunning) {
            return false;
        }

        int creatorsAtCutoff;
        synchronized (this) {
            if (state != State.RUNNING) {
                return false;
            }
            creatorAdmissionOpen = false;
            state = State.QUIESCING;
            shutdownAttemptId = nextShutdownAttemptIdLocked();
            creatorsAtCutoff = inFlightCreatorCount;
            creatorCountAtCutoff = creatorsAtCutoff;
        }

        try {
            fenceAdapter.assertOnRenderThread();
            if (!fenceAdapter.pinnedAdapterAvailable()) {
                failWithoutFinalBatch(
                        ClientGenerationResourceOwner.ShutdownFailure.ADAPTER_MISMATCH,
                        creatorsAtCutoff,
                        new IllegalStateException("Minecraft 26.1.2 final-present adapter hash mismatch"));
                return true;
            }
            if (creatorsAtCutoff != 0) {
                failWithoutFinalBatch(
                        ClientGenerationResourceOwner.ShutdownFailure.OUTSTANDING_CREATORS,
                        creatorsAtCutoff,
                        null);
                return true;
            }

            ClientGenerationResourceOwner.ShutdownBatchArmResult armed =
                    resourceOwner.armShutdownFinalBatch();
            if (!armed.armed()) {
                transitionToFailure(stateFor(armed.failure()), null);
                return true;
            }

            ClientGenerationResourceOwner.FinalCloseBatch batch = armed.batch();
            synchronized (this) {
                if (state != State.QUIESCING) {
                    resourceOwner.failShutdownFinalBatch(
                            batch,
                            ClientGenerationResourceOwner.ShutdownFailure.INTERNAL_FAILURE,
                            new IllegalStateException("Final-present shutdown attempt lost coordinator ownership"));
                    return true;
                }
                finalBatch = batch;
                state = State.FINAL_BATCH_SEALED;
                if (!batch.isEmpty()) {
                    state = State.FINAL_FENCE_ARMING;
                }
            }
            if (batch.isEmpty()) {
                return true;
            }

            OwnedFence createdFence;
            try {
                createdFence = Objects.requireNonNull(
                        fenceAdapter.createOwnedFence(), "owned final fence");
            } catch (Throwable createFailure) {
                resourceOwner.failShutdownFinalBatch(
                        batch,
                        ClientGenerationResourceOwner.ShutdownFailure.FENCE_ARM_FAILED,
                        createFailure);
                transitionToFailure(State.FAILED_FENCE_ARM, createFailure);
                return true;
            }

            boolean accepted;
            synchronized (this) {
                accepted = state == State.FINAL_FENCE_ARMING && finalBatch == batch;
                if (accepted) {
                    ownedFence = createdFence;
                    state = State.FINAL_FENCE_ARMED;
                }
            }
            if (!accepted) {
                closeUnacceptedFenceBestEffort(createdFence);
            }
            return true;
        } catch (Throwable unexpected) {
            failUnexpectedly(unexpected);
            return true;
        }
    }

    /** Called only by the AFTER injection, which proves vanilla's original present returned normally. */
    void afterOriginalPresent() {
        ClientGenerationResourceOwner.FinalCloseBatch batch;
        OwnedFence fence;
        synchronized (this) {
            if (state != State.FINAL_BATCH_SEALED && state != State.FINAL_FENCE_ARMED) {
                return;
            }
            state = State.FINAL_PRESENT_DONE;
            batch = finalBatch;
            fence = ownedFence;
        }

        try {
            fenceAdapter.assertOnRenderThread();
            if (!resourceOwner.markShutdownFinalFenceWait(batch)) {
                failUnexpectedly(new IllegalStateException("D1 rejected its active final-close batch"));
                return;
            }
            synchronized (this) {
                if (state != State.FINAL_PRESENT_DONE) {
                    return;
                }
                state = State.FINAL_FENCE_WAIT;
            }

            if (batch.isEmpty()) {
                completeSignaledBatch(batch);
                return;
            }

            PollResult poll = pollUntilDeadline(fence);
            if (poll.failure != null) {
                resourceOwner.failShutdownFinalBatch(
                        batch,
                        ClientGenerationResourceOwner.ShutdownFailure.FENCE_WAIT_FAILED,
                        poll.failure);
                transitionToFailure(State.FAILED_FENCE_WAIT, poll.failure);
                closeOwnedFenceBestEffort(fence);
                return;
            }
            if (!poll.signaled) {
                resourceOwner.failShutdownFinalBatch(
                        batch,
                        ClientGenerationResourceOwner.ShutdownFailure.FENCE_TIMEOUT,
                        null);
                transitionToFailure(State.FAILED_FENCE_TIMEOUT, null);
                closeOwnedFenceBestEffort(fence);
                return;
            }

            completeSignaledBatch(batch);
            closeOwnedFenceBestEffort(fence);
        } catch (Throwable unexpected) {
            failUnexpectedly(unexpected);
        }
    }

    /** Non-throwing CLIENT_STOPPING fallback; it never attempts a post-present buffer close. */
    void clientStoppingFallback() {
        try {
            ClientGenerationResourceOwner.FinalCloseBatch batch;
            OwnedFence fence;
            int creators;
            boolean terminal;
            synchronized (this) {
                if (state == State.PHYSICAL_CLOSE) {
                    return;
                }
                terminal = isTerminal(state);
                if (!terminal) {
                    creatorAdmissionOpen = false;
                    if (state == State.RUNNING) {
                        state = State.QUIESCING;
                        shutdownAttemptId = nextShutdownAttemptIdLocked();
                        creatorCountAtCutoff = inFlightCreatorCount;
                    }
                }
                batch = finalBatch;
                fence = ownedFence;
                creators = inFlightCreatorCount;
            }
            if (terminal) {
                closeOwnedFenceBestEffort(fence);
                return;
            }

            ClientGenerationResourceOwner.ShutdownFailureSummary summary;
            if (batch != null) {
                resourceOwner.failShutdownFinalBatch(
                        batch,
                        ClientGenerationResourceOwner.ShutdownFailure.NO_FINAL_PRESENT,
                        null);
                summary = new ClientGenerationResourceOwner.ShutdownFailureSummary(
                        ClientGenerationResourceOwner.ShutdownFailure.NO_FINAL_PRESENT,
                        creators,
                        0,
                        0,
                        0,
                        0);
            } else {
                summary = resourceOwner.failShutdownWithoutFinalBatch(
                        ClientGenerationResourceOwner.ShutdownFailure.NO_FINAL_PRESENT,
                        creators,
                        null);
            }
            transitionToFailure(stateFor(summary.requestedFailure()), null);
            closeOwnedFenceBestEffort(fence);
        } catch (Throwable unexpected) {
            failUnexpectedly(unexpected);
        } finally {
            publishStoppingDiagnosticOnce();
        }
    }

    void failUnexpectedly(Throwable unexpected) {
        Throwable checkedFailure = Objects.requireNonNull(unexpected, "unexpected");
        OwnedFence fence = null;
        try {
            ClientGenerationResourceOwner.FinalCloseBatch batch;
            int creators;
            synchronized (this) {
                fence = ownedFence;
                if (isTerminal(state)) {
                    return;
                }
                creatorAdmissionOpen = false;
                batch = finalBatch;
                creators = inFlightCreatorCount;
            }
            if (batch != null) {
                resourceOwner.failShutdownFinalBatch(
                        batch,
                        ClientGenerationResourceOwner.ShutdownFailure.INTERNAL_FAILURE,
                        checkedFailure);
            } else {
                resourceOwner.failShutdownWithoutFinalBatch(
                        ClientGenerationResourceOwner.ShutdownFailure.INTERNAL_FAILURE,
                        creators,
                        checkedFailure);
            }
            transitionToFailure(State.FAILED_INTERNAL, checkedFailure);
        } catch (Throwable ignored) {
            synchronized (this) {
                creatorAdmissionOpen = false;
                state = State.FAILED_INTERNAL;
                if (failure == null) {
                    failure = checkedFailure;
                }
            }
        } finally {
            closeOwnedFenceBestEffort(fence);
        }
    }

    Diagnostics diagnostics() {
        State snapshotState;
        boolean snapshotAdmission;
        int snapshotCreators;
        int snapshotCreatorsAtCutoff;
        long snapshotAttempt;
        long snapshotBatchAttempt;
        int snapshotBatchRecords;
        int snapshotPolls;
        String snapshotFailure;
        String snapshotFenceCloseFailure;
        synchronized (this) {
            snapshotState = state;
            snapshotAdmission = creatorAdmissionOpen;
            snapshotCreators = inFlightCreatorCount;
            snapshotCreatorsAtCutoff = creatorCountAtCutoff;
            snapshotAttempt = shutdownAttemptId;
            snapshotBatchAttempt = finalBatch == null ? 0L : finalBatch.attemptId();
            snapshotBatchRecords = finalBatch == null ? 0 : finalBatch.resourceRecordCount();
            snapshotPolls = finalFencePollCount;
            snapshotFailure = failure == null ? null : failure.getClass().getName();
            snapshotFenceCloseFailure =
                    ownedFenceCloseFailure == null ? null : ownedFenceCloseFailure.getClass().getName();
        }
        return new Diagnostics(
                snapshotState,
                snapshotAdmission,
                snapshotCreators,
                snapshotCreatorsAtCutoff,
                snapshotAttempt,
                snapshotBatchAttempt,
                snapshotBatchRecords,
                snapshotPolls,
                snapshotFailure,
                snapshotFenceCloseFailure,
                resourceOwner.diagnostics());
    }

    private void publishStoppingDiagnosticOnce() {
        boolean publish;
        synchronized (this) {
            publish = !stoppingDiagnosticPublished
                    && (state.name().startsWith("FAILED_") || ownedFenceCloseFailure != null);
            if (publish) {
                stoppingDiagnosticPublished = true;
            }
        }
        if (!publish) {
            return;
        }
        try {
            Diagnostics snapshot = diagnostics();
            LOGGER.log(System.Logger.Level.WARNING, "BlendLib shutdown diagnostic snapshot: {0}", snapshot);
        } catch (Throwable ignored) {
            // Diagnostics must never escape CLIENT_STOPPING or interfere with vanilla teardown.
        }
    }

    private void completeSignaledBatch(ClientGenerationResourceOwner.FinalCloseBatch batch) {
        synchronized (this) {
            if (state != State.FINAL_FENCE_WAIT) {
                return;
            }
            state = State.PHYSICAL_CLOSE;
        }
        ClientGenerationResourceOwner.ShutdownBatchCompletion completion =
                resourceOwner.completeShutdownFinalBatch(batch);
        if (!completion.accepted()) {
            failUnexpectedly(new IllegalStateException("D1 final-close completion lost attempt authority"));
        } else if (completion.failedRecordCount() != 0) {
            transitionToFailure(State.FAILED_PHYSICAL_CLOSE, completion.failure());
        } else {
            synchronized (this) {
                if (state == State.PHYSICAL_CLOSE) {
                    state = State.DRAINED;
                    finalBatch = null;
                    failure = null;
                }
            }
        }
    }

    private PollResult pollUntilDeadline(OwnedFence fence) {
        long start;
        try {
            start = clock.nanoTime();
        } catch (Throwable clockFailure) {
            return new PollResult(false, clockFailure);
        }
        for (int pollIndex = 0; pollIndex < maximumZeroPolls; pollIndex++) {
            int poll = pollIndex + 1;
            try {
                if (fence.awaitCompletionZeroTimeout()) {
                    synchronized (this) {
                        finalFencePollCount = poll;
                    }
                    return new PollResult(true, null);
                }
            } catch (Throwable waitFailure) {
                synchronized (this) {
                    finalFencePollCount = poll;
                }
                return new PollResult(false, waitFailure);
            }
            synchronized (this) {
                finalFencePollCount = poll;
            }
            long now;
            try {
                now = clock.nanoTime();
            } catch (Throwable clockFailure) {
                return new PollResult(false, clockFailure);
            }
            if (now - start >= fenceWaitBudgetNanos) {
                return new PollResult(false, null);
            }
            Thread.onSpinWait();
        }
        return new PollResult(false, null);
    }

    private void failWithoutFinalBatch(
            ClientGenerationResourceOwner.ShutdownFailure requestedFailure,
            int creatorsAtCutoff,
            Throwable cause) {
        ClientGenerationResourceOwner.ShutdownFailureSummary summary =
                resourceOwner.failShutdownWithoutFinalBatch(requestedFailure, creatorsAtCutoff, cause);
        transitionToFailure(stateFor(summary.requestedFailure()), cause);
    }

    private void transitionToFailure(State failureState, Throwable cause) {
        synchronized (this) {
            if (isTerminal(state)) {
                return;
            }
            state = failureState;
            creatorAdmissionOpen = false;
            if (cause != null && failure == null) {
                failure = cause;
            }
        }
    }

    private void closeOwnedFenceBestEffort(OwnedFence fence) {
        if (fence == null) {
            return;
        }
        boolean ownsFence;
        synchronized (this) {
            ownsFence = ownedFence == fence;
        }
        if (!ownsFence || !closeFenceBestEffort(fence)) {
            return;
        }
        synchronized (this) {
            if (ownedFence == fence) {
                ownedFence = null;
            }
        }
    }

    private void closeUnacceptedFenceBestEffort(OwnedFence fence) {
        if (closeFenceBestEffort(fence)) {
            return;
        }
        synchronized (this) {
            if (ownedFence == null) {
                ownedFence = fence;
            }
        }
    }

    private boolean closeFenceBestEffort(OwnedFence fence) {
        try {
            fence.closeOnRenderThread();
            return true;
        } catch (Throwable closeFailure) {
            synchronized (this) {
                if (ownedFenceCloseFailure == null) {
                    ownedFenceCloseFailure = closeFailure;
                }
            }
            return false;
        }
    }

    private synchronized void releaseCreator(CreatorPermit permit) {
        if (!permit.markReleased()) {
            return;
        }
        if (inFlightCreatorCount <= 0) {
            throw new IllegalStateException("Creator permit count underflow");
        }
        inFlightCreatorCount--;
    }

    private long nextShutdownAttemptIdLocked() {
        long attemptId = ++lastShutdownAttemptId;
        if (attemptId == 0L) {
            attemptId = ++lastShutdownAttemptId;
        }
        return attemptId;
    }

    private static State stateFor(ClientGenerationResourceOwner.ShutdownFailure failure) {
        if (failure == null) {
            return State.FAILED_INTERNAL;
        }
        return switch (failure) {
            case NO_FINAL_PRESENT -> State.FAILED_NO_FINAL_PRESENT;
            case OUTSTANDING_CREATORS -> State.FAILED_OUTSTANDING_CREATORS;
            case OUTSTANDING_TRANSACTIONS -> State.FAILED_OUTSTANDING_TRANSACTIONS;
            case OUTSTANDING_LEASES -> State.FAILED_OUTSTANDING_LEASES;
            case HANDOFF_NOT_RUN -> State.FAILED_HANDOFF_NOT_RUN;
            case EXTERNAL_QUEUE_PENDING -> State.FAILED_EXTERNAL_QUEUE;
            case ADAPTER_MISMATCH -> State.FAILED_ADAPTER_MISMATCH;
            case FENCE_ARM_FAILED -> State.FAILED_FENCE_ARM;
            case FENCE_TIMEOUT -> State.FAILED_FENCE_TIMEOUT;
            case FENCE_WAIT_FAILED -> State.FAILED_FENCE_WAIT;
            case PHYSICAL_CLOSE_FAILED -> State.FAILED_PHYSICAL_CLOSE;
            case INTERNAL_FAILURE -> State.FAILED_INTERNAL;
        };
    }

    private static boolean isTerminal(State state) {
        return state == State.DRAINED || state.name().startsWith("FAILED_");
    }

    static final class CreatorPermit implements AutoCloseable {
        private final ClientFinalFrameShutdownCoordinator owner;
        private boolean released;

        private CreatorPermit(ClientFinalFrameShutdownCoordinator owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            owner.releaseCreator(this);
        }

        private synchronized boolean markReleased() {
            if (released) {
                return false;
            }
            released = true;
            return true;
        }
    }

    private record PollResult(boolean signaled, Throwable failure) { }
}
