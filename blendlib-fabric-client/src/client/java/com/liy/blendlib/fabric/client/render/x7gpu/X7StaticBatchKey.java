package com.liy.blendlib.fabric.client.render.x7gpu;

import java.util.Objects;

/**
 * Complete compatibility key for a static B1 batch. Equal keys may share only a candidate plan;
 * the planner never joins unequal keys or performs an actual render-pass call.
 */
record X7StaticBatchKey(
        long generation,
        String modelId,
        String geometryId,
        String renderTypeRoute,
        String materialId,
        int lod,
        X7StaticBatchVertexFormat vertexFormat,
        String primitiveMode,
        String indexType,
        int indexCount) {
    X7StaticBatchKey {
        if (generation < 0L) {
            throw new IllegalArgumentException("X7 static batch generation must be non-negative");
        }
        modelId = identity(modelId, "modelId");
        geometryId = identity(geometryId, "geometryId");
        renderTypeRoute = identity(renderTypeRoute, "renderTypeRoute");
        materialId = identity(materialId, "materialId");
        if (lod < 0) {
            throw new IllegalArgumentException("X7 static batch LOD must be non-negative");
        }
        vertexFormat = Objects.requireNonNull(vertexFormat, "vertexFormat");
        primitiveMode = identity(primitiveMode, "primitiveMode");
        indexType = identity(indexType, "indexType");
        if (indexCount <= 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("X7 static batch index count must describe non-empty triangles");
        }
    }

    private static String identity(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException("X7 static batch " + name + " must not be blank");
        }
        return checked;
    }
}
