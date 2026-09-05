package com.liy.blendlib.fabric.client.reload;

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
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.internal.x7.X7PolicyMetricsAccess;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Focused T5 proof for same-record CPU/LOD0 projection state without a fake frame producer. */
class X7LivePolicyProjectionTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x7_live_policy:models/base");

    @Test
    void transactionProjectionBecomesVisibleOnlyThroughItsExactActiveD1Record() {
        ClientModelRegistry registry = new ClientModelRegistry();
        TestRenderHandle handle = new TestRenderHandle(KEY, 1L);
        ModelRegistryGeneration candidate = generation(1L, handle);
        X7PublishedGenerationProjection projection = X7PublishedGenerationProjection.cpuOnly(candidate);

        assertSame(candidate, registry.publish(PendingGenerationTransaction.cpuOnly(candidate, projection)));
        ModelHandle modelHandle = candidate.handles().get(KEY);
        X7ProductionPolicyOwner policyOwner = registry.generationResourceOwner().policyOwnerForActive(
                candidate, KEY, modelHandle, handle);
        assertSame(projection, policyOwner.publishedProjection());

        X7PolicyMetricsView metrics = policyOwner.latestMetricsView();
        assertEquals(1L, metrics.generationId());
        assertEquals(X7PolicyMetricsView.NO_COMPLETED_FRAME_ID, metrics.completedFrameId());
        assertEquals(
                X7PreparedFrameProjection.FrameProducerStatus.UNAVAILABLE_NO_VERIFIED_FRAME_BOUNDARY,
                metrics.frameProducerStatus());
        assertEquals(0L, metrics.gpuCandidateSelections());
        assertEquals(1L, metrics.cpuSelections());
        assertEquals(1L, metrics.capabilityUnavailableFallbacks());
        assertEquals(0L, metrics.prepareFailureFallbacks());
        assertEquals(0L, metrics.uploadFailureFallbacks());
        assertEquals(1L, metrics.currentGenerationModels());
        assertEquals(0L, metrics.currentGenerationPrimitives());
        assertEquals(0L, metrics.currentGenerationBones());
        assertEquals(0L, metrics.currentGenerationVertices());
        assertTrue(metrics.actualLodSelectionsByLevel().isEmpty());
        assertTrue(X7PolicyMetricsAccess.latestCompleted().isEmpty(),
                "an unavailable D1 projection must never be promoted to completed metrics");
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.actualLodSelectionsByLevel().put(0, 1L));

        ClientModelView sourceView = ClientModelLookupTestSupport.sourceOwnedLookup(registry).resolve(KEY);
        ModelRenderSnapshot snapshot = snapshot(handle, RenderVisibility.VISIBLE);
        X7PreparedFrameProjection prepared = policyOwner.prepareUnavailableFrame(sourceView, snapshot);
        assertEquals(0, prepared.selectedLodLevel());
        assertFalse(prepared.frameProducerAvailable());
        assertEquals(
                X7PreparedFrameProjection.FrameProducerStatus.UNAVAILABLE_NO_VERIFIED_FRAME_BOUNDARY,
                prepared.frameProducerStatus());
        assertTrue(prepared.permitsCpuRoute(snapshot, sourceView));

        ClientGenerationLeaseBinding binding = ClientModelLookupTestSupport.sourceOwnedBinding(registry, KEY, snapshot);
        assertTrue(binding.managed());
        assertTrue(binding.permitsFrozenCpuRoute(snapshot));
        binding.close();

        assertSame(metrics, policyOwner.latestMetricsView());
        assertEquals(0L, metrics.instanceDraws());
        assertEquals(0L, metrics.instanceCulls());
        assertEquals(0L, metrics.primitiveDraws());
        assertEquals(0L, metrics.primitiveCulls());
        assertEquals(0L, metrics.boneDraws());
        assertEquals(0L, metrics.boneCulls());
        assertEquals(0L, metrics.submittedPrimitiveCount());
        assertEquals(0L, metrics.skippedPrimitiveCount());
        assertEquals(0L, metrics.skippedBoneLayerCount());
    }

    @Test
    void projectionRejectsASameGenerationDifferentHandleCandidate() {
        ModelRegistryGeneration first = generation(3L, new TestRenderHandle(KEY, 3L));
        X7PublishedGenerationProjection firstProjection = X7PublishedGenerationProjection.cpuOnly(first);
        ModelRegistryGeneration sameGenerationDifferentHandle = generation(3L, new TestRenderHandle(KEY, 3L));

        assertThrows(IllegalArgumentException.class,
                () -> PendingGenerationTransaction.cpuOnly(sameGenerationDifferentHandle, firstProjection));
    }

    @Test
    void metricsMaterializationFailureBeforeCasLeavesTheExistingGenerationActive() {
        AtomicInteger materializationCalls = new AtomicInteger();
        AtomicInteger casCalls = new AtomicInteger();
        ClientGenerationResourceOwner.PolicyMetricsMaterializer materializer = (projection, collector) -> {
            if (materializationCalls.incrementAndGet() == 2) {
                throw new OutOfMemoryError("deterministic X7 policy metrics materialization failure");
            }
            return X7PolicyMetricsView.unavailable(projection, collector.liveSnapshot());
        };
        ClientModelRegistry registry = new ClientModelRegistry(
                ModelRegistryGeneration.empty(0L),
                null,
                (active, replacement) -> {
                    casCalls.incrementAndGet();
                    throw new AssertionError("metrics materialization failure must occur before the registry CAS");
                },
                materializer);
        ModelRegistryGeneration existing = registry.current();
        ModelRegistryGeneration candidate = generation(1L, new TestRenderHandle(KEY, 1L));
        PendingGenerationTransaction transaction = PendingGenerationTransaction.cpuOnly(candidate);

        OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, () -> registry.publish(transaction));

        assertEquals("deterministic X7 policy metrics materialization failure", failure.getMessage());
        assertEquals(2, materializationCalls.get());
        assertEquals(0, casCalls.get());
        assertSame(existing, registry.current());
        assertFalse(existing.isRetired());
        assertFalse(candidate.isRetired());
        assertEquals(PendingGenerationTransaction.State.PREPARED_CALLER_OWNED, transaction.state());
        assertEquals(0, registry.resourceLifecycleDiagnostics().outstandingPublicationTransactionCount());
    }

    @Test
    void liveCountersDoNotPromoteFoundationRecommendationsToExecutedActions() {
        X7PolicyMetricsCollector collector = new X7PolicyMetricsCollector();
        collector.recordAnimationWork(X7AnimationWorkPolicy.Mode.REDUCED);
        collector.recordExecutedAnimationWork(X7AnimationWorkPolicy.Mode.FULL);
        collector.recordActualLodSelection(0, false);
        collector.recordActualLodRejection();

        X7PolicyMetricsCollector.LiveSnapshot live = collector.liveSnapshot();
        assertEquals(Map.of(0, 1L), live.actualLodSelectionsByLevel());
        assertEquals(1L, live.lodRejections());
        assertEquals(1L, live.animationFullExecuted());
        assertEquals(0L, live.animationReducedExecuted());
        assertEquals(1L, collector.snapshot().reducedWorkRecommendations());
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

    private static ModelRenderSnapshot snapshot(TestRenderHandle handle, RenderVisibility visibility) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                visibility,
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
