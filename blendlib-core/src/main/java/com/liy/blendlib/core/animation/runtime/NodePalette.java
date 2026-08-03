package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable hierarchy-composed palette for rigid nodes and socket lookup. */
public final class NodePalette {
    private final Map<Integer, Transform> worldTransforms;

    private NodePalette(Map<Integer, Transform> worldTransforms) {
        this.worldTransforms = Collections.unmodifiableMap(new LinkedHashMap<>(worldTransforms));
    }

    /** Composes local transforms in parent-before-child order without mutating model data. */
    public static NodePalette from(LocalPose localPose, List<ModelNode> nodes) {
        Objects.requireNonNull(localPose, "localPose");
        Objects.requireNonNull(nodes, "nodes");
        Map<Integer, ModelNode> nodesByIndex = new LinkedHashMap<>();
        Map<Integer, Integer> parents = new LinkedHashMap<>();
        for (ModelNode node : nodes) {
            if (nodesByIndex.putIfAbsent(node.index(), node) != null) {
                throw new IllegalArgumentException("Model nodes must have unique indices: " + node.index());
            }
            localPose.transform(node.index());
        }
        for (ModelNode node : nodes) {
            for (int child : node.children()) {
                if (!nodesByIndex.containsKey(child)) {
                    throw new IllegalArgumentException("Model node references an absent child: " + child);
                }
                if (parents.putIfAbsent(child, node.index()) != null) {
                    throw new IllegalArgumentException("Model node has multiple parents: " + child);
                }
            }
        }

        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int nodeIndex : nodesByIndex.keySet()) {
            if (!parents.containsKey(nodeIndex)) {
                ready.addLast(nodeIndex);
            }
        }
        Map<Integer, Transform> world = new LinkedHashMap<>();
        while (!ready.isEmpty()) {
            int nodeIndex = ready.removeFirst();
            ModelNode node = nodesByIndex.get(nodeIndex);
            Transform parent = parents.containsKey(nodeIndex) ? world.get(parents.get(nodeIndex)) : null;
            if (parent != null) {
                world.put(nodeIndex, parent.compose(localPose.transform(nodeIndex)));
            } else {
                world.put(nodeIndex, localPose.transform(nodeIndex));
            }
            for (int child : node.children()) {
                ready.addLast(child);
            }
        }
        if (world.size() != nodesByIndex.size()) {
            throw new IllegalArgumentException("Model node hierarchy is cyclic or disconnected from its declared parents");
        }
        return new NodePalette(world);
    }

    /**
     * Composes only the retained default-scene roots and their reachable descendants.
     *
     * <p>The root sequence is preserved exactly. Structural nodes outside the selected scene are
     * validated for child-reference and parent ambiguity, but they do not require a local pose and
     * are omitted from the resulting palette. A selected root is authoritative for this traversal:
     * a structural parent outside the selected scene is not composed into it.</p>
     */
    public static NodePalette fromCanonicalScene(
            LocalPose localPose,
            List<ModelNode> nodes,
            List<Integer> defaultSceneRoots
    ) {
        Objects.requireNonNull(localPose, "localPose");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(defaultSceneRoots, "defaultSceneRoots");
        if (defaultSceneRoots.isEmpty()) {
            throw new IllegalArgumentException("Canonical scene must declare at least one root");
        }

        Map<Integer, ModelNode> nodesByIndex = new LinkedHashMap<>();
        for (ModelNode node : nodes) {
            ModelNode checkedNode = Objects.requireNonNull(node, "modelNode");
            if (nodesByIndex.putIfAbsent(checkedNode.index(), checkedNode) != null) {
                throw new IllegalArgumentException("Model nodes must have unique indices: " + checkedNode.index());
            }
        }

        Map<Integer, Integer> parents = new LinkedHashMap<>();
        for (ModelNode node : nodesByIndex.values()) {
            for (int child : node.children()) {
                if (!nodesByIndex.containsKey(child)) {
                    throw new IllegalArgumentException("Model node references an absent child: " + child);
                }
                if (parents.putIfAbsent(child, node.index()) != null) {
                    throw new IllegalArgumentException("Model node has multiple parents: " + child);
                }
            }
        }

        ArrayDeque<Integer> ready = new ArrayDeque<>();
        Map<Integer, Transform> world = new LinkedHashMap<>();
        for (Integer rootValue : defaultSceneRoots) {
            int root = Objects.requireNonNull(rootValue, "defaultSceneRoot");
            if (!nodesByIndex.containsKey(root)) {
                throw new IllegalArgumentException("Canonical scene root references an absent node: " + root);
            }
            if (world.putIfAbsent(root, localPose.transform(root)) != null) {
                throw new IllegalArgumentException("Canonical scene roots must be unique: " + root);
            }
            ready.addLast(root);
        }

        while (!ready.isEmpty()) {
            int parentIndex = ready.removeFirst();
            ModelNode parent = nodesByIndex.get(parentIndex);
            Transform parentWorld = world.get(parentIndex);
            for (int child : parent.children()) {
                if (world.containsKey(child)) {
                    throw new IllegalArgumentException("Canonical scene reaches node more than once: " + child);
                }
                world.put(child, parentWorld.compose(localPose.transform(child)));
                ready.addLast(child);
            }
        }
        return new NodePalette(world);
    }

    public Transform worldTransform(int nodeIndex) {
        Transform transform = worldTransforms.get(nodeIndex);
        if (transform == null) {
            throw new IllegalArgumentException("Node palette does not contain node index: " + nodeIndex);
        }
        return transform;
    }

    public Map<Integer, Transform> worldTransforms() {
        return worldTransforms;
    }
}
