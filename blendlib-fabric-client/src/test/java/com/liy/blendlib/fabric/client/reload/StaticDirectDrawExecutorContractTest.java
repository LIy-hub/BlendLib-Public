package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderLayer;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.StaticGeometry;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/** Focused T3b contract proof for exact D1 leaf/queue ownership and after-solid-only direct execution. */
class StaticDirectDrawExecutorContractTest {
    private static final long RENDER_THREAD_ID = 7744L;

    @Test
    void exactD1LeafAndQueuedChildRemainRetainedUntilTheCandidateFenceCompletes() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        X7DeferredFrameQueue.QueuedSubmission queued = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();

        StaticDirectFrameInputs inputs = inputs(fixture, new Matrix4f(), new Matrix3f());
        RecordingDriver driver = new RecordingDriver();
        StaticDirectDrawExecutor.Result result = StaticDirectDrawExecutor.execute(
                queued, inputs, fixture.resources, () -> queued.request().targetFrame(), driver);

        assertEquals(StaticDirectDrawExecutor.Disposition.COMMAND_RECORDED, result.disposition());
        assertNull(result.failureOrNull());
        assertNotNull(result.receiptOrNull());
        assertTrue(driver.called);
        assertSame(inputs, driver.batch.entries().getFirst().inputs());
        assertSame(fixture.resources, driver.batch.exactResources());
        assertEquals(0x00B000A0, inputs.packedLight());
        assertEquals(OverlayTexture.NO_OVERLAY, inputs.packedOverlay());
        assertEquals(1, queue.retainedCompletionCount());

        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertFalse(fixture.resources.isClosed());
        assertEquals(0, fixture.buffers.closeCalls);

        assertEquals(StaticDirectFenceReceipt.PollResult.COMPLETED, result.receiptOrNull().poll(() -> { }));
        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.buffers.closeCalls);
        assertEquals(0, queue.retainedCompletionCount());
    }

    @Test
    void wrongAfterSolidFrameIsAPreCommandOmissionAndLeavesNoCompletionReceipt() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        X7DeferredFrameQueue.QueuedSubmission queued = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();
        StaticDirectFrameInputs inputs = inputs(fixture, new Matrix4f(), new Matrix3f());
        RecordingDriver driver = new RecordingDriver();
        X7AfterSolidFrameIdentity target = queued.request().targetFrame();
        X7AfterSolidFrameIdentity wrongFrame = new X7AfterSolidFrameIdentity(
                target.afterSolidEpoch() + 1L, target.renderThreadId(), target.phase());

        StaticDirectDrawExecutor.Result result = StaticDirectDrawExecutor.execute(
                queued, inputs, fixture.resources, () -> wrongFrame, driver);
        assertEquals(StaticDirectDrawExecutor.Disposition.OMITTED_BEFORE_COMMAND, result.disposition());
        assertFalse(driver.called);
        assertEquals(0, queue.retainedCompletionCount());

        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.buffers.closeCalls);
    }

    @Test
    void immutableCallerPoseNormalAndPackedLightAreCopiedIntoTheStd140InstanceUpload() {
        Fixture fixture = Fixture.create();
        Matrix4f callerModelView = new Matrix4f().translation(7.0F, 11.0F, 13.0F).scale(2.0F, 3.0F, 4.0F);
        Matrix3f callerNormal = new Matrix3f(callerModelView).invert().transpose();
        StaticDirectFrameInputs inputs = inputs(fixture, callerModelView, callerNormal);

        callerModelView.identity();
        callerNormal.identity();
        assertEquals(2.0F, inputs.modelViewCopy().m00());
        assertEquals(3.0F, inputs.modelViewCopy().m11());
        assertEquals(4.0F, inputs.modelViewCopy().m22());
        assertEquals(0.5F, inputs.normalTransformCopy().m00());
        assertEquals(1.0F / 3.0F, inputs.normalTransformCopy().m11());
        assertEquals(0.25F, inputs.normalTransformCopy().m22());

        StaticDirectInstanceUpload upload = StaticDirectInstanceUpload.pack(List.of(inputs));
        assertEquals(StaticDirectInstanceUpload.INSTANCE_STRIDE_BYTES, upload.byteSize());
        ByteBuffer bytes = upload.bytesForUpload();
        assertEquals(2.0F, bytes.getFloat(0));
        assertEquals(0.5F, bytes.getFloat(StaticDirectInstanceUpload.MODEL_VIEW_BYTES));
        assertEquals(1.0F / 3.0F, bytes.getFloat(StaticDirectInstanceUpload.MODEL_VIEW_BYTES + 16 + Float.BYTES));
        assertEquals(0.25F, bytes.getFloat(StaticDirectInstanceUpload.MODEL_VIEW_BYTES + 32 + 2 * Float.BYTES));
        assertEquals(160.0F, bytes.getFloat(128));
        assertEquals(176.0F, bytes.getFloat(132));
        assertEquals(OverlayTexture.NO_OVERLAY, inputs.packedOverlay());
    }

    @Test
    void boundedBatchGroupsOnlyTheSameD1ModelTextureAndUploadsTwoInstances() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt firstParent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.PlanTransferReceipt secondParent = fixture.openPlanReceipt();
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw,
                firstParent.beginDeferredSubmission(fixture.snapshot, fixture.draw)).accepted());
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw,
                secondParent.beginDeferredSubmission(fixture.snapshot, fixture.draw)).accepted());
        List<X7DeferredFrameQueue.QueuedSubmission> queued = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID);

        StaticDirectFrameInputs firstInputs = inputs(fixture, new Matrix4f(), new Matrix3f());
        StaticDirectFrameInputs secondInputs = inputs(
                fixture,
                new Matrix4f().translation(2.0F, 0.0F, 0.0F),
                new Matrix3f());
        StaticDirectBatch.Builder builder = StaticDirectBatch.builder(queued.get(0), firstInputs, fixture.resources);
        assertTrue(builder.tryAdd(queued.get(1), secondInputs, fixture.resources));
        StaticDirectBatch batch = builder.build();
        assertEquals(2, batch.instanceCount());
        assertSame(fixture.resources, batch.exactResources());
        assertEquals(fixture.draw.material().textureId(), batch.exactTextureId());
        assertEquals(2 * StaticDirectInstanceUpload.INSTANCE_STRIDE_BYTES, batch.instanceUpload().byteSize());

        Fixture otherTexture = Fixture.create("static_direct_contract:textures/other");
        StaticDirectFrameInputs otherTextureInputs = inputs(otherTexture, new Matrix4f(), new Matrix3f());
        StaticDirectBatch.Builder textureBound = StaticDirectBatch.builder(queued.get(0), firstInputs, fixture.resources);
        assertFalse(textureBound.tryAdd(queued.get(1), otherTextureInputs, fixture.resources));
        assertFalse(textureBound.tryAdd(queued.get(0), firstInputs, fixture.resources));

        int[] draw = {-1, -1, -1, -1};
        StaticDirectDrawExecutor.issueIndexedDraw(
                (baseVertex, firstIndex, indexCount, instanceCount) -> {
                    draw[0] = baseVertex;
                    draw[1] = firstIndex;
                    draw[2] = indexCount;
                    draw[3] = instanceCount;
                },
                batch.exactResources(),
                batch.instanceCount());
        assertEquals(List.of(0, 0, 3, 2), List.of(draw[0], draw[1], draw[2], draw[3]));

        queue.cancelNoCommandForFrame(batch.exactTargetFrame());
        firstParent.close();
        secondParent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertTrue(fixture.resources.isClosed());
    }

    @Test
    void falseFencePollRetainsTheSubmittedChildUntilALaterPositivePoll() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw,
                parent.beginDeferredSubmission(fixture.snapshot, fixture.draw)).accepted());
        X7DeferredFrameQueue.QueuedSubmission queued = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();
        MutableFence fence = new MutableFence();
        StaticDirectDrawExecutor.Result result = StaticDirectDrawExecutor.execute(
                queued,
                inputs(fixture, new Matrix4f(), new Matrix3f()),
                fixture.resources,
                () -> queued.request().targetFrame(),
                new FenceDriver(fence));

        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertEquals(StaticDirectFenceReceipt.PollResult.PENDING, result.receiptOrNull().poll(() -> { }));
        assertEquals(StaticDirectFenceReceipt.State.PENDING, result.receiptOrNull().state());
        assertFalse(fixture.resources.isClosed());
        assertEquals(1, queue.retainedCompletionCount());

        fence.complete = true;
        assertEquals(StaticDirectFenceReceipt.PollResult.COMPLETED, result.receiptOrNull().poll(() -> { }));
        assertTrue(fixture.resources.isClosed());
        assertEquals(0, queue.retainedCompletionCount());
    }

    @Test
    void throwingFencePollBecomesTerminalNonCloseAndRetainsTheSubmittedChild() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw,
                parent.beginDeferredSubmission(fixture.snapshot, fixture.draw)).accepted());
        X7DeferredFrameQueue.QueuedSubmission queued = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();
        MutableFence fence = new MutableFence();
        fence.failure = new IllegalStateException("fence failed");
        StaticDirectDrawExecutor.Result result = StaticDirectDrawExecutor.execute(
                queued,
                inputs(fixture, new Matrix4f(), new Matrix3f()),
                fixture.resources,
                () -> queued.request().targetFrame(),
                new FenceDriver(fence));

        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertEquals(StaticDirectFenceReceipt.PollResult.TERMINAL_NON_CLOSE, result.receiptOrNull().poll(() -> { }));
        assertEquals(StaticDirectFenceReceipt.State.TERMINAL_NON_CLOSE, result.receiptOrNull().state());
        assertSame(fence.failure, result.receiptOrNull().terminalFailureOrNull());
        assertFalse(fixture.resources.isClosed());
        assertEquals(1, queue.retainedCompletionCount());
    }

    @Test
    void firstBatchCompletionPreallocationFailureLeavesEveryChildUndrawnAndUntransferred() {
        assertAtomicBatchPreallocationFailureLeavesNoPartialTransfer(1);
    }

    @Test
    void middleBatchCompletionPreallocationFailureLeavesEveryChildUndrawnAndUntransferred() {
        assertAtomicBatchPreallocationFailureLeavesNoPartialTransfer(2);
    }

    private static void assertAtomicBatchPreallocationFailureLeavesNoPartialTransfer(int failingReceiptOccurrence) {
        Fixture fixture = Fixture.create();
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        List<ClientGenerationLeaseBinding.PlanTransferReceipt> parents = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
            parents.add(parent);
            assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw,
                    parent.beginDeferredSubmission(fixture.snapshot, fixture.draw)).accepted());
        }
        List<X7DeferredFrameQueue.QueuedSubmission> queued = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID);
        StaticDirectFrameInputs inputs = inputs(fixture, new Matrix4f(), new Matrix3f());
        StaticDirectBatch.Builder builder = StaticDirectBatch.builder(queued.getFirst(), inputs, fixture.resources);
        assertTrue(builder.tryAdd(queued.get(1), inputs, fixture.resources));
        assertTrue(builder.tryAdd(queued.get(2), inputs, fixture.resources));
        StaticDirectBatch batch = builder.build();
        PreDrawProbeDriver driver = new PreDrawProbeDriver();
        AssertionError allocationFailure = new AssertionError("completion allocation " + failingReceiptOccurrence);

        X7DeferredSubmissionBridge.failAllocationAtForTest(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_RECEIPT,
                failingReceiptOccurrence,
                allocationFailure);
        try {
            assertSame(allocationFailure, assertThrows(AssertionError.class,
                    () -> StaticDirectDrawExecutor.executeBatch(
                            batch, () -> batch.exactTargetFrame(), driver)));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        assertTrue(driver.called);
        assertFalse(driver.nativeDrawAttempted);
        assertEquals(0, queue.retainedCompletionCount());
        assertEquals(0, queue.queuedRequestCount());
        assertEquals(0, queue.inFlightSubmissionCount());
        for (ClientGenerationLeaseBinding.PlanTransferReceipt parent : parents) {
            parent.close();
        }
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.buffers.closeCalls);
    }

    private static final class RecordingDriver implements StaticDirectDrawExecutor.Driver {
        private boolean called;
        private StaticDirectBatch batch;

        @Override
        public StaticDirectFenceReceipt record(
                StaticDirectBatch batch,
                StaticDirectDrawExecutor.AfterSolidScope scope,
                StaticDirectDrawExecutor.CommandsRecorded commandsRecorded) {
            called = true;
            this.batch = batch;
            List<X7DeferredSubmissionCompletionReceipt> completions = commandsRecorded.recordAllBeforeNativeDraw();
            return StaticDirectFenceReceipt.pending(new StaticDirectFenceReceipt.Fence() {
                @Override
                public boolean awaitCompletionZero() {
                    return true;
                }

                @Override
                public void close() {
                }
            }, () -> { }, completions);
        }
    }

    private static final class FenceDriver implements StaticDirectDrawExecutor.Driver {
        private final MutableFence fence;

        private FenceDriver(MutableFence fence) {
            this.fence = fence;
        }

        @Override
        public StaticDirectFenceReceipt record(
                StaticDirectBatch batch,
                StaticDirectDrawExecutor.AfterSolidScope scope,
                StaticDirectDrawExecutor.CommandsRecorded commandsRecorded) {
            List<X7DeferredSubmissionCompletionReceipt> completions = commandsRecorded.recordAllBeforeNativeDraw();
            return StaticDirectFenceReceipt.pending(fence, () -> { }, completions);
        }
    }

    private static final class PreDrawProbeDriver implements StaticDirectDrawExecutor.Driver {
        private boolean called;
        private boolean nativeDrawAttempted;

        @Override
        public StaticDirectFenceReceipt record(
                StaticDirectBatch batch,
                StaticDirectDrawExecutor.AfterSolidScope scope,
                StaticDirectDrawExecutor.CommandsRecorded commandsRecorded) {
            called = true;
            commandsRecorded.recordAllBeforeNativeDraw();
            nativeDrawAttempted = true;
            throw new AssertionError("probe must not reach native draw after a preallocation failure");
        }
    }

    private static final class MutableFence implements StaticDirectFenceReceipt.Fence {
        private boolean complete;
        private RuntimeException failure;

        @Override
        public boolean awaitCompletionZero() {
            if (failure != null) {
                throw failure;
            }
            return complete;
        }

        @Override
        public void close() {
        }
    }

    private static StaticDirectFrameInputs inputs(Fixture fixture, Matrix4f modelView, Matrix3f normalTransform) {
        return StaticDirectFrameInputs.create(
                fixture.snapshot,
                fixture.draw,
                StaticDirectOpaqueMarker.forExact(fixture.snapshot, fixture.draw),
                StaticDirectPoseSnapshot.freeze(modelView, normalTransform));
    }

    private static final class Fixture {
        private final BlendModelKey key;
        private final ClientModelRegistry registry;
        private final TestRenderHandle handle;
        private final ModelRenderSnapshot snapshot;
        private final X6DrawPrimitive draw;
        private final StaticDirectGenerationResources resources;
        private final FakeBuffers buffers;

        private Fixture(
                BlendModelKey key,
                ClientModelRegistry registry,
                TestRenderHandle handle,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                StaticDirectGenerationResources resources,
                FakeBuffers buffers) {
            this.key = key;
            this.registry = registry;
            this.handle = handle;
            this.snapshot = snapshot;
            this.draw = draw;
            this.resources = resources;
            this.buffers = buffers;
        }

        private static Fixture create() {
            return create("static_direct_contract:textures/exact");
        }

        private static Fixture create(String textureId) {
            BlendModelKey key = BlendModelKey.parse("static_direct_contract:models/exact");
            StaticGeometry geometry = StaticGeometry.of(
                    new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new int[] {0, 1, 2});
            RenderMaterial material = new RenderMaterial(
                    BlendResourceId.parse(textureId),
                    RenderLayer.SOLID,
                    false,
                    false,
                    0xFFFFFFFF,
                    false);
            PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(0, geometry, material);
            TestRenderHandle handle = new TestRenderHandle(key, 1L, primitive);
            ModelRegistryGeneration generation = new ModelRegistryGeneration(
                    1L,
                    Map.of(key, new LoadedModelHandle(key, asset(key, handle), handle)),
                    Map.of(),
                    List.of());
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory =
                    X7GenerationResourceBridge.authoritativeInventory(generation);
            List<X7GenerationPerformancePlan.Prepared> preparedPlans = inventory.keysInDeterministicOrder().stream()
                    .map(inventory::preparationBindingFor)
                    .map(binding -> X7GenerationPerformancePlan.prepare(
                            binding, X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()))
                    .toList();
            X7GenerationResourceBridge.FrozenSelection selection = X7GenerationResourceBridge
                    .freezeCanonicalSelections(inventory, preparedPlans)
                    .selectionsInDeterministicOrder()
                    .getFirst();
            FakeBuffers buffers = new FakeBuffers();
            StaticDirectGenerationResources resources = new StaticDirectGenerationResources(
                    selection.key(), 3, 3, 3 * 32, 3 * Integer.BYTES, buffers);
            CompletedGenerationResourceSet aggregate = CompletedGenerationResourceSet.complete(generation, List.of(resources));
            ClientModelRegistry registry = new ClientModelRegistry(
                    ModelRegistryGeneration.empty(0L), new ImmediateRenderOwner());
            registry.publish(PendingGenerationTransaction.complete(generation, aggregate));
            ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                    handle,
                    Transform.IDENTITY,
                    0x00B000A0,
                    OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF,
                    RenderVisibility.VISIBLE,
                    new CullingMetadata(handle.bounds(), true));
            X6DrawPrimitive draw = new X6DrawPrimitive(
                    BlendResourceId.parse("static_direct_contract:draw/exact"), primitive, material, 0xFFFFFFFF);
            return new Fixture(key, registry, handle, snapshot, draw, resources, buffers);
        }

        private ClientGenerationLeaseBinding.PlanTransferReceipt openPlanReceipt() {
            ClientModelLookup lookup = ClientModelLookupTestSupport.sourceOwnedLookup(registry);
            ClientGenerationLeaseBinding binding = lookup.acquireGenerationLeaseBinding(key, snapshot);
            assertTrue(binding.managed());
            return binding.transferToPlan();
        }
    }

    private static ModelAsset asset(BlendModelKey key, TestRenderHandle handle) {
        return new ModelAsset(
                key.resourceId(),
                key.descriptorResourceId(),
                1L,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                new SocketTable(Map.of()),
                handle.bounds(),
                List.of());
    }

    private record TestRenderHandle(BlendModelKey modelKey, long generation, PreparedRenderPrimitive primitive)
            implements ModelRenderHandle {
        private static final Bounds BOUNDS = new Bounds(Vec3.ZERO, Vec3.ZERO);

        @Override
        public Bounds bounds() {
            return BOUNDS;
        }

        @Override
        public float unitsToBlocksScale() {
            return 1.0F;
        }

        @Override
        public List<PreparedRenderPrimitive> primitives() {
            return List.of(primitive);
        }

        @Override
        public Transform nodeTransform(int nodeIndex) {
            if (nodeIndex != 0) {
                throw new IndexOutOfBoundsException(nodeIndex);
            }
            return Transform.IDENTITY;
        }

        @Override
        public boolean missingModel() {
            return false;
        }
    }

    private static final class FakeBuffers implements StaticDirectGenerationResources.BufferBindings {
        private int closeCalls;

        @Override
        public void bind(RenderPass pass) {
            // The focused contract uses an injected driver; native pass binding is covered by the production adapter.
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class ImmediateRenderOwner implements ClientGenerationResourceOwner.RenderOwnerCallbacks {
        @Override
        public void handoffToRenderThread(Runnable handoff) {
            handoff.run();
        }

        @Override
        public void assertOnRenderThread() {
        }

        @Override
        public void queueFencedTask(Runnable callback) {
            callback.run();
        }
    }
}
