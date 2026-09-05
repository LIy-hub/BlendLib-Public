package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class X7GenerationPerformancePlanTest {
    @Test
    void selectsCpuFallbackForEveryPreparationFailureBeforePublication() {
        X7GenerationPerformancePlan.Binding binding = binding(7L);

        List<BackendCase> cases = List.of(
                new BackendCase(new X7GenerationPerformancePlan.CapabilitySnapshot(false, true, true),
                        X7GenerationPerformancePlan.BackendChoice.CPU,
                        X7GenerationPerformancePlan.FallbackReason.CAPABILITY_UNAVAILABLE),
                new BackendCase(new X7GenerationPerformancePlan.CapabilitySnapshot(true, false, true),
                        X7GenerationPerformancePlan.BackendChoice.CPU,
                        X7GenerationPerformancePlan.FallbackReason.PREPARE_FAILED),
                new BackendCase(new X7GenerationPerformancePlan.CapabilitySnapshot(true, true, false),
                        X7GenerationPerformancePlan.BackendChoice.CPU,
                        X7GenerationPerformancePlan.FallbackReason.UPLOAD_FAILED),
                new BackendCase(X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady(),
                        X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE,
                        X7GenerationPerformancePlan.FallbackReason.NONE));

        for (BackendCase backendCase : cases) {
            X7GenerationPerformancePlan.Prepared prepared = X7GenerationPerformancePlan.prepare(
                    binding, backendCase.capabilities());
            X7GenerationPerformancePlan.Published published = prepared.publish();

            assertSame(binding, prepared.binding());
            assertSame(binding, published.binding());
            assertEquals(backendCase.backend(), prepared.backend());
            assertEquals(backendCase.fallbackReason(), prepared.fallbackReason());
            assertEquals(backendCase.backend(), published.backend());
            assertEquals(backendCase.fallbackReason(), published.fallbackReason());
            requireCurrent(published, binding);
        }
    }

    @Test
    void publishedPlanRejectsStaleOrCrossIdentitySubmitWithoutAnyBackendSwitch() {
        X7GenerationPerformancePlan.Binding binding = binding(12L);
        X7GenerationPerformancePlan.Published published = X7GenerationPerformancePlan
                .prepare(binding, X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady())
                .publish();

        requireCurrent(published, binding);
        requireCurrent(published, binding);
        assertEquals(X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE, published.backend());
        assertEquals(X7GenerationPerformancePlan.FallbackReason.NONE, published.fallbackReason());

        assertThrows(IllegalStateException.class, () -> published.requireCurrentSubmitBinding(
                13L,
                binding.handleIdentity(),
                binding.modelIdentity(),
                binding.geometryIdentity(),
                binding.materialIdentity(),
                binding.lodIdentity()));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentSubmitBinding(
                binding.generation(),
                new Object(),
                binding.modelIdentity(),
                binding.geometryIdentity(),
                binding.materialIdentity(),
                binding.lodIdentity()));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentSubmitBinding(
                binding.generation(),
                binding.handleIdentity(),
                new Object(),
                binding.geometryIdentity(),
                binding.materialIdentity(),
                binding.lodIdentity()));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentSubmitBinding(
                binding.generation(),
                binding.handleIdentity(),
                binding.modelIdentity(),
                new Object(),
                binding.materialIdentity(),
                binding.lodIdentity()));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentSubmitBinding(
                binding.generation(),
                binding.handleIdentity(),
                binding.modelIdentity(),
                binding.geometryIdentity(),
                new Object(),
                binding.lodIdentity()));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentSubmitBinding(
                binding.generation(),
                binding.handleIdentity(),
                binding.modelIdentity(),
                binding.geometryIdentity(),
                binding.materialIdentity(),
                new Object()));
        assertEquals(X7GenerationPerformancePlan.BackendChoice.GPU_CANDIDATE, published.backend());
    }

    @Test
    void privateProofConstructorsLeaveNoNormalSamePackageConstructionEntry() {
        assertConstructionOwnedByOuter(X7GenerationPerformancePlan.Prepared.class);
        assertConstructionOwnedByOuter(X7GenerationPerformancePlan.Published.class);

        Class<?> decisionProof = Arrays.stream(X7GenerationPerformancePlan.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("DecisionProof"))
                .findFirst()
                .orElseThrow();
        assertTrue(Modifier.isPrivate(decisionProof.getModifiers()));
        assertTrue(Arrays.stream(decisionProof.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(X7GenerationPerformancePlan.Published.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("requireCurrentSubmitBinding"))
                .noneMatch(method -> Arrays.asList(method.getParameterTypes())
                        .contains(X7GenerationPerformancePlan.BackendChoice.class)));
    }

    @Test
    void cpuOnlySnapshotProducesNoPartialGpuPublication() {
        X7GenerationPerformancePlan.Binding binding = binding(4L);
        X7GenerationPerformancePlan.Published published = X7GenerationPerformancePlan
                .prepare(binding, X7GenerationPerformancePlan.CapabilitySnapshot.cpuOnly())
                .publish();

        assertEquals(X7GenerationPerformancePlan.BackendChoice.CPU, published.backend());
        assertEquals(X7GenerationPerformancePlan.FallbackReason.CAPABILITY_UNAVAILABLE, published.fallbackReason());
        requireCurrent(published, binding);
    }

    @Test
    void cpuSubsetFreezesOnlyTheAvailableSourceIdentitiesAndCannotPublishGpu() {
        Object handle = new Object();
        Object source = new Object();
        X7GenerationPerformancePlan.CpuSubsetBinding binding =
                new X7GenerationPerformancePlan.CpuSubsetBinding(21L, handle, source);

        X7GenerationPerformancePlan.CpuSubsetPrepared prepared = X7GenerationPerformancePlan
                .prepareCpuOnly(binding);
        X7GenerationPerformancePlan.CpuSubsetPublished published = prepared.publish();

        assertSame(binding, prepared.binding());
        assertSame(binding, published.binding());
        assertEquals(X7GenerationPerformancePlan.BackendChoice.CPU, prepared.backend());
        assertEquals(X7GenerationPerformancePlan.FallbackReason.CAPABILITY_UNAVAILABLE, prepared.fallbackReason());
        assertEquals(X7GenerationPerformancePlan.BackendChoice.CPU, published.backend());
        assertEquals(X7GenerationPerformancePlan.FallbackReason.CAPABILITY_UNAVAILABLE, published.fallbackReason());
        published.requireCurrentCpuBinding(21L, handle, source);
        assertThrows(IllegalStateException.class, () -> published.requireCurrentCpuBinding(22L, handle, source));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentCpuBinding(21L, new Object(), source));
        assertThrows(IllegalStateException.class, () -> published.requireCurrentCpuBinding(21L, handle, new Object()));

        X7PolicyMetricsCollector metrics = new X7PolicyMetricsCollector();
        metrics.recordBackend(published);
        assertEquals(1L, metrics.snapshot().cpuSelections());
        assertEquals(1L, metrics.snapshot().capabilityUnavailableFallbacks());

        assertConstructionOwnedByOuter(X7GenerationPerformancePlan.CpuSubsetPrepared.class);
        assertConstructionOwnedByOuter(X7GenerationPerformancePlan.CpuSubsetPublished.class);
    }

    private static void assertConstructionOwnedByOuter(Class<?> type) {
        assertSame(X7GenerationPerformancePlan.class, type.getNestHost());
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertFalse(constructors.length == 0);
        for (Constructor<?> constructor : constructors) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            assertFalse(Arrays.asList(constructor.getParameterTypes())
                    .contains(X7GenerationPerformancePlan.BackendChoice.class));
        }
        assertFalse(Arrays.stream(constructors)
                .anyMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(Modifier.isStatic(method.getModifiers()) && method.getReturnType() == type,
                    () -> type.getSimpleName() + " must not expose an alternate package factory");
        }
    }

    private static X7GenerationPerformancePlan.Binding binding(long generation) {
        return new X7GenerationPerformancePlan.Binding(
                generation, new Object(), new Object(), new Object(), new Object(), new Object());
    }

    private static void requireCurrent(
            X7GenerationPerformancePlan.Published published, X7GenerationPerformancePlan.Binding binding) {
        published.requireCurrentSubmitBinding(
                binding.generation(),
                binding.handleIdentity(),
                binding.modelIdentity(),
                binding.geometryIdentity(),
                binding.materialIdentity(),
                binding.lodIdentity());
    }

    private record BackendCase(
            X7GenerationPerformancePlan.CapabilitySnapshot capabilities,
            X7GenerationPerformancePlan.BackendChoice backend,
            X7GenerationPerformancePlan.FallbackReason fallbackReason) {
    }
}
