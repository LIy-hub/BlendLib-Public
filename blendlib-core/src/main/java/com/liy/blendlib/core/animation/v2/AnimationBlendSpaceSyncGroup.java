package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.*;

/** Immutable member ownership and positive-cadence binding shared by the bounded 1D and 2D solvers.
 * Created by a blendspace definition; this does not infer phase or choose event leaders. */
public final class AnimationBlendSpaceSyncGroup {
    public static final double MIN_CADENCE_MULTIPLIER = 1.0D / 64.0D;
    public static final double MAX_CADENCE_MULTIPLIER = 64.0D;
    private final Set<BlendResourceId> members;
    private final double cycleSeconds;
    AnimationBlendSpaceSyncGroup(Collection<BlendResourceId> members, double cycleSeconds) {
        this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
        this.cycleSeconds = cycleSeconds;
    }
    public Set<BlendResourceId> memberLayerIds() { return members; }
    public double cycleSeconds() { return cycleSeconds; }
    /** Validates the group-level bound; a binding also checks every derived member rate. */
    public static void validateCadenceMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < MIN_CADENCE_MULTIPLIER
                || multiplier > MAX_CADENCE_MULTIPLIER)
            throw new IllegalArgumentException("blendspace cadence multiplier must be finite and in ["
                    + MIN_CADENCE_MULTIPLIER + ", " + MAX_CADENCE_MULTIPLIER + "]");
    }
    public void validateExternalWeights(AnimationV2LayerWeights weights) {
        for (var key : Objects.requireNonNull(weights, "weights").multipliers().keySet())
            if (members.contains(key.controllerId())) throw new IllegalArgumentException("blendspace owns member weights: " + key);
    }
    public void validateExternalCommands(List<AnimationV2Command> commands) {
        for (var command : Objects.requireNonNull(commands, "commands"))
            if (members.contains(command.controllerId())) throw new IllegalArgumentException("blendspace owns controller: " + command.controllerId());
    }
    public Binding bind(AnimationV2InstancePlan plan) { return new Binding(this, plan); }

    /**
     * Immutable complete member-rate vector created only by a generation-local binding. Its plan identity and
     * member states cannot be replaced, so even an otherwise equivalent reloaded plan requires a fresh binding.
     */
    public static final class RateUpdate {
        private final AnimationV2InstancePlan plan;
        private final List<AnimationV2ControllerDefinition> controllers;
        private final double cadenceMultiplier;
        private final Map<BlendResourceId, Double> commandRates;

        private RateUpdate(AnimationV2InstancePlan plan, List<AnimationV2ControllerDefinition> controllers,
                double cadenceMultiplier, Map<BlendResourceId, Double> commandRates) {
            this.plan = plan;
            this.controllers = controllers;
            this.cadenceMultiplier = cadenceMultiplier;
            this.commandRates = Collections.unmodifiableMap(new LinkedHashMap<>(commandRates));
        }

        public double cadenceMultiplier() { return cadenceMultiplier; }
        /** Complete immutable command-speed multipliers, including members whose presentation weight is zero. */
        public Map<BlendResourceId, Double> commandRates() { return commandRates; }
        AnimationV2InstancePlan plan() { return plan; }
        List<AnimationV2ControllerDefinition> controllers() { return controllers; }
    }

    /** Immutable generation-local resolution. Do not reuse with a different model plan. */
    public static final class Binding {
        private final AnimationV2InstancePlan plan;
        private final List<AnimationV2ControllerDefinition> controllers;
        private final List<Double> rates;
        private final List<Double> effectiveRates;
        private Binding(AnimationBlendSpaceSyncGroup definition, AnimationV2InstancePlan plan) {
            this.plan = Objects.requireNonNull(plan, "plan");
            List<AnimationV2ControllerDefinition> resolved = new ArrayList<>();
            List<Double> speeds = new ArrayList<>();
            List<Double> effectiveSpeeds = new ArrayList<>();
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
                validateMemberRate(effective, state.speed(), rate);
                resolved.add(controller); speeds.add(rate); effectiveSpeeds.add(effective); first = controller;
            }
            controllers = List.copyOf(resolved); rates = List.copyOf(speeds);
            effectiveRates = List.copyOf(effectiveSpeeds);
        }

        /**
         * Captures all rates for a future frame boundary without issuing a command or changing phase. Cadence one
         * must already have been bindable; a different multiplier cannot rescue an invalid baseline definition.
         */
        public RateUpdate rateUpdate(double multiplier) {
            validateCadenceMultiplier(multiplier);
            Map<BlendResourceId, Double> updated = new LinkedHashMap<>();
            for (int i = 0; i < controllers.size(); i++) {
                var controller = controllers.get(i);
                double stateSpeed = controller.initialStateDefinition().speed();
                double effective = effectiveRates.get(i) * multiplier;
                double rate = effective / stateSpeed;
                validateMemberRate(effective, stateSpeed, rate);
                updated.put(controller.id(), rate);
            }
            return new RateUpdate(plan, controllers, multiplier, updated);
        }

        private static void validateMemberRate(double effective, double stateSpeed, double rate) {
            AnimationV2Limits.requireSpeed(effective, "blendspace effective rate");
            AnimationV2Limits.requireSpeed(rate, "blendspace command rate");
            // Division followed by multiplication can round beyond a bound even when the desired effective rate
            // is representable and valid. Validate the exact product that controller advancement will consume.
            if (!AnimationV2Limits.isValidEffectivePlaybackSpeed(stateSpeed, rate))
                throw new IllegalArgumentException("blendspace rounded effective rate exceeds v2 bounds");
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
