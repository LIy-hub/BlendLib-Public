package com.liy.blendlib.core.procedural;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Shared bounded model topology validation; does not publish or authorize any rig plan. */
public final class AttachmentTopology {
    private AttachmentTopology() {}

    /**
     * Validates a complete node adjacency map and returns the longest path in edges.
     * Procedural publication, exact plan identity and retirement remain owned by ProceduralAttachmentGraph.
     */
    public static <K> int validate(Map<K, List<K>> graph, Comparator<? super K> order) {
        Objects.requireNonNull(graph, "graph");
        if (graph.isEmpty() || graph.size() > ProceduralLimits.MAX_ATTACHMENT_GRAPH_MODELS) {
            throw new IllegalArgumentException("attachment topology owner count exceeds the bound");
        }
        int visits = 0;
        for (Map.Entry<K, List<K>> entry : graph.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "owner");
            for (K child : Objects.requireNonNull(entry.getValue(), "children")) {
                if (++visits > ProceduralLimits.MAX_ATTACHMENT_GRAPH_RAW_DESCRIPTOR_VISITS) {
                    throw new IllegalArgumentException("attachment topology edge visits exceed the bound");
                }
                if (!graph.containsKey(Objects.requireNonNull(child, "child"))) {
                    throw new IllegalArgumentException("attachment topology is missing a child owner");
                }
            }
        }
        LinkedHashMap<K, Integer> incoming = new LinkedHashMap<>();
        LinkedHashMap<K, Integer> depths = new LinkedHashMap<>();
        for (K owner : graph.keySet()) {
            incoming.put(owner, 0);
            depths.put(owner, 0);
        }
        for (List<K> children : graph.values()) {
            for (K child : children) {
                incoming.put(child, Math.addExact(incoming.get(child), 1));
            }
        }
        PriorityQueue<K> ready = new PriorityQueue<>(Objects.requireNonNull(order, "order"));
        for (Map.Entry<K, Integer> entry : incoming.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        int processed = 0;
        int maximumDepth = 0;
        while (!ready.isEmpty()) {
            K owner = ready.remove();
            processed++;
            int ownerDepth = depths.get(owner);
            maximumDepth = Math.max(maximumDepth, ownerDepth);
            for (K child : graph.get(owner)) {
                int childDepth = Math.max(depths.get(child), Math.addExact(ownerDepth, 1));
                if (childDepth > ProceduralLimits.MAX_ATTACHMENT_DEPTH) {
                    throw new IllegalArgumentException("child-model attachment graph exceeds the X3 eight-edge depth limit");
                }
                depths.put(child, childDepth);
                int remaining = incoming.get(child) - 1;
                incoming.put(child, remaining);
                if (remaining == 0) {
                    ready.add(child);
                }
            }
        }
        if (processed != graph.size()) {
            throw new IllegalArgumentException("child-model attachment graph contains a directed cycle");
        }
        return maximumDepth;
    }

}
