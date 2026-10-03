package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-frame runtime multipliers, keyed by controller and layer rather than layer alone.
 * Omitted targets use one; effective layer weight is configured weight times this multiplier.
 * These values affect composition only and never pause or reset controller time or transitions.
 */
public final class AnimationV2LayerWeights {
    private static final AnimationV2LayerWeights EMPTY = new AnimationV2LayerWeights(Map.of());
    private final Map<Key, Float> multipliers;

    public AnimationV2LayerWeights(Map<Key, Float> multipliers) {
        Objects.requireNonNull(multipliers, "multipliers");
        if (multipliers.size() > AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE
                * AnimationV2Limits.MAX_LAYERS_PER_CONTROLLER) {
            throw new IllegalArgumentException("layer multiplier count exceeds the fixed v2 layer bound");
        }
        LinkedHashMap<Key, Float> copied = new LinkedHashMap<>();
        for (Map.Entry<Key, Float> entry : multipliers.entrySet()) {
            Key key = Objects.requireNonNull(entry.getKey(), "layer key");
            float value = Objects.requireNonNull(entry.getValue(), "layer multiplier");
            if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
                throw new IllegalArgumentException("layer multiplier must be finite and in [0, 1]");
            }
            copied.put(key, value);
        }
        this.multipliers = Collections.unmodifiableMap(copied);
    }

    public static AnimationV2LayerWeights empty() {
        return EMPTY;
    }

    public Map<Key, Float> multipliers() {
        return multipliers;
    }

    public float multiplier(Key key) {
        return multipliers.getOrDefault(Objects.requireNonNull(key, "key"), 1.0F);
    }

    /** Canonical pair: identically named layers in different controllers are independent targets. */
    public record Key(BlendResourceId controllerId, BlendResourceId layerId) {
        public Key {
            Objects.requireNonNull(controllerId, "controllerId");
            Objects.requireNonNull(layerId, "layerId");
            AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "controller id");
            AnimationV2Limits.requireCanonicalIdLength(layerId.value(), "layer id");
        }
    }
}
