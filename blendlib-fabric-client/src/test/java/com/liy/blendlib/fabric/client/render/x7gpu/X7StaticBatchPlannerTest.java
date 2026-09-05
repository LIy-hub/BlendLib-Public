package com.liy.blendlib.fabric.client.render.x7gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class X7StaticBatchPlannerTest {
    @Test
    void preservesInputOrderAndNeverMergesAcrossAnyCompatibilityKeyBoundary() {
        X7StaticBatchKey base = key(3L, "model-a", "geometry-a", "solid", "material-a", 0,
                X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32);
        List<X7StaticBatchInput> inputs = List.of(
                new X7StaticBatchInput(base, "a-1"),
                new X7StaticBatchInput(base, "a-2"),
                new X7StaticBatchInput(key(4L, "model-a", "geometry-a", "solid", "material-a", 0,
                        X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "generation"),
                new X7StaticBatchInput(key(3L, "model-b", "geometry-a", "solid", "material-a", 0,
                        X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "model"),
                new X7StaticBatchInput(key(3L, "model-a", "geometry-a", "solid", "material-b", 0,
                        X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "material"),
                new X7StaticBatchInput(key(3L, "model-a", "geometry-a", "translucent", "material-a", 0,
                        X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "route"),
                new X7StaticBatchInput(key(3L, "model-a", "geometry-a", "solid", "material-a", 1,
                        X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "lod"),
                new X7StaticBatchInput(key(3L, "model-a", "geometry-a", "solid", "material-a", 0,
                        new X7StaticBatchVertexFormat("OTHER_FROZEN_LAYOUT", 24)), "format"),
                new X7StaticBatchInput(base, "a-3"));

        X7StaticBatchPlan plan = X7StaticBatchPlanner.plan(inputs, X7StaticBatchLimits.STRICT_V1);

        assertEquals(X7StaticBatchPlan.SubmissionKind.DRAW_MULTIPLE_INDEXED_CANDIDATE_WAITING, plan.submissionKind());
        assertEquals(8, plan.batches().size());
        assertEquals(List.of("a-1", "a-2"), plan.batches().get(0).instanceIds());
        assertEquals("generation", plan.batches().get(1).instanceIds().getFirst());
        assertEquals("model", plan.batches().get(2).instanceIds().getFirst());
        assertEquals("material", plan.batches().get(3).instanceIds().getFirst());
        assertEquals("route", plan.batches().get(4).instanceIds().getFirst());
        assertEquals("lod", plan.batches().get(5).instanceIds().getFirst());
        assertEquals("format", plan.batches().get(6).instanceIds().getFirst());
        assertEquals(List.of("a-3"), plan.batches().get(7).instanceIds());
        assertEquals(9, plan.instanceCount());
    }

    @Test
    void splitsOnlyAtExplicitInstanceLimitAndRejectsBatchModelAndTotalBounds() {
        X7StaticBatchKey base = key(3L, "model-a", "geometry-a", "solid", "material-a", 0,
                X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32);
        List<X7StaticBatchInput> sameKey = List.of(
                new X7StaticBatchInput(base, "one"),
                new X7StaticBatchInput(base, "two"),
                new X7StaticBatchInput(base, "three"));
        X7StaticBatchPlan split = X7StaticBatchPlanner.plan(
                sameKey, new X7StaticBatchLimits(3, 1, 1, 3));
        assertEquals(List.of(List.of("one"), List.of("two"), List.of("three")),
                split.batches().stream().map(X7StaticBatchPlan.Batch::instanceIds).toList());

        assertThrows(IllegalArgumentException.class, () -> X7StaticBatchPlanner.plan(
                List.of(new X7StaticBatchInput(base, "one"),
                        new X7StaticBatchInput(key(3L, "model-a", "geometry-b", "solid", "material-a", 0,
                                X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "two")),
                new X7StaticBatchLimits(1, 1, 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> X7StaticBatchPlanner.plan(
                List.of(new X7StaticBatchInput(base, "one"),
                        new X7StaticBatchInput(key(3L, "model-b", "geometry-a", "solid", "material-a", 0,
                                X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "two")),
                new X7StaticBatchLimits(3, 1, 2, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> X7StaticBatchPlanner.plan(sameKey, new X7StaticBatchLimits(3, 1, 3, 2)));
    }

    @Test
    void diagnosticsSnapshotIsNumericOnlyAndResetDoesNotImplyResourceOwnership() {
        X7StaticBatchPlan plan = X7StaticBatchPlanner.plan(
                List.of(new X7StaticBatchInput(key(17L, "model-a", "geometry-a", "solid", "material-a", 0,
                        X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32), "one")),
                X7StaticBatchLimits.STRICT_V1);
        X7GpuDiagnosticsCollector diagnostics = new X7GpuDiagnosticsCollector();
        diagnostics.recordBatchPlan(plan);

        X7GpuDiagnosticsSnapshot observed = diagnostics.snapshot();
        assertEquals(1L, observed.plannedBatchCount());
        assertEquals(1L, observed.plannedModelCount());
        assertEquals(1L, observed.plannedInstanceCount());
        diagnostics.reset();

        X7GpuDiagnosticsSnapshot reset = diagnostics.snapshot();
        assertEquals(0L, reset.plannedBatchCount());
        assertEquals(0L, reset.plannedModelCount());
        assertEquals(0L, reset.plannedInstanceCount());
    }

    @Test
    void diagnosticsOverflowLeavesEveryBatchCounterUnchangedUntilAllCheckedSumsSucceed() {
        X7StaticBatchKey key = key(17L, "model-a", "geometry-a", "solid", "material-a", 0,
                X7StaticBatchVertexFormat.POSITION_NORMAL_UV_F32);
        X7StaticBatchPlan plan = new X7StaticBatchPlan(
                List.of(new X7StaticBatchPlan.Batch(key, List.of("one"))),
                1,
                1,
                X7StaticBatchPlan.SubmissionKind.DRAW_MULTIPLE_INDEXED_CANDIDATE_WAITING);
        X7GpuDiagnosticsCollector diagnostics = new X7GpuDiagnosticsCollector(
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE,
                23L);

        X7GpuDiagnosticsSnapshot before = diagnostics.snapshot();
        assertThrows(ArithmeticException.class, () -> diagnostics.recordBatchPlan(plan));
        X7GpuDiagnosticsSnapshot after = diagnostics.snapshot();

        assertEquals(before.plannedBatchCount(), after.plannedBatchCount());
        assertEquals(before.plannedModelCount(), after.plannedModelCount());
        assertEquals(before.plannedInstanceCount(), after.plannedInstanceCount());
    }

    private static X7StaticBatchKey key(
            long generation,
            String model,
            String geometry,
            String route,
            String material,
            int lod,
            X7StaticBatchVertexFormat format) {
        return new X7StaticBatchKey(
                generation,
                model,
                geometry,
                route,
                material,
                lod,
                format,
                "TRIANGLES",
                "UINT32_LE",
                3);
    }
}
