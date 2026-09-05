package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.X7T3cFrozenStageA;
import com.liy.blendlib.fabric.client.render.X7T3cFrozenStageA.Route;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance.PaletteUpload;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Client-internal bridge owning the T2a3 queue interaction and T3b static execution.
 *
 * <p>The facade is public only because the root client package and render package are sibling Java packages. Its
 * methods intentionally expose neither the D1 record/leaf/child nor native buffers. The existing queue retains one
 * private inline payload as its exact-plan identity; no lookup table or cache is introduced.</p>
 */
public final class X7T3cReloadGateway {
    private static final int MAX_RETAINED_COMMAND_RECEIPTS = X7DeferredFrameQueue.MAX_REQUESTS_PER_AFTER_SOLID_FRAME;

    /** Root-client T4 adapter invoked only for an already queue-owned typed skinned request. */
    @FunctionalInterface
    public interface SkinnedExecutor {
        SkinnedStageBDisposition execute(SkinnedWork work, GpuTextureView colorTarget, GpuTextureView depthTarget);
    }

    /** The root adapter reports only pre-command omission versus retained post-command ownership. */
    public enum SkinnedStageBDisposition {
        OMITTED_BEFORE_COMMAND,
        RETAINED_AFTER_COMMAND
    }

    /** Opaque typed T4 work view. It deliberately has no D1 or native-handle accessor. */
    public interface SkinnedWork {
        FrozenSkinnedInputs inputs();

        AuthorityToken authority();

        int vertexCount();

        int indexCount();

        Identifier textureId();

        void bindTo(RenderPass pass);

        void recordAllBeforeNativeDraw(Runnable movedChild);

        void cancelNoCommand();

        CompletionHooks completionHooks();
    }

    /** Copy-only final pose/light/overlay plus the exact T4 typed palette unit. */
    public record FrozenSkinnedInputs(
            Matrix4f modelView,
            Matrix3f normalTransform,
            int composedArgb,
            int packedLight,
            int packedOverlay,
            PaletteUpload paletteUpload) {
        public FrozenSkinnedInputs {
            modelView = new Matrix4f(Objects.requireNonNull(modelView, "modelView"));
            normalTransform = new Matrix3f(Objects.requireNonNull(normalTransform, "normalTransform"));
            paletteUpload = Objects.requireNonNull(paletteUpload, "paletteUpload");
        }

        @Override
        public Matrix4f modelView() {
            return new Matrix4f(modelView);
        }

        @Override
        public Matrix3f normalTransform() {
            return new Matrix3f(normalTransform);
        }
    }

    /** Non-forgeable-at-construction opaque identity shared only by one drained typed skinned work item. */
    public static final class AuthorityToken {
        private AuthorityToken() {
        }
    }

    /** Queue/completion-only terminal actions. No record, child, or native value is exposed. */
    public interface CompletionHooks {
        void closeNoCommand();

        void completeAfterVerifiedFence();

        void retainTerminalNonClose(Throwable failure);
    }

    private final long renderThreadId;
    private final X7DeferredFrameQueue queue;
    private final StaticDirectDrawExecutor.Driver staticDriver;
    private final boolean registeredT3bPipeline;
    // Fixed preallocated owner: completed receipts leave; terminal uncertainty remains and consumes capacity.
    private final StaticDirectFenceReceipt[] pendingStaticFences =
            new StaticDirectFenceReceipt[MAX_RETAINED_COMMAND_RECEIPTS];
    private int pendingStaticFenceCount;
    private X7AfterSolidFrameIdentity lastDrainedFrame;
    private boolean admissionClosed;

    private X7T3cReloadGateway(
            long renderThreadId, X7DeferredFrameQueue queue, StaticDirectDrawExecutor.Driver staticDriver, boolean registeredT3bPipeline) {
        this.renderThreadId = renderThreadId;
        this.queue = Objects.requireNonNull(queue, "queue");
        this.staticDriver = staticDriver;
        this.registeredT3bPipeline = registeredT3bPipeline;
    }

    /** Creates the real client-only queue/registration owner; a registration failure leaves admission fail-closed. */
    public static X7T3cReloadGateway production() {
        long renderThreadId = Thread.currentThread().threadId();
        RenderPipeline pipeline = registerT3bPipelineOnce();
        return new X7T3cReloadGateway(
                renderThreadId,
                new X7DeferredFrameQueue(renderThreadId),
                pipeline == null ? null : StaticDirectDrawExecutor.minecraftDriver(pipeline),
                pipeline != null);
    }

    /** Package-private focused-test constructor. It never registers a global pipeline. */
    static X7T3cReloadGateway forTest(long renderThreadId, int capacity, StaticDirectDrawExecutor.Driver staticDriver) {
        return new X7T3cReloadGateway(
                renderThreadId,
                new X7DeferredFrameQueue(renderThreadId, capacity),
                Objects.requireNonNull(staticDriver, "staticDriver"),
                true);
    }

    /** The production facade registers during construction; this is an idempotent observability hook. */
    public boolean registerT3bPipeline() {
        return registeredT3bPipeline;
    }

    public boolean hasRegisteredT3bPipeline() {
        return registeredT3bPipeline;
    }

    /**
     * Performs the final Stage-A atomic child-to-queue transfer. False means the caller still owns and must close
     * the frozen object/receipt so the current X6 invocation retains CPU.
     */
    public boolean tryAdmit(X7T3cFrozenStageA input) {
        X7T3cFrozenStageA checkedInput = Objects.requireNonNull(input, "input");
        if (admissionClosed || !checkedInput.cadenceAlreadyConsumed()) {
            return false;
        }
        if (!registeredT3bPipeline || staticDriver == null) {
            return false;
        }
        if (checkedInput.route() == Route.STATIC_RIGID && pendingStaticFenceCount == pendingStaticFences.length) {
            return false;
        }
        X7T3cQueuedPayload payload = preparePayload(checkedInput);
        if (payload == null) {
            return false;
        }
        X7DeferredFrameQueue.Admission admission;
        try {
            admission = queue.tryAdmit(payload, checkedInput.exactSnapshot(), checkedInput.exactDraw(), checkedInput.childReceipt());
        } catch (Error error) {
            throw error;
        } catch (RuntimeException rejected) {
            return false;
        }
        if (!admission.accepted()) {
            return false;
        }
        X7DeferredFrameQueue.QueuedSubmission queued = admission.submission();
        try {
            if (!payload.seal(queued)) {
                fuseAndCancelNoCommand(queued);
                return false;
            }
            return true;
        } catch (Error error) {
            try {
                fuseAndCancelNoCommand(queued);
            } catch (Throwable cleanup) {
                if (cleanup != error) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        } catch (RuntimeException rejected) {
            fuseAndCancelNoCommand(queued);
            return false;
        }
    }

    private static X7T3cQueuedPayload preparePayload(X7T3cFrozenStageA input) {
        try {
            if (input.route() == Route.STATIC_RIGID) {
                StaticDirectOpaqueMarker marker = StaticDirectOpaqueMarker.forExact(input.exactSnapshot(), input.exactDraw());
                StaticDirectFrameInputs staticInputs = StaticDirectFrameInputs.create(
                        input.exactSnapshot(),
                        input.exactDraw(),
                        marker,
                        StaticDirectPoseSnapshot.freeze(input.modelViewCopy(), input.normalCopy()));
                return X7T3cQueuedPayload.staticPayload(input, staticInputs);
            }
            if (input.route() == Route.SKINNED_T4P) {
                X7SkinnedTexelProvenance.Attempt attempt = input.skinnedAttemptOrNull();
                X7SkinnedTexelProvenance provenance = attempt == null ? null : attempt.provenanceOrNull();
                if (provenance == null || input.skinnedFrameProofOrNull() == null
                        || !provenance.matchesExactly(input.skinnedFrameProofOrNull())) {
                    return null;
                }
                return X7T3cQueuedPayload.skinnedPayload(input, provenance);
            }
            return null;
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    /** Seals exactly the current queue epoch, executes only phase-B work, and never replays CPU collectors. */
    public void sealDrainAndExecute(
            GpuTextureView colorTarget, GpuTextureView depthTarget, SkinnedExecutor skinnedExecutor) {
        pollRetainedStaticFences();
        List<X7DeferredFrameQueue.QueuedSubmission> drained = queue.sealAndDrainAfterSolid(renderThreadId);
        if (drained.isEmpty()) {
            return;
        }
        lastDrainedFrame = drained.getFirst().request().targetFrame();
        for (X7DeferredFrameQueue.QueuedSubmission queued : drained) {
            X7T3cQueuedPayload payload;
            try {
                payload = payloadOf(queued);
            } catch (RuntimeException malformedPayload) {
                fuseAndCancelNoCommand(queued);
                continue;
            }
            if (colorTarget == null || depthTarget == null) {
                fuseAndCancelNoCommand(queued);
                continue;
            }
            if (payload.staticInputsOrNull != null && payload.staticResourcesOrNull != null) {
                executeStatic(queued, payload, colorTarget, depthTarget);
                continue;
            }
            if (payload.skinnedWorkOrNull != null) {
                executeSkinned(queued, payload.skinnedWorkOrNull, colorTarget, depthTarget, skinnedExecutor);
                continue;
            }
            // A typed skinned packet can only be manufactured by an exact T4p leaf match. Any absent or mismatched
            // packet is an explicit pre-command omission and fuses only its exact record.
            fuseAndCancelNoCommand(queued);
        }
    }

    /** Cancels no-command work for only the exact epoch just drained; submitted/fence work remains retained. */
    public void cancelNoCommandForAfterSolidEpoch() {
        X7AfterSolidFrameIdentity frame = lastDrainedFrame;
        if (frame == null) {
            return;
        }
        queue.cancelNoCommandForFrame(frame);
    }

    /** Reload/world/T1c cutoff: stop admission and cancel only children that have not recorded a command. */
    public void closeAdmissionAndCancelNoCommand() {
        if (admissionClosed) {
            return;
        }
        admissionClosed = true;
        queue.closeAdmissionAndCancelNoCommand();
    }

    /** Package-private focused-test observability; command-recorded fence receipts stay retained here. */
    int pendingStaticFenceCount() {
        return pendingStaticFenceCount;
    }

    StaticDirectFenceReceipt.State staticFenceStateForTest(int slot) {
        if (slot < 0 || slot >= pendingStaticFenceCount || pendingStaticFences[slot] == null) {
            throw new IllegalArgumentException("No retained static fence exists at the requested slot");
        }
        return pendingStaticFences[slot].state();
    }

    /** Zero-polls the fixed static owner from the synchronous render phase; terminal uncertainty stays retained. */
    public void pollRetainedStaticFences() {
        pollRetainedStaticFences(StaticDirectFenceReceipt::pollOnRenderThread);
    }

    /** Package-private focused-test seam for the same zero-timeout/removal policy without a live RenderSystem. */
    void pollRetainedStaticFencesForTest(StaticDirectFenceReceipt.RenderThreadAssertion renderThreadAssertion) {
        Objects.requireNonNull(renderThreadAssertion, "renderThreadAssertion");
        pollRetainedStaticFences(receipt -> receipt.poll(renderThreadAssertion));
    }

    /** Package-private focused-test observability. */
    int queuedRequestCount() {
        return queue.queuedRequestCount();
    }

    private void executeStatic(
            X7DeferredFrameQueue.QueuedSubmission queued,
            X7T3cQueuedPayload payload,
            GpuTextureView colorTarget,
            GpuTextureView depthTarget) {
        int receiptSlot = reserveStaticFenceSlot();
        if (receiptSlot < 0) {
            fuseAndCancelNoCommand(queued);
            return;
        }
        StaticDirectDrawExecutor.Result result;
        try {
            result = StaticDirectDrawExecutor.execute(
                    queued,
                    payload.staticInputsOrNull,
                    payload.staticResourcesOrNull,
                    new StaticDirectDrawExecutor.MinecraftAfterSolidScope(
                            queued.request().targetFrame(), colorTarget, depthTarget),
                    staticDriver);
        } catch (Error error) {
            discardStaticFenceSlot(receiptSlot);
            fuseExactRecord(queued);
            throw error;
        } catch (RuntimeException rejected) {
            discardStaticFenceSlot(receiptSlot);
            fuseAndCancelNoCommand(queued);
            return;
        }
        if (result.disposition() == StaticDirectDrawExecutor.Disposition.OMITTED_BEFORE_COMMAND) {
            discardStaticFenceSlot(receiptSlot);
            fuseExactRecord(queued);
            return;
        }
        if (result.disposition() != StaticDirectDrawExecutor.Disposition.COMMAND_RECORDED) {
            discardStaticFenceSlot(receiptSlot);
            return;
        }
        StaticDirectFenceReceipt receipt = result.receiptOrNull();
        if (receipt == null) {
            // The static executor has already retained the exact child terminally; disable only future GPU work.
            discardStaticFenceSlot(receiptSlot);
            fuseExactRecord(queued);
            return;
        }
        retainStaticFence(receiptSlot, receipt);
    }

    private void executeSkinned(
            X7DeferredFrameQueue.QueuedSubmission queued,
            QueuedSkinnedWork work,
            GpuTextureView colorTarget,
            GpuTextureView depthTarget,
            SkinnedExecutor skinnedExecutor) {
        try {
            SkinnedStageBDisposition disposition = Objects.requireNonNull(skinnedExecutor, "skinnedExecutor")
                    .execute(work, colorTarget, depthTarget);
            if (disposition == SkinnedStageBDisposition.OMITTED_BEFORE_COMMAND) {
                fuseAndCancelNoCommand(queued);
            }
        } catch (Error error) {
            if (!work.postCommandRetained()) {
                fuseAndCancelNoCommand(queued);
            }
            throw error;
        } catch (RuntimeException rejected) {
            if (!work.postCommandRetained()) {
                fuseAndCancelNoCommand(queued);
            }
            return;
        }
    }

    private void fuseAndCancelNoCommand(X7DeferredFrameQueue.QueuedSubmission queued) {
        fuseExactRecord(queued);
        queued.cancelNoCommand();
    }

    private static void fuseExactRecord(X7DeferredFrameQueue.QueuedSubmission queued) {
        X7PublishedSubmissionBridge.disableFutureGpu(
                Objects.requireNonNull(queued, "queued").request().exactPolicyRecord());
    }

    /** Reserves a no-allocation receipt slot before a command-recorded static child can leave the queue. */
    int reserveStaticFenceSlot() {
        if (pendingStaticFenceCount == pendingStaticFences.length) {
            return -1;
        }
        return pendingStaticFenceCount++;
    }

    private void discardStaticFenceSlot(int slot) {
        if (slot < 0 || slot >= pendingStaticFenceCount) {
            return;
        }
        int last = --pendingStaticFenceCount;
        pendingStaticFences[slot] = pendingStaticFences[last];
        pendingStaticFences[last] = null;
    }

    /** Completes a pre-command fixed reservation with the exact receipt that owns the moved child. */
    void retainStaticFence(int slot, StaticDirectFenceReceipt receipt) {
        if (slot < 0 || slot >= pendingStaticFenceCount || pendingStaticFences[slot] != null) {
            throw new IllegalStateException("Static fence receipt did not have an exact reserved slot");
        }
        pendingStaticFences[slot] = Objects.requireNonNull(receipt, "receipt");
    }

    private void pollRetainedStaticFences(StaticFencePoller poller) {
        StaticFencePoller checkedPoller = Objects.requireNonNull(poller, "poller");
        for (int index = 0; index < pendingStaticFenceCount; ) {
            StaticDirectFenceReceipt receipt = pendingStaticFences[index];
            StaticDirectFenceReceipt.PollResult result;
            try {
                result = checkedPoller.poll(receipt);
            } catch (Error error) {
                receipt.retainTerminalNonClose(error);
                throw error;
            } catch (RuntimeException failure) {
                receipt.retainTerminalNonClose(failure);
                index++;
                continue;
            }
            if (result == StaticDirectFenceReceipt.PollResult.COMPLETED) {
                discardStaticFenceSlot(index);
            } else {
                index++;
            }
        }
    }

    @FunctionalInterface
    private interface StaticFencePoller {
        StaticDirectFenceReceipt.PollResult poll(StaticDirectFenceReceipt receipt);
    }

    private static X7T3cQueuedPayload payloadOf(X7DeferredFrameQueue.QueuedSubmission queued) {
        Object identity = Objects.requireNonNull(queued, "queued").request().exactPlanIdentity();
        if (!(identity instanceof X7T3cQueuedPayload payload) || !payload.matchesRequest(queued.request())) {
            throw new IllegalStateException("T3c queue request lost its inline exact frozen payload");
        }
        return payload;
    }

    private static RenderPipeline registerT3bPipelineOnce() {
        try {
            RenderPipeline existing = RenderPipelines.getStaticPipelines().stream()
                    .filter(candidate -> StaticDirectPipeline.PIPELINE_ID.equals(candidate.getLocation()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                return existing;
            }
            RenderPipeline candidate = StaticDirectPipeline.buildCandidate();
            return RenderPipelines.register(candidate) == candidate ? candidate : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Private inline request payload; it is the actual exact-plan identity handed to the existing immutable queue. */
    private static final class X7T3cQueuedPayload implements X7DeferredFrameQueue.NoCommandPayload {
        private final X7T3cFrozenStageA frozen;
        private final StaticDirectFrameInputs staticInputsOrNull;
        private final X7SkinnedTexelProvenance skinnedProvenanceOrNull;
        private StaticDirectGenerationResources staticResourcesOrNull;
        private QueuedSkinnedWork skinnedWorkOrNull;
        private boolean sealed;
        private boolean releasedAfterNoCommand;

        private X7T3cQueuedPayload(
                X7T3cFrozenStageA frozen,
                StaticDirectFrameInputs staticInputsOrNull,
                X7SkinnedTexelProvenance skinnedProvenanceOrNull) {
            this.frozen = Objects.requireNonNull(frozen, "frozen");
            this.staticInputsOrNull = staticInputsOrNull;
            this.skinnedProvenanceOrNull = skinnedProvenanceOrNull;
            if ((staticInputsOrNull == null) == (skinnedProvenanceOrNull == null)) {
                throw new IllegalArgumentException("T3c queue payload requires exactly one typed backend route");
            }
        }

        private static X7T3cQueuedPayload staticPayload(
                X7T3cFrozenStageA frozen, StaticDirectFrameInputs staticInputs) {
            return new X7T3cQueuedPayload(
                    Objects.requireNonNull(frozen, "frozen"), Objects.requireNonNull(staticInputs, "staticInputs"), null);
        }

        private static X7T3cQueuedPayload skinnedPayload(
                X7T3cFrozenStageA frozen, X7SkinnedTexelProvenance skinnedProvenance) {
            return new X7T3cQueuedPayload(
                    Objects.requireNonNull(frozen, "frozen"), null, Objects.requireNonNull(skinnedProvenance, "skinnedProvenance"));
        }

        private boolean seal(X7DeferredFrameQueue.QueuedSubmission queued) {
            X7PreAdmittedFrameRequest request = Objects.requireNonNull(queued, "queued").request();
            return staticInputsOrNull != null ? sealStatic(request) : sealSkinned(queued, request);
        }

        private boolean sealStatic(X7PreAdmittedFrameRequest request) {
            if (sealed
                    || !matchesCommonRequest(request)
                    || !(request.exactLeaf() instanceof StaticDirectGenerationResources resources)) {
                return false;
            }
            if (resources.isClosed()
                    || resources.vertexCount() != staticInputsOrNull.vertexCount()
                    || resources.indexCount() != staticInputsOrNull.indexCount()) {
                return false;
            }
            staticResourcesOrNull = resources;
            sealed = true;
            return true;
        }

        private boolean sealSkinned(
                X7DeferredFrameQueue.QueuedSubmission queued, X7PreAdmittedFrameRequest request) {
            if (sealed
                    || !matchesCommonRequest(request)
                    || !(request.exactLeaf() instanceof SkinnedTexelGenerationResources resources)
                    || resources.isClosed()
                    || !resources.matchesCurrentFrame(skinnedProvenanceOrNull)
                    || resources.vertexCount() != skinnedProvenanceOrNull.vertexCount()
                    || resources.indexCount() != skinnedProvenanceOrNull.indexCount()
                    || !frozen.transferSkinnedProofToExactD1Leaf(skinnedProvenanceOrNull)) {
                return false;
            }
            skinnedWorkOrNull = new QueuedSkinnedWork(queued, frozen, resources, skinnedProvenanceOrNull);
            sealed = true;
            return true;
        }

        private boolean matchesCommonRequest(X7PreAdmittedFrameRequest request) {
            return request.exactPlanIdentity() == this
                    && request.exactSnapshot() == frozen.exactSnapshot()
                    && request.exactDraw() == frozen.exactDraw()
                    && !request.exactPolicyRecord().isTerminallyCleared()
                    && request.exactPublished().backend() == X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE;
        }

        private boolean matchesRequest(X7PreAdmittedFrameRequest request) {
            return sealed
                    && request.exactPlanIdentity() == this
                    && request.exactSnapshot() == frozen.exactSnapshot()
                    && request.exactDraw() == frozen.exactDraw();
        }

        @Override
        public synchronized void releaseAfterNoCommandCancellation() {
            if (releasedAfterNoCommand) {
                return;
            }
            releasedAfterNoCommand = true;
            frozen.close();
        }
    }

    /** Reload-private exact queue/leaf adapter for the root T4 pass contract. */
    private static final class QueuedSkinnedWork implements SkinnedWork {
        private final X7DeferredFrameQueue.QueuedSubmission queued;
        private final X7T3cFrozenStageA frozen;
        private final SkinnedTexelGenerationResources resources;
        private final X7SkinnedTexelProvenance provenance;
        private final AuthorityToken authority = new AuthorityToken();
        private X7DeferredFrameQueue.CompletionHandoff completionHandoff;
        private boolean postCommandRetained;

        private QueuedSkinnedWork(
                X7DeferredFrameQueue.QueuedSubmission queued,
                X7T3cFrozenStageA frozen,
                SkinnedTexelGenerationResources resources,
                X7SkinnedTexelProvenance provenance) {
            this.queued = Objects.requireNonNull(queued, "queued");
            this.frozen = Objects.requireNonNull(frozen, "frozen");
            this.resources = Objects.requireNonNull(resources, "resources");
            this.provenance = Objects.requireNonNull(provenance, "provenance");
        }

        @Override
        public FrozenSkinnedInputs inputs() {
            return new FrozenSkinnedInputs(
                    frozen.modelViewCopy(),
                    frozen.normalCopy(),
                    frozen.composedArgb(),
                    frozen.packedLight(),
                    frozen.packedOverlay(),
                    provenance.paletteUpload());
        }

        @Override
        public AuthorityToken authority() {
            return authority;
        }

        @Override
        public int vertexCount() {
            return resources.vertexCount();
        }

        @Override
        public int indexCount() {
            return resources.indexCount();
        }

        @Override
        public Identifier textureId() {
            return Identifier.fromNamespaceAndPath(
                    frozen.exactDraw().material().textureId().namespace(),
                    frozen.exactDraw().material().textureId().path());
        }

        @Override
        public void bindTo(RenderPass pass) {
            resources.bindTo(pass);
        }

        @Override
        public synchronized void recordAllBeforeNativeDraw(Runnable movedChild) {
            if (completionHandoff != null) {
                throw new IllegalStateException("The exact T4 queued child was moved more than once");
            }
            X7DeferredFrameQueue.CompletionHandoff handoff =
                    new X7DeferredFrameQueue.CompletionHandoff(Objects.requireNonNull(movedChild, "movedChild"));
            // Store the holder before the queue's ownership-moving call. If its post-move Error boundary fires, the
            // exact indexed completion receipt and T4 transfer state are already reachable through this holder.
            completionHandoff = handoff;
            queued.recordCommand(handoff);
        }

        @Override
        public synchronized void cancelNoCommand() {
            X7DeferredSubmissionCompletionReceipt completion = completionOrNull();
            if (completion == null) {
                queued.cancelNoCommand();
                return;
            }
            completion.completeAfterVerifiedFence();
            frozen.close();
        }

        @Override
        public CompletionHooks completionHooks() {
            return new CompletionHooks() {
                @Override
                public void closeNoCommand() {
                    QueuedSkinnedWork.this.cancelNoCommand();
                }

                @Override
                public void completeAfterVerifiedFence() {
                    QueuedSkinnedWork.this.completeAfterVerifiedFence();
                }

                @Override
                public void retainTerminalNonClose(Throwable failure) {
                    QueuedSkinnedWork.this.retainTerminalNonClose(failure);
                }
            };
        }

        private synchronized void completeAfterVerifiedFence() {
            X7DeferredSubmissionCompletionReceipt completion = completionOrNull();
            if (completion != null) {
                completion.completeAfterVerifiedFence();
                frozen.close();
            }
        }

        private synchronized void retainTerminalNonClose(Throwable failure) {
            X7DeferredSubmissionCompletionReceipt completion = completionOrNull();
            if (completion != null) {
                postCommandRetained = true;
                completion.retainTerminalNonClose(failure);
            }
        }

        private synchronized boolean postCommandRetained() {
            return postCommandRetained;
        }

        private X7DeferredSubmissionCompletionReceipt completionOrNull() {
            return completionHandoff == null ? null : completionHandoff.receiptOrNull();
        }
    }
}
