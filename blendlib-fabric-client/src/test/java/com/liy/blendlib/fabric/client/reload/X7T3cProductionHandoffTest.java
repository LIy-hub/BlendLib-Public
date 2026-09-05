package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skeleton;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.X7T3cClientGateway;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.SkinnedRenderSnapshot;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import com.liy.blendlib.fabric.client.render.X6LifecycleDrainDispatcher;
import com.liy.blendlib.fabric.client.render.X6MaterialIntent;
import com.liy.blendlib.fabric.client.render.X6MaterialMode;
import com.liy.blendlib.fabric.client.render.X6MaterialPlan;
import com.liy.blendlib.fabric.client.render.X6MaterialProviderGeneration;
import com.liy.blendlib.fabric.client.render.X6PreparedGeometryCatalog;
import com.liy.blendlib.fabric.client.render.X6PreparedRenderPlan;
import com.liy.blendlib.fabric.client.render.X6PreparedRenderPlanFactory;
import com.liy.blendlib.fabric.client.render.X6RenderLayerPlan;
import com.liy.blendlib.fabric.client.render.X6RenderLayerPlanner;
import com.liy.blendlib.fabric.client.render.X7T3cFrozenStageA;
import com.liy.blendlib.fabric.client.render.X6VariantApplicationPlan;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedFrameProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real T3c queue handoff through the client gateway and T4 command submitter. Reflection is limited
 * to sibling-package test access; the test never constructs a queue completion handoff or invokes its move itself.
 */
class X7T3cProductionHandoffTest {
    private static final String T4_OWNER = "com.liy.blendlib.fabric.client.X7Minecraft2612SkinnedPassSubmitter";

    @Test
    void postMoveErrorKeepsTheRealIndexedReceiptReachableBeforeOneTerminalD1Release() {
        Fixture fixture = Fixture.create();
        X7T3cReloadGateway reload = X7T3cReloadGateway.forTest(
                Thread.currentThread().threadId(),
                1,
                (batch, scope, commands) -> {
                    throw new AssertionError("skinned production-handoff fixture must not select the static path");
                });
        X7DeferredFrameQueue queue = queueOf(reload);
        HandoffProbe probe = new HandoffProbe(queue);
        X7T3cClientGateway gateway = clientGateway(reload, probe);
        X7T3cFrozenStageA frozen = freezeAndMint(fixture.plan, fixture.snapshot, fixture.draw);
        boolean admitted = false;
        try {
            admitted = gateway.tryAdmit(frozen);
            assertTrue(admitted, "source-order-proven test seam must atomically transfer one exact child into the real queue");

            InjectedAllocationError failure = new InjectedAllocationError(
                    X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE);
            X7DeferredSubmissionBridge.failAllocationAtForTest(
                    X7DeferredSubmissionBridge.AllocationBoundary.COMPLETION_POST_DRAINED_REMOVE, failure);
            try {
                assertSame(failure, assertThrows(
                        InjectedAllocationError.class,
                        () -> gateway.sealDrainAndExecute(new FakeTextureView(), new FakeTextureView())));
            } finally {
                X7DeferredSubmissionBridge.clearAllocationFailureForTest();
            }

            X7DeferredSubmissionCompletionReceipt indexed = probe.indexedReceipt.get();
            assertNotNull(indexed,
                    "the real queue must publish its exact indexed receipt before the injected post-move error escapes");
            assertTrue(probe.indexedWhenTerminalOwnerRan.get(),
                    "the real pre-command terminal owner must observe the indexed receipt before releasing it");
            assertTrue(probe.t4ObservedMove.get(),
                    "the real T4 CompletionTransfer must observe the queue move before the post-move error");
            assertEquals(1, probe.terminalOwnerActions.get(),
                    "the real pre-command terminal callback must run exactly once");
            assertEquals(X7DeferredSubmissionCompletionReceipt.State.COMPLETED, indexed.state());
            assertEquals(0, queue.retainedCompletionCount(),
                    "the one terminal owner must remove the indexed completion exactly once");
            assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(fixture.registry),
                    "the submitted child decrements exactly once while the still-open plan retains the parent");
        } finally {
            if (!admitted) {
                frozen.close();
            }
            fixture.closeAfterTerminalChild();
        }

        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(fixture.registry));
        assertEquals(1, fixture.bindings.closeCalls,
                "D1 must close the exact skinned source leaf exactly once after the plan and child both release");
    }

    private static X7T3cClientGateway clientGateway(X7T3cReloadGateway reload, HandoffProbe probe) {
        try {
            Class<?> driverType = Class.forName(T4_OWNER + "$Driver");
            Method factory = X7T3cClientGateway.class.getDeclaredMethod(
                    "forTest", X7T3cReloadGateway.class, BooleanSupplier.class, driverType);
            factory.setAccessible(true);
            return (X7T3cClientGateway) invoke(
                    factory,
                    null,
                    reload,
                    (BooleanSupplier) () -> true,
                    probe.driver(driverType));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("focused test cannot create the package-private T3c driver seam", failure);
        }
    }

    private static X7T3cFrozenStageA freezeAndMint(
            X6PreparedRenderPlan plan, ModelRenderSnapshot snapshot, X6DrawPrimitive draw) {
        try {
            Method acquire = X6PreparedRenderPlan.class.getDeclaredMethod("acquireSubmissionHold", ModelRenderSnapshot.class);
            Method release = X6PreparedRenderPlan.class.getDeclaredMethod("releaseSubmissionHold");
            acquire.setAccessible(true);
            release.setAccessible(true);
            Class<?> inputsType = Class.forName(
                    "com.liy.blendlib.fabric.client.render.X6PlanSubmitter$FinalDrawInputs");
            Constructor<?> inputs = inputsType.getDeclaredConstructor(Matrix4f.class, Matrix3f.class, int.class, int.class);
            inputs.setAccessible(true);
            Object finalInputs = inputs.newInstance(
                    new Matrix4f(), new Matrix3f(), 0xFFFFFFFF, 0x00F000F0);
            Method freeze = X7T3cFrozenStageA.class.getDeclaredMethod(
                    "freezeAndMint", X6PreparedRenderPlan.class, ModelRenderSnapshot.class, X6DrawPrimitive.class, inputsType);
            freeze.setAccessible(true);
            invoke(acquire, plan, snapshot);
            try {
                return (X7T3cFrozenStageA) invoke(freeze, null, plan, snapshot, draw, finalInputs);
            } finally {
                invoke(release, plan);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("focused test cannot enter the real Stage-A mint seam", failure);
        }
    }

    private static X7DeferredFrameQueue queueOf(X7T3cReloadGateway reload) {
        try {
            Field field = X7T3cReloadGateway.class.getDeclaredField("queue");
            field.setAccessible(true);
            return (X7DeferredFrameQueue) field.get(reload);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("focused test cannot observe the real T3c queue", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static X7DeferredSubmissionCompletionReceipt onlyRetainedCompletion(X7DeferredFrameQueue queue) {
        try {
            Field field = X7DeferredFrameQueue.class.getDeclaredField("completionReceipts");
            field.setAccessible(true);
            Map<X7DeferredSubmissionCompletionReceipt, Boolean> receipts =
                    (Map<X7DeferredSubmissionCompletionReceipt, Boolean>) field.get(queue);
            assertEquals(1, receipts.size(), "one exact indexed receipt must exist at the real pre-command terminal boundary");
            return receipts.keySet().iterator().next();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("focused test cannot inspect the indexed completion owner", failure);
        }
    }

    private static X6VariantApplicationPlan variants(BlendModelKey key, long generation, X6DrawPrimitive draw) {
        try {
            Constructor<X6VariantApplicationPlan> constructor = X6VariantApplicationPlan.class.getDeclaredConstructor(
                    BlendModelKey.class, long.class, List.class, List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(key, generation, List.of(draw), List.of());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("focused test cannot construct the exact X6 variant plan", failure);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException failure) {
            throw new AssertionError("focused test cannot invoke a package-private production seam", failure);
        } catch (InvocationTargetException failure) {
            throwUnchecked(failure.getCause());
            throw new AssertionError("unreachable");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private static final class HandoffProbe {
        private final X7DeferredFrameQueue queue;
        private final AtomicReference<X7DeferredSubmissionCompletionReceipt> indexedReceipt = new AtomicReference<>();
        private final AtomicBoolean indexedWhenTerminalOwnerRan = new AtomicBoolean();
        private final AtomicBoolean t4ObservedMove = new AtomicBoolean();
        private final AtomicInteger terminalOwnerActions = new AtomicInteger();

        private HandoffProbe(X7DeferredFrameQueue queue) {
            this.queue = queue;
        }

        private Object driver(Class<?> driverType) {
            return Proxy.newProxyInstance(driverType.getClassLoader(), new Class<?>[] {driverType}, (proxy, method, arguments) -> {
                if (method.getName().equals("record")) {
                    return record(arguments[3]);
                }
                if (method.getName().equals("toString")) {
                    return "T3c production-handoff test driver";
                }
                throw new AssertionError("unexpected T4 driver method " + method);
            });
        }

        private Object record(Object commands) throws Throwable {
            Class<?> commandsType = Class.forName(T4_OWNER + "$CommandsRecorded");
            Class<?> transferType = Class.forName(T4_OWNER + "$CompletionTransfer");
            Class<?> receiptType = Class.forName(T4_OWNER + "$FenceReceipt");
            Class<?> fenceType = Class.forName(T4_OWNER + "$FenceReceipt$Fence");
            Class<?> transientCloseType = Class.forName(T4_OWNER + "$FenceReceipt$TransientClose");

            Method completionTransfer = commandsType.getDeclaredMethod("completionTransfer");
            Method install = commandsType.getDeclaredMethod("installPreallocatedReceipt", receiptType);
            Method record = commandsType.getDeclaredMethod("recordAllBeforeNativeDraw");
            Method moved = transferType.getDeclaredMethod("isMoved");
            Method pending = receiptType.getDeclaredMethod("pending", fenceType, transientCloseType, transferType);
            completionTransfer.setAccessible(true);
            install.setAccessible(true);
            record.setAccessible(true);
            moved.setAccessible(true);
            pending.setAccessible(true);

            Object transfer = invoke(completionTransfer, commands);
            Object fence = Proxy.newProxyInstance(fenceType.getClassLoader(), new Class<?>[] {fenceType}, (proxy, method, arguments) -> {
                if (method.getName().equals("awaitCompletionZero")) {
                    return false;
                }
                if (method.getName().equals("close")) {
                    return null;
                }
                throw new AssertionError("unexpected T4 fence method " + method);
            });
            Object transientClose = Proxy.newProxyInstance(
                    transientCloseType.getClassLoader(), new Class<?>[] {transientCloseType}, (proxy, method, arguments) -> {
                        if (!method.getName().equals("close")) {
                            throw new AssertionError("unexpected T4 transient-close method " + method);
                        }
                        X7DeferredSubmissionCompletionReceipt indexed = onlyRetainedCompletion(queue);
                        indexedReceipt.compareAndSet(null, indexed);
                        indexedWhenTerminalOwnerRan.set(indexed.state()
                                == X7DeferredSubmissionCompletionReceipt.State.COMMAND_RECORDED);
                        terminalOwnerActions.incrementAndGet();
                        return null;
                    });
            Object receipt = invoke(pending, null, fence, transientClose, transfer);
            invoke(install, commands, receipt);
            try {
                invoke(record, commands);
                return receipt;
            } catch (Throwable failure) {
                t4ObservedMove.set((boolean) invoke(moved, transfer));
                throw failure;
            }
        }
    }

    private static final class Fixture {
        private static final long GENERATION = 71L;
        private static final BlendModelKey KEY = BlendModelKey.parse("t3c_handoff:actor/base");
        private static final BlendResourceId PART = BlendResourceId.parse("t3c_handoff:part/skin");

        private final ClientModelRegistry registry;
        private final X6PreparedRenderPlan plan;
        private final X6MaterialProviderGeneration providers;
        private final DeferredLifecycle lifecycle;
        private final ModelRenderSnapshot snapshot;
        private final X6DrawPrimitive draw;
        private final FakeBindings bindings;

        private Fixture(
                ClientModelRegistry registry,
                X6PreparedRenderPlan plan,
                X6MaterialProviderGeneration providers,
                DeferredLifecycle lifecycle,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                FakeBindings bindings) {
            this.registry = registry;
            this.plan = plan;
            this.providers = providers;
            this.lifecycle = lifecycle;
            this.snapshot = snapshot;
            this.draw = draw;
            this.bindings = bindings;
        }

        private static Fixture create() {
            MeshPrimitive mesh = new MeshPrimitive(
                    "surface",
                    new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new int[] {0, 1, 2},
                    new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new float[] {1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F});
            MaterialDefinition materialDefinition = new MaterialDefinition(
                    BlendResourceId.parse("t3c_handoff:textures/skin.png"), MaterialDefinition.Mode.OPAQUE, false, false, null);
            Skin skin = new Skin("T3cSkin", 1, List.of(1), identityMatrices(1));
            List<ModelNode> nodes = List.of(
                    new ModelNode(0, "mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                    new ModelNode(1, "joint", Transform.IDENTITY, List.of(), -1, -1, false));
            ModelAsset asset = new ModelAsset(
                    KEY.resourceId(),
                    KEY.descriptorResourceId(),
                    GENERATION,
                    ModelProfile.SKINNED_V1,
                    1.0D,
                    Map.of("surface", materialDefinition),
                    null,
                    nodes,
                    List.of(0),
                    List.of(new ModelPrimitive(0, 0, 0, mesh)),
                    new Skeleton(List.of(skin)),
                    List.of(),
                    new SocketTable(Map.of()),
                    Bounds.fromPositions(mesh.positions()),
                    List.of());
            SkinnedRenderHandle handle = SkinnedRenderHandle.prepare(KEY, asset);
            PreparedSkinnedRenderPrimitive primitive = handle.skinnedPrimitives().getFirst();
            SkinPalette palette = SkinPalette.from(
                    skin,
                    NodePalette.from(new LocalPose(Map.of(
                            0, Transform.IDENTITY,
                            1, new Transform(Vec3.ZERO, Quaternion.IDENTITY, Vec3.ONE))), nodes));
            X7SkinnedFrameProvenance.SealedFrame sealed = X7SkinnedFrameProvenance.capture(primitive, palette);
            ModelRenderSnapshot snapshot = ModelRenderSnapshot.skinned(
                    handle,
                    Transform.IDENTITY,
                    0x00F000F0,
                    OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF,
                    RenderVisibility.VISIBLE,
                    new CullingMetadata(handle.bounds(), true),
                    SkinnedRenderSnapshot.captureWithT4Provenance(handle, List.of(sealed)));
            ModelRegistryGeneration generation = new ModelRegistryGeneration(
                    GENERATION,
                    Map.of(KEY, new LoadedModelHandle(KEY, asset, handle)),
                    Map.of(),
                    List.of());
            X7GpuGenerationKey resourceKey = X7GenerationResourceBridge.authoritativeInventory(generation)
                    .keysInDeterministicOrder()
                    .getFirst();
            X7SkinnedTexelProvenance generationProof = sealed.provenance().tryMaterialize().provenanceOrNull();
            assertNotNull(generationProof);
            SkinnedTexelUploadStaging staging = SkinnedTexelUploadStaging.capture(sealed.provenance(), generationProof);
            FakeBindings bindings = new FakeBindings();
            SkinnedTexelGenerationResources leaf = new SkinnedTexelGenerationResources(
                    resourceKey,
                    staging.staticSource(),
                    staging.vertexCount(),
                    staging.indexCount(),
                    staging.vertexByteCount(),
                    staging.indexByteCount(),
                    staging.sourceByteCount(),
                    bindings);
            staging.transferStaticSourceToLeaf(leaf);
            staging.close();
            generationProof.close();
            CompletedGenerationResourceSet aggregate = CompletedGenerationResourceSet.complete(generation, List.of(leaf));
            ClientModelRegistry registry = new ClientModelRegistry(
                    ModelRegistryGeneration.empty(0L), new ImmediateRenderOwner());
            registry.publish(PendingGenerationTransaction.complete(generation, aggregate));

            X6PreparedGeometryCatalog geometry = X6PreparedGeometryCatalog.skinned(
                    KEY, GENERATION, Map.of(PART, primitive), Map.of(PART, 0), Map.of());
            X6MaterialPlan materials = X6MaterialPlan.prepare(
                    KEY,
                    GENERATION,
                    Map.of(PART, new X6MaterialIntent(materialDefinition.baseColor(), X6MaterialMode.OPAQUE, false, false, null)))
                    .plan()
                    .orElseThrow();
            X6DrawPrimitive draw = new X6DrawPrimitive(PART, geometry.binding(PART), primitive.material(), 0xFFFFFFFF);
            X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, GENERATION, List.of(), geometry)
                    .plan()
                    .orElseThrow();
            X6MaterialProviderGeneration providers = X6MaterialProviderGeneration.prepareAndPublish(
                    KEY, GENERATION, List.of(), List.of());
            DeferredLifecycle lifecycle = new DeferredLifecycle();
            ClientGenerationLeaseBinding binding = ClientModelLookupTestSupport.sourceOwnedBinding(registry, KEY, snapshot);
            X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepareManaged(
                    variants(KEY, GENERATION, draw),
                    layers,
                    materials,
                    geometry,
                    providers,
                    binding,
                    lifecycle).plan().orElseThrow();
            return new Fixture(registry, plan, providers, lifecycle, snapshot, draw, bindings);
        }

        private void closeAfterTerminalChild() {
            plan.close();
            lifecycle.runAll();
            providers.close();
            registry.publish(ModelRegistryGeneration.empty(GENERATION + 1L));
        }
    }

    private static float[] identityMatrices(int count) {
        float[] result = new float[count * 16];
        for (int index = 0; index < count; index++) {
            result[index * 16] = 1.0F;
            result[index * 16 + 5] = 1.0F;
            result[index * 16 + 10] = 1.0F;
            result[index * 16 + 15] = 1.0F;
        }
        return result;
    }

    private static final class DeferredLifecycle implements X6LifecycleDrainDispatcher {
        private final List<Runnable> retained = new ArrayList<>();

        @Override
        public Admission register(Runnable prebuiltDrain) {
            retained.add(prebuiltDrain);
            return new Admission() {
                @Override
                public void requestDrain() {
                    // The fixture invokes the prebuilt owner work explicitly after its assertions.
                }

                @Override
                public void cancel() {
                    retained.remove(prebuiltDrain);
                }
            };
        }

        private void runAll() {
            List<Runnable> ready = List.copyOf(retained);
            retained.clear();
            ready.forEach(Runnable::run);
        }
    }

    private static final class FakeBindings implements SkinnedTexelGenerationResources.BufferBindings {
        private int closeCalls;

        @Override
        public void bindTo(RenderPass pass) {
            // The injected driver fails before a native bind/draw boundary.
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

    private static final class FakeTextureView extends GpuTextureView {
        private FakeTextureView() {
            super(null, 0, 1);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

    private static final class InjectedAllocationError extends Error {
        private static final long serialVersionUID = 1L;

        private InjectedAllocationError(X7DeferredSubmissionBridge.AllocationBoundary boundary) {
            super("injected allocation failure at " + boundary);
        }
    }
}
