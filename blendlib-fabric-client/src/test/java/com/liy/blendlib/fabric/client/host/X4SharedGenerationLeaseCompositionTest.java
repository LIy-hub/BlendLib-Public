package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.ClientModelLookupTestSupport;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.MissingModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** D2a X4 parent-lease composition without changing the snapshot's immutable shape. */
class X4SharedGenerationLeaseCompositionTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_shared_lease_test:models/host");

    @Test
    void registryParentSurvivesSupersedeUntilThePreparedSnapshotCloses() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle firstHandle = new TestRenderHandle(KEY, 1L);
        registry.publish(loadedGeneration(1L, firstHandle));
        ClientModelLookup lookup = ClientModelLookupTestSupport.sourceOwnedLookup(registry);
        X4HostIdentity identity = new X4HostIdentity(
                BlendResourceId.parse("x4_shared_lease_test:scope"),
                BlendResourceId.parse("x4_shared_lease_test:host/world"));
        X4HostAdapter<X4HostFrames.WorldObject> adapter = X4HostAdapters.worldObject(
                        lookup,
                        new BlendRenderer((snapshot, context) -> { }),
                        X4LifecycleTestSupport.published(1L).session())
                .model(KEY)
                .identity(identity)
                .configuration(new X4HostConfigurations.WorldObject(
                        identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();
        adapter.freeze();
        X4PreparedSnapshot prepared = adapter.prepare(new X4HostFrames.WorldObject(
                X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, 0x00F000F0, 0, 0xFFFFFFFF, true),
                identity.scope(),
                0L));
        assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry));

        registry.publish(loadedGeneration(2L, new TestRenderHandle(KEY, 2L)));
        assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                "supersede cannot close the D1 parent while X4 still owns its prepared snapshot");

        prepared.close();
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
    }

    @Test
    void realRegistryBackedAbsentKeyPreparesStructuredMissingFallbackWithoutAD1Parent() {
        long generation = 3L;
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(ModelRegistryGeneration.empty(generation));
        X4LifecycleTestSupport.SessionHarness lifecycle = X4LifecycleTestSupport.published(generation);
        WorldObjectFixture fixture = worldObjectAdapter(
                ClientModelLookupTestSupport.sourceOwnedLookup(registry), lifecycle, "host/absent");

        fixture.adapter().freeze();
        X4PreparedSnapshot prepared = fixture.adapter().prepare(unresolvedFrame(fixture.identity(), 0L));
        assertTrue(prepared.missingModel());
        assertEquals(generation, prepared.missingDiagnostic().orElseThrow().generation());
        assertEquals(BlendDiagnosticCodes.DESC_002,
                prepared.missingDiagnostic().orElseThrow().diagnostic().code());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                "an absent key has no D1 render-resource parent to retain");

        prepared.close();
        lifecycle.session().retire();
        assertEquals(1, lifecycle.provider().retireCalls());
        assertEquals(1, lifecycle.provider().closeCalls());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
    }

    @Test
    void realRegistryBackedMapOwnedMissingPreparesStructuredFallbackWithoutAD1Parent() {
        long generation = 4L;
        MissingModelHandle missing = MissingModelHandle.notDiscovered(KEY, generation);
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(new ModelRegistryGeneration(
                generation,
                Map.of(KEY, missing),
                Map.of(KEY, missing.diagnostic()),
                List.of()));
        X4LifecycleTestSupport.SessionHarness lifecycle = X4LifecycleTestSupport.published(generation);
        WorldObjectFixture fixture = worldObjectAdapter(
                ClientModelLookupTestSupport.sourceOwnedLookup(registry), lifecycle, "host/map_missing");

        fixture.adapter().freeze();
        X4PreparedSnapshot prepared = fixture.adapter().prepare(unresolvedFrame(fixture.identity(), 0L));
        assertTrue(prepared.missingModel());
        assertEquals(generation, prepared.missingDiagnostic().orElseThrow().generation());
        assertEquals(BlendDiagnosticCodes.DESC_002,
                prepared.missingDiagnostic().orElseThrow().diagnostic().code());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                "a map-owned missing handle also has no D1 render-resource parent");

        prepared.close();
        lifecycle.session().retire();
        assertEquals(1, lifecycle.provider().retireCalls());
        assertEquals(1, lifecycle.provider().closeCalls());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
    }

    @Test
    void loadedSupersedeRaceNeverDowngradesARealX4BindingToMissingFallback() {
        long firstGeneration = 5L;
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(loadedGeneration(firstGeneration, new TestRenderHandle(KEY, firstGeneration)));
        AtomicBoolean superseded = new AtomicBoolean();
        ClientModelLookup lookup = ClientModelLookupTestSupport.sourceOwnedLookup(registry, () -> {
            if (superseded.compareAndSet(false, true)) {
                registry.publish(ModelRegistryGeneration.empty(firstGeneration + 1L));
            }
        });
        X4LifecycleTestSupport.SessionHarness lifecycle = X4LifecycleTestSupport.published(firstGeneration);
        WorldObjectFixture fixture = worldObjectAdapter(lookup, lifecycle, "host/loaded_supersede");

        fixture.adapter().freeze();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.adapter().prepare(unresolvedFrame(fixture.identity(), 0L)));
        assertTrue(superseded.get());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                "the retired loaded D1 parent must be released after the fail-closed source race");
        assertEquals(0, lifecycle.provider().retireCalls(), "X4 must fail before X1 provider pinning");
        assertEquals(0, lifecycle.provider().closeCalls());

        lifecycle.session().retire();
        assertEquals(1, lifecycle.provider().retireCalls());
        assertEquals(1, lifecycle.provider().closeCalls());
    }

    @Test
    void x4FrozenSubmissionGateHonorsPreparedVisibilityEvenWhenMetadataIsNotCullable() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle handle = new TestRenderHandle(KEY, 6L);
        registry.publish(loadedGeneration(6L, handle));
        X4LifecycleTestSupport.SessionHarness lifecycle = X4LifecycleTestSupport.published(6L);
        WorldObjectFixture fixture = worldObjectAdapter(
                ClientModelLookupTestSupport.sourceOwnedLookup(registry), lifecycle, "host/culled_metadata");
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                RenderVisibility.CULLED,
                new CullingMetadata(handle.bounds(), false));

        fixture.adapter().freeze();
        X4PreparedSnapshot prepared = fixture.adapter().prepare(new X4HostFrames.WorldObject(
                X4SnapshotFrame.extracted(
                        fixture.identity(), X4Transform.IDENTITY, 0x00F000F0, 0, 0xFFFFFFFF, false, snapshot),
                fixture.identity().scope(),
                0L));

        assertFalse(prepared.permitsFrozenCpuSubmission(),
                "RenderVisibility remains the one completed instance decision; cullable is not a competing truth");
        prepared.close();
        lifecycle.session().retire();
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
    }

    @Test
    void sameRawHandleWrapperCannotTransferAnotherLookupParentIntoDefaultX4Preparation() {
        TestRenderHandle sharedHandle = new TestRenderHandle(KEY, 1L);
        ClientModelRegistry sourceRegistry = new ClientModelRegistry();
        sourceRegistry.publish(loadedGeneration(1L, sharedHandle));
        ClientModelLookup trustedSource = ClientModelLookupTestSupport.sourceOwnedLookup(sourceRegistry);

        ClientModelRegistry foreignRegistry = new ClientModelRegistry();
        foreignRegistry.publish(loadedGeneration(1L, sharedHandle));
        ClientModelView foreignView = new ClientModelView(KEY, 1L, true, sharedHandle, Optional.empty());
        ClientModelLookup fakeForeignWrapper = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(1L, Map.of(KEY, foreignView), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                assertEquals(KEY, modelKey);
                return foreignView;
            }

            @Override
            public ClientGenerationLeaseBinding acquireGenerationLeaseBinding(
                    BlendModelKey modelKey, com.liy.blendlib.fabric.client.render.ModelRenderSnapshot snapshot) {
                return trustedSource.acquireGenerationLeaseBinding(modelKey, snapshot);
            }
        };
        X4LifecycleTestSupport.SessionHarness lifecycle = X4LifecycleTestSupport.published(1L);
        X4HostIdentity identity = new X4HostIdentity(
                BlendResourceId.parse("x4_shared_lease_test:scope"),
                BlendResourceId.parse("x4_shared_lease_test:host/wrapper"));
        X4HostAdapter<X4HostFrames.WorldObject> adapter = X4HostAdapters.worldObject(
                        fakeForeignWrapper,
                        new BlendRenderer((snapshot, context) -> { }),
                        lifecycle.session())
                .model(KEY)
                .identity(identity)
                .configuration(new X4HostConfigurations.WorldObject(
                        identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();

        adapter.freeze();
        assertThrows(IllegalArgumentException.class, () -> adapter.prepare(new X4HostFrames.WorldObject(
                X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, 0x00F000F0, 0, 0xFFFFFFFF, true),
                identity.scope(),
                0L)));
        assertEquals(X4HostLifecycleState.FROZEN, adapter.state());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(sourceRegistry),
                "failed wrapper admission must close its delegated source parent before X1 pinning");
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(foreignRegistry),
                "an external foreign registry has no trusted managed issuer to transfer");
    }

    private static WorldObjectFixture worldObjectAdapter(
            ClientModelLookup lookup, X4LifecycleTestSupport.SessionHarness lifecycle, String hostPath) {
        X4HostIdentity identity = new X4HostIdentity(
                BlendResourceId.parse("x4_shared_lease_test:scope"),
                BlendResourceId.parse("x4_shared_lease_test:" + hostPath));
        X4HostAdapter<X4HostFrames.WorldObject> adapter = X4HostAdapters.worldObject(
                        lookup,
                        new BlendRenderer((snapshot, context) -> { }),
                        lifecycle.session())
                .model(KEY)
                .identity(identity)
                .configuration(new X4HostConfigurations.WorldObject(
                        identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();
        return new WorldObjectFixture(adapter, identity);
    }

    private static X4HostFrames.WorldObject unresolvedFrame(X4HostIdentity identity, long frameSequence) {
        return new X4HostFrames.WorldObject(
                X4SnapshotFrame.unresolved(identity, X4Transform.IDENTITY, 0x00F000F0, 0, 0xFFFFFFFF, true),
                identity.scope(),
                frameSequence);
    }

    private static ModelRegistryGeneration loadedGeneration(long generation, TestRenderHandle handle) {
        ModelAsset asset = new ModelAsset(
                KEY.resourceId(),
                KEY.descriptorResourceId(),
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
                Map.of(KEY, new LoadedModelHandle(KEY, asset, handle)),
                Map.of(),
                List.of());
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

    private record WorldObjectFixture(X4HostAdapter<X4HostFrames.WorldObject> adapter, X4HostIdentity identity) {
    }
}
