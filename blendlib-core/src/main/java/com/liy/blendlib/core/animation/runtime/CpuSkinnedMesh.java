package com.liy.blendlib.core.animation.runtime;

import java.util.Arrays;
import java.util.Objects;

/** Immutable CPU-skinned vertex output for the v1 backend boundary. */
public final class CpuSkinnedMesh {
    private final SkinnedMeshTopology topology;
    private final float[] positions;
    private final float[] normals;

    CpuSkinnedMesh(SkinnedMeshTopology topology, float[] positions, float[] normals) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.positions = copyFinite(positions, "positions");
        this.normals = copyFinite(normals, "normals");
        if (this.positions.length == 0 || this.positions.length % 3 != 0 || this.normals.length != this.positions.length) {
            throw new IllegalArgumentException("Skinned output positions and normals must have equal xyz cardinality");
        }
        if (vertexCount() != topology.vertexCount()) {
            throw new IllegalArgumentException("Skinned output vertex count must match its prepared topology");
        }
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public float[] positions() {
        return Arrays.copyOf(positions, positions.length);
    }

    public float[] normals() {
        return Arrays.copyOf(normals, normals.length);
    }

    /**
     * Returns the immutable UV/index/material handoff captured with the prepared geometry.
     */
    public SkinnedMeshTopology topology() {
        return topology;
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
