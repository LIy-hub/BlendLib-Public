package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.MeshPrimitive;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, skinning-ready primitive attributes captured at an explicit preparation boundary.
 *
 * <p>The decoded primitive's accessors make the defensive copies retained here. The mutable
 * array views used by the skinning loop are package-private so callers can only observe copies.</p>
 */
public final class PreparedSkinnedGeometry {
    private final float[] positions;
    private final float[] normals;
    private final int[] joints;
    private final float[] weights;
    private final SkinnedMeshTopology topology;

    private PreparedSkinnedGeometry(
            float[] positions, float[] normals, int[] joints, float[] weights, SkinnedMeshTopology topology) {
        this.positions = copyFinite(positions, "positions");
        this.normals = copyFinite(normals, "normals");
        this.joints = Arrays.copyOf(Objects.requireNonNull(joints, "joints"), joints.length);
        this.weights = copyFinite(weights, "weights");
        this.topology = Objects.requireNonNull(topology, "topology");
        if (positions.length == 0 || positions.length % 3 != 0 || normals.length != positions.length
                || joints.length != vertexCount() * 4 || weights.length != vertexCount() * 4
                || topology.vertexCount() != vertexCount()) {
            throw new IllegalArgumentException("Prepared skinned geometry has invalid attribute cardinality");
        }
    }

    /**
     * Captures the source attributes once before repeated skinning operations.
     */
    public static PreparedSkinnedGeometry prepare(MeshPrimitive primitive) {
        Objects.requireNonNull(primitive, "primitive");
        if (!primitive.skinned()) {
            throw new IllegalArgumentException("CPU skinning requires geometry with JOINTS_0 and WEIGHTS_0");
        }
        return new PreparedSkinnedGeometry(
                primitive.positions(),
                primitive.normals(),
                primitive.joints(),
                primitive.weights(),
                SkinnedMeshTopology.capture(primitive)
        );
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

    public int[] joints() {
        return Arrays.copyOf(joints, joints.length);
    }

    public float[] weights() {
        return Arrays.copyOf(weights, weights.length);
    }

    /**
     * Returns immutable material/UV/index data paired with every skinning output of this geometry.
     */
    public SkinnedMeshTopology topology() {
        return topology;
    }

    float[] positionsForSkinning() {
        return positions;
    }

    float[] normalsForSkinning() {
        return normals;
    }

    int[] jointsForSkinning() {
        return joints;
    }

    float[] weightsForSkinning() {
        return weights;
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
