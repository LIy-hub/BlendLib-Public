package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.MorphBindingTable;
import java.util.Arrays;
import java.util.Objects;

/** Complete immutable weights tied to one exact generation-owned binding table. */
public final class MorphWeights {
    private final MorphBindingTable bindings;
    private final float[] values;

    private MorphWeights(MorphBindingTable bindings, float[] values) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.values = values;
        if (values.length != bindings.weightCount()) throw new IllegalArgumentException("Morph weight cardinality mismatch");
        for (var binding : bindings.bindings()) {
            for (int target = 0; target < binding.targetCount(); target++) {
                float value = values[binding.offset() + target];
                if (!Float.isFinite(value) || value < binding.minWeight(target) || value > binding.maxWeight(target))
                    throw new IllegalArgumentException("Morph weight is outside the declared node/target range");
            }
        }
    }

    static MorphWeights takeOwnership(MorphBindingTable bindings, float[] values) { return new MorphWeights(bindings, values); }
    static float[] defaultValues(MorphBindingTable bindings) {
        float[] values = new float[bindings.weightCount()];
        for (var binding : bindings.bindings()) for (int target = 0; target < binding.targetCount(); target++)
            values[binding.offset() + target] = binding.defaultWeight(target);
        return values;
    }
    public static MorphWeights defaults(MorphBindingTable bindings) { return new MorphWeights(bindings, defaultValues(bindings)); }
    public MorphBindingTable bindings() { return bindings; }
    public float[] values() { return Arrays.copyOf(values, values.length); }
    public float weight(int nodeIndex, int targetIndex) {
        var binding = bindings.binding(nodeIndex);
        if (binding == null || targetIndex < 0 || targetIndex >= binding.targetCount())
            throw new IllegalArgumentException("Unknown morph node/target");
        return values[binding.offset() + targetIndex];
    }
    public float weight(BlendResourceId controlName) {
        var control = bindings.controls().get(Objects.requireNonNull(controlName, "controlName"));
        if (control == null) throw new IllegalArgumentException("Unknown morph control: " + controlName);
        return values[control.weightIndex()];
    }
    public MorphWeights overridden(MorphFrameOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides").validate(bindings);
        if (overrides.isEmpty()) return this;
        float[] copy = values();
        overrides.values().forEach((name, value) -> copy[bindings.controls().get(name).weightIndex()] = value);
        return new MorphWeights(bindings, copy);
    }
    public static MorphWeights blend(MorphWeights from, MorphWeights to, double amount) {
        Objects.requireNonNull(from, "from"); Objects.requireNonNull(to, "to");
        if (from.bindings != to.bindings) throw new IllegalArgumentException("Cannot blend different morph bindings");
        if (!Double.isFinite(amount) || amount < 0 || amount > 1) throw new IllegalArgumentException("Invalid morph blend amount");
        if (amount == 0) return from;
        if (amount == 1) return to;
        float[] values = new float[to.values.length];
        for (int i = 0; i < values.length; i++) values[i] = (float) ((1 - amount) * from.values[i] + amount * to.values[i]);
        return new MorphWeights(to.bindings, values);
    }
}
