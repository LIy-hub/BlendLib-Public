package com.liy.blendlib.fabric.client;

import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance.PaletteUpload;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Phase-B-only pass candidate for one already pre-admitted skinned draw.
 *
 * <p>All D1 values are opaque identity tokens because this packet may not alter the existing D1/queue/receipt
 * implementation. T3c must adapt its exact same-record child and leaf objects into these package-private contracts.
 * Every representation required after the child moves is allocated before the move: the immutable child receipt,
 * transfer slot, deferred fence, transient-close closure, and fence receipt. The only operation at the move boundary
 * is the allocation-free {@link CompletionTransfer#acceptMovedChild()} state write.</p>
 */
final class X7Minecraft2612SkinnedPassSubmitter {
    static final int SKINNED_INSTANCE_COUNT = 1;
    static final int MODEL_VIEW_BYTES = 16 * Float.BYTES;
    static final int NORMAL_MATRIX_BYTES = 3 * 4 * Float.BYTES;
    static final int COLOR_BYTES = 4 * Float.BYTES;
    static final int PACKED_LIGHT_BYTES = 4 * Float.BYTES;
    static final int INSTANCE_UPLOAD_BYTES = MODEL_VIEW_BYTES + NORMAL_MATRIX_BYTES + COLOR_BYTES + PACKED_LIGHT_BYTES;
    private static final int MAX_LIGHTMAP_COORDINATE = 15 << 4;

    enum FailureBoundary {
        RECEIPT_CONSTRUCTION,
        CHILD_TRANSFER_AFTER_MOVE,
        DRAW_INDEXED,
        AFTER_DRAW_INDEXED,
        PASS_CLOSE,
        FENCE_ATTACHMENT
    }

    @FunctionalInterface
    interface FailureInjector {
        void hit(FailureBoundary boundary);
    }

    private static final FailureInjector NO_FAILURE = boundary -> { };
    private static volatile FailureInjector failureInjector = NO_FAILURE;

    interface AfterSolidScope {
        Object exactFrameIdentity();

        GpuTextureView colorTarget();

        GpuTextureView depthTarget();
    }

    /**
     * Exact D1-owned skinned source/geometry state supplied only by a later serial integration owner.
     *
     * <p>The native vertex/index/source handles deliberately remain inside the completed-resource leaf. The pass can
     * only request the narrow bind operation, so an adapter cannot accidentally leak or independently close a native
     * handle across the frozen D1 boundary.</p>
     */
    interface D1SkinnedResources {
        Object exactD1Record();

        Object exactGeometryLeaf();

        Object exactInfluenceLeaf();

        int vertexCount();

        int indexCount();

        Identifier textureId();

        void bindTo(RenderPass pass);
    }

    /**
     * One caller-visible submitted child that becomes completion-owned before a native command.
     *
     * <p>{@link #preallocatedCompletionReceipt()} must return the exact one-child immutable completion representation
     * before this method's ownership-moving call. {@link #recordAllBeforeNativeDraw(CompletionTransfer)} must either
     * leave D1 ownership unchanged or perform the D1 move and, as its final allocation-free action, invoke
     * {@link CompletionTransfer#acceptMovedChild()}. It may not perform allocating/throwing work after that call.
     * Thus any successful move is atomically visible to the local terminal owner before an Error/OOME can be handled.
     * </p>
     */
    interface D1Submission {
        Object exactFrameIdentity();

        Object exactD1Record();

        Object exactGeometryLeaf();

        Object exactInfluenceLeaf();

        PaletteUpload paletteUpload();

        CompletionReceipt preallocatedCompletionReceipt();

        void recordAllBeforeNativeDraw(CompletionTransfer transfer);

        void cancelNoCommand();
    }

    /**
     * The one immutable child completion representation prepared while its original owner still owns it.
     *
     * <p>T3c can construct the no-command close delegate on its allowed reload-side handoff while the exact D1
     * receipt is still package-visible, then carry only this opaque callback into the client-package adapter. Because
     * {@link #closeNoCommand()} is reached only before {@link CompletionTransfer#markDrawMayHaveIssued()}, that
     * delegate performs the D1 final release directly and never re-enters the former caller's cancellation path.
     * No T2a3/T3b ownership type, raw leaf, or native handle crosses this interface.</p>
     */
    interface CompletionReceipt {
        /** Closes the moved child when no native draw was attempted. */
        void closeNoCommand();

        void completeAfterVerifiedFence();

        /** Retains the moved child when a native draw/fence may have been issued but cannot be verified. */
        void retainTerminalNonClose(Throwable failure);
    }

    @FunctionalInterface
    interface IndexedDraw {
        void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount);
    }

    interface Driver {
        /**
         * All receipt/fence/transient state must be installed before the transfer. A driver marks the draw boundary
         * immediately before the native draw call, then returns that same installed receipt after fence attachment.
         */
        FenceReceipt record(
                D1SkinnedResources resources,
                FrameInputs inputs,
                AfterSolidScope scope,
                CommandsRecorded commands);
    }

    interface CommandsRecorded {
        CompletionTransfer completionTransfer();

        void installPreallocatedReceipt(FenceReceipt receipt);

        void recordAllBeforeNativeDraw();

        void markNativeDrawAttempted();

        boolean hasNativeDrawBeenAttempted();
    }

    enum Disposition {
        OMITTED_BEFORE_COMMAND,
        COMMAND_RECORDED
    }

    static final class Result {
        private final Disposition disposition;
        private final FenceReceipt receipt;
        private final Throwable failure;

        private Result(Disposition disposition, FenceReceipt receipt, Throwable failure) {
            this.disposition = Objects.requireNonNull(disposition, "disposition");
            this.receipt = receipt;
            this.failure = failure;
        }

        Disposition disposition() {
            return disposition;
        }

        FenceReceipt receiptOrNull() {
            return receipt;
        }

        Throwable failureOrNull() {
            return failure;
        }
    }

    /** Immutable Stage-A copy of the final pose/light/overlay/color evidence; no PoseStack or context escapes. */
    static final class FrameInputs {
        private final Object exactFrameIdentity;
        private final PaletteUpload paletteUpload;
        private final Matrix4f modelView;
        private final Matrix3f normalTransform;
        private final Vector4f color;
        private final int packedLight;
        private final int packedOverlay;

        FrameInputs(
                Object exactFrameIdentity,
                PaletteUpload paletteUpload,
                Matrix4fc modelView,
                Matrix3fc normalTransform,
                Vector4fc color,
                int packedLight,
                int packedOverlay) {
            this.exactFrameIdentity = Objects.requireNonNull(exactFrameIdentity, "exactFrameIdentity");
            this.paletteUpload = Objects.requireNonNull(paletteUpload, "paletteUpload");
            Matrix4fc checkedModelView = Objects.requireNonNull(modelView, "modelView");
            Matrix3fc checkedNormalTransform = Objects.requireNonNull(normalTransform, "normalTransform");
            Vector4fc checkedColor = Objects.requireNonNull(color, "color");
            if (!checkedModelView.isFinite() || !checkedNormalTransform.isFinite()
                    || !Float.isFinite(checkedColor.x()) || !Float.isFinite(checkedColor.y())
                    || !Float.isFinite(checkedColor.z()) || !Float.isFinite(checkedColor.w())) {
                throw new IllegalArgumentException("The skinned candidate requires finite frozen pose and color inputs");
            }
            if (checkedColor.w() != 1.0F) {
                throw new IllegalArgumentException("The direct-flat skinned candidate requires an opaque composed color");
            }
            if (packedOverlay != OverlayTexture.NO_OVERLAY) {
                throw new IllegalArgumentException("The skinned candidate requires OverlayTexture.NO_OVERLAY exactly");
            }
            this.modelView = new Matrix4f(checkedModelView);
            this.normalTransform = new Matrix3f(checkedNormalTransform);
            this.color = new Vector4f(checkedColor);
            this.packedLight = requireExactlyRepresentablePackedLight(packedLight);
            this.packedOverlay = packedOverlay;
        }

        Object exactFrameIdentity() {
            return exactFrameIdentity;
        }

        PaletteUpload paletteUpload() {
            return paletteUpload;
        }

        int packedOverlay() {
            return packedOverlay;
        }

        ByteBuffer packForUpload() {
            ByteBuffer bytes = ByteBuffer.allocateDirect(INSTANCE_UPLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            putMatrix4(bytes, modelView);
            putNormalMatrix3Std140(bytes, normalTransform);
            bytes.putFloat(color.x()).putFloat(color.y()).putFloat(color.z()).putFloat(color.w());
            bytes.putFloat(packedLight & 0xFFFF)
                    .putFloat(packedLight >>> 16 & 0xFFFF)
                    .putFloat(0.0F)
                    .putFloat(0.0F);
            bytes.flip();
            return bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }

        private static void putMatrix4(ByteBuffer destination, Matrix4fc matrix) {
            destination.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
            destination.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
            destination.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
            destination.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
        }

        private static void putNormalMatrix3Std140(ByteBuffer destination, Matrix3fc matrix) {
            destination.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(0.0F);
            destination.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(0.0F);
            destination.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(0.0F);
        }
    }

    /**
     * Preallocated exact-one-child transfer slot. Its normal move state write allocates nothing and changes authority
     * before the post-move deterministic failpoint runs. Each terminal route claims the slot before invoking foreign
     * child code, so reentry and repeated catches cannot notify the child twice.
     */
    static final class CompletionTransfer {
        private final CompletionReceipt completion;
        private TransferState state = TransferState.UNMOVED;
        private Throwable callbackFailure;

        private CompletionTransfer(CompletionReceipt completion) {
            this.completion = Objects.requireNonNull(completion, "preallocatedCompletionReceipt");
        }

        /** Called only by the D1 atomic ownership move as its final allocation-free action. */
        synchronized void acceptMovedChild() {
            if (state != TransferState.UNMOVED) {
                throw new IllegalStateException("The one-instance skinned child was moved more than once");
            }
            state = TransferState.MOVED_NO_COMMAND;
        }

        synchronized boolean isMoved() {
            return state != TransferState.UNMOVED;
        }

        /** This is the uncertainty boundary and must run immediately before the native {@code drawIndexed} call. */
        synchronized void markDrawMayHaveIssued() {
            if (state != TransferState.MOVED_NO_COMMAND) {
                throw new IllegalStateException("The skinned draw boundary cannot be marked before its exact child move");
            }
            state = TransferState.DRAW_MAY_HAVE_ISSUED;
        }

        synchronized boolean hasDrawMayHaveIssued() {
            return state == TransferState.DRAW_MAY_HAVE_ISSUED
                    || state == TransferState.TERMINAL_NON_CLOSE
                    || state == TransferState.COMPLETED;
        }

        synchronized Throwable callbackFailureOrNull() {
            return callbackFailure;
        }

        void closeNoCommandExactlyOnce(Throwable failure) {
            CompletionReceipt owned;
            synchronized (this) {
                if (state != TransferState.MOVED_NO_COMMAND) {
                    return;
                }
                state = TransferState.CLOSED_NO_COMMAND;
                owned = completion;
            }
            try {
                owned.closeNoCommand();
            } catch (Throwable closeFailure) {
                recordCallbackFailure(closeFailure);
            }
        }

        void retainTerminalNonCloseExactlyOnce(Throwable failure) {
            CompletionReceipt owned;
            synchronized (this) {
                if (state != TransferState.DRAW_MAY_HAVE_ISSUED) {
                    return;
                }
                state = TransferState.TERMINAL_NON_CLOSE;
                owned = completion;
            }
            try {
                owned.retainTerminalNonClose(Objects.requireNonNull(failure, "failure"));
            } catch (Throwable retentionFailure) {
                recordCallbackFailure(retentionFailure);
            }
        }

        void completeAfterVerifiedFence() {
            CompletionReceipt owned;
            synchronized (this) {
                if (state != TransferState.DRAW_MAY_HAVE_ISSUED) {
                    return;
                }
                state = TransferState.COMPLETED;
                owned = completion;
            }
            try {
                owned.completeAfterVerifiedFence();
            } catch (Throwable completionFailure) {
                recordCallbackFailure(completionFailure);
            }
        }

        private synchronized void recordCallbackFailure(Throwable failure) {
            callbackFailure = append(callbackFailure, failure);
        }

        private enum TransferState {
            UNMOVED,
            MOVED_NO_COMMAND,
            DRAW_MAY_HAVE_ISSUED,
            CLOSED_NO_COMMAND,
            TERMINAL_NON_CLOSE,
            COMPLETED
        }
    }

    private X7Minecraft2612SkinnedPassSubmitter() {
    }

    static void setFailureInjectorForTest(FailureInjector injector) {
        failureInjector = Objects.requireNonNull(injector, "injector");
    }

    static void clearFailureInjectorForTest() {
        failureInjector = NO_FAILURE;
    }

    /** Package-private focused-test entrypoint for the same production boundary hook used by {@link MinecraftDriver}. */
    static void hitFailureBoundaryForTest(FailureBoundary boundary) {
        hitFailureBoundary(boundary);
    }

    static Driver minecraftDriver(RenderPipeline registeredPipeline) {
        return new MinecraftDriver(registeredPipeline);
    }

    static Result execute(
            D1Submission submission, D1SkinnedResources resources, FrameInputs inputs, AfterSolidScope scope, Driver driver) {
        D1Submission checkedSubmission = null;
        CommandRecorder commands = null;
        try {
            checkedSubmission = Objects.requireNonNull(submission, "submission");
            D1SkinnedResources checkedResources = Objects.requireNonNull(resources, "resources");
            FrameInputs checkedInputs = Objects.requireNonNull(inputs, "inputs");
            AfterSolidScope checkedScope = Objects.requireNonNull(scope, "scope");
            requireExactSameRecord(checkedSubmission, checkedResources, checkedInputs, checkedScope);
            commands = new CommandRecorder(checkedSubmission);
            FenceReceipt receipt = Objects.requireNonNull(
                    Objects.requireNonNull(driver, "driver").record(checkedResources, checkedInputs, checkedScope, commands),
                    "skinned command receipt");
            if (receipt != commands.preallocatedReceiptOrNull()) {
                throw new IllegalStateException("The skinned driver returned a receipt other than its preallocated receipt");
            }
            if (!commands.completionTransfer().isMoved() || !commands.hasNativeDrawBeenAttempted()) {
                throw new IllegalStateException("The skinned driver returned without an exact moved child and draw boundary");
            }
            return new Result(Disposition.COMMAND_RECORDED, receipt, null);
        } catch (Throwable failure) {
            if (commands != null && commands.completionTransfer().isMoved()) {
                FenceReceipt receipt = commands.preallocatedReceiptOrNull();
                if (commands.hasNativeDrawBeenAttempted()) {
                    if (receipt != null) {
                        receipt.retainTerminalNonClose(failure);
                    } else {
                        commands.completionTransfer().retainTerminalNonCloseExactlyOnce(failure);
                    }
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    return new Result(Disposition.COMMAND_RECORDED, receipt, failure);
                }

                // The child has moved, but no native draw was attempted: only the new completion owner may close it.
                if (receipt != null) {
                    receipt.closeNoCommand(failure);
                } else {
                    commands.completionTransfer().closeNoCommandExactlyOnce(failure);
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                return new Result(Disposition.OMITTED_BEFORE_COMMAND, null, failure);
            }
            if (checkedSubmission != null) {
                try {
                    checkedSubmission.cancelNoCommand();
                } catch (Throwable cancellationFailure) {
                    appendSuppressedSafely(failure, cancellationFailure);
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new Result(Disposition.OMITTED_BEFORE_COMMAND, null, failure);
        }
    }

    static void issueIndexedDraw(IndexedDraw draw, int indexCount) {
        Objects.requireNonNull(draw, "draw").drawIndexed(
                0, 0, requireTriangleIndexCount(indexCount), SKINNED_INSTANCE_COUNT);
    }

    private static int requireTriangleIndexCount(int indexCount) {
        if (indexCount <= 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("The one-instance skinned candidate requires non-empty indexed triangles");
        }
        return indexCount;
    }

    private static void requireExactSameRecord(
            D1Submission submission, D1SkinnedResources resources, FrameInputs inputs, AfterSolidScope scope) {
        if (submission.exactD1Record() != resources.exactD1Record()
                || submission.exactGeometryLeaf() != resources.exactGeometryLeaf()
                || submission.exactInfluenceLeaf() != resources.exactInfluenceLeaf()
                || submission.paletteUpload() != inputs.paletteUpload()
                || submission.exactFrameIdentity() != inputs.exactFrameIdentity()
                || submission.exactFrameIdentity() != scope.exactFrameIdentity()) {
            throw new IllegalArgumentException("The skinned candidate lost its exact D1 record, current palette, or frame identity");
        }
        if (inputs.packedOverlay() != OverlayTexture.NO_OVERLAY) {
            throw new IllegalArgumentException("The skinned candidate refuses a non-neutral overlay");
        }
        if (resources.vertexCount() <= 0 || resources.indexCount() <= 0 || resources.indexCount() % 3 != 0) {
            throw new IllegalArgumentException("The skinned candidate requires non-empty exact D1 triangle geometry");
        }
    }

    private static int requireExactlyRepresentablePackedLight(int packedLight) {
        int block = packedLight & 0xFFFF;
        int sky = packedLight >>> 16 & 0xFFFF;
        if (block > MAX_LIGHTMAP_COORDINATE || sky > MAX_LIGHTMAP_COORDINATE
                || (block & 0xF) != 0 || (sky & 0xF) != 0) {
            throw new IllegalArgumentException("The skinned candidate requires exact Minecraft packed-light lanes");
        }
        return packedLight;
    }

    private static void hitFailureBoundary(FailureBoundary boundary) {
        failureInjector.hit(Objects.requireNonNull(boundary, "boundary"));
    }

    private static final class CommandRecorder implements CommandsRecorded {
        private final D1Submission submission;
        private final CompletionTransfer completionTransfer;
        private FenceReceipt preallocatedReceipt;
        private boolean transferInvoked;

        private CommandRecorder(D1Submission submission) {
            this.submission = Objects.requireNonNull(submission, "submission");
            // This is deliberately before Driver.record and before the ownership-moving method.
            this.completionTransfer = new CompletionTransfer(submission.preallocatedCompletionReceipt());
        }

        @Override
        public CompletionTransfer completionTransfer() {
            return completionTransfer;
        }

        @Override
        public void installPreallocatedReceipt(FenceReceipt receipt) {
            FenceReceipt checkedReceipt = Objects.requireNonNull(receipt, "receipt");
            if (preallocatedReceipt != null) {
                throw new IllegalStateException("The one-instance skinned command receipt was installed more than once");
            }
            if (checkedReceipt.completionTransfer() != completionTransfer) {
                throw new IllegalArgumentException("The skinned receipt must retain this command's exact completion slot");
            }
            preallocatedReceipt = checkedReceipt;
        }

        @Override
        public void recordAllBeforeNativeDraw() {
            if (preallocatedReceipt == null) {
                throw new IllegalStateException("The skinned receipt must be preallocated before the child moves");
            }
            if (transferInvoked || completionTransfer.isMoved()) {
                throw new IllegalStateException("The one-instance skinned child was recorded more than once");
            }
            transferInvoked = true;
            submission.recordAllBeforeNativeDraw(completionTransfer);
            if (!completionTransfer.isMoved()) {
                throw new IllegalStateException("The D1 move returned without atomically recording its child transfer");
            }
            // Deterministic test seam: no allocation or caller-cancellation path may exist after this point.
            hitFailureBoundary(FailureBoundary.CHILD_TRANSFER_AFTER_MOVE);
        }

        @Override
        public void markNativeDrawAttempted() {
            if (preallocatedReceipt == null) {
                throw new IllegalStateException("The native skinned draw boundary is not reachable exactly once");
            }
            completionTransfer.markDrawMayHaveIssued();
        }

        @Override
        public boolean hasNativeDrawBeenAttempted() {
            return completionTransfer.hasDrawMayHaveIssued();
        }

        private FenceReceipt preallocatedReceiptOrNull() {
            return preallocatedReceipt;
        }
    }

    /** Owns one preallocated post-command fence/retention shell and only transient palette/instance/lightmap objects. */
    static final class FenceReceipt {
        @FunctionalInterface
        interface RenderThreadAssertion {
            void assertOnRenderThread();
        }

        interface Fence {
            boolean awaitCompletionZero();

            void close();
        }

        interface TransientClose {
            void close();
        }

        enum State {
            PENDING,
            CLOSED_NO_COMMAND,
            TERMINAL_NON_CLOSE,
            COMPLETED
        }

        private final Fence fence;
        private final TransientClose transientClose;
        private final CompletionTransfer completionTransfer;
        private State state;
        private boolean transientsClosed;
        private Throwable terminalFailure;

        private FenceReceipt(Fence fence, TransientClose transientClose, CompletionTransfer completionTransfer) {
            this.fence = Objects.requireNonNull(fence, "fence");
            this.transientClose = Objects.requireNonNull(transientClose, "transientClose");
            this.completionTransfer = Objects.requireNonNull(completionTransfer, "completionTransfer");
            this.state = State.PENDING;
        }

        static FenceReceipt pending(Fence fence, TransientClose close, CompletionTransfer completionTransfer) {
            return new FenceReceipt(fence, close, completionTransfer);
        }

        synchronized State pollOnRenderThread() {
            return poll(RenderSystem::assertOnRenderThread);
        }

        synchronized State poll(RenderThreadAssertion renderThreadAssertion) {
            Objects.requireNonNull(renderThreadAssertion, "renderThreadAssertion").assertOnRenderThread();
            if (state != State.PENDING) {
                return state;
            }
            final boolean complete;
            try {
                complete = fence.awaitCompletionZero();
            } catch (Throwable failure) {
                retainTerminalNonClose(failure);
                return state;
            }
            if (!complete) {
                return state;
            }
            Throwable failure = null;
            try {
                fence.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            failure = append(failure, closeTransientsExactlyOnce());
            completionTransfer.completeAfterVerifiedFence();
            failure = append(failure, completionTransfer.callbackFailureOrNull());
            state = State.COMPLETED;
            terminalFailure = failure;
            return state;
        }

        /** Exactly-once uncertain-command retention. It intentionally does not close transient GPU state. */
        synchronized void retainTerminalNonClose(Throwable failure) {
            if (state != State.PENDING) {
                return;
            }
            if (!completionTransfer.hasDrawMayHaveIssued()) {
                closeNoCommand(failure);
                return;
            }
            state = State.TERMINAL_NON_CLOSE;
            completionTransfer.retainTerminalNonCloseExactlyOnce(Objects.requireNonNull(failure, "failure"));
            terminalFailure = append(terminalFailure, failure);
            terminalFailure = append(terminalFailure, completionTransfer.callbackFailureOrNull());
        }

        /** Exactly-once no-command close for a child that has moved but has not reached the native draw boundary. */
        synchronized void closeNoCommand(Throwable failure) {
            if (state != State.PENDING) {
                return;
            }
            state = State.CLOSED_NO_COMMAND;
            Throwable closeFailure = closeTransientsExactlyOnce();
            Throwable combined = append(Objects.requireNonNull(failure, "failure"), closeFailure);
            completionTransfer.closeNoCommandExactlyOnce(combined);
            terminalFailure = append(terminalFailure, combined);
            terminalFailure = append(terminalFailure, completionTransfer.callbackFailureOrNull());
        }

        synchronized State state() {
            return state;
        }

        synchronized Throwable terminalFailureOrNull() {
            return terminalFailure;
        }

        private CompletionTransfer completionTransfer() {
            return completionTransfer;
        }

        private Throwable closeTransientsExactlyOnce() {
            if (transientsClosed) {
                return null;
            }
            transientsClosed = true;
            try {
                transientClose.close();
                return null;
            } catch (Throwable closeFailure) {
                return closeFailure;
            }
        }
    }

    private static final class MinecraftDriver implements Driver {
        private final RenderPipeline pipeline;

        private MinecraftDriver(RenderPipeline pipeline) {
            this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        }

        @Override
        public FenceReceipt record(
                D1SkinnedResources resources,
                FrameInputs inputs,
                AfterSolidScope scope,
                CommandsRecorded commands) {
            RenderSystem.assertOnRenderThread();
            D1SkinnedResources checkedResources = Objects.requireNonNull(resources, "resources");
            CommandsRecorded checkedCommands = Objects.requireNonNull(commands, "commands");
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device == null) {
                device = RenderSystem.getDevice();
            }
            CommandEncoder encoder = Objects.requireNonNull(device.createCommandEncoder(), "commandEncoder");
            GpuBuffer paletteUniform = null;
            GpuBuffer instanceUniform = null;
            GpuSampler lightmapSampler = null;
            RenderPass pass = null;
            try {
                paletteUniform = Objects.requireNonNull(device.createBuffer(
                        () -> "blendlib-x7-skinned/palette",
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                        resourcesPaletteBytes(inputs.paletteUpload())), "skinned palette uniform");
                instanceUniform = Objects.requireNonNull(device.createBuffer(
                        () -> "blendlib-x7-skinned/instance",
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                        inputs.packForUpload()), "skinned instance uniform");
                lightmapSampler = Objects.requireNonNull(device.createSampler(
                        AddressMode.CLAMP_TO_EDGE,
                        AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST,
                        FilterMode.NEAREST,
                        1,
                        OptionalDouble.empty()), "skinned lightmap sampler");
                pass = encoder.createRenderPass(
                        () -> "blendlib-x7-skinned/direct",
                        scope.colorTarget(),
                        OptionalInt.empty(),
                        scope.depthTarget(),
                        OptionalDouble.empty());
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(checkedResources.textureId());
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), lightmapSampler);
                checkedResources.bindTo(pass);
                pass.setUniform("BonePalette", paletteUniform);
                pass.setUniform("X7SkinnedInstance", instanceUniform);
                // Validation and the captured public draw target are allocation-fallible, so they remain pre-move.
                int indexCount = requireTriangleIndexCount(checkedResources.indexCount());
                IndexedDraw indexedDraw = pass::drawIndexed;

                // Every post-transfer shell exists before the D1 child leaves its caller-owned queue.
                GpuBuffer retainedPalette = paletteUniform;
                GpuBuffer retainedInstance = instanceUniform;
                GpuSampler retainedSampler = lightmapSampler;
                DeferredGpuFence deferredFence = new DeferredGpuFence();
                hitFailureBoundary(FailureBoundary.RECEIPT_CONSTRUCTION);
                FenceReceipt preallocatedReceipt = FenceReceipt.pending(
                        deferredFence,
                        () -> closeTransients(retainedPalette, retainedInstance, retainedSampler),
                        checkedCommands.completionTransfer());
                checkedCommands.installPreallocatedReceipt(preallocatedReceipt);

                checkedCommands.recordAllBeforeNativeDraw();
                // Before this hook and draw call no native command has been attempted, so the moved child is closed.
                hitFailureBoundary(FailureBoundary.DRAW_INDEXED);
                checkedCommands.markNativeDrawAttempted();
                indexedDraw.drawIndexed(0, 0, indexCount, SKINNED_INSTANCE_COUNT);
                hitFailureBoundary(FailureBoundary.AFTER_DRAW_INDEXED);
                hitFailureBoundary(FailureBoundary.PASS_CLOSE);
                pass.close();
                pass = null;
                hitFailureBoundary(FailureBoundary.FENCE_ATTACHMENT);
                deferredFence.attach(Objects.requireNonNull(encoder.createFence(), "skinned fence"));
                return preallocatedReceipt;
            } catch (Throwable failure) {
                closePass(pass, failure);
                if (!checkedCommands.completionTransfer().isMoved()) {
                    closeTransientsBeforeTransfer(paletteUniform, instanceUniform, lightmapSampler, failure);
                }
                throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }
        }

        /** T3c supplies one exact typed palette unit; raw palette bytes never cross the adapter boundary. */
        private static ByteBuffer resourcesPaletteBytes(PaletteUpload paletteUpload) {
            ByteBuffer bytes = Objects.requireNonNull(paletteUpload, "paletteUpload").bytesForUpload();
            if (!bytes.isDirect() || bytes.remaining() != 8 * 1024) {
                throw new IllegalArgumentException("The skinned palette must retain exactly one direct 8 KiB upload view");
            }
            ByteBuffer copy = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            copy.position(bytes.position());
            return copy;
        }

        private static void closePass(RenderPass pass, Throwable primary) {
            if (pass == null) {
                return;
            }
            try {
                pass.close();
            } catch (Throwable closeFailure) {
                appendSuppressedSafely(primary, closeFailure);
            }
        }

        private static void closeTransientsBeforeTransfer(
                GpuBuffer palette, GpuBuffer instance, GpuSampler sampler, Throwable primary) {
            try {
                closeTransients(palette, instance, sampler);
            } catch (Throwable closeFailure) {
                appendSuppressedSafely(primary, closeFailure);
            }
        }

        private static void closeTransients(GpuBuffer palette, GpuBuffer instance, GpuSampler sampler) {
            Throwable failure = null;
            try {
                if (sampler != null) {
                    sampler.close();
                }
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                if (instance != null) {
                    instance.close();
                }
            } catch (Throwable closeFailure) {
                failure = append(failure, closeFailure);
            }
            try {
                if (palette != null) {
                    palette.close();
                }
            } catch (Throwable closeFailure) {
                failure = append(failure, closeFailure);
            }
            if (failure != null) {
                throwUnchecked(failure);
            }
        }

        private static final class DeferredGpuFence implements FenceReceipt.Fence {
            private GpuFence fence;

            private void attach(GpuFence fence) {
                if (this.fence != null) {
                    throw new IllegalStateException("The skinned deferred fence was attached more than once");
                }
                this.fence = Objects.requireNonNull(fence, "fence");
            }

            @Override
            public boolean awaitCompletionZero() {
                if (fence == null) {
                    throw new IllegalStateException("The skinned command has no verified fence");
                }
                return fence.awaitCompletion(0L);
            }

            @Override
            public void close() {
                if (fence != null) {
                    fence.close();
                }
            }
        }
    }

    private static Throwable append(Throwable primary, Throwable addition) {
        if (primary == null) {
            return addition;
        }
        if (primary != addition) {
            appendSuppressedSafely(primary, addition);
        }
        return primary;
    }

    private static void appendSuppressedSafely(Throwable primary, Throwable addition) {
        if (primary == null || addition == null || primary == addition) {
            return;
        }
        try {
            primary.addSuppressed(addition);
        } catch (Throwable ignored) {
            // Preserve the original failure even if a suppressed-exception allocation cannot be made.
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
