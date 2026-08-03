package com.liy.blendlib.core.model;

import java.util.List;
import java.util.Objects;

/** Immutable node hierarchy entry; absent mesh and skin references are represented by {@code -1}. */
public record ModelNode(
        int index,
        String name,
        Transform localTransform,
        List<Integer> children,
        int meshIndex,
        int skinIndex,
        boolean cameraOrLightIgnored) {
    public ModelNode {
        if (index < 0 || meshIndex < -1 || skinIndex < -1) {
            throw new IllegalArgumentException("Node indices must be non-negative or absent (-1)");
        }
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Node name must not be blank");
        }
        localTransform = Objects.requireNonNull(localTransform, "localTransform");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        for (int child : children) {
            if (child < 0) {
                throw new IllegalArgumentException("Node child index must be non-negative");
            }
        }
    }
}
