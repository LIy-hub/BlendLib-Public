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

    /** Rejects competing member multipliers even if they equal the generated value. */
    public void validateExternalWeights(AnimationV2LayerWeights weights) {
        Objects.requireNonNull(weights, "weights");
        for (var key : weights.multipliers().keySet())
            if (members.contains(key.controllerId())) throw new IllegalArgumentException("blendspace owns member weights: " + key);
    }

    /** Rejects member commands before live clocks or cue captures are changed. */
    public void validateExternalCommands(List<AnimationV2Command> commands) {
        for (var command : Objects.requireNonNull(commands, "commands"))
            if (members.contains(command.controllerId())) throw new IllegalArgumentException("blendspace owns controller: " + command.controllerId());
    }

    /** Binds exact generation-prepared controllers; masks are compared by resolved bone weights. */
    public Binding bind(AnimationV2InstancePlan plan) { return new Binding(this, plan); }

    private static AnimationV2LayerWeights.Key key(BlendResourceId id) { return new AnimationV2LayerWeights.Key(id, id); }

    /** Immutable generation-local resolution. Do not reuse with a different model plan. */
    public static final class Binding {
        private final List<AnimationV2ControllerDefinition> controllers;
        private final List<Double> rates;
        private Binding(AnimationBlendSpace1D definition, AnimationV2InstancePlan plan) {
            Objects.requireNonNull(plan, "plan");
            List<AnimationV2ControllerDefinition> resolved = new ArrayList<>();
            List<Double> speeds = new ArrayList<>();
            AnimationV2ControllerDefinition first = null;
            for (Sample sample : definition.samples) {
                var controller = plan.controller(sample.layerId());
                var layer = controller.layers().getFirst();
                var state = controller.initialStateDefinition();
                if (controller.layers().size() != 1 || !layer.id().equals(sample.layerId())
                        || layer.mode() != AnimationV2LayerMode.OVERRIDE || layer.weight() != 1F || layer.exclusive())
                    throw new IllegalArgumentException("blendspace requires unit-weight independent OVERRIDE layers");
                if (first != null && (controller.priority() != first.priority()
                        || layer.priority() != first.layers().getFirst().priority()
                        || !Arrays.equals(layer.mask().weights(), first.layers().getFirst().mask().weights())))
                    throw new IllegalArgumentException("blendspace members must share priority and mask");
                if (state.playbackMode() != AnimationV2PlaybackMode.LOOP || state.next() != null || state.durationSeconds() <= 0)
                    throw new IllegalArgumentException("blendspace initial states must be positive-duration continuous loops");
                double effective = state.durationSeconds() / definition.cycleSeconds;
                double rate = effective / state.speed();
                AnimationV2Limits.requireSpeed(rate, "blendspace command rate");
                if (!AnimationV2Limits.isValidEffectivePlaybackSpeed(state.speed(), rate))
                    throw new IllegalArgumentException("blendspace effective rate exceeds v2 bounds");
                resolved.add(controller); speeds.add(rate); first = controller;
            }
            controllers = List.copyOf(resolved); rates = List.copyOf(speeds);
        }

        /** Initialization/recovery only. Ordinary weight changes must never generate new commands. */
        public List<AnimationV2Command> commands(double phase, long sequence) {
            if (!Double.isFinite(phase) || phase < 0 || phase >= 1) throw new IllegalArgumentException("phase must be in [0, 1)");
            List<AnimationV2Command> commands = new ArrayList<>();
            for (int i = 0; i < controllers.size(); i++) {
                var controller = controllers.get(i);
                commands.add(new AnimationV2Command(controller.id(), controller.initialState(), sequence,
                        phase * controller.initialStateDefinition().durationSeconds(), rates.get(i)));
            }
            return List.copyOf(commands);
        }
    }
}
