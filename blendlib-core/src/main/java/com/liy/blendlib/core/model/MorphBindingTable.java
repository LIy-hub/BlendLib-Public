package com.liy.blendlib.core.model;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable complete node/target binding layout; its identity identifies a model generation. */
public final class MorphBindingTable {
    private static final MorphBindingTable EMPTY = new MorphBindingTable(List.of(), Map.of());
    private final List<Binding> bindings;
    private final Map<Integer, Binding> byNode;
    private final Map<BlendResourceId, Control> controls;
    private final int weightCount;
    public MorphBindingTable(List<Binding> bindings, Map<BlendResourceId, Control> controls) {
        this.bindings = List.copyOf(bindings);
        if (this.bindings.size() > 128 || controls.size() > 1024) throw new IllegalArgumentException("Morph binding limit exceeded");
        Map<Integer, Binding> nodes = new HashMap<>();
        int count = 0;
        for (Binding b : this.bindings) {
            if (nodes.putIfAbsent(b.nodeIndex(), b) != null || b.offset() != count) throw new IllegalArgumentException("Invalid morph binding order");
            count = Math.addExact(count, b.targetCount());
        }
        this.weightCount = count;
        this.byNode = Collections.unmodifiableMap(nodes);
        Map<BlendResourceId, Control> copied = new LinkedHashMap<>();
        boolean[] used = new boolean[count];
        for (var entry : controls.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "control alias");
            Control c = Objects.requireNonNull(entry.getValue(), "control");
            Binding b = nodes.get(c.nodeIndex());
            if (b == null || c.targetIndex() < 0 || c.targetIndex() >= b.targetCount()
                    || c.weightIndex() != b.offset() + c.targetIndex() || used[c.weightIndex()]
                    || c.minWeight() != b.minWeight(c.targetIndex()) || c.maxWeight() != b.maxWeight(c.targetIndex())) {
                throw new IllegalArgumentException("Duplicate or unbound morph control");
            }
            used[c.weightIndex()] = true;
            copied.put(entry.getKey(), c);
        }
        if (copied.size() != count) throw new IllegalArgumentException("Each morph node/target requires exactly one control");
        this.controls = Collections.unmodifiableMap(copied);
    }
    public static MorphBindingTable empty() { return EMPTY; }
    public List<Binding> bindings() { return bindings; }
    public Map<BlendResourceId, Control> controls() { return controls; }
    public Binding binding(int nodeIndex) { return byNode.get(nodeIndex); }
    public int weightCount() { return weightCount; }
    public boolean isEmpty() { return bindings.isEmpty(); }
    public static final class Binding {
        private final int nodeIndex, offset;
        private final List<String> targetNames;
        private final float[] defaults, minimum, maximum;
        public Binding(int nodeIndex, List<String> targetNames, int offset, float[] defaults, float[] minimum, float[] maximum) {
            this.targetNames = List.copyOf(targetNames);
            if (nodeIndex < 0 || offset < 0 || this.targetNames.isEmpty() || this.targetNames.size() > 8
                    || new HashSet<>(this.targetNames).size() != this.targetNames.size()
                    || defaults.length != targetCount() || minimum.length != targetCount() || maximum.length != targetCount()) {
                throw new IllegalArgumentException("Invalid morph node binding");
            }
            for (String name : this.targetNames) MorphTargetSet.validateTargetName(name);
            this.nodeIndex = nodeIndex; this.offset = offset;
            this.defaults = defaults.clone(); this.minimum = minimum.clone(); this.maximum = maximum.clone();
            for (int t = 0; t < targetCount(); t++) {
                if (!Float.isFinite(this.minimum[t]) || !Float.isFinite(this.maximum[t])
                        || this.minimum[t] < -2 || this.minimum[t] > 0 || this.maximum[t] < 0 || this.maximum[t] > 2
                        || !Float.isFinite(this.defaults[t]) || this.defaults[t] < this.minimum[t] || this.defaults[t] > this.maximum[t]) {
                    throw new IllegalArgumentException("Morph range must include zero within [-2,2] and contain its default");
                }
            }
        }
        public int nodeIndex() { return nodeIndex; }
        public int offset() { return offset; }
        public List<String> targetNames() { return targetNames; }
        public int targetCount() { return targetNames.size(); }
        public float defaultWeight(int target) { return defaults[target]; }
        public float minWeight(int target) { return minimum[target]; }
        public float maxWeight(int target) { return maximum[target]; }
    }
    public record Control(int nodeIndex, int targetIndex, int weightIndex, float minWeight, float maxWeight) {
        public Control {
            if (nodeIndex < 0 || targetIndex < 0 || weightIndex < 0 || !Float.isFinite(minWeight) || !Float.isFinite(maxWeight)
                    || minWeight < -2 || minWeight > 0 || maxWeight < 0 || maxWeight > 2) throw new IllegalArgumentException("Invalid morph control");
        }
    }
}
