package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D2b source-owned CPU-route composition without exporting any X7 policy type. */
class X7CpuPolicyProjectionCompositionTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x7_cpu_projection:models/base");

    @Test
    void sourceBoundBindingFreezesCpuRouteOnceForItsExactVisibleOrCulledSnapshot() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle handle = new TestRenderHandle(KEY, 1L);
        registry.publish(generation(1L, handle));

        ModelRenderSnapshot visible = snapshot(handle, RenderVisibility.VISIBLE, true);
        ClientGenerationLeaseBinding visibleBinding = ClientModelLookupTestSupport.sourceOwnedBinding(registry, KEY, visible);
        assertTrue(visibleBinding.managed());
        assertTrue(visibleBinding.permitsFrozenCpuRoute(visible));
        assertTrue(visibleBinding.permitsFrozenCpuRoute(visible), "re-reading the frozen primitive must not re-admit D1");
        assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        visibleBinding.close();
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));

        ModelRenderSnapshot culledWithNonCullableMetadata = snapshot(handle, RenderVisibility.CULLED, false);
        ClientGenerationLeaseBinding culledBinding = ClientModelLookupTestSupport.sourceOwnedBinding(
                registry, KEY, culledWithNonCullableMetadata);
        assertTrue(culledBinding.permitsFrozenCpuRoute(culledWithNonCullableMetadata),
                "CPU route and prepared visibility are separate: the route stays CPU while the snapshot remains culled");
        assertEquals(RenderVisibility.CULLED, culledWithNonCullableMetadata.visibility());
        culledBinding.close();
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
    }

    @Test
    void frozenRouteRejectsCrossSnapshotAndClosedBindingWithoutUnmanagedDowngrade() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle handle = new TestRenderHandle(KEY, 2L);
        registry.publish(generation(2L, handle));
        ModelRenderSnapshot snapshot = snapshot(handle, RenderVisibility.VISIBLE, true);
        ClientGenerationLeaseBinding binding = ClientModelLookupTestSupport.sourceOwnedBinding(registry, KEY, snapshot);

        assertThrows(IllegalArgumentException.class,
                () -> binding.permitsFrozenCpuRoute(snapshot(new TestRenderHandle(KEY, 2L), RenderVisibility.VISIBLE, true)));
        binding.close();
        assertThrows(IllegalStateException.class, () -> binding.permitsFrozenCpuRoute(snapshot));

        TestRenderHandle replacement = new TestRenderHandle(KEY, 3L);
        registry.publish(generation(3L, replacement));
        assertThrows(IllegalArgumentException.class,
                () -> ClientModelLookupTestSupport.sourceOwnedBinding(registry, KEY, snapshot),
                "a retired loaded source sample must fail closed instead of becoming an unmanaged route");
    }

    @Test
    void explicitExternalFallbackRetainsCpuCompatibilityButNeverClaimsManagedOwnership() {
        TestRenderHandle handle = new TestRenderHandle(KEY, 4L);
        ModelRenderSnapshot snapshot = snapshot(handle, RenderVisibility.VISIBLE, true);
        ClientGenerationLeaseBinding external = ClientGenerationLeaseBinding.unavailable(snapshot);

        assertFalse(external.managed());
        assertDoesNotThrow(() -> external.requireExactSnapshot(snapshot));
        assertTrue(external.permitsFrozenCpuRoute(snapshot));
        external.close();
        assertThrows(IllegalStateException.class, () -> external.permitsFrozenCpuRoute(snapshot));
    }

    private static ModelRegistryGeneration generation(long generation, TestRenderHandle handle) {
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
                generation, Map.of(KEY, new LoadedModelHandle(KEY, asset, handle)), Map.of(), List.of());
    }

    private static ModelRenderSnapshot snapshot(
            TestRenderHandle handle, RenderVisibility visibility, boolean cullable) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                visibility,
                new CullingMetadata(handle.bounds(), cullable));
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
