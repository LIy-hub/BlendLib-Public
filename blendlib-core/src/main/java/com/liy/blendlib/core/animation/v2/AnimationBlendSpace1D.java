package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.*;

/**
 * Immutable, bounded, fixed-cadence one-dimensional blendspace over existing model layers.
 * Samples must already share authored gait phase. This does not infer stride, root motion or
 * event leadership. Inactive members explicitly receive zero; unrelated weights are untouched.
 */
public final class AnimationBlendSpace1D {
    private final List<Sample> samples;
    private final Set<BlendResourceId> members;
    private final double cycleSeconds;
    private final AnimationBlendSpaceSyncGroup syncGroup;

    public AnimationBlendSpace1D(List<Sample> samples, double cycleSeconds) {
        Objects.requireNonNull(samples, "samples");
        if (samples.size() < 2 || samples.size() > AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE)
            throw new IllegalArgumentException("blendspace requires 2 to 16 samples");
        this.samples = List.copyOf(samples);
        if (!Double.isFinite(cycleSeconds) || cycleSeconds <= 0
                || cycleSeconds > AnimationV2Limits.MAX_CLIP_DURATION_SECONDS)
            throw new IllegalArgumentException("cycleSeconds must be finite and in (0, 600]");
        this.cycleSeconds = cycleSeconds;
        Set<BlendResourceId> ids = new LinkedHashSet<>();
        double previous = Double.NEGATIVE_INFINITY;
        for (Sample sample : this.samples) {
            if (sample.position() <= previous) throw new IllegalArgumentException("sample positions must strictly increase");
            if (!ids.add(sample.layerId())) throw new IllegalArgumentException("duplicate blendspace layer: " + sample.layerId());
            previous = sample.position();
        }
        members = Collections.unmodifiableSet(ids);
        syncGroup = new AnimationBlendSpaceSyncGroup(members, cycleSeconds);
    }

    public record Sample(double position, BlendResourceId layerId) {
        public Sample {
            if (!Double.isFinite(position)) throw new IllegalArgumentException("sample position must be finite");
            Objects.requireNonNull(layerId, "layerId");
            AnimationV2Limits.requireCanonicalIdLength(layerId.value(), "blendspace layer id");
        }
    }

    public List<Sample> samples() { return samples; }
    public Set<BlendResourceId> memberLayerIds() { return members; }
    public double cycleSeconds() { return cycleSeconds; }

    /** Endpoint-clamped adjacent interpolation with exact sample hits and overflow-safe intervals. */
    public AnimationV2LayerWeights weights(double parameter) {
        if (!Double.isFinite(parameter)) throw new IllegalArgumentException("blendspace parameter must be finite");
        Map<AnimationV2LayerWeights.Key, Float> result = new LinkedHashMap<>();
        for (Sample sample : samples) result.put(key(sample.layerId()), 0F);
        int right = 0;
        while (right < samples.size() && parameter > samples.get(right).position()) right++;
        if (right == 0) result.put(key(samples.getFirst().layerId()), 1F);
        else if (right == samples.size()) result.put(key(samples.getLast().layerId()), 1F);
        else if (parameter == samples.get(right).position()) result.put(key(samples.get(right).layerId()), 1F);
        else {
            double low = samples.get(right - 1).position(), high = samples.get(right).position();
            double span = high - low;
            // Opposite-sign near-MAX doubles can overflow subtraction. Halving is safe here:
            // an overflowing span implies both endpoints have large magnitude.
            double fraction = Double.isFinite(span) ? (parameter - low) / span
                    : (parameter * 0.5 - low * 0.5) / (high * 0.5 - low * 0.5);
            float upper = (float) Math.max(0.0, Math.min(fraction, 1.0));
            result.put(key(samples.get(right - 1).layerId()), 1F - upper);
            result.put(key(samples.get(right).layerId()), upper);
        }
        return new AnimationV2LayerWeights(result);
    }

    /** Stable immutable fixed-cycle group; ordinary frames only change solver weights. */
    public AnimationBlendSpaceSyncGroup syncGroup() { return syncGroup; }
    public void validateExternalWeights(AnimationV2LayerWeights weights) { syncGroup.validateExternalWeights(weights); }
    public void validateExternalCommands(List<AnimationV2Command> commands) { syncGroup.validateExternalCommands(commands); }
    public Binding bind(AnimationV2InstancePlan plan) { return new Binding(syncGroup.bind(plan)); }
    private static AnimationV2LayerWeights.Key key(BlendResourceId id) { return new AnimationV2LayerWeights.Key(id, id); }

    /** Immutable generation-local resolution. Existing 1D signature retained. */
    public static final class Binding {
        private final AnimationBlendSpaceSyncGroup.Binding binding;
        private Binding(AnimationBlendSpaceSyncGroup.Binding binding) { this.binding = binding; }
        public List<AnimationV2Command> commands(double phase, long sequence) { return binding.commands(phase, sequence); }
    }
}
