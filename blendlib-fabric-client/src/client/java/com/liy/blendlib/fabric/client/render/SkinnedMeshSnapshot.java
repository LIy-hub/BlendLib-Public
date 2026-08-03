package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.SkinnedMeshTopology;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable client-owned CPU-skinned vertex payload ready for one later collector callback.
 *
 * <p>Creation copies the extraction result and its prepared topology exactly once. The submit
 * path reads the retained arrays directly, so it does not call {@link CpuSkinnedMesh} accessors,
 * re-run skinning, or allocate in proportion to vertex count.</p>
 */
final class SkinnedMeshSnapshot {
    private final RenderMaterial material;
    private final float[] positions;
    private final float[] normals;
    private final float[] texCoords;
    private final int[] indices;

    private SkinnedMeshSnapshot(
            RenderMaterial material, float[] positions, float[] normals, float[] texCoords, int[] indices) {
        this.material = Objects.requireNonNull(material, "material");
        this.positions = copyFinite(positions, "positions");
        this.normals = copyFinite(normals, "normals");
        this.texCoords = copyFinite(texCoords, "texCoords");
        this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
        if (this.positions.length == 0 || this.positions.length % 3 != 0 || this.normals.length != this.positions.length
                || this.texCoords.length != vertexCount() * 2 || this.indices.length == 0 || this.indices.length % 3 != 0) {
            throw new IllegalArgumentException("Captured skinned geometry has an invalid strict-v1 layout");
        }
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount()) {
                throw new IllegalArgumentException("Captured skinned geometry index is outside the vertex range");
            }
        }
    }

    static SkinnedMeshSnapshot capture(PreparedSkinnedRenderPrimitive prepared, CpuSkinnedMesh output) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(output, "output");
        SkinnedMeshTopology expectedTopology = prepared.geometry().topology();
        if (output.topology() != expectedTopology) {
            throw new IllegalArgumentException(
                    "CPU-skinned output must originate from this exact prepared primitive topology");
        }
        return new SkinnedMeshSnapshot(
                prepared.material(), output.positions(), output.normals(), expectedTopology.texCoords(), expectedTopology.indices());
    }

    RenderMaterial material() {
        return material;
    }

    int vertexCount() {
        return positions.length / 3;
    }

    int indexCount() {
        return indices.length;
    }

    /**
     * Emits captured strict-v1 triangles as degenerate quads without copying arrays.
     *
     * <p>The public 26.1.2 entity render types consume {@code QUADS}; each source
     * {@code (a, b, c)} triangle becomes {@code (a, b, c, c)} so its source winding remains the
     * visible face and the second quad triangle is degenerate.</p>
     */
    void emit(VertexSink sink) {
        Objects.requireNonNull(sink, "sink");
        for (int offset = 0; offset < indices.length; offset += 3) {
            emitIndex(sink, indices[offset]);
            emitIndex(sink, indices[offset + 1]);
            emitIndex(sink, indices[offset + 2]);
            emitIndex(sink, indices[offset + 2]);
        }
    }

    private void emitIndex(VertexSink sink, int index) {
        int position = index * 3;
        int uv = index * 2;
        sink.vertex(
                positions[position], positions[position + 1], positions[position + 2],
                normals[position], normals[position + 1], normals[position + 2],
                texCoords[uv], texCoords[uv + 1]);
    }

    private static float[] copyFinite(float[] source, String name) {
        float[] copy = Arrays.copyOf(Objects.requireNonNull(source, name), source.length);
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain finite values");
            }
        }
        return copy;
    }

    @FunctionalInterface
    interface VertexSink {
        void vertex(float x, float y, float z, float normalX, float normalY, float normalZ, float u, float v);
    }
}
