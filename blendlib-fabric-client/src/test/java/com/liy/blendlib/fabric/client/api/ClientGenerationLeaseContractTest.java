package com.liy.blendlib.fabric.client.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLease;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.ClientModelLookupTestSupport;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.MissingModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.X6LifecycleDrainDispatcher;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import com.liy.blendlib.fabric.client.render.X6MaterialPlan;
import com.liy.blendlib.fabric.client.render.X6MaterialProviderGeneration;
import com.liy.blendlib.fabric.client.render.X6PreparedGeometryCatalog;
import com.liy.blendlib.fabric.client.render.X6PreparedRenderPlanFactory;
import com.liy.blendlib.fabric.client.render.X6RenderLayerPlan;
import com.liy.blendlib.fabric.client.render.X6VariantApplicationPlan;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exact-registry admission and public opaque-lease boundary contracts. */
class ClientGenerationLeaseContractTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("shared_lease_test:models/exact");
    private static final BlendModelKey OTHER_KEY = BlendModelKey.parse("shared_lease_test:models/other");

    @Test
    void registryBackedLookupAdmitsOnlyItsExactCurrentHandleAndRetainsTheAdmittedRetiringParent() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle firstHandle = new TestRenderHandle(KEY, 1L);
        registry.publish(loadedGeneration(1L, KEY, firstHandle));
        ClientModelLookup lookup = ClientModelLookupTestSupport.sourceOwnedLookup(registry);
        ModelRenderSnapshot firstSnapshot = snapshot(firstHandle);

        ClientGenerationLeaseBinding firstBinding = lookup.acquireGenerationLeaseBinding(KEY, firstSnapshot);
        assertTrue(firstBinding.managed());
        assertDoesNotThrow(firstBinding::requireManagedForPlan);

        TestRenderHandle replacementHandle = new TestRenderHandle(KEY, 2L);
        registry.publish(loadedGeneration(2L, KEY, replacementHandle));
        assertThrows(IllegalArgumentException.class, () -> lookup.acquireGenerationLeaseBinding(KEY, firstSnapshot),
                "a post-reload source-owned binding must never accept a snapshot captured before reload");
        assertDoesNotThrow(firstBinding::requireManagedForPlan,
                "the already-admitted parent remains valid while its retired generation drains");

        firstBinding.close();
        firstBinding.close();
        assertThrows(IllegalStateException.class, firstBinding::requireManagedForPlan);
    }

    @Test
    void sameRawHandleForeignRegistryCannotCreateOrMasqueradeAsASourceBoundManagedComposite() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle activeHandle = new TestRenderHandle(KEY, 1L);
        registry.publish(loadedGeneration(1L, KEY, activeHandle));
        ClientModelLookup lookup = ClientModelLookupTestSupport.sourceOwnedLookup(registry);
        ClientModelView activeView = lookup.resolve(KEY);
        ModelRenderSnapshot activeSnapshot = snapshot(activeHandle);

        ClientGenerationLeaseBinding sourceBinding = lookup.acquireGenerationLeaseBinding(KEY, activeSnapshot);
        assertThrows(IllegalArgumentException.class,
                () -> sourceBinding.requireExactSnapshot(snapshot(new TestRenderHandle(KEY, 1L))));
        sourceBinding.close();

        ClientModelRegistry foreignRegistry = new ClientModelRegistry();
        foreignRegistry.publish(loadedGeneration(1L, KEY, activeHandle));
        ClientModelLookup fakeExternal = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(activeView.generationId(), Map.of(KEY, activeView), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                return activeView;
            }

            @Override
            public ClientGenerationLeaseBinding acquireGenerationLeaseBinding(
                    BlendModelKey modelKey, ModelRenderSnapshot snapshot) {
                return lookup.acquireGenerationLeaseBinding(modelKey, snapshot);
            }
        };

        ClientGenerationLeaseBinding delegated = fakeExternal.acquireGenerationLeaseBinding(KEY, activeSnapshot);
        assertThrows(IllegalArgumentException.class, () -> delegated.requireCompatible(fakeExternal),
                "a wrapper cannot present A's trusted composite as the fake lookup's own source");
        assertDoesNotThrow(() -> delegated.requireCompatible(lookup));
        delegated.close();

        ClientModelLookup ordinaryExternal = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(activeView.generationId(), Map.of(KEY, activeView), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                return activeView;
            }
        };
        assertFalse(ordinaryExternal.acquireGenerationLeaseBinding(KEY, activeSnapshot).managed(),
                "an external lookup cannot upgrade its default fallback into B-managed ownership");
        assertTrue(java.util.Arrays.stream(ClientModelRegistry.class.getMethods())
                .filter(method -> method.getReturnType() == ClientModelLookup.class)
                .allMatch(method -> java.util.Arrays.asList(method.getParameterTypes())
                        .contains(ClientModelLookupBootstrap.class)),
                "the registry must not expose a no-capability source-lookup factory for foreign B registries");
        assertTrue(ClientModelLookupBootstrap.class.isSealed());
        assertEquals(Set.of(BlendLibClientServices.ManagedLookupBootstrap.class),
                Set.of(ClientModelLookupBootstrap.class.getPermittedSubclasses()));
        assertFalse(Modifier.isPublic(BlendLibClientServices.ManagedLookupBootstrap.class.getModifiers()));
        assertTrue(java.util.Arrays.stream(BlendLibClientServices.ManagedLookupBootstrap.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(java.util.Arrays.stream(BlendLibClientServices.class.getMethods())
                .noneMatch(method -> method.getReturnType() == ClientModelLookupBootstrap.class));
        assertTrue(java.util.Arrays.stream(BlendLibClientServices.class.getFields())
                .noneMatch(field -> field.getType() == ClientModelLookupBootstrap.class),
                "no public service member may leak the sole registry-lookup bootstrap capability");
    }

    @Test
    void lookupAdditionIsDefaultCompatibleAndOpaqueManagedConstructionIsNotPublic() throws Exception {
        ClientModelLookup external = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(0L, Map.of(), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                return new ClientModelView(
                        modelKey,
                        0L,
                        false,
                        new com.liy.blendlib.fabric.client.render.MissingModelRenderHandle(modelKey, 0L),
                        Optional.empty());
            }
        };
        ModelRenderSnapshot externalSnapshot = snapshot(new TestRenderHandle(KEY, 0L));
        assertSame(ClientGenerationLease.unavailable(), external.acquireGenerationLease(KEY));
        assertFalse(external.acquireGenerationLeaseBinding(KEY, externalSnapshot).managed());
        assertTrue(ClientModelLookup.class
                .getMethod("acquireGenerationLease", BlendModelKey.class)
                .isDefault());
        assertTrue(ClientModelLookup.class
                .getMethod("acquireGenerationLeaseBinding", BlendModelKey.class, ModelRenderSnapshot.class)
                .isDefault());
        assertTrue(List.of(ClientGenerationLease.class.getConstructors()).isEmpty());
        assertTrue(List.of(ClientGenerationLease.class.getDeclaredConstructors()).stream()
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(java.util.Arrays.stream(ClientGenerationLease.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .noneMatch(method -> method.getName().equals("acquire")),
                "no public ClientGenerationLease method may manufacture managed ownership");
        assertTrue(java.util.Arrays.stream(ClientGenerationLeaseBinding.class.getConstructors()).findAny().isEmpty());
        assertTrue(java.util.Arrays.stream(ClientGenerationLeaseBinding.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
                .allMatch(method -> method.getName().equals("unavailable")),
                "the composite has no public managed factory");
        assertEquals(Set.of(
                        "close",
                        "managed",
                        "permitsFrozenCpuRoute",
                        "requireCompatible",
                        "requireExactSnapshot",
                        "requireManagedForPlan",
                        "snapshot",
                        "transferToPlan",
                        "unavailable"),
                java.util.Arrays.stream(ClientGenerationLeaseBinding.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName)
                        .collect(java.util.stream.Collectors.toSet()),
                "the opaque composite must expose validation and close only, never a raw lease extractor");
    }

    @Test
    void existingPublicDescriptorsRemainLinkableAndTheNewSurfaceLeaksNoOwnerOrGpuType() throws Exception {
        assertDoesNotThrow(() -> ModelRenderSnapshot.class.getConstructor(
                ModelRenderHandle.class,
                Transform.class,
                int.class,
                int.class,
                int.class,
                RenderVisibility.class,
                CullingMetadata.class));
        assertDoesNotThrow(() -> ClientModelView.class.getConstructor(
                BlendModelKey.class,
                long.class,
                boolean.class,
                ModelRenderHandle.class,
                Optional.class));
        assertDoesNotThrow(() -> ClientGenerationLeaseBinding.class.getMethod(
                "permitsFrozenCpuRoute", ModelRenderSnapshot.class));
        assertDoesNotThrow(() -> X6PreparedRenderPlanFactory.class.getMethod(
                "prepare",
                X6VariantApplicationPlan.class,
                X6RenderLayerPlan.class,
                X6MaterialPlan.class,
                X6PreparedGeometryCatalog.class,
                X6MaterialProviderGeneration.class,
                ModelRenderSnapshot.class));
        assertDoesNotThrow(() -> X6PreparedRenderPlanFactory.class.getMethod(
                "prepare",
                X6VariantApplicationPlan.class,
                X6RenderLayerPlan.class,
                X6MaterialPlan.class,
                X6PreparedGeometryCatalog.class,
                X6MaterialProviderGeneration.class,
                ModelRenderSnapshot.class,
                X6LifecycleDrainDispatcher.class));
        assertDoesNotThrow(() -> X6PreparedRenderPlanFactory.class.getMethod(
                "prepareManaged",
                X6VariantApplicationPlan.class,
                X6RenderLayerPlan.class,
                X6MaterialPlan.class,
                X6PreparedGeometryCatalog.class,
                X6MaterialProviderGeneration.class,
                ClientGenerationLeaseBinding.class,
                X6LifecycleDrainDispatcher.class));
        assertThrows(NoSuchMethodException.class, () -> X6PreparedRenderPlanFactory.class.getMethod(
                "prepare",
                X6VariantApplicationPlan.class,
                X6RenderLayerPlan.class,
                X6MaterialPlan.class,
                X6PreparedGeometryCatalog.class,
                X6MaterialProviderGeneration.class,
                ClientGenerationLeaseBinding.class,
                X6LifecycleDrainDispatcher.class));
        assertThrows(NoSuchMethodException.class, () -> X6PreparedRenderPlanFactory.class.getMethod(
                "prepare",
                X6VariantApplicationPlan.class,
                X6RenderLayerPlan.class,
                X6MaterialPlan.class,
                X6PreparedGeometryCatalog.class,
                X6MaterialProviderGeneration.class,
                ModelRenderSnapshot.class,
                ClientGenerationLease.class,
                X6LifecycleDrainDispatcher.class));
        assertTrue(List.of(ClientGenerationLease.class.getMethods()).stream()
                .flatMap(method -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(method.getReturnType().getName()),
                        java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName)))
                .noneMatch(type -> type.contains("Gpu")
                        || type.contains("RenderSystem")
                        || type.contains("ClientGenerationResourceOwner")
                        || type.contains("GenerationRenderResourceLease")));
        assertTrue(List.of(ClientGenerationLeaseBinding.class.getMethods()).stream()
                .flatMap(method -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(method.getReturnType().getName()),
                        java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName)))
                .noneMatch(type -> type.contains("Gpu")
                        || type.contains("RenderSystem")
                        || type.contains("ClientGenerationResourceOwner")
                        || type.contains("GenerationRenderResourceLease")));
        assertTrue(List.of(ClientGenerationLeaseBinding.PlanTransferReceipt.class.getConstructors()).isEmpty());
        assertEquals(Set.of("beginDeferredSubmission", "close"), java.util.Arrays.stream(ClientGenerationLeaseBinding.PlanTransferReceipt.class
                        .getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet()),
                "the transfer receipt may expose only its additive opaque submitted-child mint and close");
        assertDoesNotThrow(() -> ClientGenerationLeaseBinding.PlanTransferReceipt.class.getMethod(
                "beginDeferredSubmission", ModelRenderSnapshot.class, X6DrawPrimitive.class));
        assertTrue(List.of(ClientGenerationLeaseBinding.DeferredSubmissionReceipt.class.getConstructors()).isEmpty());
        assertEquals(Set.of("close"), java.util.Arrays.stream(ClientGenerationLeaseBinding.DeferredSubmissionReceipt.class
                        .getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet()),
                "the submitted child receipt must remain close-only and opaque");
    }

    @Test
    void historicalSevenArgumentRawNullCallRemainsSourceUnambiguous() {
        assertThrows(NullPointerException.class, () -> X6PreparedRenderPlanFactory.prepare(
                null, null, null, null, null, null, null));
    }

    @Test
    void currentX6CpuSubmitPathConsumesOnlyPreparedPlanStateAndDoesNotQueryRegistryOrLeaseSources() throws Exception {
        Path submitter = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src/client/java/com/liy/blendlib/fabric/client/render/X6PlanSubmitter.java");
        String source = Files.readString(submitter);
        String hotPath = source.substring(
                source.indexOf("public static void submit("),
                source.indexOf("private static void submitAdmitted("));
        for (String forbidden : List.of(
                "ClientModelRegistry", "ClientModelLookup", "ClientGenerationLease", "resolve(", "java.nio", "java.io", "parse(")) {
            assertFalse(hotPath.contains(forbidden), "X6 CPU submit hot path must not contain " + forbidden);
        }
    }

    private static ModelRegistryGeneration loadedGeneration(
            long generation, BlendModelKey key, TestRenderHandle handle) {
        ModelAsset asset = new ModelAsset(
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
        return new ModelRegistryGeneration(
                generation,
                Map.of(key, new LoadedModelHandle(key, asset, handle)),
                Map.of(),
                List.of());
    }

    private static ModelRenderSnapshot snapshot(TestRenderHandle handle) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
    }

    private record TestRenderHandle(BlendModelKey modelKey, long generation) implements ModelRenderHandle {
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
            return List.of();
        }

        @Override
        public Transform nodeTransform(int nodeIndex) {
            throw new IndexOutOfBoundsException("test handle has no nodes");
        }

        @Override
        public boolean missingModel() {
            return false;
        }
    }
}
