package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.spi.experimental.ProviderLease;
import java.util.Objects;
import java.util.Optional;

/**
 * One caller-owned immutable X4 snapshot pinned by an actual X1 provider lease and, when managed,
 * one D1 registry-generation lease.
 *
 * <p>{@link #close()} immediately prevents any new public lease access. The raw renderer snapshot
 * never crosses this public boundary: only the owning adapter can acquire its package-private
 * {@link SubmissionHold}. An already acquired hold remains valid until its matching release, so a
 * close/submit race cannot release the underlying {@link ProviderLease} while the renderer is
 * consuming the immutable snapshot.</p>
 */
public final class X4PreparedSnapshot implements AutoCloseable {
    interface ReleaseListener {
        void release(X4PreparedSnapshot snapshot, SubmissionHold releasingHold);
    }

    private final Object monitor = new Object();
    private final X4HostKind hostKind;
    private final X4HostIdentity identity;
    private final BlendModelKey modelKey;
    private final long generation;
    private final ModelRenderSnapshot snapshot;
    private final Optional<X4MissingModelDiagnostic> missingDiagnostic;
    private final ProviderLease generationLease;
    private final ClientGenerationLeaseBinding sharedGenerationBinding;
    private final boolean permitsFrozenCpuSubmission;
    private final ReleaseListener releaseListener;
    private boolean closeRequested;
    private boolean released;
    private int inFlightSubmissions;

    // These fields are owned by DefaultX4HostAdapter while it holds its lifecycle monitor. They
    // turn this already allocated snapshot into the exact carrier for one monitor-out provider
    // callback.  They are deliberately not another terminal token, future, or callback queue.
    private SubmissionHold providerReleaseHold;
    private Thread providerReleaseOwnerThread;
    private Throwable providerReleaseFailure;
    private boolean providerReleaseFailureRecorded;
    private long terminalEpochId;
    private boolean terminalContributionCommitted;
    private boolean terminalInterruptReceiptPending;
    private X4PreparedSnapshot nextReleaseInProgress;
    private boolean releaseInProgressLinked;

    X4PreparedSnapshot(
            X4HostKind hostKind,
            X4HostIdentity identity,
            BlendModelKey modelKey,
            ModelRenderSnapshot snapshot,
            Optional<X4MissingModelDiagnostic> missingDiagnostic,
            ProviderLease generationLease,
            ClientGenerationLeaseBinding sharedGenerationBinding,
            boolean permitsFrozenCpuSubmission,
            ReleaseListener releaseListener) {
        this.hostKind = Objects.requireNonNull(hostKind, "hostKind");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.missingDiagnostic = Objects.requireNonNull(missingDiagnostic, "missingDiagnostic");
        this.generationLease = Objects.requireNonNull(generationLease, "generationLease");
        this.sharedGenerationBinding = Objects.requireNonNull(sharedGenerationBinding, "sharedGenerationBinding");
        this.permitsFrozenCpuSubmission = permitsFrozenCpuSubmission;
        this.releaseListener = Objects.requireNonNull(releaseListener, "releaseListener");
        if (!modelKey.equals(snapshot.handle().modelKey())) {
            throw new IllegalArgumentException("Prepared snapshot model key must match its host specification");
        }
        this.generation = snapshot.generation();
        if (generation < 0L || generationLease.generation() != generation) {
            throw new IllegalArgumentException("Prepared snapshot must retain an exact same-generation provider lease");
        }
        sharedGenerationBinding.requireExactSnapshot(snapshot);
        if (permitsFrozenCpuSubmission != (snapshot.visibility() != RenderVisibility.CULLED)) {
            throw new IllegalArgumentException(
                    "X4 frozen CPU submission gate must preserve the immutable snapshot visibility decision");
        }
        if (snapshot.handle().missingModel()) {
            missingDiagnostic.orElseThrow(() -> new IllegalArgumentException(
                    "A missing prepared handle requires exact structured diagnostic evidence"))
                    .requireFor(modelKey, generation);
        } else if (missingDiagnostic.isPresent()) {
            throw new IllegalArgumentException("Only a missing prepared handle may retain a missing-model diagnostic");
        }
    }

    public X4HostKind hostKind() {
        return hostKind;
    }

    public X4HostIdentity identity() {
        return identity;
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    /** Exact lookup/extraction evidence when this snapshot uses the missing handle. */
    public Optional<X4MissingModelDiagnostic> missingDiagnostic() {
        synchronized (monitor) {
            requireOpenLocked();
            return missingDiagnostic;
        }
    }

    public boolean missingModel() {
        synchronized (monitor) {
            requireOpenLocked();
            return snapshot.handle().missingModel();
        }
    }

    /** Whether this caller has requested release; no new snapshot access or submit hold is legal. */
    public boolean closed() {
        synchronized (monitor) {
            return closeRequested;
        }
    }

    /**
     * Atomically holds the raw immutable renderer input for one adapter submit operation.
     *
     * <p>This is intentionally package-private and returns a releasable hold rather than a bare
     * {@link ModelRenderSnapshot}; only the controlled X4 adapter seam may consume it.</p>
     */
    SubmissionHold acquireSubmitHold() {
        synchronized (monitor) {
            requireOpenLocked();
            inFlightSubmissions++;
            return new SubmissionHold(this, snapshot);
        }
    }

    private void releaseSubmit(SubmissionHold hold) {
        ReleaseListener listener = null;
        synchronized (monitor) {
            if (hold.owner != this) {
                throw new IllegalArgumentException("An X4 submit hold belongs to another prepared snapshot");
            }
            if (hold.released) {
                throw new IllegalStateException("X4 snapshot submit hold was already released");
            }
            if (inFlightSubmissions <= 0) {
                throw new IllegalStateException("X4 snapshot submit hold underflow");
            }
            hold.released = true;
            inFlightSubmissions--;
            listener = releaseIfDrainedLocked();
        }
        if (listener != null) {
            listener.release(this, hold);
        }
    }

    ProviderLease generationLease() {
        return generationLease;
    }

    /** Managed D1 ownership composite, or the explicit external/missing no-resource fallback. */
    ClientGenerationLeaseBinding sharedGenerationBinding() {
        return sharedGenerationBinding;
    }

    /** Immutable X4-only projection; it is not another visibility or ownership authority. */
    boolean permitsFrozenCpuSubmission() {
        return permitsFrozenCpuSubmission;
    }

    void beginProviderRelease(SubmissionHold hold, Thread ownerThread) {
        if (providerReleaseOwnerThread != null || providerReleaseFailureRecorded || terminalContributionCommitted) {
            throw new IllegalStateException("X4 prepared snapshot provider-release carrier is already active");
        }
        providerReleaseHold = hold;
        providerReleaseOwnerThread = Objects.requireNonNull(ownerThread, "ownerThread");
    }

    SubmissionHold providerReleaseHold() {
        if (providerReleaseOwnerThread == null) {
            throw new IllegalStateException("X4 prepared snapshot provider-release carrier is not active");
        }
        return providerReleaseHold;
    }

    Thread providerReleaseOwnerThread() {
        return providerReleaseOwnerThread;
    }

    void recordProviderReleaseFailure(Throwable failure) {
        if (providerReleaseOwnerThread == null || providerReleaseFailureRecorded) {
            throw new IllegalStateException("X4 prepared snapshot provider-release result is already fixed");
        }
        providerReleaseFailure = failure;
        providerReleaseFailureRecorded = true;
    }

    Throwable providerReleaseFailure() {
        if (!providerReleaseFailureRecorded) {
            throw new IllegalStateException("X4 prepared snapshot provider-release result is not fixed");
        }
        return providerReleaseFailure;
    }

    boolean providerReleaseFailureRecorded() {
        return providerReleaseFailureRecorded;
    }

    void joinTerminalEpoch(long epoch) {
        if (epoch <= 0L) {
            throw new IllegalArgumentException("X4 terminal epoch must be positive");
        }
        if (terminalEpochId != 0L && terminalEpochId != epoch) {
            throw new IllegalStateException("X4 prepared snapshot cannot join two terminal epochs");
        }
        terminalEpochId = epoch;
    }

    long terminalEpochId() {
        return terminalEpochId;
    }

    boolean terminalContributionCommitted() {
        return terminalContributionCommitted;
    }

    void commitTerminalContribution(long epoch) {
        if (terminalContributionCommitted) {
            throw new IllegalStateException("X4 prepared snapshot terminal contribution is already committed");
        }
        if (epoch != 0L) {
            joinTerminalEpoch(epoch);
        }
        terminalContributionCommitted = true;
    }

    void recordTerminalInterruptReceipt() {
        terminalInterruptReceiptPending = true;
    }

    boolean takeTerminalInterruptReceipt() {
        boolean pending = terminalInterruptReceiptPending;
        terminalInterruptReceiptPending = false;
        return pending;
    }

    void linkReleaseInProgress(X4PreparedSnapshot next) {
        if (releaseInProgressLinked) {
            throw new IllegalStateException("X4 prepared snapshot provider-release carrier is already linked");
        }
        nextReleaseInProgress = next;
        releaseInProgressLinked = true;
    }

    boolean releaseInProgressLinked() {
        return releaseInProgressLinked;
    }

    X4PreparedSnapshot nextReleaseInProgress() {
        if (!releaseInProgressLinked) {
            throw new IllegalStateException("X4 prepared snapshot provider-release carrier is not linked");
        }
        return nextReleaseInProgress;
    }

    void unlinkReleaseInProgress() {
        if (!releaseInProgressLinked) {
            throw new IllegalStateException("X4 prepared snapshot provider-release carrier is not linked");
        }
        nextReleaseInProgress = null;
        releaseInProgressLinked = false;
    }

    /** Releases this caller's generation pin exactly once after any active renderer use drains. */
    @Override
    public void close() {
        ReleaseListener listener;
        synchronized (monitor) {
            closeRequested = true;
            listener = releaseIfDrainedLocked();
        }
        if (listener != null) {
            listener.release(this, null);
        }
    }

    private void requireOpenLocked() {
        if (closeRequested) {
            throw new IllegalStateException("The X4 prepared snapshot lease has already been released");
        }
    }

    private ReleaseListener releaseIfDrainedLocked() {
        if (!closeRequested || inFlightSubmissions != 0 || released) {
            return null;
        }
        released = true;
        return releaseListener;
    }

    /** Package-private raw renderer access tied to one in-flight submit hold. */
    static final class SubmissionHold {
        private final X4PreparedSnapshot owner;
        private final ModelRenderSnapshot snapshot;
        private boolean released;

        // The adapter owns these fields while holding its lifecycle monitor. They deliberately
        // live on the already allocated submit hold: a renderer primary must never be staged in
        // one adapter-global pre-cleanup slot when more than one submit is in flight.
        private long submissionEpoch;
        private Thread submissionOwner;
        // Adapter-monitor-owned intrusive source-owner chain. This is only for exact callback
        // reentry lookup while a renderer is still active; it never carries terminal evidence.
        private SubmissionHold nextAdmittedSubmission;
        private boolean admittedSubmissionLinked;
        private Throwable rendererFailure;
        private boolean rendererFailureRecorded;
        private Throwable providerReleaseFailure;
        private boolean providerReleaseFailureRecorded;
        private Throwable bookkeepingFailure;
        private boolean bookkeepingFailureRecorded;
        private long terminalEpochId;
        private boolean terminalContributionCommitted;
        private long terminalContributionSequence;
        private boolean terminalInterruptReceiptPending;

        private SubmissionHold(X4PreparedSnapshot owner, ModelRenderSnapshot snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        ModelRenderSnapshot snapshot() {
            synchronized (owner.monitor) {
                if (released) {
                    throw new IllegalStateException("X4 snapshot submit hold was already released");
                }
                return snapshot;
            }
        }

        void release() {
            owner.releaseSubmit(this);
        }

        X4PreparedSnapshot ownerSnapshot() {
            return owner;
        }

        void admit(long epoch, Thread submitOwner) {
            if (epoch <= 0L || submissionEpoch != 0L || submissionOwner != null) {
                throw new IllegalStateException("X4 snapshot submit hold admission is already fixed");
            }
            submissionEpoch = epoch;
            submissionOwner = Objects.requireNonNull(submitOwner, "submitOwner");
        }

        long submissionEpoch() {
            return submissionEpoch;
        }

        boolean ownedBy(Thread candidate) {
            return submissionOwner == candidate;
        }

        void linkAdmittedSubmission(SubmissionHold next) {
            if (admittedSubmissionLinked) {
                throw new IllegalStateException("X4 submit hold is already linked as an active source");
            }
            nextAdmittedSubmission = next;
            admittedSubmissionLinked = true;
        }

        SubmissionHold nextAdmittedSubmission() {
            if (!admittedSubmissionLinked) {
                throw new IllegalStateException("X4 submit hold is not linked as an active source");
            }
            return nextAdmittedSubmission;
        }

        void unlinkAdmittedSubmission() {
            if (!admittedSubmissionLinked) {
                throw new IllegalStateException("X4 submit hold is not linked as an active source");
            }
            nextAdmittedSubmission = null;
            admittedSubmissionLinked = false;
        }

        void recordRendererFailure(Throwable failure) {
            if (rendererFailureRecorded) {
                throw new IllegalStateException("X4 snapshot renderer result is already recorded");
            }
            rendererFailureRecorded = true;
            rendererFailure = failure;
        }

        Throwable rendererFailure() {
            if (!rendererFailureRecorded) {
                throw new IllegalStateException("X4 snapshot renderer result is not yet recorded");
            }
            return rendererFailure;
        }

        void recordProviderReleaseFailure(Throwable failure) {
            if (providerReleaseFailureRecorded) {
                throw new IllegalStateException("X4 submit provider-release result is already fixed");
            }
            providerReleaseFailure = failure;
            providerReleaseFailureRecorded = true;
        }

        Throwable providerReleaseFailure() {
            if (!providerReleaseFailureRecorded) {
                throw new IllegalStateException("X4 submit provider-release result is not fixed");
            }
            return providerReleaseFailure;
        }

        boolean providerReleaseFailureRecorded() {
            return providerReleaseFailureRecorded;
        }

        void recordBookkeepingFailure(Throwable failure) {
            if (bookkeepingFailureRecorded) {
                throw new IllegalStateException("X4 submit bookkeeping result is already fixed");
            }
            bookkeepingFailure = failure;
            bookkeepingFailureRecorded = true;
        }

        Throwable bookkeepingFailure() {
            if (!bookkeepingFailureRecorded) {
                throw new IllegalStateException("X4 submit bookkeeping result is not fixed");
            }
            return bookkeepingFailure;
        }

        void joinTerminalEpoch(long epoch) {
            if (epoch <= 0L) {
                throw new IllegalArgumentException("X4 terminal epoch must be positive");
            }
            if (terminalEpochId != 0L && terminalEpochId != epoch) {
                throw new IllegalStateException("X4 submit cannot join two terminal epochs");
            }
            terminalEpochId = epoch;
        }

        long terminalEpochId() {
            return terminalEpochId;
        }

        boolean terminalContributionCommitted() {
            return terminalContributionCommitted;
        }

        long terminalContributionSequence() {
            return terminalContributionSequence;
        }

        void commitTerminalContribution(long epoch, long sequence) {
            if (sequence <= 0L || terminalContributionCommitted) {
                throw new IllegalStateException("X4 submit terminal contribution is already committed");
            }
            joinTerminalEpoch(epoch);
            terminalContributionSequence = sequence;
            terminalContributionCommitted = true;
        }

        void recordTerminalInterruptReceipt() {
            terminalInterruptReceiptPending = true;
        }

        boolean takeTerminalInterruptReceipt() {
            boolean pending = terminalInterruptReceiptPending;
            terminalInterruptReceiptPending = false;
            return pending;
        }

    }
}
