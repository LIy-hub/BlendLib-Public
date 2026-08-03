package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Transform;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal immutable rigid-node world-transform palette prepared before render submit.
 *
 * <p>The palette is deliberately scoped to one model key and reload generation. It carries no
 * entity, world, controller, resource, parser, or mutable matrix-array reference.</p>
 */
final class RigidNodePaletteSnapshot {
    private final BlendModelKey modelKey;
    private final long generation;
    private final Map<Integer, Transform> worldTransforms;

    private RigidNodePaletteSnapshot(BlendModelKey modelKey, long generation, Map<Integer, Transform> worldTransforms) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.worldTransforms = immutableTransforms(worldTransforms);
    }

    static RigidNodePaletteSnapshot copyOf(
            BlendModelKey modelKey, long generation, Map<Integer, Transform> worldTransforms) {
        return new RigidNodePaletteSnapshot(modelKey, generation, worldTransforms);
    }

    BlendModelKey modelKey() {
        return modelKey;
    }

    long generation() {
        return generation;
    }

    Map<Integer, Transform> worldTransforms() {
        return worldTransforms;
    }

    Transform nodeTransform(int nodeIndex) {
        Transform transform = worldTransforms.get(nodeIndex);
        if (transform == null) {
            throw new IllegalArgumentException("Rigid node palette does not contain node index: " + nodeIndex);
        }
        return transform;
    }

    /** Fails before submit if a prepared palette could mix a stale model or generation. */
    void requireCompatible(ModelRenderHandle handle) {
        ModelRenderHandle checkedHandle = Objects.requireNonNull(handle, "handle");
        if (!modelKey.equals(checkedHandle.modelKey()) || generation != checkedHandle.generation()) {
            throw new IllegalArgumentException("Rigid node palette model key and generation must match the render handle");
        }
        for (PreparedRenderPrimitive primitive : checkedHandle.primitives()) {
            if (!worldTransforms.containsKey(primitive.nodeIndex())) {
                throw new IllegalArgumentException(
                        "Rigid node palette is missing prepared primitive node index: " + primitive.nodeIndex());
            }
        }
    }

    private static Map<Integer, Transform> immutableTransforms(Map<Integer, Transform> input) {
        Objects.requireNonNull(input, "worldTransforms");
        Map<Integer, Transform> copied = new LinkedHashMap<>();
        for (Map.Entry<Integer, Transform> entry : input.entrySet()) {
            Integer nodeIndex = Objects.requireNonNull(entry.getKey(), "nodeIndex");
            if (nodeIndex < 0) {
                throw new IllegalArgumentException("Rigid node palette node indices must be non-negative");
            }
            copied.put(nodeIndex, Objects.requireNonNull(entry.getValue(), "transform"));
        }
        return Map.copyOf(copied);
    }
}
