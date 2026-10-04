package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.*;

/** Immutable member ownership and fixed-cycle binding shared by the bounded 1D and 2D solvers.
 * Created by a blendspace definition; this does not infer phase or choose event leaders. */
public final class AnimationBlendSpaceSyncGroup {
    private final Set<BlendResourceId> members;
    private final double cycleSeconds;
    AnimationBlendSpaceSyncGroup(Collection<BlendResourceId> members, double cycleSeconds) {
        this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
        this.cycleSeconds = cycleSeconds;
    }
    public Set<BlendResourceId> memberLayerIds() { return members; }
    public double cycleSeconds() { return cycleSeconds; }
    public void validateExternalWeights(AnimationV2LayerWeights weights) {
        for (var key : Objects.requireNonNull(weights, "weights").multipliers().keySet())
            if (members.contains(key.controllerId())) throw new IllegalArgumentException("blendspace owns member weights: " + key);
    }
    public void validateExternalCommands(List<AnimationV2Command> commands) {
        for (var command : Objects.requireNonNull(commands, "commands"))
            if (members.contains(command.controllerId())) throw new IllegalArgumentException("blendspace owns controller: " + command.controllerId());
    }
    public Binding bind(AnimationV2InstancePlan plan) { return new Binding(this, plan); }
    /** Immutable generation-local resolution. Do not reuse with a different model plan. */
    public static final class Binding {
        private final List<AnimationV2ControllerDefinition> controllers;
        private final List<Double> rates;
        private Binding(AnimationBlendSpaceSyncGroup definition, AnimationV2InstancePlan plan) {
            Objects.requireNonNull(plan, "plan");
            List<AnimationV2ControllerDefinition> resolved = new ArrayList<>();
            List<Double> speeds = new ArrayList<>();
            AnimationV2ControllerDefinition first = null;
            for (BlendResourceId member : definition.members) {
                var controller = plan.controller(member);
                var layer = controller.layers().getFirst();
                var state = controller.initialStateDefinition();
                if (controller.layers().size() != 1 || !layer.id().equals(member)
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
