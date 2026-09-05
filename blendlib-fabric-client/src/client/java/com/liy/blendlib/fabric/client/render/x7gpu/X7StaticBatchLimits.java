package com.liy.blendlib.fabric.client.render.x7gpu;

/** Explicit bounded work limits for deterministic static batch planning. */
record X7StaticBatchLimits(int maxBatches, int maxDistinctModels, int maxInstancesPerBatch, int maxInstancesTotal) {
    static final X7StaticBatchLimits STRICT_V1 = new X7StaticBatchLimits(4_096, 2_048, 1_024, 65_536);

    X7StaticBatchLimits {
        if (maxBatches <= 0 || maxDistinctModels <= 0 || maxInstancesPerBatch <= 0 || maxInstancesTotal <= 0) {
            throw new IllegalArgumentException("X7 static batch limits must all be positive");
        }
        if (maxInstancesPerBatch > maxInstancesTotal) {
            throw new IllegalArgumentException("X7 max instances per batch cannot exceed total instances");
        }
    }
}
