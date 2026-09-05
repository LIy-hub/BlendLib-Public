package com.liy.blendlib.fabric.client.reload;

import java.util.Comparator;
import java.util.Objects;

/**
 * Immutable, complete identity of one caller-owned generation geometry resource leaf.
 *
 * <p>This key is deliberately richer than the historic B1 generation/model/geometry tuple: an
 * aggregate may only be complete when route, LOD, typed format, primitive mode, and index format
 * are also frozen. It is package-private so no adapter-private choice leaks into the public API.</p>
 */
record X7GpuGenerationKey(
        long generation,
        String modelId,
        String geometryId,
        String materialRoute,
        int lod,
        X7GpuVertexFormat vertexFormat,
        X7PrimitiveMode primitiveMode,
        X7GpuIndexType indexType) {
    X7GpuGenerationKey {
        if (generation < 0L) {
            throw new IllegalArgumentException("X7 GPU generation must be non-negative");
        }
        modelId = requireIdentity(modelId, "modelId");
        geometryId = requireIdentity(geometryId, "geometryId");
        materialRoute = requireIdentity(materialRoute, "materialRoute");
        if (lod < 0) {
            throw new IllegalArgumentException("X7 GPU lod must be non-negative");
        }
        vertexFormat = Objects.requireNonNull(vertexFormat, "vertexFormat");
        primitiveMode = Objects.requireNonNull(primitiveMode, "primitiveMode");
        indexType = Objects.requireNonNull(indexType, "indexType");
    }

    static Comparator<X7GpuGenerationKey> deterministicOrder() {
        return Comparator.comparingLong(X7GpuGenerationKey::generation)
                .thenComparing(X7GpuGenerationKey::modelId)
                .thenComparing(X7GpuGenerationKey::geometryId)
                .thenComparing(X7GpuGenerationKey::materialRoute)
                .thenComparingInt(X7GpuGenerationKey::lod)
                .thenComparing(key -> key.vertexFormat().name())
                .thenComparing(key -> key.primitiveMode().name())
                .thenComparing(key -> key.indexType().name());
    }

    private static String requireIdentity(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException("X7 GPU " + name + " must not be blank");
        }
        return checked;
    }
}
