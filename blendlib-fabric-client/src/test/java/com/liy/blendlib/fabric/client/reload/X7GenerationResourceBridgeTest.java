package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class X7GenerationResourceBridgeTest {
    @Test
    void completeAuthoritativeGenerationRequiresExactCoverageAndDerivesDeterministicKeys() {
        ModelRegistryGeneration generation = renderableGeneration(17L, "zeta", "alpha");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        List<X7GpuGenerationKey> keys = inventory.keysInDeterministicOrder();
        assertEquals(4, keys.size());

        List<X7GenerationPerformancePlan.Prepared> reversedPlans = new ArrayList<>();
        for (int index = keys.size() - 1; index >= 0; index--) {
            reversedPlans.add(prepared(inventory, keys.get(index), true));
        }
        X7GenerationResourceBridge.FrozenSelectionSet set =
                X7GenerationResourceBridge.freezeCanonicalSelections(inventory, reversedPlans);
        Map<X7GpuGenerationKey, X7GpuTestFixtures.FakeGpuDevice> devices = new LinkedHashMap<>();
        List<X7GenerationResourceBridge.ResourceAttempt> attempts = new ArrayList<>();
        for (int index = keys.size() - 1; index >= 0; index--) {
            X7GpuGenerationKey key = keys.get(index);
            X7GpuTestFixtures.FakeGpuDevice device = new X7GpuTestFixtures.FakeGpuDevice();
            devices.put(key, device);
            attempts.add(X7GenerationResourceBridge.resourceAttempt(selection(set, key), success(device, key)));
        }

        X7GenerationResourceBridge.Composition composition = X7GenerationResourceBridge.compose(set, attempts);

        assertEquals(X7GenerationResourceBridge.Composition.Route.GPU_COMPLETE, composition.route());
        CompletedGenerationResourceSet aggregate = composition.aggregateOrNull();
        assertSame(generation, aggregate.exactGeneration());
        assertEquals(keys, aggregate.keysInDeterministicOrder());
        assertEquals(8, aggregate.physicalResourceCount());
        assertEquals(432L, aggregate.physicalByteCount());
        assertTrue(aggregate.closeIfStillCallerOwned());
        for (X7GpuTestFixtures.FakeGpuDevice device : devices.values()) {
            assertAllClosedOnce(device);
        }
    }

    @Test
    void authoritativeInventoryRejectsRealPrimitiveOmittedFromFormerSelectionAndPlanInputs() {
        ModelRegistryGeneration generation = renderableGeneration(17L, "actual-model");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        List<X7GpuGenerationKey> keys = inventory.keysInDeterministicOrder();
        assertEquals(2, keys.size(), "missing-model handle contributes both real renderable primitives");

        // There is intentionally no caller-controlled selection collection. The second real primitive
        // is absent from every caller input below, yet inventory coverage still rejects the forgery.
        X7GenerationPerformancePlan.Prepared firstOnly = prepared(inventory, keys.getFirst(), true);
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(
                inventory, List.of(firstOnly)));
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(
                inventory, List.of()));

        List<X7GenerationPerformancePlan.Prepared> subset = new ArrayList<>(preparedPlans(inventory, true));
        subset.removeLast();
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(
                inventory, subset));
    }

    @Test
    void authoritativeProofRejectsExtraDuplicateUnclassifiedCrossGenerationAndIdentityMismatches() {
        ModelRegistryGeneration generation = renderableGeneration(17L, "actual-model");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        List<X7GpuGenerationKey> keys = inventory.keysInDeterministicOrder();
        X7GpuGenerationKey first = keys.getFirst();
        X7GenerationPerformancePlan.Binding binding = inventory.preparationBindingFor(first);

        List<X7GenerationPerformancePlan.Prepared> duplicate = new ArrayList<>(preparedPlans(inventory, true));
        duplicate.add(prepared(inventory, first, true));
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(inventory, duplicate));

        X7GenerationPerformancePlan.Prepared foreign = prepared(new X7GenerationPerformancePlan.Binding(
                generation.generationId(), new Object(), new Object(), new Object(), new Object(), new Object()), true);
        List<X7GenerationPerformancePlan.Prepared> unclassified = new ArrayList<>(preparedPlans(inventory, true));
        unclassified.add(foreign);
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(
                inventory, unclassified));

        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(
                inventory,
                replacePlan(inventory, first, prepared(new X7GenerationPerformancePlan.Binding(
                        generation.generationId() + 1L,
                        binding.handleIdentity(),
                        binding.modelIdentity(),
                        binding.geometryIdentity(),
                        binding.materialIdentity(),
                        binding.lodIdentity()), true))));

        assertIdentityMismatchRejected(inventory, first, new Object(), binding.modelIdentity(), binding.geometryIdentity(),
                binding.materialIdentity(), binding.lodIdentity());
        assertIdentityMismatchRejected(inventory, first, binding.handleIdentity(), new Object(), binding.geometryIdentity(),
                binding.materialIdentity(), binding.lodIdentity());
        assertIdentityMismatchRejected(inventory, first, binding.handleIdentity(), binding.modelIdentity(), new Object(),
                binding.materialIdentity(), binding.lodIdentity());
        assertIdentityMismatchRejected(inventory, first, binding.handleIdentity(), binding.modelIdentity(), binding.geometryIdentity(),
                new Object(), binding.lodIdentity());
        assertIdentityMismatchRejected(inventory, first, binding.handleIdentity(), binding.modelIdentity(), binding.geometryIdentity(),
                binding.materialIdentity(), new Object());
    }

    @Test
    void compositionRejectsOmittedExtraDuplicateAndCpuOnlyWithoutCompleteProof() {
        ModelRegistryGeneration generation = renderableGeneration(17L, "actual-model");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        X7GenerationResourceBridge.FrozenSelectionSet set = freeze(inventory, true);
        List<X7GpuGenerationKey> keys = inventory.keysInDeterministicOrder();
        X7GpuGenerationKey first = keys.getFirst();

        X7GpuTestFixtures.FakeGpuDevice omittedDevice = new X7GpuTestFixtures.FakeGpuDevice();
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.compose(
                set,
                List.of(X7GenerationResourceBridge.resourceAttempt(selection(set, first), success(omittedDevice, first)))));
        assertAllClosedOnce(omittedDevice);

        X7GenerationResourceBridge.AuthoritativeGenerationInventory foreignInventory =
                inventory(renderableGeneration(17L, "foreign-model"));
        X7GenerationResourceBridge.FrozenSelectionSet foreignSet = freeze(foreignInventory, true);
        X7GpuGenerationKey foreignKey = foreignInventory.keysInDeterministicOrder().getFirst();
        X7GpuTestFixtures.FakeGpuDevice extraDevice = new X7GpuTestFixtures.FakeGpuDevice();
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.compose(
                set,
                List.of(X7GenerationResourceBridge.resourceAttempt(
                        selection(foreignSet, foreignKey), success(extraDevice, foreignKey)))));
        assertAllClosedOnce(extraDevice);

        X7GpuTestFixtures.FakeGpuDevice duplicateDevice = new X7GpuTestFixtures.FakeGpuDevice();
        X7GpuResourceFactory.Attempt duplicateAttempt = success(duplicateDevice, first);
        X7GenerationResourceBridge.FrozenSelection firstSelection = selection(set, first);
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.compose(
                set,
                List.of(
                        X7GenerationResourceBridge.resourceAttempt(firstSelection, duplicateAttempt),
                        X7GenerationResourceBridge.resourceAttempt(firstSelection, duplicateAttempt))));
        assertAllClosedOnce(duplicateDevice);

        X7GenerationResourceBridge.AuthoritativeGenerationInventory cpuInventory =
                inventory(renderableGeneration(17L, "cpu-model"));
        X7GenerationResourceBridge.FrozenSelectionSet cpuSet = freeze(cpuInventory, false);
        X7GenerationResourceBridge.Composition cpuOnly = X7GenerationResourceBridge.compose(cpuSet, List.of());
        assertEquals(X7GenerationResourceBridge.Composition.Route.CPU_ONLY, cpuOnly.route());
        assertNull(cpuOnly.aggregateOrNull());
        X7GpuGenerationKey cpuKey = cpuInventory.keysInDeterministicOrder().getFirst();
        X7GpuTestFixtures.FakeGpuDevice cpuAttemptDevice = new X7GpuTestFixtures.FakeGpuDevice();
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.compose(
                cpuSet,
                List.of(X7GenerationResourceBridge.resourceAttempt(
                        selection(cpuSet, cpuKey), success(cpuAttemptDevice, cpuKey)))));
        assertAllClosedOnce(cpuAttemptDevice);

        X7GenerationResourceBridge.AuthoritativeGenerationInventory emptyInventory =
                inventory(ModelRegistryGeneration.empty(17L));
        X7GenerationResourceBridge.FrozenSelectionSet emptySet =
                X7GenerationResourceBridge.freezeCanonicalSelections(emptyInventory, List.of());
        assertEquals(X7GenerationResourceBridge.Composition.Route.CPU_ONLY,
                X7GenerationResourceBridge.compose(emptySet, List.of()).route());
    }

    @Test
    void fullAuthoritativeKeyRejectsModelGeometryMaterialRouteAndLodMismatches() {
        ModelRegistryGeneration generation = renderableGeneration(17L, "actual-model");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        X7GenerationResourceBridge.FrozenSelectionSet set = freeze(inventory, true);
        X7GpuGenerationKey key = inventory.keysInDeterministicOrder().getFirst();
        X7GenerationResourceBridge.FrozenSelection selection = selection(set, key);
        assertEquals(X7GpuVertexFormat.POSITION_NORMAL_UV_F32, key.vertexFormat());
        assertEquals(X7PrimitiveMode.TRIANGLES, key.primitiveMode());
        assertEquals(X7GpuIndexType.UINT32_LE, key.indexType());
        List<X7GpuGenerationKey> mismatches = List.of(
                new X7GpuGenerationKey(key.generation(), "other-model", key.geometryId(), key.materialRoute(), key.lod(),
                        key.vertexFormat(), key.primitiveMode(), key.indexType()),
                new X7GpuGenerationKey(key.generation(), key.modelId(), "other-geometry", key.materialRoute(), key.lod(),
                        key.vertexFormat(), key.primitiveMode(), key.indexType()),
                new X7GpuGenerationKey(key.generation(), key.modelId(), key.geometryId(), "other/material", key.lod(),
                        key.vertexFormat(), key.primitiveMode(), key.indexType()),
                new X7GpuGenerationKey(key.generation(), key.modelId(), key.geometryId(), key.materialRoute(), key.lod() + 1,
                        key.vertexFormat(), key.primitiveMode(), key.indexType()));
        for (X7GpuGenerationKey mismatch : mismatches) {
            ManualAttempt attempt = manualSuccess(mismatch);
            assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.compose(
                    set, List.of(X7GenerationResourceBridge.resourceAttempt(selection, attempt.attempt()))));
            assertEquals(1, attempt.vertex().closeCalls);
            assertEquals(1, attempt.index().closeCalls);
        }
        assertThrows(NullPointerException.class, () -> new X7GpuGenerationKey(
                generation.generationId(), "model", "geometry", "solid/material", 0,
                null, X7PrimitiveMode.TRIANGLES, X7GpuIndexType.UINT32_LE));
        assertThrows(NullPointerException.class, () -> new X7GpuGenerationKey(
                generation.generationId(), "model", "geometry", "solid/material", 0,
                X7GpuVertexFormat.POSITION_NORMAL_UV_F32, null, X7GpuIndexType.UINT32_LE));
        assertThrows(NullPointerException.class, () -> new X7GpuGenerationKey(
                generation.generationId(), "model", "geometry", "solid/material", 0,
                X7GpuVertexFormat.POSITION_NORMAL_UV_F32, X7PrimitiveMode.TRIANGLES, null));
    }

    @Test
    void nthLeafFailureClosesEveryPriorLeafExactlyOnceAndPublishesNoAggregate() {
        ModelRegistryGeneration generation = renderableGeneration(17L, "actual-model");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        X7GenerationResourceBridge.FrozenSelectionSet set = freeze(inventory, true);
        List<X7GpuGenerationKey> keys = inventory.keysInDeterministicOrder();
        X7GpuGenerationKey first = keys.getFirst();
        X7GpuGenerationKey failed = keys.get(1);
        X7GpuTestFixtures.FakeGpuDevice firstDevice = new X7GpuTestFixtures.FakeGpuDevice();
        X7GpuTestFixtures.FakeGpuDevice failedDevice = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException expected = new IllegalStateException("second leaf allocation failed");
        failedDevice.indexFailure = expected;

        RuntimeException observed = assertThrows(RuntimeException.class, () -> X7GenerationResourceBridge.compose(
                set,
                List.of(
                        X7GenerationResourceBridge.resourceAttempt(
                                selection(set, first), success(firstDevice, first)),
                        X7GenerationResourceBridge.resourceAttempt(
                                selection(set, failed), X7GpuResourceFactory.prepare(
                                        failedDevice, failed, X7GpuTestFixtures.staging())))));

        assertSame(expected, observed);
        assertAllClosedOnce(firstDevice);
        assertEquals(1, failedDevice.buffers.getFirst().closeCalls);
        assertEquals(1, failedDevice.vertexCreates);
        assertEquals(1, failedDevice.indexCreates);
    }

    @Test
    void aggregateConstructionPromotesLaterAssertionErrorOrOomeOverNonFatalPrimaryAndCleanup() {
        assertAggregateFatalPromotion(false, new AssertionError("aggregate assertion"));
        assertAggregateFatalPromotion(true, new AssertionError("aggregate assertion first"));
        assertAggregateFatalPromotion(false, new OutOfMemoryError("aggregate oome"));
        assertAggregateFatalPromotion(true, new OutOfMemoryError("aggregate oome first"));
    }

    @Test
    void bridgeCompositionPromotesLaterAssertionErrorOrOomeOverNonFatalPrimaryAndCleanup() {
        assertBridgeFatalPromotion(false, new AssertionError("bridge assertion"));
        assertBridgeFatalPromotion(true, new AssertionError("bridge assertion first"));
        assertBridgeFatalPromotion(false, new OutOfMemoryError("bridge oome"));
        assertBridgeFatalPromotion(true, new OutOfMemoryError("bridge oome first"));
    }

    @Test
    void derivedCountsAndBytesUseCheckedArithmeticAndPartialCloseRetriesOnlyFailedLeaves() {
        ModelRegistryGeneration generation = ModelRegistryGeneration.empty(17L);
        AtomicInteger closed = new AtomicInteger();
        CompletedGenerationResourceSet aggregate = CompletedGenerationResourceSet.complete(
                generation,
                List.of(
                        leaf(key(17L, "a", "geometry-a", "solid/a", 0), 2, 41L, closed::incrementAndGet),
                        leaf(key(17L, "b", "geometry-b", "solid/b", 0), 3, 59L, closed::incrementAndGet)));
        assertEquals(5, aggregate.physicalResourceCount());
        assertEquals(100L, aggregate.physicalByteCount());
        aggregate.closeIfStillCallerOwned();
        assertEquals(2, closed.get());

        AtomicInteger overflowClosed = new AtomicInteger();
        assertThrows(ArithmeticException.class, () -> CompletedGenerationResourceSet.complete(
                generation,
                List.of(
                        leaf(key(17L, "c", "geometry-c", "solid/c", 0), Integer.MAX_VALUE, 0L, overflowClosed::incrementAndGet),
                        leaf(key(17L, "d", "geometry-d", "solid/d", 0), 1, 0L, overflowClosed::incrementAndGet))));
        assertEquals(2, overflowClosed.get());

        AtomicInteger byteOverflowClosed = new AtomicInteger();
        assertThrows(ArithmeticException.class, () -> CompletedGenerationResourceSet.complete(
                generation,
                List.of(
                        leaf(key(17L, "byte-a", "geometry-byte-a", "solid/byte-a", 0), 1, Long.MAX_VALUE,
                                byteOverflowClosed::incrementAndGet),
                        leaf(key(17L, "byte-b", "geometry-byte-b", "solid/byte-b", 0), 1, 1L,
                                byteOverflowClosed::incrementAndGet))));
        assertEquals(2, byteOverflowClosed.get());

        AtomicInteger firstCloseCalls = new AtomicInteger();
        AtomicInteger retryingCloseCalls = new AtomicInteger();
        RuntimeException expected = new IllegalStateException("retry this leaf");
        CompletedGenerationResourceSet retrying = CompletedGenerationResourceSet.complete(
                generation,
                List.of(
                        leaf(key(17L, "e", "geometry-e", "solid/e", 0), 1, 8L, firstCloseCalls::incrementAndGet),
                        leaf(key(17L, "f", "geometry-f", "solid/f", 0), 1, 12L, () -> {
                            if (retryingCloseCalls.incrementAndGet() == 1) {
                                throw expected;
                            }
                        })));
        assertEquals(CompletedGenerationResourceSet.ClaimMove.CLAIMED, retrying.tryClaimCallerOwnedCompleteForD1());
        assertEquals(CompletedGenerationResourceSet.D1Adoption.ADOPTED, retrying.tryAdoptClaimOwnedCompleteByD1());
        RuntimeException observed = assertThrows(RuntimeException.class, retrying::closeFromD1VerifiedCompletion);
        assertSame(expected, observed);
        assertEquals(1, firstCloseCalls.get());
        assertEquals(1, retryingCloseCalls.get());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSE_FAILED, retrying.ownership());
        retrying.closeFromD1VerifiedCompletion();
        assertEquals(1, firstCloseCalls.get());
        assertEquals(2, retryingCloseCalls.get());
        assertEquals(CompletedGenerationResourceSet.Ownership.D1_CLOSED, retrying.ownership());
    }

    private static void assertIdentityMismatchRejected(
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory,
            X7GpuGenerationKey key,
            Object handle,
            Object model,
            Object geometry,
            Object material,
            Object lod) {
        X7GenerationPerformancePlan.Prepared mismatch = prepared(new X7GenerationPerformancePlan.Binding(
                inventory.exactGeneration().generationId(), handle, model, geometry, material, lod), true);
        assertThrows(IllegalArgumentException.class, () -> X7GenerationResourceBridge.freezeCanonicalSelections(
                inventory, replacePlan(inventory, key, mismatch)));
    }

    private static void assertAggregateFatalPromotion(boolean fatalFirst, Error fatal) {
        ModelRegistryGeneration generation = ModelRegistryGeneration.empty(17L);
        RuntimeException primary = new IllegalStateException("aggregate primary");
        RuntimeException runtimeCleanup = new IllegalStateException("aggregate runtime cleanup");
        AtomicInteger runtimeCloseCalls = new AtomicInteger();
        AtomicInteger fatalCloseCalls = new AtomicInteger();
        CompletedGenerationResourceSet.ResourceLeaf runtimeLeaf = leaf(
                key(17L, "runtime", "geometry-runtime", "solid/runtime", 0), 1, 1L, () -> {
                    runtimeCloseCalls.incrementAndGet();
                    throw runtimeCleanup;
                });
        CompletedGenerationResourceSet.ResourceLeaf fatalLeaf = leaf(
                key(17L, "fatal", "geometry-fatal", "solid/fatal", 0), 1, 1L, () -> {
                    fatalCloseCalls.incrementAndGet();
                    throw fatal;
                });
        CompletedGenerationResourceSet.ResourceLeaf primaryLeaf = new CompletedGenerationResourceSet.ResourceLeaf() {
            @Override
            X7GpuGenerationKey key() {
                throw primary;
            }

            @Override
            int physicalResourceCount() {
                return 1;
            }

            @Override
            long physicalByteCount() {
                return 1L;
            }

            @Override
            void closeSynchronouslyAfterVerifiedD1Completion() {
            }
        };
        List<CompletedGenerationResourceSet.ResourceLeaf> leaves = fatalFirst
                ? List.of(fatalLeaf, runtimeLeaf, primaryLeaf)
                : List.of(runtimeLeaf, fatalLeaf, primaryLeaf);
        Error observed = assertThrows(fatal.getClass(), () -> CompletedGenerationResourceSet.complete(generation, leaves));
        assertSame(fatal, observed);
        assertArrayEquals(new Throwable[] {primary, runtimeCleanup}, observed.getSuppressed());
        assertEquals(1, runtimeCloseCalls.get());
        assertEquals(1, fatalCloseCalls.get());
    }

    private static void assertBridgeFatalPromotion(boolean fatalFirst, Error fatal) {
        ModelRegistryGeneration generation = renderableGeneration(17L, "a", "b");
        X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory = inventory(generation);
        List<X7GpuGenerationKey> keys = inventory.keysInDeterministicOrder();
        X7GpuGenerationKey first = keys.get(0);
        X7GpuGenerationKey second = keys.get(1);
        X7GpuGenerationKey failed = keys.get(2);
        List<X7GenerationPerformancePlan.Prepared> plans = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            plans.add(prepared(inventory, keys.get(index), index < 3));
        }
        X7GenerationResourceBridge.FrozenSelectionSet set =
                X7GenerationResourceBridge.freezeCanonicalSelections(inventory, plans);
        X7GpuTestFixtures.FakeGpuDevice firstDevice = new X7GpuTestFixtures.FakeGpuDevice();
        X7GpuTestFixtures.FakeGpuDevice secondDevice = new X7GpuTestFixtures.FakeGpuDevice();
        X7GpuTestFixtures.FakeGpuDevice failedDevice = new X7GpuTestFixtures.FakeGpuDevice();
        RuntimeException primary = new IllegalStateException("bridge primary");
        RuntimeException runtimeCleanup = new IllegalStateException("bridge runtime cleanup");
        X7GpuResourceFactory.Attempt firstAttempt = success(firstDevice, first);
        X7GpuResourceFactory.Attempt secondAttempt = success(secondDevice, second);
        if (fatalFirst) {
            firstDevice.buffers.getFirst().closeFailure = fatal;
            secondDevice.buffers.getFirst().closeFailure = runtimeCleanup;
        } else {
            firstDevice.buffers.getFirst().closeFailure = runtimeCleanup;
            secondDevice.buffers.getFirst().closeFailure = fatal;
        }
        failedDevice.indexFailure = primary;
        X7GpuResourceFactory.Attempt failedAttempt = X7GpuResourceFactory.prepare(
                failedDevice, failed, X7GpuTestFixtures.staging());

        Error observed = assertThrows(fatal.getClass(), () -> X7GenerationResourceBridge.compose(
                set,
                List.of(
                        X7GenerationResourceBridge.resourceAttempt(selection(set, first), firstAttempt),
                        X7GenerationResourceBridge.resourceAttempt(selection(set, second), secondAttempt),
                        X7GenerationResourceBridge.resourceAttempt(selection(set, failed), failedAttempt))));
        assertSame(fatal, observed);
        assertArrayEquals(new Throwable[] {primary, runtimeCleanup}, observed.getSuppressed());
        assertEquals(1, firstDevice.buffers.getFirst().closeCalls);
        assertEquals(1, secondDevice.buffers.getFirst().closeCalls);
        assertEquals(1, failedDevice.buffers.getFirst().closeCalls);
    }

    private static X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory(ModelRegistryGeneration generation) {
        return X7GenerationResourceBridge.authoritativeInventory(generation);
    }

    private static X7GenerationResourceBridge.FrozenSelectionSet freeze(
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory, boolean gpuCandidate) {
        return X7GenerationResourceBridge.freezeCanonicalSelections(inventory, preparedPlans(inventory, gpuCandidate));
    }

    private static List<X7GenerationPerformancePlan.Prepared> preparedPlans(
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory, boolean gpuCandidate) {
        return inventory.keysInDeterministicOrder().stream()
                .map(key -> prepared(inventory, key, gpuCandidate))
                .toList();
    }

    private static List<X7GenerationPerformancePlan.Prepared> replacePlan(
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory,
            X7GpuGenerationKey replacementKey,
            X7GenerationPerformancePlan.Prepared replacement) {
        List<X7GenerationPerformancePlan.Prepared> plans = new ArrayList<>(preparedPlans(inventory, true));
        int index = inventory.keysInDeterministicOrder().indexOf(replacementKey);
        if (index < 0) {
            throw new IllegalArgumentException("replacement key must be authoritative");
        }
        plans.set(index, replacement);
        return plans;
    }

    private static X7GenerationResourceBridge.FrozenSelection selection(
            X7GenerationResourceBridge.FrozenSelectionSet set, X7GpuGenerationKey key) {
        return set.selectionsInDeterministicOrder().stream()
                .filter(selection -> selection.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static ModelRegistryGeneration renderableGeneration(long generationId, String... modelPaths) {
        Map<BlendModelKey, ModelHandle> handles = new LinkedHashMap<>();
        for (String modelPath : modelPaths) {
            BlendModelKey key = BlendModelKey.of("test", modelPath);
            handles.put(key, MissingModelHandle.notDiscovered(key, generationId));
        }
        return new ModelRegistryGeneration(generationId, handles, Map.of(), List.of());
    }

    private static X7GenerationPerformancePlan.Prepared prepared(
            X7GenerationResourceBridge.AuthoritativeGenerationInventory inventory,
            X7GpuGenerationKey key,
            boolean gpuCandidate) {
        return prepared(inventory.preparationBindingFor(key), gpuCandidate);
    }

    private static X7GenerationPerformancePlan.Prepared prepared(
            X7GenerationPerformancePlan.Binding binding, boolean gpuCandidate) {
        return X7GenerationPerformancePlan.prepare(
                binding,
                gpuCandidate
                        ? X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady()
                        : X7GenerationPerformancePlan.CapabilitySnapshot.cpuOnly());
    }

    private static X7GpuResourceFactory.Attempt success(
            X7GpuTestFixtures.FakeGpuDevice device, X7GpuGenerationKey key) {
        return X7GpuResourceFactory.prepare(device, key, X7GpuTestFixtures.staging());
    }

    private static ManualAttempt manualSuccess(X7GpuGenerationKey key) {
        X7GpuTestFixtures.FakeGpuBuffer vertex = new X7GpuTestFixtures.FakeGpuBuffer("manual-vertex");
        X7GpuTestFixtures.FakeGpuBuffer index = new X7GpuTestFixtures.FakeGpuBuffer("manual-index");
        X7SharedGeometryResources resources = new X7SharedGeometryResources(
                key,
                key.vertexFormat(),
                key.indexType(),
                key.primitiveMode(),
                3,
                3,
                96,
                12,
                vertex,
                index);
        return new ManualAttempt(X7GpuResourceFactory.Attempt.completed(resources), vertex, index);
    }

    private static CompletedGenerationResourceSet.ResourceLeaf leaf(
            X7GpuGenerationKey key,
            int count,
            long bytes,
            CompletedGenerationResourceSet.PhysicalLeafClose close) {
        return CompletedGenerationResourceSet.leaf(key, count, bytes, close);
    }

    private static X7GpuGenerationKey key(
            long generation, String model, String geometry, String route, int lod) {
        return new X7GpuGenerationKey(
                generation,
                model,
                geometry,
                route,
                lod,
                X7GpuVertexFormat.POSITION_NORMAL_UV_F32,
                X7PrimitiveMode.TRIANGLES,
                X7GpuIndexType.UINT32_LE);
    }

    private static void assertAllClosedOnce(X7GpuTestFixtures.FakeGpuDevice device) {
        assertEquals(2, device.buffers.size());
        assertEquals(1, device.buffers.get(0).closeCalls);
        assertEquals(1, device.buffers.get(1).closeCalls);
    }

    private record ManualAttempt(
            X7GpuResourceFactory.Attempt attempt,
            X7GpuTestFixtures.FakeGpuBuffer vertex,
            X7GpuTestFixtures.FakeGpuBuffer index) {
    }
}
