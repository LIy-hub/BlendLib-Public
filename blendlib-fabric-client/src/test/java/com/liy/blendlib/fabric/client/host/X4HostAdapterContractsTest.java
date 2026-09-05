package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientDiagnostic;
import com.liy.blendlib.fabric.client.api.ClientDiagnosticSeverity;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

/** Executable X4 lifecycle, target-boundary, missing-model, and reload-generation contracts. */
class X4HostAdapterContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_test:models/host");

    @Test
    void everyFormalTargetPreparesExactlyOnePinnedSnapshotAndSubmitsOnlyThatSnapshot() {
        FakeLookup lookup = new FakeLookup(7L);
        var generation = X4LifecycleTestSupport.published(7L);
        AtomicInteger submits = new AtomicInteger();
        AtomicReference<ModelRenderSnapshot> lastSubmission = new AtomicReference<>();
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> {
            submits.incrementAndGet();
            lastSubmission.set(snapshot);
        });
        RenderSubmissionContext context = unusedContext();

        X4HostIdentity armorIdentity = identity("armor");
        submit(
                X4HostAdapters.armor(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(armorIdentity)
                        .configuration(new X4HostConfigurations.Armor(
                                X4ArmorSlot.CHEST,
                                X4ArmorLayer.OVERLAY,
                                armorIdentity.scope(),
                                X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.Armor(frame(armorIdentity), X4ArmorSlot.CHEST, X4ArmorLayer.OVERLAY),
                context);

        X4HostIdentity projectileIdentity = identity("projectile");
        submit(
                X4HostAdapters.projectile(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(projectileIdentity)
                        .configuration(new X4HostConfigurations.Projectile(
                                80L, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.Projectile(frame(projectileIdentity), 8L, 45.0F, -15.0F),
                context);

        X4HostIdentity heldIdentity = identity("held");
        submit(
                X4HostAdapters.heldItem(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(heldIdentity)
                        .configuration(new X4HostConfigurations.HeldItem(
                                X4RenderHand.MAIN,
                                X4HeldItemContext.THIRD_PERSON,
                                X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.HeldItem(frame(heldIdentity), X4RenderHand.MAIN, X4HeldItemContext.THIRD_PERSON),
                context);

        X4HostIdentity handIdentity = identity("first-person");
        submit(
                X4HostAdapters.firstPersonHand(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(handIdentity)
                        .configuration(new X4HostConfigurations.FirstPersonHand(
                                X4RenderHand.OFF,
                                handIdentity.scope(),
                                X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.FirstPersonHand(frame(handIdentity), X4RenderHand.OFF, handIdentity.scope()),
                context);

        X4HostIdentity guiIdentity = identity("gui");
        submit(
                X4HostAdapters.guiPreview(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(guiIdentity)
                        .configuration(new X4HostConfigurations.GuiPreview(
                                320,
                                240,
                                8.0F,
                                0.5F,
                                2.0F,
                                X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.GuiPreview(frame(guiIdentity), 160, 90, 1L),
                context);

        X4HostIdentity worldIdentity = identity("world");
        submit(
                X4HostAdapters.worldObject(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(worldIdentity)
                        .configuration(new X4HostConfigurations.WorldObject(
                                worldIdentity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.WorldObject(frame(worldIdentity), worldIdentity.scope(), 2L),
                context);

        X4HostIdentity vfxIdentity = identity("vfx");
        submit(
                X4HostAdapters.persistentVfx(lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(vfxIdentity)
                        .configuration(new X4HostConfigurations.PersistentVfx(
                                vfxIdentity.scope(),
                                200L,
                                4,
                                X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.PersistentVfx(frame(vfxIdentity), vfxIdentity.scope(), 20L, 1),
                context);

        assertEquals(7, submits.get());
        assertEquals(7, lookup.resolveCalls.get(), "lookup happens in preparation, once per formal target");
        assertTrue(lastSubmission.get().handle().missingModel());
    }

    @Test
    void missingModelDiagnosticAndGenerationArePinnedAcrossReloadAndReleasedOnlyAfterLeaseDrain() throws Exception {
        FakeLookup lookup = new FakeLookup(3L);
        var firstGeneration = X4LifecycleTestSupport.published(3L);
        AtomicInteger submits = new AtomicInteger();
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> submits.incrementAndGet());
        X4HostIdentity identity = identity("generation");

        X4HostAdapter<X4HostFrames.WorldObject> first = worldAdapter(lookup, renderer, identity, firstGeneration.session());
        first.freeze();
        X4PreparedSnapshot oldLease = first.prepare(new X4HostFrames.WorldObject(frame(identity), identity.scope(), 0L));
        lookup.publish(4L);

        assertEquals(3L, oldLease.generation());
        assertTrue(oldLease.missingModel());
        assertEquals("X4-MISSING", oldLease.missingDiagnostic().orElseThrow().diagnostic().code());
        first.submit(oldLease, unusedContext());

        AtomicReference<Throwable> retirementFailure = new AtomicReference<>();
        CountDownLatch retirementStarted = new CountDownLatch(1);
        CountDownLatch retirementFinished = new CountDownLatch(1);
        Thread retiring = new Thread(() -> {
            retirementStarted.countDown();
            try {
                first.retire();
            } catch (Throwable failure) {
                retirementFailure.set(failure);
            } finally {
                retirementFinished.countDown();
            }
        }, "x4-generation-retire");
        retiring.setDaemon(true);
        var firstDrain = first.drainCompletion().toCompletableFuture();
        try {
            retiring.start();
            await(retirementStarted, "old-generation retirement worker did not start");
            awaitState(first, X4HostLifecycleState.RETIRING, retirementFinished, retirementFailure);
            assertEquals(1L, retirementFinished.getCount(),
                    "retire must still wait while the exact old-generation snapshot is open");
            assertTrue(retiring.isAlive(), "retire worker returned before the old-generation snapshot drained");
            assertFalse(firstDrain.isDone(), "retire drain completed before the old-generation snapshot drained");
            X4HostLeaseDiagnostics retiringDiagnostics = first.leaseDiagnostics();
            assertTrue(retiringDiagnostics.retirementRequested());
            assertEquals("", retiringDiagnostics.terminalFailureType());
            assertFalse(oldLease.closed(),
                    "retirement leaves the exact old-generation lease alive until its owner drains it");

            oldLease.close();
            await(retirementFinished, "old-generation retirement did not finish after its snapshot drained");
            retiring.join(TimeUnit.SECONDS.toMillis(5L));
        } finally {
            try {
                oldLease.close();
            } finally {
                if (retiring.isAlive()) {
                    retiring.interrupt();
                    retiring.join(TimeUnit.SECONDS.toMillis(5L));
                }
            }
        }
        assertFalse(retiring.isAlive(), "old-generation retirement worker did not terminate");
        assertNull(retirementFailure.get(), "old-generation retirement failed");
        assertTrue(oldLease.closed());
        assertEquals(X4HostLifecycleState.RETIRED, first.state());
        assertEquals(X4HostLifecycleState.RETIRED, firstDrain.get(5L, TimeUnit.SECONDS));

        var reloadedGeneration = X4LifecycleTestSupport.published(4L);
        X4HostAdapter<X4HostFrames.WorldObject> reloaded = worldAdapter(
                lookup, renderer, identity("generation-reloaded"), reloadedGeneration.session());
        reloaded.freeze();
        try (X4PreparedSnapshot newLease = reloaded.prepare(new X4HostFrames.WorldObject(
                frame(reloaded.configure().identity()), reloaded.configure().identity().scope(), 1L))) {
            assertEquals(4L, newLease.generation());
            reloaded.submit(newLease, unusedContext());
        }
        assertEquals(2, submits.get(), "both exact old and reloaded generations submit once");
    }

    @Test
    void capturedSnapshotPathAvoidsLookupAndStillValidatesTheExactImmutableFrameMetadata() {
        X4HostIdentity identity = identity("captured");
        var generation = X4LifecycleTestSupport.published(11L);
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 11L);
        ModelRenderSnapshot captured = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        ClientModelLookup failingLookup = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                throw new AssertionError("captured X4 preparation must not query a registry snapshot");
            }

            @Override
            public ClientModelView resolve(BlendModelKey key) {
                throw new AssertionError("captured X4 preparation must not resolve a model");
            }
        };
        AtomicReference<ModelRenderSnapshot> submitted = new AtomicReference<>();
        X4HostAdapter<X4HostFrames.WorldObject> adapter = worldAdapter(
                failingLookup, new BlendRenderer((snapshot, context) -> submitted.set(snapshot)), identity, generation.session());
        adapter.freeze();
        X4SnapshotFrame extracted = X4SnapshotFrame.extractedMissing(
                identity,
                X4Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                true,
                captured,
                new X4MissingModelDiagnostic(11L, new ClientDiagnostic(
                        ClientDiagnosticSeverity.ERROR,
                        "X4-MISSING",
                        KEY.resourceId(),
                        KEY.resourceId(),
                        "x4-test",
                        "fixture missing model",
                        "none")));
        try (X4PreparedSnapshot lease = adapter.prepare(new X4HostFrames.WorldObject(extracted, identity.scope(), 0L))) {
            adapter.submit(lease, unusedContext());
            assertSame(captured, submitted.get());
        }
    }

    @Test
    void targetSpecificValidationBuilderSealingRegistryConflictsAndExperimentalOptInFailClosed() {
        FakeLookup lookup = new FakeLookup(1L);
        var generation = X4LifecycleTestSupport.published(1L);
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> { });
        X4HostIdentity heldIdentity = identity("held-validation");
        X4HostAdapter<X4HostFrames.HeldItem> held = X4HostAdapters.heldItem(lookup, renderer, generation.session())
                .model(KEY)
                .identity(heldIdentity)
                .configuration(new X4HostConfigurations.HeldItem(
                        X4RenderHand.MAIN, X4HeldItemContext.FIXED, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();
        held.freeze();
        assertThrows(IllegalArgumentException.class, () -> held.prepare(
                new X4HostFrames.HeldItem(frame(heldIdentity), X4RenderHand.OFF, X4HeldItemContext.FIXED)));
        assertEquals(X4HostLifecycleState.FROZEN, held.state());
        try (X4PreparedSnapshot ignored = held.prepare(
                new X4HostFrames.HeldItem(frame(heldIdentity), X4RenderHand.MAIN, X4HeldItemContext.FIXED))) {
            assertEquals(X4HostLifecycleState.PREPARED, held.state());
            assertFalse(ignored.closed());
        }

        X4HostAdapterBuilder<X4HostConfigurations.Armor, X4HostFrames.Armor> incomplete =
                X4HostAdapters.armor(lookup, renderer, generation.session());
        assertThrows(IllegalStateException.class, incomplete::build);
        X4HostIdentity armorIdentity = identity("builder");
        incomplete.model(KEY)
                .identity(armorIdentity)
                .configuration(new X4HostConfigurations.Armor(
                        X4ArmorSlot.HEAD,
                        X4ArmorLayer.BASE,
                        armorIdentity.scope(),
                        X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));
        assertDoesNotThrow(incomplete::build);
        assertThrows(IllegalStateException.class, () -> incomplete.model(KEY));
        assertThrows(IllegalArgumentException.class, () -> new X4HostConfigurations.Projectile(0L, 1.0F));
        assertThrows(IllegalArgumentException.class, () -> new X4Transform(
                Float.NaN, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F));
        assertThrows(IllegalStateException.class,
                () -> X4HostAdapters.playerReplacement(X4ExperimentalAccess.disabled(), lookup, renderer, generation.session()));

        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        X4HostIdentity registryIdentity = identity("registry");
        registry.register(worldAdapter(lookup, renderer, registryIdentity, generation.session()));
        assertThrows(IllegalStateException.class,
                () -> registry.register(worldAdapter(lookup, renderer, registryIdentity, generation.session())));
        registry.close();
        assertThrows(IllegalStateException.class,
                () -> registry.register(worldAdapter(lookup, renderer, identity("closed"), generation.session())));
    }

    @Test
    void explicitExperimentalTargetsRemainBoundedAndUseTheSameSnapshotLifecycle() {
        FakeLookup lookup = new FakeLookup(5L);
        var generation = X4LifecycleTestSupport.published(5L);
        AtomicInteger submits = new AtomicInteger();
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> submits.incrementAndGet());
        X4ExperimentalAccess access = X4ExperimentalAccess.optIn(BlendResourceId.parse("x4_test:acknowledged"));

        X4HostIdentity playerIdentity = identity("player");
        submit(
                X4HostAdapters.playerReplacement(access, lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(playerIdentity)
                        .configuration(new X4HostConfigurations.PlayerReplacement(
                                access, playerIdentity.scope(), true, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.PlayerReplacement(frame(playerIdentity), playerIdentity.scope(), true),
                unusedContext());

        X4HostIdentity root = identity("composite-root");
        X4HostIdentity child = identity("composite-child");
        X4CompositeGraph graph = new X4CompositeGraph(Map.of(root, List.of(child), child, List.of()));
        submit(
                X4HostAdapters.mountComposite(access, lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(root)
                        .configuration(new X4HostConfigurations.MountComposite(
                                access, graph, root, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.MountComposite(frame(root), graph, root),
                unusedContext());

        X4HostIdentity multiRoot = identity("multi-root");
        X4HostIdentity multiChild = identity("multi-child");
        X4CompositeGraph multiGraph = new X4CompositeGraph(Map.of(multiRoot, List.of(multiChild), multiChild, List.of()));
        submit(
                X4HostAdapters.multiEntityComposite(access, lookup, renderer, generation.session())
                        .model(KEY)
                        .identity(multiRoot)
                        .configuration(new X4HostConfigurations.MultiEntityComposite(
                                access, multiGraph, multiRoot, X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new X4HostFrames.MultiEntityComposite(frame(multiRoot), multiGraph, multiRoot),
                unusedContext());
        assertEquals(3, submits.get());
        assertThrows(IllegalArgumentException.class, () -> new X4CompositeGraph(Map.of(root, List.of(child), child, List.of(root))));
    }

    @Test
    void submitHotPathContainsOnlyThePreparedSnapshotRendererSeam() throws IOException {
        Path sourceFile = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src/client/java/com/liy/blendlib/fabric/client/host/DefaultX4HostAdapter.java");
        String source = Files.readString(sourceFile);
        int submitStart = source.indexOf("public void submit(");
        int submitEnd = source.indexOf("\n    @Override\n    public void retire()", submitStart);
        String submitMethod = source.substring(submitStart, submitEnd);
        for (String forbidden : new String[] {
                ".resolve(", "ClientModelLookup", "ClientGenerationLeaseBinding", "permitsFrozenCpuRoute",
                "X7GenerationPerformancePlan", "X7CullingPolicy", "X7BudgetPolicy", "X7LodPolicy", "X7AnimationWorkPolicy",
                "ResourceManager", "GlbReader", "StrictJsonParser", "java.nio.file", "java.io."}) {
            assertFalse(submitMethod.contains(forbidden), forbidden);
        }
        assertTrue(submitMethod.contains("renderer.submit(submittedSnapshot, checkedContext)"));
        assertTrue(submitMethod.contains("permitsFrozenCpuSubmission"));
    }

    private static X4HostAdapter<X4HostFrames.WorldObject> worldAdapter(
            ClientModelLookup lookup,
            BlendRenderer renderer,
            X4HostIdentity identity,
            com.liy.blendlib.spi.experimental.ProviderLifecycleSession generationSession) {
        return X4HostAdapters.worldObject(lookup, renderer, generationSession)
                .model(KEY)
                .identity(identity)
                .configuration(new X4HostConfigurations.WorldObject(
                        identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();
    }

    private static <F extends X4HostFrame> void submit(
            X4HostAdapter<F> adapter, F hostFrame, RenderSubmissionContext context) {
        adapter.freeze();
        try (X4PreparedSnapshot prepared = adapter.prepare(hostFrame)) {
            adapter.submit(prepared, context);
            assertEquals(X4HostLifecycleState.PREPARED, adapter.state());
        }
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_test:scope"),
                BlendResourceId.parse("x4_test:host/" + local));
    }

    private static X4SnapshotFrame frame(X4HostIdentity identity) {
        return X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, 0x00F000F0, 0, 0xFFFFFFFF, true);
    }

    private static RenderSubmissionContext unusedContext() {
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                X4HostAdapterContractsTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("X4 test backend must not access collector method " + method.getName());
                });
        return new RenderSubmissionContext(new PoseStack(), collector);
    }

    private static void await(CountDownLatch latch, String failureMessage) throws InterruptedException {
        assertTrue(latch.await(5L, TimeUnit.SECONDS), failureMessage);
    }

    private static void awaitState(
            X4HostAdapter<?> adapter,
            X4HostLifecycleState expected,
            CountDownLatch workerFinished,
            AtomicReference<Throwable> workerFailure) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (adapter.state() == expected) return;
            if (workerFinished.getCount() == 0L) break;
            Thread.onSpinWait();
        }
        Throwable failure = workerFailure.get();
        if (failure != null) {
            throw new AssertionError("terminal worker failed before reaching " + expected, failure);
        }
        throw new AssertionError("X4 adapter did not reach " + expected + " before the deadline; actual="
                + adapter.state());
    }

    private static final class FakeLookup implements ClientModelLookup {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private volatile ClientModelView view;

        private FakeLookup(long generation) {
            publish(generation);
        }

        private void publish(long generation) {
            MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, generation);
            ClientDiagnostic diagnostic = new ClientDiagnostic(
                    ClientDiagnosticSeverity.ERROR,
                    "X4-MISSING",
                    KEY.resourceId(),
                    KEY.resourceId(),
                    "x4-test",
                    "fixture missing model",
                    "none");
            view = new ClientModelView(KEY, generation, false, handle, Optional.of(diagnostic));
        }

        @Override
        public ClientRegistryView snapshot() {
            ClientModelView current = view;
            return new ClientRegistryView(current.generationId(), Map.of(KEY, current), current.primaryDiagnostic().stream().toList());
        }

        @Override
        public ClientModelView resolve(BlendModelKey modelKey) {
            assertEquals(KEY, modelKey);
            resolveCalls.incrementAndGet();
            return view;
        }
    }
}
