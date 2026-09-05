package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Focused T2a3 evidence for D1 child retention, exact policy lookup, and bounded deferred ownership. */
class ClientGenerationResourceOwnerSubmittedLeaseTest {
    private static final long RENDER_THREAD_ID = 7331L;

    @Test
    void submittedChildKeepsTheExactD1RecordAndPolicyMapAliveUntilVerifiedRelease() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);

        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));

        assertFalse(fixture.resources.isClosed(), "a retired D1 aggregate must remain live while its submitted child exists");
        assertEquals(0, fixture.vertex.closeCalls);
        assertEquals(0, fixture.index.closeCalls);

        child.close();
        child.close();

        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.vertex.closeCalls, "the child releases the shared D1 count exactly once");
        assertEquals(1, fixture.index.closeCalls, "the child releases the shared D1 count exactly once");
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, fixture.aggregate.ownership());
    }

    @Test
    void queueBackpressureAndNoCommandCancellationReleaseOnlyTransferredChildren() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt first = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt rejected = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID, 1);

        X7DeferredFrameQueue.Admission admitted = queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, first);
        X7DeferredFrameQueue.Admission full = queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, rejected);
        assertTrue(admitted.accepted());
        assertFalse(full.accepted());
        assertEquals(X7DeferredFrameQueue.Rejection.CAPACITY, full.rejection());

        // Capacity rejection is pre-transfer: CPU fallback owns and closes this child itself.
        rejected.close();
        List<X7DeferredFrameQueue.QueuedSubmission> drained = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID);
        assertEquals(1, drained.size());
        assertEquals(1L, drained.getFirst().request().targetFrame().afterSolidEpoch());
        X7DeferredSubmissionCompletionReceipt completion = drained.getFirst().recordCommand();

        // Queue shutdown cancels no-command work only; command-recorded work remains held until a verified fence.
        queue.close();
        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertFalse(fixture.resources.isClosed());
        assertEquals(1, queue.retainedCompletionCount());

        completion.retainTerminalNonClose(new IllegalStateException("fence uncertain"));
        assertFalse(fixture.resources.isClosed());
        completion.completeAfterVerifiedFence();
        completion.completeAfterVerifiedFence();

        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.vertex.closeCalls);
        assertEquals(1, fixture.index.closeCalls);
        assertEquals(0, queue.retainedCompletionCount());
    }

    @Test
    void shutdownCancellationReleasesAnUnrecordedQueueChildExactlyOnce() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);

        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        queue.closeAdmissionAndCancelNoCommand();
        queue.closeAdmissionAndCancelNoCommand();
        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));

        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.vertex.closeCalls);
        assertEquals(1, fixture.index.closeCalls);
    }

    @Test
    void policyIdentityMismatchAndClosedParentCannotMintOrResurrectAChild() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        RenderMaterial equalButForeignMaterial = new RenderMaterial(
                fixture.primitive.material().textureId(),
                fixture.primitive.material().layer(),
                fixture.primitive.material().emissive(),
                fixture.primitive.material().doubleSided(),
                fixture.primitive.material().argbTint(),
                fixture.primitive.material().missingModelMaterial());
        X6DrawPrimitive foreignDraw = new X6DrawPrimitive(
                BlendResourceId.parse("test:foreign"), fixture.primitive, equalButForeignMaterial, 0xFFFFFFFF);

        assertThrows(IllegalStateException.class, () -> parent.beginDeferredSubmission(fixture.snapshot, foreignDraw));
        parent.close();
        assertThrows(IllegalStateException.class, () -> parent.beginDeferredSubmission(fixture.snapshot, fixture.draw));

        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertTrue(fixture.resources.isClosed(), "a failed/closed parent cannot manufacture a late attachment or child");
        assertEquals(1, fixture.vertex.closeCalls);
        assertEquals(1, fixture.index.closeCalls);
    }

    @Test
    void allocationFailureAtEverySubmittedOwnershipBoundaryLeavesOneCloseableD1Path() {
        assertMintFailureReleasesWithoutAChild(X7DeferredSubmissionBridge.AllocationBoundary.OWNER_CHILD_LEASE);
        assertMintFailureReleasesWithoutAChild(X7DeferredSubmissionBridge.AllocationBoundary.OWNER_SUBMITTED_LEASE);
        assertMintFailureReleasesWithoutAChild(X7DeferredSubmissionBridge.AllocationBoundary.BRIDGE_SUBMISSION_CHILD);
        assertMintFailureReleasesWithoutAChild(X7DeferredSubmissionBridge.AllocationBoundary.PUBLIC_RECEIPT);

        assertQueueAdmissionFailureReleasesTheTransferredChild(X7DeferredSubmissionBridge.AllocationBoundary.QUEUE_CHILD);
        assertQueueAdmissionFailureReleasesTheTransferredChild(
                X7DeferredSubmissionBridge.AllocationBoundary.QUEUED_SUBMISSION);
        assertQueueAdmissionFailureReleasesTheTransferredChild(X7DeferredSubmissionBridge.AllocationBoundary.QUEUE_APPEND);

        assertDrainIndexFailureKeepsTheQueueOwner(X7DeferredSubmissionBridge.AllocationBoundary.DRAINED_INDEX);
        assertCompletionFailureKeepsTheDrainedOwner(X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_RECEIPT);
        assertCompletionFailureKeepsTheDrainedOwner(X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_INDEX);
    }

    @Test
    void nonCachedEpochPostDrainFaultRetainsTheExactChildInItsCompletionReceipt() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        for (long epoch = 1L; epoch < 129L; epoch++) {
            assertTrue(queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).isEmpty());
        }
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        X7DeferredFrameQueue.QueuedSubmission drained = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();
        assertEquals(129L, drained.request().targetFrame().afterSolidEpoch());
        InjectedAllocationError failure = new InjectedAllocationError(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE);

        X7DeferredSubmissionBridge.failAllocationAtForTest(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE, failure);
        try {
            assertSame(failure, assertThrows(InjectedAllocationError.class, drained::recordCommand));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        assertEquals(0, queue.queuedRequestCount(), "the drained entry was consumed exactly once");
        assertEquals(1, queue.retainedCompletionCount(), "the indexed receipt retains the exact child after the fault");
        assertThrows(IllegalStateException.class, drained::recordCommand, "a detached request cannot resurrect its child");
        queue.close();
        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertFalse(fixture.resources.isClosed(), "ordinary shutdown must retain command-recorded work after the fault");

        onlyRetainedCompletion(queue).completeAfterVerifiedFence();

        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.vertex.closeCalls, "the retained child decrements D1 exactly once");
        assertEquals(1, fixture.index.closeCalls, "the retained child decrements D1 exactly once");
        assertEquals(0, queue.retainedCompletionCount());
    }

    @Test
    void postMoveFaultPublishesTheExactIndexedReceiptAndT4TransferBeforeItCanEscape() {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        X7DeferredFrameQueue.QueuedSubmission drained = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();
        T4CompletionTransferProbe t4Transfer = T4CompletionTransferProbe.create();
        X7DeferredFrameQueue.CompletionHandoff handoff = new X7DeferredFrameQueue.CompletionHandoff(
                t4Transfer::acceptMovedChild);
        InjectedAllocationError failure = new InjectedAllocationError(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE);

        X7DeferredSubmissionBridge.failAllocationAtForTest(
                X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE, failure);
        try {
            assertSame(failure, assertThrows(InjectedAllocationError.class, () -> drained.recordCommand(handoff)));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        X7DeferredSubmissionCompletionReceipt completion = handoff.receiptOrNull();
        assertNotNull(completion, "the preallocated holder must expose the exact indexed receipt before the post-move fault");
        assertTrue(t4Transfer.isMoved(),
                "the real T4 CompletionTransfer must observe the exact child before the queue post-move fault escapes");
        assertEquals(X7DeferredSubmissionCompletionReceipt.State.COMMAND_RECORDED, completion.state());
        assertEquals(1, queue.retainedCompletionCount());
        assertThrows(IllegalStateException.class, () -> drained.recordCommand(handoff));

        t4Transfer.installExactQueueReceipt(completion);
        t4Transfer.closeNoCommandExactlyOnce(failure);
        t4Transfer.closeNoCommandExactlyOnce(failure);
        assertEquals(1, t4Transfer.closeNoCommandCalls(),
                "the actual T4 pre-command terminal callback must claim the moved child exactly once");
        assertEquals(X7DeferredSubmissionCompletionReceipt.State.COMPLETED, completion.state());
        assertEquals(0, queue.retainedCompletionCount(), "the one reachable holder must remove the receipt exactly once");
        queue.close();
        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertTrue(fixture.resources.isClosed());
        assertEquals(1, fixture.vertex.closeCalls);
        assertEquals(1, fixture.index.closeCalls);
    }

    /** Reflects only the package-private T4 transfer slot so the queue test executes its actual production state move. */
    private static final class T4CompletionTransferProbe {
        private static final String T4_OWNER = "com.liy.blendlib.fabric.client.X7Minecraft2612SkinnedPassSubmitter";

        private final Object transfer;
        private final Method acceptMovedChild;
        private final Method isMoved;
        private final Method closeNoCommandExactlyOnce;
        private final AtomicReference<X7DeferredSubmissionCompletionReceipt> exactQueueReceipt;
        private final AtomicInteger closeNoCommandCalls;

        private T4CompletionTransferProbe(
                Object transfer,
                Method acceptMovedChild,
                Method isMoved,
                Method closeNoCommandExactlyOnce,
                AtomicReference<X7DeferredSubmissionCompletionReceipt> exactQueueReceipt,
                AtomicInteger closeNoCommandCalls) {
            this.transfer = transfer;
            this.acceptMovedChild = acceptMovedChild;
            this.isMoved = isMoved;
            this.closeNoCommandExactlyOnce = closeNoCommandExactlyOnce;
            this.exactQueueReceipt = exactQueueReceipt;
            this.closeNoCommandCalls = closeNoCommandCalls;
        }

        private static T4CompletionTransferProbe create() {
            try {
                Class<?> owner = Class.forName(T4_OWNER);
                Class<?> receiptType = Class.forName(T4_OWNER + "$CompletionReceipt");
                Class<?> transferType = Class.forName(T4_OWNER + "$CompletionTransfer");
                AtomicReference<X7DeferredSubmissionCompletionReceipt> exactQueueReceipt = new AtomicReference<>();
                AtomicInteger closeNoCommandCalls = new AtomicInteger();
                Object receipt = Proxy.newProxyInstance(
                        receiptType.getClassLoader(),
                        new Class<?>[] {receiptType},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("closeNoCommand")) {
                                closeNoCommandCalls.incrementAndGet();
                                X7DeferredSubmissionCompletionReceipt completion = exactQueueReceipt.get();
                                if (completion == null) {
                                    throw new AssertionError("T4 close callback ran before the exact queue receipt was published");
                                }
                                completion.completeAfterVerifiedFence();
                            }
                            return null;
                        });
                java.lang.reflect.Constructor<?> constructor = transferType.getDeclaredConstructor(receiptType);
                constructor.setAccessible(true);
                Method acceptMovedChild = transferType.getDeclaredMethod("acceptMovedChild");
                Method isMoved = transferType.getDeclaredMethod("isMoved");
                Method closeNoCommandExactlyOnce = transferType.getDeclaredMethod("closeNoCommandExactlyOnce", Throwable.class);
                acceptMovedChild.setAccessible(true);
                isMoved.setAccessible(true);
                closeNoCommandExactlyOnce.setAccessible(true);
                return new T4CompletionTransferProbe(
                        constructor.newInstance(receipt),
                        acceptMovedChild,
                        isMoved,
                        closeNoCommandExactlyOnce,
                        exactQueueReceipt,
                        closeNoCommandCalls);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("focused H3 test cannot construct the T4 preallocated transfer slot", failure);
            }
        }

        private void acceptMovedChild() {
            invoke(acceptMovedChild);
        }

        private boolean isMoved() {
            return (boolean) invoke(isMoved);
        }

        private void installExactQueueReceipt(X7DeferredSubmissionCompletionReceipt completion) {
            exactQueueReceipt.set(completion);
        }

        private void closeNoCommandExactlyOnce(Throwable failure) {
            invoke(closeNoCommandExactlyOnce, failure);
        }

        private int closeNoCommandCalls() {
            return closeNoCommandCalls.get();
        }

        private Object invoke(Method method, Object... arguments) {
            try {
                return method.invoke(transfer, arguments);
            } catch (IllegalAccessException | InvocationTargetException failure) {
                Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                        ? invocation.getCause()
                        : failure;
                throw new AssertionError("focused H3 test could not invoke the real T4 transfer slot", cause);
            }
        }
    }

    private static void assertMintFailureReleasesWithoutAChild(X7DeferredSubmissionBridge.AllocationBoundary boundary) {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        InjectedAllocationError failure = new InjectedAllocationError(boundary);

        X7DeferredSubmissionBridge.failAllocationAtForTest(boundary, failure);
        try {
            assertSame(failure, assertThrows(
                    InjectedAllocationError.class,
                    () -> parent.beginDeferredSubmission(fixture.snapshot, fixture.draw)));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        closeParentAndRetire(fixture, parent);
    }

    private static void assertQueueAdmissionFailureReleasesTheTransferredChild(
            X7DeferredSubmissionBridge.AllocationBoundary boundary) {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        InjectedAllocationError failure = new InjectedAllocationError(boundary);

        X7DeferredSubmissionBridge.failAllocationAtForTest(boundary, failure);
        try {
            assertSame(failure, assertThrows(
                    InjectedAllocationError.class,
                    () -> queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child)));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        assertEquals(0, queue.inFlightSubmissionCount(), "a failed queue transfer must not retain an orphan child");
        queue.close();
        closeParentAndRetire(fixture, parent);
    }

    private static void assertDrainIndexFailureKeepsTheQueueOwner(X7DeferredSubmissionBridge.AllocationBoundary boundary) {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        InjectedAllocationError failure = new InjectedAllocationError(boundary);

        X7DeferredSubmissionBridge.failAllocationAtForTest(boundary, failure);
        try {
            assertSame(failure, assertThrows(
                    InjectedAllocationError.class,
                    () -> queue.sealAndDrainAfterSolid(RENDER_THREAD_ID)));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        assertEquals(1, queue.queuedRequestCount(), "the previous queue index must still own the child after index failure");
        queue.close();
        closeParentAndRetire(fixture, parent);
    }

    private static void assertCompletionFailureKeepsTheDrainedOwner(
            X7DeferredSubmissionBridge.AllocationBoundary boundary) {
        Fixture fixture = Fixture.create();
        ClientGenerationLeaseBinding.PlanTransferReceipt parent = fixture.openPlanReceipt();
        ClientGenerationLeaseBinding.DeferredSubmissionReceipt child = parent.beginDeferredSubmission(
                fixture.snapshot, fixture.draw);
        X7DeferredFrameQueue queue = new X7DeferredFrameQueue(RENDER_THREAD_ID);
        assertTrue(queue.tryAdmit(new Object(), fixture.snapshot, fixture.draw, child).accepted());
        X7DeferredFrameQueue.QueuedSubmission drained = queue.sealAndDrainAfterSolid(RENDER_THREAD_ID).getFirst();
        InjectedAllocationError failure = new InjectedAllocationError(boundary);

        X7DeferredSubmissionBridge.failAllocationAtForTest(boundary, failure);
        try {
            assertSame(failure, assertThrows(InjectedAllocationError.class, drained::recordCommand));
        } finally {
            X7DeferredSubmissionBridge.clearAllocationFailureForTest();
        }

        assertEquals(0, queue.retainedCompletionCount());
        assertEquals(1, queue.queuedRequestCount(), "the drained no-command index must still own the child");
        queue.close();
        closeParentAndRetire(fixture, parent);
    }

    private static void closeParentAndRetire(Fixture fixture, ClientGenerationLeaseBinding.PlanTransferReceipt parent) {
        parent.close();
        fixture.registry.publish(ModelRegistryGeneration.empty(2L));
        assertTrue(fixture.resources.isClosed(), "fatal allocation failure must leave a D1 path to final close");
        assertEquals(1, fixture.vertex.closeCalls, "every injected loss point decrements D1 exactly once");
        assertEquals(1, fixture.index.closeCalls, "every injected loss point decrements D1 exactly once");
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, fixture.aggregate.ownership());
    }

    @SuppressWarnings("unchecked")
    private static X7DeferredSubmissionCompletionReceipt onlyRetainedCompletion(X7DeferredFrameQueue queue) {
        try {
            Field field = X7DeferredFrameQueue.class.getDeclaredField("completionReceipts");
            field.setAccessible(true);
            Map<X7DeferredSubmissionCompletionReceipt, Boolean> retained =
                    (Map<X7DeferredSubmissionCompletionReceipt, Boolean>) field.get(queue);
            assertEquals(1, retained.size());
            return retained.keySet().iterator().next();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("focused test cannot inspect the queue's retained completion receipt", failure);
        }
    }

    private static final class InjectedAllocationError extends Error {
        private InjectedAllocationError(X7DeferredSubmissionBridge.AllocationBoundary boundary) {
            super("injected allocation failure at " + boundary);
        }
    }

    private static final class Fixture {
        private final BlendModelKey key;
        private final ClientModelRegistry registry;
        private final TestRenderHandle handle;
        private final PreparedRenderPrimitive primitive;
        private final ModelRenderSnapshot snapshot;
        private final X6DrawPrimitive draw;
        private final CompletedGenerationResourceSet aggregate;
        private final X7SharedGeometryResources resources;
        private final X7GpuTestFixtures.FakeGpuBuffer vertex;
        private final X7GpuTestFixtures.FakeGpuBuffer index;

        private Fixture(
                BlendModelKey key,
                ClientModelRegistry registry,
                TestRenderHandle handle,
                PreparedRenderPrimitive primitive,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                CompletedGenerationResourceSet aggregate,
                X7SharedGeometryResources resources,
                X7GpuTestFixtures.FakeGpuBuffer vertex,
                X7GpuTestFixtures.FakeGpuBuffer index) {
            this.key = key;
            this.registry = registry;
            this.handle = handle;
            this.primitive = primitive;
            this.snapshot = snapshot;
            this.draw = draw;
            this.aggregate = aggregate;
            this.resources = resources;
            this.vertex = vertex;
            this.index = index;
        }

        private static Fixture create() {
            BlendModelKey key = BlendModelKey.parse("submitted_lease_test:models/exact");
            StaticGeometry geometry = StaticGeometry.of(
                    new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new int[] {0, 1, 2});
            RenderMaterial material = new RenderMaterial(
                    BlendResourceId.parse("submitted_lease_test:textures/exact"),
                    RenderLayer.SOLID,
                    false,
                    false,
                    0xFFFFFFFF,
                    false);
            PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(0, geometry, material);
            TestRenderHandle handle = new TestRenderHandle(key, 1L, primitive);
            ModelRegistryGeneration generation = new ModelRegistryGeneration(
                    1L,
                    Map.of(key, new LoadedModelHandle(key, asset(key, 1L, handle), handle)),
                    Map.of(),
                    List.of());
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory =
                    X7GenerationResourceBridge.authoritativeInventory(generation);
            List<X7GenerationPerformancePlan.Prepared> preparedPlans = inventory.keysInDeterministicOrder().stream()
                    .map(inventory::preparationBindingFor)
                    .map(binding -> X7GenerationPerformancePlan.prepare(
                            binding, X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()))
                    .toList();
            X7GenerationResourceBridge.FrozenSelectionSet selections =
                    X7GenerationResourceBridge.freezeCanonicalSelections(inventory, preparedPlans);
            X7GenerationResourceBridge.FrozenSelection selection = selections.selectionsInDeterministicOrder().getFirst();
            X7GpuGenerationKey resourceKey = selection.key();
            X7GpuTestFixtures.FakeGpuBuffer vertex = new X7GpuTestFixtures.FakeGpuBuffer("submitted-vertex");
            X7GpuTestFixtures.FakeGpuBuffer index = new X7GpuTestFixtures.FakeGpuBuffer("submitted-index");
            X7SharedGeometryResources resources = new X7SharedGeometryResources(
                    resourceKey,
                    resourceKey.vertexFormat(),
                    resourceKey.indexType(),
                    resourceKey.primitiveMode(),
                    3,
                    3,
                    96,
                    12,
                    vertex,
                    index);
            X7GenerationResourceBridge.Composition composition = X7GenerationResourceBridge.compose(
                    selections,
                    List.of(X7GenerationResourceBridge.resourceAttempt(
                            selection, X7GpuResourceFactory.Attempt.completed(resources))));
            CompletedGenerationResourceSet aggregate = composition.aggregateOrNull();
            ClientModelRegistry registry = new ClientModelRegistry(
                    ModelRegistryGeneration.empty(0L), new ImmediateRenderOwner());
            assertDoesNotThrow(() -> registry.publish(PendingGenerationTransaction.complete(generation, aggregate)));
            ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                    handle,
                    Transform.IDENTITY,
                    0x00F000F0,
                    0,
                    0xFFFFFFFF,
                    RenderVisibility.VISIBLE,
                    new CullingMetadata(handle.bounds(), true));
            X6DrawPrimitive draw = new X6DrawPrimitive(
                    BlendResourceId.parse("submitted_lease_test:draw/exact"), primitive, material, 0xFFFFFFFF);
            return new Fixture(key, registry, handle, primitive, snapshot, draw, aggregate, resources, vertex, index);
        }

        private ClientGenerationLeaseBinding.PlanTransferReceipt openPlanReceipt() {
            ClientModelLookup lookup = ClientModelLookupTestSupport.sourceOwnedLookup(registry);
            ClientGenerationLeaseBinding binding = lookup.acquireGenerationLeaseBinding(key, snapshot);
            assertTrue(binding.managed());
            return binding.transferToPlan();
        }
    }

    private static ModelAsset asset(BlendModelKey key, long generation, ModelRenderHandle handle) {
        return new ModelAsset(
                key.resourceId(),
                key.descriptorResourceId(),
                generation,
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
                throw new IndexOutOfBoundsException("test handle has one node");
            }
            return Transform.IDENTITY;
        }

        @Override
        public boolean missingModel() {
            return false;
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
