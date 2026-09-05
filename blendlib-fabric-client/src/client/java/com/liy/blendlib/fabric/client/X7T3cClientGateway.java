package com.liy.blendlib.fabric.client;

import com.liy.blendlib.fabric.client.reload.X7T3cReloadGateway;
import com.liy.blendlib.fabric.client.reload.X7T3cReloadGateway.AuthorityToken;
import com.liy.blendlib.fabric.client.reload.X7T3cReloadGateway.CompletionHooks;
import com.liy.blendlib.fabric.client.reload.X7T3cReloadGateway.FrozenSkinnedInputs;
import com.liy.blendlib.fabric.client.reload.X7T3cReloadGateway.SkinnedStageBDisposition;
import com.liy.blendlib.fabric.client.reload.X7T3cReloadGateway.SkinnedWork;
import com.liy.blendlib.fabric.client.render.X7T3cFrozenStageA;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;

/**
 * Client-package half of the T3c gateway.
 *
 * <p>It is deliberately client-internal: render sees only the endpoint gateway contract, reload sees only the
 * typed phase-B callback, and the package-private T4 pass remains the only code that touches its native pass ABI.
 * No D1 record, queue child, resource leaf, or native handle is made public here.</p>
 */
public final class X7T3cClientGateway {
    private static final int MAX_RETAINED_COMMAND_RECEIPTS = 256;

    private final X7T3cReloadGateway reload;
    private final BooleanSupplier sourceOrderProof;
    private final RenderPipeline skinnedPipeline;
    private final boolean registeredT4Pipeline;
    /**
     * Package-private focused-test executor only. Production obtains its driver solely from the registered pipeline;
     * this value has no public construction or bootstrap path.
     */
    private final X7Minecraft2612SkinnedPassSubmitter.Driver testDriverOrNull;
    // This fixed owner is allocated before any child can move. Terminal entries intentionally consume capacity.
    private final X7Minecraft2612SkinnedPassSubmitter.FenceReceipt[] pendingSkinnedFences =
            new X7Minecraft2612SkinnedPassSubmitter.FenceReceipt[MAX_RETAINED_COMMAND_RECEIPTS];
    private int pendingSkinnedFenceCount;

    private X7T3cClientGateway(
            X7T3cReloadGateway reload,
            BooleanSupplier sourceOrderProof,
            RenderPipeline skinnedPipeline,
            boolean registeredT4Pipeline) {
        this(reload, sourceOrderProof, skinnedPipeline, registeredT4Pipeline, null);
    }

    private X7T3cClientGateway(
            X7T3cReloadGateway reload,
            BooleanSupplier sourceOrderProof,
            RenderPipeline skinnedPipeline,
            boolean registeredT4Pipeline,
            X7Minecraft2612SkinnedPassSubmitter.Driver testDriverOrNull) {
        this.reload = Objects.requireNonNull(reload, "reload");
        this.sourceOrderProof = Objects.requireNonNull(sourceOrderProof, "sourceOrderProof");
        this.skinnedPipeline = skinnedPipeline;
        this.registeredT4Pipeline = registeredT4Pipeline;
        this.testDriverOrNull = testDriverOrNull;
    }

    /** Builds the real registration/admission/execution path, while deliberately retaining the absent-proof gate. */
    public static X7T3cClientGateway production() {
        RenderPipeline skinned = registerT4PipelineOnce();
        return new X7T3cClientGateway(X7T3cReloadGateway.production(), () -> false, skinned, skinned != null);
    }

    /** Package-private focused-test factory; production never receives a fabricated source-order proof. */
    static X7T3cClientGateway forTest(X7T3cReloadGateway reload, BooleanSupplier sourceOrderProof) {
        return new X7T3cClientGateway(reload, sourceOrderProof, null, true);
    }

    /**
     * Package-private test-only driver seam. It preserves the real reload-to-T4 call graph while keeping arbitrary
     * drivers out of public production bootstrap and endpoint installation.
     */
    static X7T3cClientGateway forTest(
            X7T3cReloadGateway reload,
            BooleanSupplier sourceOrderProof,
            X7Minecraft2612SkinnedPassSubmitter.Driver testDriver) {
        return new X7T3cClientGateway(
                reload,
                sourceOrderProof,
                null,
                true,
                Objects.requireNonNull(testDriver, "testDriver"));
    }

    public boolean provesStageABeforeAfterSolidSeal() {
        return sourceOrderProof.getAsBoolean();
    }

    public boolean hasRegisteredPipelines() {
        return registeredT4Pipeline && reload.hasRegisteredT3bPipeline();
    }

    public boolean tryAdmit(X7T3cFrozenStageA input) {
        X7T3cFrozenStageA checkedInput = Objects.requireNonNull(input, "input");
        if (!hasRegisteredPipelines()
                || (checkedInput.route() == X7T3cFrozenStageA.Route.SKINNED_T4P
                        && pendingSkinnedFenceCount == pendingSkinnedFences.length)) {
            return false;
        }
        return reload.tryAdmit(checkedInput);
    }

    public void sealDrainAndExecute(GpuTextureView colorTarget, GpuTextureView depthTarget) {
        pollRetainedCompletionFences();
        reload.sealDrainAndExecute(colorTarget, depthTarget, this::executeSkinned);
        pollRetainedCompletionFences();
    }

    public void cancelNoCommandForAfterSolidEpoch() {
        reload.cancelNoCommandForAfterSolidEpoch();
    }

    public void closeAdmissionAndCancelNoCommand() {
        reload.closeAdmissionAndCancelNoCommand();
    }

    /** Zero-polls only command-recorded receipts; terminal uncertainty remains strongly retained. */
    public void pollRetainedCompletionFences() {
        pollPendingSkinnedFences();
        reload.pollRetainedStaticFences();
    }

    private SkinnedStageBDisposition executeSkinned(
            SkinnedWork work, GpuTextureView colorTarget, GpuTextureView depthTarget) {
        SkinnedWork checkedWork = Objects.requireNonNull(work, "work");
        if ((skinnedPipeline == null && testDriverOrNull == null) || colorTarget == null || depthTarget == null) {
            checkedWork.cancelNoCommand();
            return SkinnedStageBDisposition.OMITTED_BEFORE_COMMAND;
        }
        int receiptSlot = reserveSkinnedFenceSlot();
        if (receiptSlot < 0) {
            checkedWork.cancelNoCommand();
            return SkinnedStageBDisposition.OMITTED_BEFORE_COMMAND;
        }
        SkinnedReceiptReservation reservation = new SkinnedReceiptReservation(receiptSlot);
        FrozenSkinnedInputs frozen = checkedWork.inputs();
        AuthorityToken token = checkedWork.authority();
        CompletionHooks hooks = checkedWork.completionHooks();
        X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt completion = new X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt() {
            @Override
            public void closeNoCommand() {
                hooks.closeNoCommand();
            }

            @Override
            public void completeAfterVerifiedFence() {
                hooks.completeAfterVerifiedFence();
            }

            @Override
            public void retainTerminalNonClose(Throwable failure) {
                hooks.retainTerminalNonClose(failure);
            }
        };
        X7Minecraft2612SkinnedPassSubmitter.D1Submission submission = new X7Minecraft2612SkinnedPassSubmitter.D1Submission() {
            @Override
            public Object exactFrameIdentity() {
                return token;
            }

            @Override
            public Object exactD1Record() {
                return token;
            }

            @Override
            public Object exactGeometryLeaf() {
                return token;
            }

            @Override
            public Object exactInfluenceLeaf() {
                return token;
            }

            @Override
            public com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance.PaletteUpload paletteUpload() {
                return frozen.paletteUpload();
            }

            @Override
            public X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt preallocatedCompletionReceipt() {
                return completion;
            }

            @Override
            public void recordAllBeforeNativeDraw(X7Minecraft2612SkinnedPassSubmitter.CompletionTransfer transfer) {
                checkedWork.recordAllBeforeNativeDraw(transfer::acceptMovedChild);
            }

            @Override
            public void cancelNoCommand() {
                checkedWork.cancelNoCommand();
            }
        };
        X7Minecraft2612SkinnedPassSubmitter.D1SkinnedResources resources = new X7Minecraft2612SkinnedPassSubmitter.D1SkinnedResources() {
            @Override
            public Object exactD1Record() {
                return token;
            }

            @Override
            public Object exactGeometryLeaf() {
                return token;
            }

            @Override
            public Object exactInfluenceLeaf() {
                return token;
            }

            @Override
            public int vertexCount() {
                return checkedWork.vertexCount();
            }

            @Override
            public int indexCount() {
                return checkedWork.indexCount();
            }

            @Override
            public net.minecraft.resources.Identifier textureId() {
                return checkedWork.textureId();
            }

            @Override
            public void bindTo(RenderPass pass) {
                checkedWork.bindTo(pass);
            }
        };
        X7Minecraft2612SkinnedPassSubmitter.FrameInputs inputs = new X7Minecraft2612SkinnedPassSubmitter.FrameInputs(
                token,
                frozen.paletteUpload(),
                frozen.modelView(),
                frozen.normalTransform(),
                color(frozen.composedArgb()),
                frozen.packedLight(),
                frozen.packedOverlay());
        X7Minecraft2612SkinnedPassSubmitter.AfterSolidScope scope = new X7Minecraft2612SkinnedPassSubmitter.AfterSolidScope() {
            @Override
            public Object exactFrameIdentity() {
                return token;
            }

            @Override
            public GpuTextureView colorTarget() {
                return colorTarget;
            }

            @Override
            public GpuTextureView depthTarget() {
                return depthTarget;
            }
        };
        try {
            X7Minecraft2612SkinnedPassSubmitter.Driver driver = testDriverOrNull != null
                    ? testDriverOrNull
                    : X7Minecraft2612SkinnedPassSubmitter.minecraftDriver(skinnedPipeline);
            X7Minecraft2612SkinnedPassSubmitter.Result result = X7Minecraft2612SkinnedPassSubmitter.execute(
                    submission,
                    resources,
                    inputs,
                    scope,
                    retainingDriver(driver, reservation));
            if (result.disposition() == X7Minecraft2612SkinnedPassSubmitter.Disposition.OMITTED_BEFORE_COMMAND) {
                discardSkinnedFenceSlot(receiptSlot);
                return SkinnedStageBDisposition.OMITTED_BEFORE_COMMAND;
            }
            if (reservation.receiptOrNull() == null || result.receiptOrNull() != reservation.receiptOrNull()) {
                // A command may have issued, so never replay CPU or cancel the moved child. The exact queue receipt
                // is terminally retained even if a malformed T4 driver failed to return its preallocated shell.
                checkedWork.completionHooks().retainTerminalNonClose(
                        new IllegalStateException("T4 command result lost its preallocated fence receipt"));
                discardSkinnedFenceSlot(receiptSlot);
            }
            return SkinnedStageBDisposition.RETAINED_AFTER_COMMAND;
        } catch (Error error) {
            if (!reservation.drawMayHaveIssued()) {
                discardSkinnedFenceSlot(receiptSlot);
            }
            throw error;
        }
    }

    private X7Minecraft2612SkinnedPassSubmitter.Driver retainingDriver(
            X7Minecraft2612SkinnedPassSubmitter.Driver delegate, SkinnedReceiptReservation reservation) {
        X7Minecraft2612SkinnedPassSubmitter.Driver checkedDelegate = Objects.requireNonNull(delegate, "delegate");
        SkinnedReceiptReservation checkedReservation = Objects.requireNonNull(reservation, "reservation");
        return (resources, inputs, scope, commands) -> checkedDelegate.record(
                resources, inputs, scope, new RetainingCommandsRecorded(commands, checkedReservation));
    }

    private int reserveSkinnedFenceSlot() {
        if (pendingSkinnedFenceCount == pendingSkinnedFences.length) {
            return -1;
        }
        return pendingSkinnedFenceCount++;
    }

    private void discardSkinnedFenceSlot(int slot) {
        if (slot < 0 || slot >= pendingSkinnedFenceCount) {
            return;
        }
        int last = --pendingSkinnedFenceCount;
        pendingSkinnedFences[slot] = pendingSkinnedFences[last];
        pendingSkinnedFences[last] = null;
    }

    private void pollPendingSkinnedFences() {
        for (int index = 0; index < pendingSkinnedFenceCount; ) {
            X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt = pendingSkinnedFences[index];
            X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State state;
            try {
                state = receipt.pollOnRenderThread();
            } catch (Error error) {
                receipt.retainTerminalNonClose(error);
                throw error;
            } catch (RuntimeException failure) {
                receipt.retainTerminalNonClose(failure);
                index++;
                continue;
            }
            if (state == X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.COMPLETED
                    || state == X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.CLOSED_NO_COMMAND) {
                discardSkinnedFenceSlot(index);
            } else {
                index++;
            }
        }
    }

    /** Captures the real T4 preallocated fence before its child moves, including an Error after native draw. */
    private static final class SkinnedReceiptReservation {
        private final int slot;
        private X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt;
        private boolean drawMayHaveIssued;

        private SkinnedReceiptReservation(int slot) {
            this.slot = slot;
        }

        private void install(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt candidate) {
            if (receipt != null) {
                throw new IllegalStateException("The T4 preallocated fence was installed more than once");
            }
            receipt = Objects.requireNonNull(candidate, "candidate");
        }

        private X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receiptOrNull() {
            return receipt;
        }

        private void markDrawMayHaveIssued() {
            drawMayHaveIssued = true;
        }

        private boolean drawMayHaveIssued() {
            return drawMayHaveIssued;
        }
    }

    /** Forwarder that stores a fence in its reserved fixed slot before it can move the exact queue child. */
    private final class RetainingCommandsRecorded implements X7Minecraft2612SkinnedPassSubmitter.CommandsRecorded {
        private final X7Minecraft2612SkinnedPassSubmitter.CommandsRecorded delegate;
        private final SkinnedReceiptReservation reservation;

        private RetainingCommandsRecorded(
                X7Minecraft2612SkinnedPassSubmitter.CommandsRecorded delegate, SkinnedReceiptReservation reservation) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        @Override
        public X7Minecraft2612SkinnedPassSubmitter.CompletionTransfer completionTransfer() {
            return delegate.completionTransfer();
        }

        @Override
        public void installPreallocatedReceipt(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt) {
            delegate.installPreallocatedReceipt(receipt);
            reservation.install(receipt);
            pendingSkinnedFences[reservation.slot] = receipt;
        }

        @Override
        public void recordAllBeforeNativeDraw() {
            delegate.recordAllBeforeNativeDraw();
        }

        @Override
        public void markNativeDrawAttempted() {
            delegate.markNativeDrawAttempted();
            reservation.markDrawMayHaveIssued();
        }

        @Override
        public boolean hasNativeDrawBeenAttempted() {
            return delegate.hasNativeDrawBeenAttempted();
        }
    }

    private static Vector4f color(int argb) {
        return new Vector4f(
                (argb >>> 16 & 0xFF) / 255.0F,
                (argb >>> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F,
                (argb >>> 24) / 255.0F);
    }

    private static RenderPipeline registerT4PipelineOnce() {
        try {
            RenderPipeline existing = RenderPipelines.getStaticPipelines().stream()
                    .filter(candidate -> X7Minecraft2612SkinnedPipeline.PIPELINE_ID.equals(candidate.getLocation()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                return existing;
            }
            RenderPipeline candidate = X7Minecraft2612SkinnedPipeline.buildCandidate();
            return RenderPipelines.register(candidate) == candidate ? candidate : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
