package com.liy.blendlib.fabric.client.reload;

/** Bounded allocation limits for one direct CPU staging pair. */
record X7GeometryStagingLimits(int maxVertices, int maxIndices, int maxVertexBytes, int maxIndexBytes) {
    static final X7GeometryStagingLimits STRICT_V1 = new X7GeometryStagingLimits(
            1_000_000,
            3_000_000,
            32_000_000,
            12_000_000);

    X7GeometryStagingLimits {
        if (maxVertices <= 0 || maxIndices <= 0 || maxVertexBytes <= 0 || maxIndexBytes <= 0) {
            throw new IllegalArgumentException("X7 staging limits must be positive");
        }
    }
}
