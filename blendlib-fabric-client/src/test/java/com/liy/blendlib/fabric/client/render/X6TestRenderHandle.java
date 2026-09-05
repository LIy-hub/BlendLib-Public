package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Transform;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Test-only exact static/rigid handle used to prove X6 identity binding before submit. */
final class X6TestRenderHandle implements ModelRenderHandle {
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<PreparedRenderPrimitive> primitives;
    private final Map<Integer, Transform> nodeTransforms;
    private final boolean missing;
    private final Bounds bounds;

    X6TestRenderHandle(
            BlendModelKey modelKey,
            long generation,
            List<PreparedRenderPrimitive> primitives,
            Map<Integer, Transform> nodeTransforms,
            boolean missing) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        this.nodeTransforms = Map.copyOf(Objects.requireNonNull(nodeTransforms, "nodeTransforms"));
        this.missing = missing;
        this.bounds = Bounds.fromPositions(new float[] {
                0.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 1.0f
        });
    }

    @Override
    public BlendModelKey modelKey() {
        return modelKey;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    @Override
    public float unitsToBlocksScale() {
        return 1.0f;
    }

    @Override
    public List<PreparedRenderPrimitive> primitives() {
        return primitives;
    }

    @Override
    public Transform nodeTransform(int nodeIndex) {
        Transform transform = nodeTransforms.get(nodeIndex);
        if (transform == null) {
            throw new IndexOutOfBoundsException("missing test node " + nodeIndex);
        }
        return transform;
    }

    @Override
    public boolean missingModel() {
        return missing;
    }
}
