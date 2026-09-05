package com.liy.blendlib.fabric.client.reload;

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
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * Phase-B-only executor for already-queued, bounded direct-static batches.
 *
 * <p>This type has no host registration, X6 call site, queue ownership, or policy mutation authority. A future T3c
 * endpoint supplies an exact queue-owned batch and a live AFTER_SOLID scope. Before any command, every failure
 * cancels each queue-owned child and reports a current-frame omission. Once {@link CommandsRecorded} runs, the
 * returned receipt retains every transferred child until a verified fence completion.</p>
 */
final class StaticDirectDrawExecutor {
    interface AfterSolidScope {
        X7AfterSolidFrameIdentity frame();
    }

    @FunctionalInterface
    interface CommandsRecorded {
        /**
         * Atomically adopts every child before a native draw is permitted and returns its complete immutable receipt
         * set. A driver must invoke this only after every pre-command allocation is complete and before drawIndexed.
         */
        List<X7DeferredSubmissionCompletionReceipt> recordAllBeforeNativeDraw();
    }

    /** The verified public RenderPass ABI: baseVertex, firstIndex, indexCount, instanceCount. */
    @FunctionalInterface
    interface IndexedDraw {
        void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount);
    }

    interface Driver {
        /**
         * Throws only before {@code commandsRecorded.recordAllBeforeNativeDraw()} has transferred every child. After
         * that transfer, an uncertain command must be returned as a terminal non-close receipt rather than thrown.
         */
        StaticDirectFenceReceipt record(
                StaticDirectBatch batch,
                AfterSolidScope scope,
                CommandsRecorded commandsRecorded);
    }

    enum Disposition {
        CULLED_NO_DRAW,
        OMITTED_BEFORE_COMMAND,
        COMMAND_RECORDED
    }

    static final class Result {
        private final Disposition disposition;
        private final StaticDirectFenceReceipt receipt;
        private final Throwable failure;

        private Result(Disposition disposition, StaticDirectFenceReceipt receipt, Throwable failure) {
            this.disposition = Objects.requireNonNull(disposition, "disposition");
            this.receipt = receipt;
            this.failure = failure;
        }

        Disposition disposition() {
            return disposition;
        }

        StaticDirectFenceReceipt receiptOrNull() {
            return receipt;
        }

        Throwable failureOrNull() {
            return failure;
        }
    }

    private StaticDirectDrawExecutor() {
    }

    /** Creates the public-API-only Minecraft driver after T3c has registered the supplied pipeline. */
    static Driver minecraftDriver(RenderPipeline registeredPipeline) {
        return new MinecraftDriver(registeredPipeline);
    }

    /** Compatibility-shaped singleton entrypoint; the real command path is always a batch. */
    static Result execute(
            X7DeferredFrameQueue.QueuedSubmission queued,
            StaticDirectFrameInputs inputs,
            StaticDirectGenerationResources resources,
            AfterSolidScope scope,
            Driver driver) {
        try {
            return executeBatch(StaticDirectBatch.builder(queued, inputs, resources).build(), scope, driver);
        } catch (Throwable failure) {
            X7DeferredFrameQueue.QueuedSubmission checkedQueued = Objects.requireNonNull(queued, "queued");
            try {
                checkedQueued.cancelNoCommand();
            } catch (Throwable cancelFailure) {
                if (cancelFailure != failure) {
                    failure.addSuppressed(cancelFailure);
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new Result(Disposition.OMITTED_BEFORE_COMMAND, null, failure);
        }
    }

    static Result executeBatch(StaticDirectBatch batch, AfterSolidScope scope, Driver driver) {
        StaticDirectBatch checkedBatch = Objects.requireNonNull(batch, "batch");
        CommandRecorder commandsRecorded = null;
        try {
            AfterSolidScope checkedScope = Objects.requireNonNull(scope, "scope");
            Driver checkedDriver = Objects.requireNonNull(driver, "driver");
            if (checkedBatch.entries().stream().anyMatch(entry -> entry.inputs().exactSnapshot().visibility()
                    != com.liy.blendlib.fabric.client.render.RenderVisibility.VISIBLE)) {
                checkedBatch.cancelNoCommand();
                return new Result(Disposition.CULLED_NO_DRAW, null, null);
            }
            for (StaticDirectBatch.Entry entry : checkedBatch.entries()) {
                requireExactPhaseBInputs(entry.queued().request(), entry.inputs(), checkedBatch, checkedScope);
            }
            commandsRecorded = new CommandRecorder(checkedBatch);
            StaticDirectFenceReceipt receipt = Objects.requireNonNull(
                    checkedDriver.record(checkedBatch, checkedScope, commandsRecorded),
                    "direct-static command receipt");
            return new Result(Disposition.COMMAND_RECORDED, receipt, null);
        } catch (Throwable failure) {
            if (commandsRecorded != null && commandsRecorded.hasRecordedChildren()) {
                StaticDirectFenceReceipt.retainTerminalCompletions(commandsRecorded.completions(), failure);
                try {
                    return new Result(
                            Disposition.COMMAND_RECORDED,
                            StaticDirectFenceReceipt.terminal(() -> { }, commandsRecorded.completions(), failure),
                            failure);
                } catch (Throwable terminalReceiptFailure) {
                    X7DeferredSubmissionBridge.appendSuppressedSafely(failure, terminalReceiptFailure);
                    // The queue completion receipts already retain every child. Never cancel or label this as a
                    // pre-command omission after a driver has crossed the atomic command-retention boundary.
                    return new Result(Disposition.COMMAND_RECORDED, null, failure);
                }
            }
            try {
                checkedBatch.cancelNoCommand();
            } catch (Throwable cancelFailure) {
                if (cancelFailure != failure) {
                    failure.addSuppressed(cancelFailure);
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new Result(Disposition.OMITTED_BEFORE_COMMAND, null, failure);
        }
    }

    /** Single-use executor-owned gate that makes a duplicate or late child adoption fail before any native draw. */
    private static final class CommandRecorder implements CommandsRecorded {
        private final StaticDirectBatch batch;
        private List<X7DeferredSubmissionCompletionReceipt> completions;

        private CommandRecorder(StaticDirectBatch batch) {
            this.batch = batch;
        }

        @Override
        public List<X7DeferredSubmissionCompletionReceipt> recordAllBeforeNativeDraw() {
            if (completions != null) {
                throw new IllegalStateException("The direct-static command batch was recorded more than once");
            }
            completions = batch.recordCommandsAtomically();
            return completions;
        }

        private boolean hasRecordedChildren() {
            return completions != null;
        }

        private List<X7DeferredSubmissionCompletionReceipt> completions() {
            return completions;
        }
    }

    private static void requireExactPhaseBInputs(
            X7PreAdmittedFrameRequest request,
            StaticDirectFrameInputs inputs,
            StaticDirectBatch batch,
            AfterSolidScope scope) {
        X7AfterSolidFrameIdentity targetFrame = request.targetFrame();
        if (targetFrame.phase() != X7AfterSolidFrameIdentity.Phase.AFTER_SOLID_FEATURES
                || !targetFrame.equals(batch.exactTargetFrame())
                || !targetFrame.equals(Objects.requireNonNull(scope.frame(), "scope.frame"))) {
            throw new IllegalArgumentException("The direct-static draw crossed its exact AFTER_SOLID frame boundary");
        }
        if (request.exactSnapshot() != inputs.exactSnapshot()
                || request.exactDraw() != inputs.exactDraw()
                || !inputs.exactMarker().matchesExactly(request.exactSnapshot(), request.exactDraw())) {
            throw new IllegalArgumentException("The direct-static frame inputs lost their exact snapshot/draw/marker identities");
        }
        if (!StaticDirectOpaqueMarker.isExactNeutralOverlay(inputs.packedOverlay())) {
            throw new IllegalArgumentException("The direct-static executor refuses a non-neutral overlay");
        }
        if (request.exactPolicyRecord().isTerminallyCleared()
                || request.exactLeaf() != batch.exactResources()
                || request.exactPublished().backend() != X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE) {
            throw new IllegalStateException("The direct-static draw no longer has its exact D1 policy/resource authority");
        }
        StaticDirectGenerationResources resources = batch.exactResources();
        if (resources.isClosed()
                || resources.vertexCount() != inputs.vertexCount()
                || resources.indexCount() != inputs.indexCount()) {
            throw new IllegalStateException("The direct-static resource leaf does not match its exact immutable geometry");
        }
    }

    /** Live target scope constructed only inside a synchronous T3c AFTER_SOLID callback. */
    record MinecraftAfterSolidScope(
            X7AfterSolidFrameIdentity frame, GpuTextureView colorTarget, GpuTextureView depthTarget)
            implements AfterSolidScope {
        MinecraftAfterSolidScope {
            frame = Objects.requireNonNull(frame, "frame");
            colorTarget = Objects.requireNonNull(colorTarget, "colorTarget");
            depthTarget = Objects.requireNonNull(depthTarget, "depthTarget");
        }
    }

    /**
     * Emits the one verified native indexed command. The separate seam makes the four-int Minecraft ABI testable
     * without mocking or reflecting into a RenderPass implementation.
     */
    static void issueIndexedDraw(IndexedDraw indexedDraw, StaticDirectGenerationResources resources, int instanceCount) {
        StaticDirectGenerationResources checkedResources = Objects.requireNonNull(resources, "resources");
        if (checkedResources.indexCount() <= 0 || instanceCount <= 0) {
            throw new IllegalArgumentException("The direct-static indexed draw requires positive index and instance counts");
        }
        Objects.requireNonNull(indexedDraw, "indexedDraw").drawIndexed(
                0, 0, checkedResources.indexCount(), instanceCount);
    }

    private static final class MinecraftDriver implements Driver {
        private final RenderPipeline pipeline;

        private MinecraftDriver(RenderPipeline pipeline) {
            this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        }

        @Override
        public StaticDirectFenceReceipt record(
                StaticDirectBatch batch,
                AfterSolidScope scope,
                CommandsRecorded commandsRecorded) {
            if (!(scope instanceof MinecraftAfterSolidScope minecraftScope)) {
                throw new IllegalArgumentException("The Minecraft direct-static driver requires a live target scope");
            }
            RenderSystem.assertOnRenderThread();
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device == null) {
                device = RenderSystem.getDevice();
            }
            CommandEncoder encoder = Objects.requireNonNull(device.createCommandEncoder(), "commandEncoder");
            GpuBuffer instanceUniform = null;
            GpuSampler lightmapSampler = null;
            RenderPass pass = null;
            List<X7DeferredSubmissionCompletionReceipt> completions = null;
            StaticDirectFenceReceipt preallocatedReceipt = null;
            try {
                instanceUniform = Objects.requireNonNull(device.createBuffer(
                        () -> "blendlib-x7-static/instances",
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                        batch.instanceUpload().bytesForUpload()), "direct-static instance buffer");
                // sample_lightmap samples texel centers, so this public nearest/edge sampler preserves the packed
                // light UV contract instead of deriving a scalar brightness approximation.
                lightmapSampler = Objects.requireNonNull(device.createSampler(
                        AddressMode.CLAMP_TO_EDGE,
                        AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST,
                        FilterMode.NEAREST,
                        1,
                        OptionalDouble.empty()), "direct-static lightmap sampler");
                pass = encoder.createRenderPass(
                        () -> "blendlib-x7-static/direct",
                        minecraftScope.colorTarget(),
                        OptionalInt.empty(),
                        minecraftScope.depthTarget(),
                        OptionalDouble.empty());
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(textureId(batch));
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), lightmapSampler);
                pass.setUniform("X7StaticInstances", instanceUniform);
                batch.exactResources().bindTo(pass);
                // No command may be issued until every queued child is atomically completion-owned. All receipt and
                // transient-retention allocation is deliberately complete before drawIndexed can make work visible.
                completions = commandsRecorded.recordAllBeforeNativeDraw();
                GpuBuffer retainedInstanceUniform = instanceUniform;
                GpuSampler retainedLightmapSampler = lightmapSampler;
                DeferredGpuFence deferredFence = new DeferredGpuFence();
                preallocatedReceipt = StaticDirectFenceReceipt.pending(
                        deferredFence,
                        () -> closeTransients(retainedInstanceUniform, retainedLightmapSampler),
                        completions);
                issueIndexedDraw(pass::drawIndexed, batch.exactResources(), batch.instanceCount());
                pass.close();
                pass = null;
                deferredFence.attach(Objects.requireNonNull(encoder.createFence(), "direct-static fence"));
                return preallocatedReceipt;
            } catch (Throwable failure) {
                closePass(pass, failure);
                if (completions == null) {
                    closeTransientsBeforeTransfer(instanceUniform, lightmapSampler, failure);
                    StaticDirectDrawExecutor.<RuntimeException>throwUnchecked(failure);
                    throw new AssertionError("unreachable");
                }
                if (preallocatedReceipt != null) {
                    preallocatedReceipt.retainTerminalNonClose(failure);
                    return preallocatedReceipt;
                }
                // This path is still before drawIndexed: command retention succeeded but receipt construction did
                // not. The queue retains every child; no transient has been referenced by a native command.
                closeTransientsBeforeTransfer(instanceUniform, lightmapSampler, failure);
                StaticDirectDrawExecutor.<RuntimeException>throwUnchecked(failure);
                throw new AssertionError("unreachable");
            }
        }

        /** Preallocated before draw so a post-draw fence creation failure has a terminal receipt already available. */
        private static final class DeferredGpuFence implements StaticDirectFenceReceipt.Fence {
            private GpuFence fence;

            private void attach(GpuFence fence) {
                this.fence = Objects.requireNonNull(fence, "fence");
            }

            @Override
            public boolean awaitCompletionZero() {
                GpuFence current = fence;
                if (current == null) {
                    throw new IllegalStateException("The direct-static command has no verified fence");
                }
                return current.awaitCompletion(0L);
            }

            @Override
            public void close() {
                GpuFence current = fence;
                if (current != null) {
                    current.close();
                }
            }
        }

        private static Identifier textureId(StaticDirectBatch batch) {
            return Identifier.fromNamespaceAndPath(
                    batch.exactTextureId().namespace(), batch.exactTextureId().path());
        }

        private static void closePass(RenderPass pass, Throwable primary) {
            if (pass == null) {
                return;
            }
            try {
                pass.close();
            } catch (Throwable closeFailure) {
                if (closeFailure != primary) {
                    primary.addSuppressed(closeFailure);
                }
            }
        }

        private static void closeTransientsBeforeTransfer(
                GpuBuffer instanceUniform, GpuSampler lightmapSampler, Throwable primary) {
            try {
                closeTransients(instanceUniform, lightmapSampler);
            } catch (Throwable closeFailure) {
                if (closeFailure != primary) {
                    primary.addSuppressed(closeFailure);
                }
            }
        }

        private static void closeTransients(GpuBuffer instanceUniform, GpuSampler lightmapSampler) {
            Throwable failure = null;
            try {
                if (lightmapSampler != null) {
                    lightmapSampler.close();
                }
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                if (instanceUniform != null) {
                    instanceUniform.close();
                }
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                StaticDirectDrawExecutor.<RuntimeException>throwUnchecked(failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
