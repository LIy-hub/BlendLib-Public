package com.liy.blendlib.core.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable generation-owned dense deltas in stable mesh-local target order. */
public final class MorphTargetSet {
    public static final int MAX_TARGETS = 8;
    public static final int MAX_TARGET_NAME_LENGTH = 128;
    private final List<String> targetNames;
    private final int vertexCount;
    private final float[][] positionDeltas;
    private final float[][] normalDeltas;

    public MorphTargetSet(List<String> targetNames, int vertexCount, float[][] positionDeltas, float[][] normalDeltas) {
        this.targetNames = List.copyOf(Objects.requireNonNull(targetNames, "targetNames"));
        if (vertexCount <= 0 || this.targetNames.isEmpty() || this.targetNames.size() > MAX_TARGETS
                || new HashSet<>(this.targetNames).size() != this.targetNames.size()) {
            throw new IllegalArgumentException("Morph target names and vertex count are invalid");
        }
        for (String name : this.targetNames) validateTargetName(name);
        this.vertexCount = vertexCount;
        this.positionDeltas = copy(positionDeltas);
        this.normalDeltas = copy(normalDeltas);
    }
    public static void validateTargetName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_TARGET_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Morph target name must be nonblank, bounded and contain no control characters");
        }
    }
    private float[][] copy(float[][] arrays) {
        Objects.requireNonNull(arrays, "deltas");
        if (arrays.length != targetCount()) throw new IllegalArgumentException("Morph target delta count mismatch");
        float[][] result = new float[arrays.length][];
        for (int t = 0; t < arrays.length; t++) {
            float[] values = Objects.requireNonNull(arrays[t], "target delta");
            if (values.length != (long) vertexCount * 3) throw new IllegalArgumentException("Morph vertex count mismatch");
            for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite morph delta");
            result[t] = Arrays.copyOf(values, values.length);
        }
        return result;
    }
    public List<String> targetNames() { return targetNames; }
    public int targetCount() { return targetNames.size(); }
    public int vertexCount() { return vertexCount; }
    public float positionDelta(int target, int vertex, int component) { return positionDeltas[target][index(vertex, component)]; }
    public float normalDelta(int target, int vertex, int component) { return normalDeltas[target][index(vertex, component)]; }
    private int index(int vertex, int component) {
        if (vertex < 0 || vertex >= vertexCount || component < 0 || component > 2) throw new IndexOutOfBoundsException();
        return vertex * 3 + component;
    }
}
