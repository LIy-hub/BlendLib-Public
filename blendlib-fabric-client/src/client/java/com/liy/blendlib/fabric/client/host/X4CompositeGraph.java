package com.liy.blendlib.fabric.client.host;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded, acyclic Experimental composite-host graph. */
public record X4CompositeGraph(Map<X4HostIdentity, List<X4HostIdentity>> edges) {
    /** Maximum composite members, preventing unbounded presentation recursion. */
    public static final int MAX_NODES = 32;

    /** Maximum directed links in one composite graph. */
    public static final int MAX_EDGES = 128;

    /** Maximum root-to-leaf depth. */
    public static final int MAX_DEPTH = 8;

    public X4CompositeGraph {
        edges = immutableAndValidate(edges);
    }

    /** Whether a candidate identity is a declared graph member. */
    public boolean contains(X4HostIdentity identity) {
        return edges.containsKey(Objects.requireNonNull(identity, "identity"));
    }

    /** Immutable direct children of a declared graph member. */
    public List<X4HostIdentity> children(X4HostIdentity identity) {
        List<X4HostIdentity> children = edges.get(Objects.requireNonNull(identity, "identity"));
        if (children == null) {
            throw new IllegalArgumentException("Composite graph does not contain the requested member");
        }
        return children;
    }

    /** Computes the validated root-to-leaf depth from one declared root. */
    public int depthFrom(X4HostIdentity root) {
        Objects.requireNonNull(root, "root");
        if (!contains(root)) {
            throw new IllegalArgumentException("Composite root is not part of its graph");
        }
        return depthFrom(root, edges, new LinkedHashMap<>());
    }

    /**
     * Requires one supplied host identity to be the exact graph root and to reach every member.
     * All graph members are already scope-checked during construction, so this rejects both a
     * disconnected graph and a root that belongs to another host/session scope.
     */
    public void requireRootedScope(X4HostIdentity root) {
        Objects.requireNonNull(root, "root");
        if (!contains(root)) {
            throw new IllegalArgumentException("Composite graph root is not a declared member");
        }
        ArrayDeque<X4HostIdentity> pending = new ArrayDeque<>();
        LinkedHashSet<X4HostIdentity> reached = new LinkedHashSet<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            X4HostIdentity current = pending.removeFirst();
            if (!reached.add(current)) {
                continue;
            }
            pending.addAll(edges.get(current));
        }
        if (reached.size() != edges.size()) {
            throw new IllegalArgumentException("Composite graph must be connected from its configured root");
        }
        depthFrom(root);
    }

    private static Map<X4HostIdentity, List<X4HostIdentity>> immutableAndValidate(
            Map<X4HostIdentity, List<X4HostIdentity>> input) {
        Objects.requireNonNull(input, "edges");
        if (input.size() < 2 || input.size() > MAX_NODES) {
            throw new IllegalArgumentException("Composite graph node count must be in [2, " + MAX_NODES + "]");
        }
        Map<X4HostIdentity, List<X4HostIdentity>> copy = new LinkedHashMap<>();
        X4HostIdentity scopeAnchor = null;
        int edgeCount = 0;
        for (Map.Entry<X4HostIdentity, List<X4HostIdentity>> entry : input.entrySet()) {
            X4HostIdentity source = Objects.requireNonNull(entry.getKey(), "composite source");
            if (scopeAnchor == null) {
                scopeAnchor = source;
            } else if (!scopeAnchor.scope().equals(source.scope())) {
                throw new IllegalArgumentException("Composite graph members must share one world/session scope");
            }
            List<X4HostIdentity> suppliedChildren = Objects.requireNonNull(entry.getValue(), "composite children");
            LinkedHashSet<X4HostIdentity> uniqueChildren = new LinkedHashSet<>();
            for (X4HostIdentity child : suppliedChildren) {
                child = Objects.requireNonNull(child, "composite child");
                if (!scopeAnchor.scope().equals(child.scope())) {
                    throw new IllegalArgumentException("Composite graph members must share one world/session scope");
                }
                if (source.equals(child)) {
                    throw new IllegalArgumentException("Composite graph cannot contain a self edge");
                }
                if (!uniqueChildren.add(child)) {
                    throw new IllegalArgumentException("Composite graph cannot contain duplicate edges");
                }
            }
            edgeCount += uniqueChildren.size();
            copy.put(source, List.copyOf(uniqueChildren));
        }
        if (edgeCount > MAX_EDGES) {
            throw new IllegalArgumentException("Composite graph edge count exceeds " + MAX_EDGES);
        }
        for (List<X4HostIdentity> children : copy.values()) {
            for (X4HostIdentity child : children) {
                if (!copy.containsKey(child)) {
                    throw new IllegalArgumentException("Composite graph cannot reference an undeclared member");
                }
            }
        }
        validateAcyclicAndDepth(copy);
        return Collections.unmodifiableMap(copy);
    }

    private static void validateAcyclicAndDepth(Map<X4HostIdentity, List<X4HostIdentity>> graph) {
        Map<X4HostIdentity, Integer> incomingEdges = new LinkedHashMap<>();
        Map<X4HostIdentity, Integer> longestDepth = new LinkedHashMap<>();
        for (X4HostIdentity node : graph.keySet()) {
            incomingEdges.put(node, 0);
            longestDepth.put(node, 1);
        }
        for (List<X4HostIdentity> children : graph.values()) {
            for (X4HostIdentity child : children) {
                incomingEdges.compute(child, (ignored, count) -> count + 1);
            }
        }
        ArrayDeque<X4HostIdentity> ready = new ArrayDeque<>();
        for (Map.Entry<X4HostIdentity, Integer> entry : incomingEdges.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }
        int processed = 0;
        while (!ready.isEmpty()) {
            X4HostIdentity node = ready.removeFirst();
            processed++;
            int nodeDepth = longestDepth.get(node);
            if (nodeDepth > MAX_DEPTH) {
                throw new IllegalArgumentException("Composite graph depth exceeds " + MAX_DEPTH);
            }
            for (X4HostIdentity child : graph.get(node)) {
                longestDepth.compute(child, (ignored, depth) -> Math.max(depth, nodeDepth + 1));
                int remaining = incomingEdges.compute(child, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(child);
                }
            }
        }
        if (processed != graph.size()) {
            throw new IllegalArgumentException("Composite graph contains a cycle");
        }
    }

    private static int depthFrom(
            X4HostIdentity node,
            Map<X4HostIdentity, List<X4HostIdentity>> graph,
            Map<X4HostIdentity, Integer> memoizedDepths) {
        Integer existing = memoizedDepths.get(node);
        if (existing != null) {
            return existing;
        }
        int result = 1;
        for (X4HostIdentity child : graph.get(node)) {
            result = Math.max(result, 1 + depthFrom(child, graph, memoizedDepths));
        }
        memoizedDepths.put(node, result);
        return result;
    }
}
