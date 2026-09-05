package com.liy.blendlib.fabric.v262.model;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Transform;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable generation-bound rigid-node palette frozen during Fabric 26.2 extraction.
 *
 * <p>The snapshot contains only copied core transform values, a strict model/generation identity,
 * and the descriptor unit conversion. It deliberately retains no controller, host, resource
 * manager, decoded asset, or provider. Submit can therefore consume this value without evaluating
 * an animation source, advancing a playhead, parsing GLB/JSON, or discovering resources.</p>
 */
public final class Fabric262PoseSnapshot {
    private final BlendModelKey modelKey;
    private final long generation;
    private final Map<Integer, Transform> worldTransforms;
    private final float unitsToBlocksScale;

    private Fabric262PoseSnapshot(
            BlendModelKey modelKey,
            long generation,
            Map<Integer, Transform> worldTransforms,
            float unitsToBlocksScale) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        if (!Float.isFinite(unitsToBlocksScale) || unitsToBlocksScale <= 0.0F) {
            throw new IllegalArgumentException("unitsToBlocksScale must be positive and finite");
        }
        LinkedHashMap<Integer, Transform> copied = new LinkedHashMap<>();
        for (Map.Entry<Integer, Transform> entry : Objects.requireNonNull(worldTransforms, "worldTransforms").entrySet()) {
            Integer index = Objects.requireNonNull(entry.getKey(), "world transform node index");
            if (index < 0) {
                throw new IllegalArgumentException("world transform node index must be non-negative");
            }
            copied.put(index, Objects.requireNonNull(entry.getValue(), "world transform"));
        }
        this.worldTransforms = Map.copyOf(copied);
        this.unitsToBlocksScale = unitsToBlocksScale;
    }

    /** Creates a frozen regular rigid palette from extraction-side core world transforms. */
    static Fabric262PoseSnapshot regular(
            BlendModelKey modelKey,
            long generation,
            Map<Integer, Transform> worldTransforms,
            float unitsToBlocksScale) {
        return new Fabric262PoseSnapshot(modelKey, generation, worldTransforms, unitsToBlocksScale);
    }

    /** Creates an identity-only carrier for a diagnostic fallback handle that submits no rigid nodes. */
    static Fabric262PoseSnapshot fallback(BlendModelKey modelKey, long generation) {
        return new Fabric262PoseSnapshot(modelKey, generation, Map.of(), 1.0F);
    }

    /** Returns the exact strict model identity that created this palette. */
    public BlendModelKey modelKey() {
        return modelKey;
    }

    /** Returns the exact resource generation that created this palette. */
    public long generation() {
        return generation;
    }

    /**
     * Returns the frozen world transform for one primitive's original node index.
     *
     * @throws IllegalArgumentException if the primitive is outside the selected canonical scene
     */
    public Transform transformFor(int nodeIndex) {
        Transform transform = worldTransforms.get(nodeIndex);
        if (transform == null) {
            throw new IllegalArgumentException(
                    "Frozen Fabric 26.2 pose has no transform for primitive node index: " + nodeIndex);
        }
        return transform;
    }

    /** Returns the descriptor {@code units_per_block} conversion captured with the palette. */
    public float unitsToBlocksScale() {
        return unitsToBlocksScale;
    }

    void requireCompatible(BlendModelKey expectedModelKey, long expectedGeneration) {
        if (!modelKey.equals(Objects.requireNonNull(expectedModelKey, "expectedModelKey"))
                || generation != expectedGeneration) {
            throw new IllegalStateException(
                    "Frozen Fabric 26.2 pose cannot cross a model key or resource generation");
        }
    }
}
