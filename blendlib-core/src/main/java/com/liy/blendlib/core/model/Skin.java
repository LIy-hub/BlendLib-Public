package com.liy.blendlib.core.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable skin joints and inverse-bind matrices. */
public final class Skin {
    private final String name;
    private final int skeletonRoot;
    private final List<Integer> joints;
    private final float[] inverseBindMatrices;

    public Skin(String name, int skeletonRoot, List<Integer> joints, float[] inverseBindMatrices) {
        this.name = name == null || name.isBlank() ? "skin" : name;
        if (skeletonRoot < -1) {
            throw new IllegalArgumentException("skeletonRoot must be -1 or a node index");
        }
        this.skeletonRoot = skeletonRoot;
        this.joints = List.copyOf(Objects.requireNonNull(joints, "joints"));
        if (this.joints.isEmpty()) {
            throw new IllegalArgumentException("A skin must contain at least one joint");
        }
        for (int joint : this.joints) {
            if (joint < 0) {
                throw new IllegalArgumentException("Skin joint indices must be non-negative");
            }
        }
        this.inverseBindMatrices = Arrays.copyOf(Objects.requireNonNull(inverseBindMatrices, "inverseBindMatrices"),
                inverseBindMatrices.length);
        if (this.inverseBindMatrices.length != this.joints.size() * 16) {
            throw new IllegalArgumentException("Inverse-bind data must contain one mat4 per skin joint");
        }
        for (float value : this.inverseBindMatrices) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Inverse-bind matrices must be finite");
            }
        }
    }

    public String name() {
        return name;
    }

    public int skeletonRoot() {
        return skeletonRoot;
    }

    public List<Integer> joints() {
        return joints;
    }

    public float[] inverseBindMatrices() {
        return Arrays.copyOf(inverseBindMatrices, inverseBindMatrices.length);
    }

    public Matrix4 inverseBindMatrix(int jointIndex) {
        if (jointIndex < 0 || jointIndex >= joints.size()) {
            throw new IndexOutOfBoundsException("Skin joint index outside range: " + jointIndex);
        }
        return new Matrix4(Arrays.copyOfRange(inverseBindMatrices, jointIndex * 16, (jointIndex + 1) * 16));
    }
}
