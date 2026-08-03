package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.Transform;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable posed local transforms keyed by frozen model-node index. */
public final class LocalPose {
    private final Map<Integer, Transform> transforms;

    public LocalPose(Map<Integer, Transform> transforms) {
        Objects.requireNonNull(transforms, "transforms");
        LinkedHashMap<Integer, Transform> copied = new LinkedHashMap<>();
        for (Map.Entry<Integer, Transform> entry : transforms.entrySet()) {
            Integer nodeIndex = Objects.requireNonNull(entry.getKey(), "nodeIndex");
            if (nodeIndex < 0) {
                throw new IllegalArgumentException("Pose node indices must be non-negative");
            }
            copied.put(nodeIndex, Objects.requireNonNull(entry.getValue(), "transform"));
        }
        this.transforms = Collections.unmodifiableMap(copied);
    }

    public Transform transform(int nodeIndex) {
        Transform transform = transforms.get(nodeIndex);
        if (transform == null) {
            throw new IllegalArgumentException("Pose does not contain node index: " + nodeIndex);
        }
        return transform;
    }

    public Map<Integer, Transform> transforms() {
        return transforms;
    }
}
