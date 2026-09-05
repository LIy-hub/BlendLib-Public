package com.liy.blendlib.fabric.client.render.x7gpu;

import java.util.Objects;

/**
 * Synchronized numeric observations for candidate batch planning only.
 *
 * <p>This collector intentionally has no resource, generation, lease, fence, or physical-close
 * fields. Those lifecycle diagnostics belong to D1 once a later tranche adopts a complete
 * aggregate.</p>
 */
final class X7GpuDiagnosticsCollector {
    private long plannedBatchCount;
    private long plannedModelCount;
    private long plannedInstanceCount;

    X7GpuDiagnosticsCollector() {
    }

    X7GpuDiagnosticsCollector(long plannedBatchCount, long plannedModelCount, long plannedInstanceCount) {
        if (plannedBatchCount < 0L || plannedModelCount < 0L || plannedInstanceCount < 0L) {
            throw new IllegalArgumentException("X7 diagnostic counters must be non-negative");
        }
        this.plannedBatchCount = plannedBatchCount;
        this.plannedModelCount = plannedModelCount;
        this.plannedInstanceCount = plannedInstanceCount;
    }

    synchronized void recordBatchPlan(X7StaticBatchPlan plan) {
        X7StaticBatchPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        long nextBatchCount = Math.addExact(plannedBatchCount, checkedPlan.batches().size());
        long nextModelCount = Math.addExact(plannedModelCount, checkedPlan.distinctModelCount());
        long nextInstanceCount = Math.addExact(plannedInstanceCount, checkedPlan.instanceCount());
        plannedBatchCount = nextBatchCount;
        plannedModelCount = nextModelCount;
        plannedInstanceCount = nextInstanceCount;
    }

    synchronized X7GpuDiagnosticsSnapshot snapshot() {
        return new X7GpuDiagnosticsSnapshot(plannedBatchCount, plannedModelCount, plannedInstanceCount);
    }

    synchronized void reset() {
        plannedBatchCount = 0L;
        plannedModelCount = 0L;
        plannedInstanceCount = 0L;
    }
}
