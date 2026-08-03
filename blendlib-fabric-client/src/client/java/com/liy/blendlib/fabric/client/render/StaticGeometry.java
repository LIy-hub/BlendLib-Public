package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.MeshPrimitive;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable vertex payload copied once while a render handle is prepared.
 *
 * <p>The backing arrays are intentionally not exposed. The static/rigid submit path reads them
 * directly, so it does not create vertex-count-proportional copies per snapshot or per frame.</p>
 */
public final class StaticGeometry {
    private final float[] positions;
    private final float[] normals;
    private final float[] texCoords;
    private final int[] indices;

    private StaticGeometry(float[] positions, float[] normals, float[] texCoords, int[] indices) {
        this.positions = copyFinite(positions, "positions");
        this.normals = copyFinite(normals, "normals");
        this.texCoords = copyFinite(texCoords, "texCoords");
        this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
        if (this.positions.length == 0 || this.positions.length % 3 != 0 || this.normals.length != this.positions.length
                || this.texCoords.length != vertexCount() * 2 || this.indices.length == 0 || this.indices.length % 3 != 0) {
            throw new IllegalArgumentException("Static geometry arrays have an invalid strict-v1 layout");
        }
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount()) {
                throw new IllegalArgumentException("Static geometry index is outside the vertex range");
            }
        }
    }

    /** Copies a core primitive once at handle construction; never call this from submit. */
    public static StaticGeometry copyOf(MeshPrimitive primitive) {
        Objects.requireNonNull(primitive, "primitive");
        if (primitive.skinned()) {
            throw new IllegalArgumentException("P4 static/rigid geometry must not contain skin vertex data");
        }
        return new StaticGeometry(primitive.positions(), primitive.normals(), primitive.texCoords(), primitive.indices());
    }

    /** Creates bounded diagnostic geometry for the missing-model handle. */
    public static StaticGeometry of(float[] positions, float[] normals, float[] texCoords, int[] indices) {
        return new StaticGeometry(positions, normals, texCoords, indices);
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int indexCount() {
        return indices.length;
    }

    /**
     * Emits strict-v1 triangles as degenerate quads for the public 26.1.2 entity render types.
     *
     * <p>Those entity pipelines consume {@code QUADS}, while strict GLB geometry is necessarily
     * indexed triangles. Each {@code (a, b, c)} triangle is therefore submitted as
     * {@code (a, b, c, c)}: the first triangle retains its source winding and the second is
     * degenerate. This expands only at vertex emission time and does not allocate or alter the
     * immutable strict-GLB topology.</p>
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
