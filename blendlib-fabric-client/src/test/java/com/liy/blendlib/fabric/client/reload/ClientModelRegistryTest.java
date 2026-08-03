package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientModelRegistryTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("reload_test:fixtures/static_model");

    @Test
    void publishAtomicallySwapsACompleteImmutableGenerationAndRetiresTheOldOne() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration before = registry.current();
        Map<BlendModelKey, ModelHandle> mutableHandles = new LinkedHashMap<>();
        BlendDiagnostic diagnostic = diagnostic(KEY);
        mutableHandles.put(KEY, MissingModelHandle.failed(KEY, 1L, diagnostic));
        ModelRegistryGeneration candidate = new ModelRegistryGeneration(1L, mutableHandles, Map.of(KEY, diagnostic), List.of());
        mutableHandles.clear();

        assertSame(candidate, registry.publish(candidate));
        assertSame(candidate, registry.current());
        assertTrue(before.isRetired());
        assertFalse(candidate.isRetired());
        assertEquals(1, candidate.handles().size());
        assertThrows(UnsupportedOperationException.class, () -> candidate.handles().clear());
    }

    @Test
    void stalePreparedGenerationCannotReplaceANewerOneAndIsRetired() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration newer = missingGeneration(2L, KEY);
        ModelRegistryGeneration stale = missingGeneration(1L, KEY);

        registry.publish(newer);
        assertSame(newer, registry.publish(stale));
        assertSame(newer, registry.current());
        assertTrue(stale.isRetired());
        assertFalse(newer.isRetired());
        assertEquals(3L, registry.reserveNextGenerationId());
    }

    @Test
    void retentionMetricsReleaseRegistryOwnershipWithoutInvalidatingCapturedGenerations() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration first = missingGeneration(1L, KEY);
        registry.publish(first);
        ModelHandle capturedHandle = first.find(KEY).orElseThrow();

        ModelRegistryGeneration second = missingGeneration(2L, KEY);
        registry.publish(second);
        ModelRegistryGeneration stale = missingGeneration(1L, KEY);
        registry.publish(stale);

        ReloadRetentionMetrics metrics = registry.reloadRetentionMetrics();
        assertSame(second, registry.current());
        assertTrue(first.isRetired());
        assertTrue(stale.isRetired());
        assertSame(capturedHandle, first.find(KEY).orElseThrow());
        assertEquals(2L, metrics.activeGenerationId());
        assertEquals(1, metrics.activeBackendHandleCount());
        assertEquals(0, metrics.activeLoadedBackendHandleCount());
        assertEquals(1, metrics.activeMissingBackendHandleCount());
        assertEquals(0, metrics.registryRetainedRetiredBackendHandleCount());
        assertEquals(1, metrics.mostRecentlyRetiredBackendHandleCount());
        assertEquals(3L, metrics.retiredGenerationCount());
        assertEquals(1L, metrics.staleGenerationCount());
        assertEquals(1, metrics.peakActiveBackendHandleCount());
    }

    @Test
    void findReturnsOnlyPrebuiltHandlesAndDoesNotGrowGenerationDiagnosticsForAnUnknownKey() {
        ClientModelRegistry registry = new ClientModelRegistry();
        ModelRegistryGeneration generation = missingGeneration(1L, KEY);
        registry.publish(generation);
        BlendModelKey absent = BlendModelKey.parse("reload_test:fixtures/not_discovered");

        assertTrue(registry.find(absent).isEmpty());
        assertTrue(registry.find(absent).isEmpty());
        assertSame(generation, registry.current());
        assertEquals(1, generation.diagnostics().size());
        assertTrue(generation.primaryDiagnostic(KEY).isPresent());
        assertTrue(generation.primaryDiagnostic(absent).isEmpty());
    }

    private static ModelRegistryGeneration missingGeneration(long generationId, BlendModelKey key) {
        BlendDiagnostic diagnostic = diagnostic(key);
        return new ModelRegistryGeneration(
                generationId,
                Map.of(key, MissingModelHandle.failed(key, generationId, diagnostic)),
                Map.of(key, diagnostic),
                List.of());
    }

    private static BlendDiagnostic diagnostic(BlendModelKey key) {
        return BlendDiagnostic.error(
                BlendDiagnosticCodes.DESC_002,
                key.resourceId(),
                key.descriptorResourceId(),
                "/",
                "fixture load failure");
    }
}
