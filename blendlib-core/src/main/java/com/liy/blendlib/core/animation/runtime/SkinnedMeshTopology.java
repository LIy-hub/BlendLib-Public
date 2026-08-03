package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.MeshPrimitive;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable non-skinned primitive data shared by prepared geometry and CPU-skinned output.
 *
 * <p>This is an internal backend handoff, not a descriptor/material interpretation API. It
 * captures the material slot, UV0, and triangle indices once at preparation time so a later
 * backend can consume a {@link CpuSkinnedMesh} without returning to a decoded primitive or
 * resource.</p>
 */
public final class SkinnedMeshTopology {
    private final String materialSlot;
    private final int vertexCount;
    private final float[] texCoords;
    private final int[] indices;

    SkinnedMeshTopology(String materialSlot, int vertexCount, float[] texCoords, int[] indices) {
        this.materialSlot = requireMaterialSlot(materialSlot);
        if (vertexCount <= 0) {
            throw new IllegalArgumentException("Skinned topology must contain at least one vertex");
        }
        this.vertexCount = vertexCount;
        this.texCoords = copyFinite(texCoords, "texCoords");
        this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
        if (this.texCoords.length != vertexCount * 2 || this.indices.length == 0 || this.indices.length % 3 != 0) {
            throw new IllegalArgumentException("Skinned topology UV0 or triangle index cardinality is invalid");
        }
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Skinned topology index is outside the vertex range");
            }
        }
    }

    static SkinnedMeshTopology capture(MeshPrimitive primitive) {
        Objects.requireNonNull(primitive, "primitive");
        return new SkinnedMeshTopology(
                primitive.materialSlot(),
                primitive.vertexCount(),
                primitive.texCoords(),
                primitive.indices());
    }

    public String materialSlot() {
        return materialSlot;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indices.length;
    }

    public float[] texCoords() {
        return Arrays.copyOf(texCoords, texCoords.length);
    }

    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    float[] texCoordsForBackend() {
        return texCoords;
    }

    int[] indicesForBackend() {
        return indices;
    }

    private static String requireMaterialSlot(String materialSlot) {
        Objects.requireNonNull(materialSlot, "materialSlot");
        if (materialSlot.isBlank()) {
            throw new IllegalArgumentException("Material slot must not be blank");
        }
        return materialSlot;
    }

    private static float[] copyFinite(float[] source, String name) {
        float[] copy = Arrays.copyOf(Objects.requireNonNull(source, name), source.length);
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
        return copy;
    }
}
