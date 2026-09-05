package com.liy.blendlib.fabric.client.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;

/**
 * Fail-closed owner of the future AFTER_SOLID handoff boundary.
 *
 * <p>The T2a3 queue remains the only owner of real frame epochs, request admission, submitted
 * children, and completion receipts. This driver deliberately does not manufacture a second epoch
 * counter. It can ask one package-private port to perform those operations only after that port
 * proves, through public APIs, that Stage A can reserve the exact queue epoch before AFTER_SOLID
 * seals it. The current Fabric callback surface cannot provide that proof, so the real client
 * gateway keeps the CPU path selected until a proof becomes available.</p>
 */
final class X7AfterSolidQueueDriver {
    enum StageAResult {
        KEEP_CPU_SOURCE_ORDER_UNPROVEN,
        KEEP_CPU_PIPELINE_UNAVAILABLE,
        KEEP_CPU_ADMISSION_CLOSED,
        KEEP_CPU_PRE_ADMISSION_REJECTED,
        SUPPRESS_CPU_AFTER_FULL_ADMISSION
    }

    /** Exact Stage-B dispositions; none permits a retroactive collector replay. */
    enum StageBResult {
        NO_WORK,
        OMIT_CURRENT_FRAME_AND_DISABLE_FUTURE_GPU,
        RETAIN_SUBMITTED_OR_UNCERTAIN_NO_CPU_REPLAY
    }

    /**
     * Narrow future adapter seam for the T2a3 queue plus T3b/T4 backend registration.
     *
     * <p>The port is package-private so it cannot become stable API. Its implementation must own
     * the exact T2a3 frame identity, child receipt, policy record, and pre/post-command transition;
     * this driver never receives any of those private objects.</p>
     */
    interface OptionalT4Adapter {
        /** True only with a local public-API proof that Stage A precedes the matching epoch seal. */
        boolean provesStageABeforeAfterSolidSeal();

        /** True only after the exact T3b pipeline registration transaction succeeded. */
        boolean hasRegisteredT3bPipeline();

        /** Performs every Stage-A check and returns suppression only after the queue owns a child. */
        StageAResult tryPreAdmit(
                X6PreparedRenderPlan plan,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                X6PlanSubmitter.FinalDrawInputs finalDrawInputs);

        /** Seals/drains exactly one real queue epoch and performs phase-only Stage B. */
        StageBResult sealDrainAndExecute(GpuTextureView colorTarget, GpuTextureView depthTarget);

        /** Zero-polls only already command-recorded receipts and releases only verified completions. */
        void pollRetainedCompletionFences();

        /** Cancels only no-command work for the epoch just driven by this phase sequence. */
        void cancelNoCommandForAfterSolidEpoch();

        /** Reload/world/T1c cutoff: stop admission and retain command-recorded work. */
        void closeAdmissionAndCancelNoCommand();
    }

    /** Bootstrap fallback used only before the client gateway has installed its real queue port. */
    static final class UnavailableQueueEpochPort implements OptionalT4Adapter {
        @Override
        public boolean provesStageABeforeAfterSolidSeal() {
            return false;
        }

        @Override
        public boolean hasRegisteredT3bPipeline() {
            return false;
        }

        @Override
        public StageAResult tryPreAdmit(
                X6PreparedRenderPlan plan,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
            throw new AssertionError("An unavailable X7 queue port must never receive Stage A");
        }

        @Override
        public StageBResult sealDrainAndExecute(GpuTextureView colorTarget, GpuTextureView depthTarget) {
            throw new AssertionError("An unavailable X7 queue port must never receive Stage B");
        }

        @Override
        public void pollRetainedCompletionFences() {
            // No command can have been recorded through an unavailable port.
        }

        @Override
        public void cancelNoCommandForAfterSolidEpoch() {
            throw new AssertionError("An unavailable X7 queue port must never cancel work");
        }

        @Override
        public void closeAdmissionAndCancelNoCommand() {
            // No queue child was ever admitted through this port.
        }
    }

    private final OptionalT4Adapter port;
    private boolean admissionClosed;
    private StageBResult lastStageBResult = StageBResult.NO_WORK;

    X7AfterSolidQueueDriver(OptionalT4Adapter port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    boolean mayInstallHostEndpoint() {
        synchronized (this) {
            return !admissionClosed
                    && port.provesStageABeforeAfterSolidSeal()
                    && port.hasRegisteredT3bPipeline();
        }
    }

    boolean hasPublicSourceOrderProof() {
        return port.provesStageABeforeAfterSolidSeal();
    }

    synchronized StageBResult lastStageBResult() {
        return lastStageBResult;
    }

    StageAResult tryPreAdmit(
            X6PreparedRenderPlan plan,
            ModelRenderSnapshot snapshot,
            X6DrawPrimitive draw,
            X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(draw, "draw");
        Objects.requireNonNull(finalDrawInputs, "finalDrawInputs");
        synchronized (this) {
            if (admissionClosed) {
                return StageAResult.KEEP_CPU_ADMISSION_CLOSED;
            }
            if (!port.provesStageABeforeAfterSolidSeal()) {
                return StageAResult.KEEP_CPU_SOURCE_ORDER_UNPROVEN;
            }
            if (!port.hasRegisteredT3bPipeline()) {
                return StageAResult.KEEP_CPU_PIPELINE_UNAVAILABLE;
            }
        }
        try {
            StageAResult result = Objects.requireNonNull(
                    port.tryPreAdmit(plan, snapshot, draw, finalDrawInputs), "port Stage-A result");
            return result == StageAResult.SUPPRESS_CPU_AFTER_FULL_ADMISSION
                    ? result
                    : StageAResult.KEEP_CPU_PRE_ADMISSION_REJECTED;
        } catch (Error error) {
            // A real port owns any minted child and must clean it before this fatal result escapes.
            throw error;
        } catch (RuntimeException ignored) {
            return StageAResult.KEEP_CPU_PRE_ADMISSION_REJECTED;
        }
    }

    void onAfterSolid(GpuTextureView colorTarget, GpuTextureView depthTarget) {
        try {
            // This remains live after a source-order gate closes: a previously command-recorded child may only
            // leave retention through a later zero-timeout verified fence poll.
            port.pollRetainedCompletionFences();
        } catch (Error error) {
            throw error;
        } catch (RuntimeException ignored) {
            // The port has retained the post-command receipt terminally before returning this failure.
            return;
        }
        if (!mayInstallHostEndpoint()) {
            return;
        }
        try {
            StageBResult result = Objects.requireNonNull(
                    port.sealDrainAndExecute(colorTarget, depthTarget), "port Stage-B result");
            synchronized (this) {
                lastStageBResult = result;
            }
        } catch (Error error) {
            throw error;
        } catch (RuntimeException ignored) {
            // The port must classify an encountered request as a pre-command omission or retained
            // post-command uncertainty before it returns/throws. There is never a CPU replay here.
        }
    }

    void onBeforeTranslucent(GpuTextureView colorTarget, GpuTextureView depthTarget) {
        if (!mayInstallHostEndpoint()) {
            return;
        }
        port.cancelNoCommandForAfterSolidEpoch();
    }

    void onReloadOrWorldLeave() {
        boolean close;
        synchronized (this) {
            close = !admissionClosed;
            admissionClosed = true;
        }
        if (close) {
            port.closeAdmissionAndCancelNoCommand();
        }
    }
}
