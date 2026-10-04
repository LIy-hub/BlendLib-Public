package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.MorphBindingTable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, frame-local named replacements applied after clip sampling. Missing names resume animation. */
public final class MorphFrameOverrides {
    private static final MorphFrameOverrides EMPTY = new MorphFrameOverrides(Map.of());
    private final Map<BlendResourceId, Float> values;

    public MorphFrameOverrides(Map<BlendResourceId, Float> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() > 1024) throw new IllegalArgumentException("Morph override batch exceeds 1024 controls");
        Map<BlendResourceId, Float> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "control");
            if (value == null || !Float.isFinite(value) || value < -2 || value > 2)
                throw new IllegalArgumentException("Morph weights must be finite and inside [-2,2]");
            copy.put(key, value);
        });
        this.values = Map.copyOf(copy);
    }

    public static MorphFrameOverrides empty() { return EMPTY; }
    public Map<BlendResourceId, Float> values() { return values; }
    public boolean isEmpty() { return values.isEmpty(); }

    /** Validates the entire batch before callers mutate clocks, controllers or caches. Never clamps. */
    public void validate(MorphBindingTable bindings) {
        Objects.requireNonNull(bindings, "bindings");
        values.forEach((name, value) -> {
            var control = bindings.controls().get(name);
            if (control == null) throw new IllegalArgumentException("Unknown morph control: " + name);
            if (value < control.minWeight() || value > control.maxWeight())
                throw new IllegalArgumentException("Morph control is outside its declared range: " + name);
        });
    }
}
