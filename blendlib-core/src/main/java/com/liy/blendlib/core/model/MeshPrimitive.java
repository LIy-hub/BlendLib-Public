package com.liy.blendlib.core.model;

import java.util.Arrays;
import java.util.Objects;

/** Immutable decoded triangle primitive with optional four-weight skin data. */
public final class MeshPrimitive {
    private final String materialSlot;
    private final float[] positions;
    private final float[] normals;
    private final float[] texCoords;
    private final int[] indices;
    private final int[] joints;
    private final float[] weights;
    private final Bounds localBounds;

    public MeshPrimitive(
            String materialSlot,
            float[] positions,
            float[] normals,
            float[] texCoords,
            int[] indices,
            int[] joints,
            float[] weights) {
        this.materialSlot = requireMaterialSlot(materialSlot);
        this.positions = copyFinite(positions, "positions");
        this.normals = copyFinite(normals, "normals");
        this.texCoords = copyFinite(texCoords, "texCoords");
        this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
        if (this.positions.length == 0 || this.positions.length % 3 != 0 || this.normals.length != this.positions.length
                || this.texCoords.length != vertexCount() * 2 || this.indices.length == 0 || this.indices.length % 3 != 0) {
            throw new IllegalArgumentException("Primitive vertex attributes or triangle indices have invalid cardinality");
        }
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount()) {
                throw new IllegalArgumentException("Primitive index is outside the vertex range");
            }
        }
        if ((joints == null) != (weights == null)) {
            throw new IllegalArgumentException("Skin joints and weights must be supplied together");
        }
        if (joints == null) {
            this.joints = null;
            this.weights = null;
        } else {
            if (joints.length != vertexCount() * 4 || weights.length != vertexCount() * 4) {
                throw new IllegalArgumentException("Skinned vertices require exactly four joints and weights each");
            }
            this.joints = Arrays.copyOf(joints, joints.length);
            this.weights = copyFinite(weights, "weights");
            for (int vertex = 0; vertex < vertexCount(); vertex++) {
                float sum = 0.0f;
                for (int component = 0; component < 4; component++) {
                    int offset = vertex * 4 + component;
                    if (this.joints[offset] < 0 || this.weights[offset] < 0.0f) {
                        throw new IllegalArgumentException("Skin joints and weights must be non-negative");
                    }
                    sum += this.weights[offset];
                }
                for (int first = 0; first < 4; first++) {
                    if (!(this.weights[vertex * 4 + first] > 0.0f)) {
                        continue;
                    }
                    for (int second = first + 1; second < 4; second++) {
                        if (this.weights[vertex * 4 + second] > 0.0f
                                && this.joints[vertex * 4 + first] == this.joints[vertex * 4 + second]) {
                            throw new IllegalArgumentException("A vertex must not repeat a positive-weight joint influence");
                        }
                    }
                }
                if (!Float.isFinite(sum) || Math.abs(sum - 1.0f) > 1.0e-3f) {
                    throw new IllegalArgumentException("Each vertex's skin weights must be normalized");
                }
            }
        }
        this.localBounds = Bounds.fromPositions(this.positions);
    }

    public String materialSlot() {
        return materialSlot;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int indexCount() {
        return indices.length;
    }

    public boolean skinned() {
        return joints != null;
    }

    public float[] positions() {
        return Arrays.copyOf(positions, positions.length);
    }

    public float[] normals() {
        return Arrays.copyOf(normals, normals.length);
    }

    public float[] texCoords() {
        return Arrays.copyOf(texCoords, texCoords.length);
    }

    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    public int[] joints() {
        return joints == null ? null : Arrays.copyOf(joints, joints.length);
    }

    public float[] weights() {
        return weights == null ? null : Arrays.copyOf(weights, weights.length);
    }

    public Bounds localBounds() {
        return localBounds;
    }

    private static String requireMaterialSlot(String materialSlot) {
        Objects.requireNonNull(materialSlot, "materialSlot");
        if (materialSlot.isBlank()) {
            throw new IllegalArgumentException("Material slot must not be blank");
        }
        return materialSlot;
    }

    private static float[] copyFinite(float[] values, String name) {
        float[] copy = Arrays.copyOf(Objects.requireNonNull(values, name), values.length);
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
        return copy;
    }
}
