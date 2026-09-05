package com.liy.blendlib.fabric.client.render.x7gpu;

/** Immutable numeric observation of candidate batch work since the previous reset. */
record X7GpuDiagnosticsSnapshot(long plannedBatchCount, long plannedModelCount, long plannedInstanceCount) {
}
