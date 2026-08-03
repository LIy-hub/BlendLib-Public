package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.core.model.ModelNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Immutable name/index/parent view of one prepared strict-v1 animation rig.
 *
 * <p>Only names that occur exactly once may be resolved. Duplicate model-node names remain valid
 * asset data, but a procedural modifier must use an unambiguous name instead of silently choosing
 * one of them. The view owns no model asset, controller, entity, or mutable hierarchy collection.</p>
 */
public final class ClientAnimationRigView {
    private final Set<Integer> nodeIndices;
    private final Map<String, Integer> uniqueNodeIndices;
    private final Set<String> ambiguousNodeNames;
    private final Map<Integer, Integer> parentIndices;

    private ClientAnimationRigView(
            Set<Integer> nodeIndices,
            Map<String, Integer> uniqueNodeIndices,
            Set<String> ambiguousNodeNames,
            Map<Integer, Integer> parentIndices) {
        this.nodeIndices = Collections.unmodifiableSet(new LinkedHashSet<>(nodeIndices));
        this.uniqueNodeIndices = Collections.unmodifiableMap(new LinkedHashMap<>(uniqueNodeIndices));
        this.ambiguousNodeNames = Collections.unmodifiableSet(new LinkedHashSet<>(ambiguousNodeNames));
        this.parentIndices = Collections.unmodifiableMap(new LinkedHashMap<>(parentIndices));
    }

    /**
     * Freezes the validated model-node names and structural parent links for one asset generation.
     *
     * <p>This is preparation work over already-decoded immutable nodes. It performs no resource
     * lookup, I/O, descriptor parsing, or GLB parsing.</p>
     */
    static ClientAnimationRigView fromNodes(List<ModelNode> nodes) {
        List<ModelNode> checkedNodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        Set<Integer> indices = new LinkedHashSet<>();
        Map<String, Integer> uniqueNames = new LinkedHashMap<>();
        Set<String> ambiguousNames = new LinkedHashSet<>();
        for (ModelNode nodeValue : checkedNodes) {
            ModelNode node = Objects.requireNonNull(nodeValue, "modelNode");
            if (!indices.add(node.index())) {
                throw new IllegalArgumentException("Model nodes must have unique indices: " + node.index());
            }
            if (ambiguousNames.contains(node.name())) {
                continue;
            }
            Integer previous = uniqueNames.putIfAbsent(node.name(), node.index());
            if (previous != null) {
                uniqueNames.remove(node.name());
                ambiguousNames.add(node.name());
            }
        }

        Map<Integer, Integer> parents = new LinkedHashMap<>();
        for (ModelNode node : checkedNodes) {
            for (int child : node.children()) {
                if (!indices.contains(child)) {
                    throw new IllegalArgumentException("Model node references an absent child: " + child);
                }
                if (parents.putIfAbsent(child, node.index()) != null) {
                    throw new IllegalArgumentException("Model node has multiple parents: " + child);
                }
            }
        }
        return new ClientAnimationRigView(indices, uniqueNames, ambiguousNames, parents);
    }

    /** Returns the number of frozen model nodes in this rig view. */
    public int nodeCount() {
        return nodeIndices.size();
    }

    /** Returns the immutable complete node-index set used to validate a modifier's pose domain. */
    public Set<Integer> nodeIndices() {
        return nodeIndices;
    }

    /** Returns the immutable set of names that can be resolved without ambiguity. */
    public Set<String> uniqueNodeNames() {
        return uniqueNodeIndices.keySet();
    }

    /**
     * Resolves one unique node name, returning empty when the name is absent.
     *
     * @throws IllegalArgumentException when the requested model-node name is ambiguous
     */
    public OptionalInt nodeIndex(String uniqueNodeName) {
        String checkedName = requireNodeName(uniqueNodeName);
        requireUnambiguous(checkedName);
        Integer index = uniqueNodeIndices.get(checkedName);
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /** Resolves one unique node name or fails when it is absent or ambiguous. */
    public int requireNodeIndex(String uniqueNodeName) {
        String checkedName = requireNodeName(uniqueNodeName);
        return nodeIndex(checkedName).orElseThrow(
                () -> new IllegalArgumentException("Animation rig does not contain node name: " + checkedName));
    }

    /** Returns the structural parent index, or empty for a root node. */
    public OptionalInt parentIndex(int nodeIndex) {
        if (!nodeIndices.contains(nodeIndex)) {
            throw new IllegalArgumentException("Animation rig does not contain node index: " + nodeIndex);
        }
        Integer parent = parentIndices.get(nodeIndex);
        return parent == null ? OptionalInt.empty() : OptionalInt.of(parent);
    }

    /** Resolves a unique node name and returns its structural parent index, if any. */
    public OptionalInt parentIndex(String uniqueNodeName) {
        return parentIndex(requireNodeIndex(uniqueNodeName));
    }

    private void requireUnambiguous(String nodeName) {
        if (ambiguousNodeNames.contains(nodeName)) {
            throw new IllegalArgumentException("Animation rig node name is not unique: " + nodeName);
        }
    }

    private static String requireNodeName(String nodeName) {
        String checkedName = Objects.requireNonNull(nodeName, "nodeName");
        if (checkedName.isBlank()) {
            throw new IllegalArgumentException("nodeName must not be blank");
        }
        return checkedName;
    }
}
