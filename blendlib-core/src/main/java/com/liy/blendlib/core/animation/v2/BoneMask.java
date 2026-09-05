package com.liy.blendlib.core.animation.v2;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable numeric mask whose lookup and validation are completed before hot-path evaluation. */
public final class BoneMask {
    private final int boneCount;
    private final float[] weights;
    private final boolean empty;

    private BoneMask(int boneCount, float[] weights) {
        this.boneCount = boneCount;
        this.weights = weights;
        boolean any = false;
        for (float weight : weights) {
            any |= weight > 0.0F;
        }
        this.empty = !any;
    }

    public static BoneMask all(BoneSchema schema) {
        Objects.requireNonNull(schema, "schema");
        float[] weights = new float[schema.boneCount()];
        Arrays.fill(weights, 1.0F);
        return new BoneMask(schema.boneCount(), weights);
    }

    public static BoneMask empty(BoneSchema schema) {
        Objects.requireNonNull(schema, "schema");
        return new BoneMask(schema.boneCount(), new float[schema.boneCount()]);
    }

    /** Builds from declared names and rejects duplicates before any runtime evaluation can occur. */
    public static BoneMask named(BoneSchema schema, List<NamedWeight> declarations) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(declarations, "declarations");
        float[] weights = new float[schema.boneCount()];
        boolean[] seen = new boolean[schema.boneCount()];
        for (NamedWeight declaration : declarations) {
            NamedWeight checked = Objects.requireNonNull(declaration, "maskDeclaration");
            int index = schema.requireIndex(checked.boneName());
            if (seen[index]) {
                throw new IllegalArgumentException("duplicate bone mask declaration: " + checked.boneName());
            }
            seen[index] = true;
            weights[index] = checked.weight();
        }
        return new BoneMask(schema.boneCount(), weights);
    }

    /** Builds from already-resolved indices while retaining the same strict bounds and duplicate checks. */
    public static BoneMask indexed(BoneSchema schema, List<IndexedWeight> declarations) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(declarations, "declarations");
        float[] weights = new float[schema.boneCount()];
        boolean[] seen = new boolean[schema.boneCount()];
        for (IndexedWeight declaration : declarations) {
            IndexedWeight checked = Objects.requireNonNull(declaration, "maskDeclaration");
            int index = schema.requireIndexInRange(checked.boneIndex());
            if (seen[index]) {
                throw new IllegalArgumentException("duplicate bone mask declaration at index: " + index);
            }
            seen[index] = true;
            weights[index] = checked.weight();
        }
        return new BoneMask(schema.boneCount(), weights);
    }

    public int boneCount() {
        return boneCount;
    }

    public boolean isEmpty() {
        return empty;
    }

    /** Hot-path O(1) numeric lookup; no name resolution, parsing, or map access occurs here. */
    public float weightAt(int index) {
        if (index < 0 || index >= boneCount) {
            throw new IllegalArgumentException("bone index is outside mask bounds: " + index);
        }
        return weights[index];
    }

    public float[] weights() {
        return Arrays.copyOf(weights, weights.length);
    }

    public record NamedWeight(String boneName, float weight) {
        public NamedWeight {
            boneName = Objects.requireNonNull(boneName, "boneName");
            validateWeight(weight);
        }
    }

    public record IndexedWeight(int boneIndex, float weight) {
        public IndexedWeight {
            validateWeight(weight);
        }
    }

    private static void validateWeight(float value) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException("bone mask weight must be finite and in [0, 1]");
        }
    }
}
