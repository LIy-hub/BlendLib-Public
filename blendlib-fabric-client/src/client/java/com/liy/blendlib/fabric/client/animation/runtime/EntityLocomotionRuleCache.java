package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.rules.*;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import java.util.*;

/** Single extraction-owner state, one rule-owned controller per entity renderer registration. */
final class EntityLocomotionRuleCache {
    private final Map<BlendInstanceKey.Entity, Capture> captures = new HashMap<>();
    private record Capture(Object source, Object owner, BlendModelKey model, long generation,
            BlendResourceId controller, LocomotionRules rules, int selectedRule,
            BlendAnimationKey animation, long sequence, double changedAt, double highTick, AnimationV2Command command) {
        boolean matches(Object source, Object owner, BlendModelKey model, long generation,
                BlendResourceId controller, LocomotionRules rules) {
            return this.source == source && this.owner == owner && this.model.equals(model)
                    && this.generation == generation && this.controller.equals(controller) && this.rules == rules;
        }
    }

    boolean contains(BlendInstanceKey.Entity instance) { return captures.containsKey(instance); }

    boolean replaced(BlendInstanceKey.Entity instance, Object source, Object owner, BlendModelKey model,
            long generation, BlendResourceId controller, LocomotionRules rules) {
        Capture previous = captures.get(instance);
        return previous != null && !previous.matches(source, owner, model, generation, controller, rules);
    }

    List<AnimationV2Command> capture(BlendInstanceKey.Entity instance, Object source, Object owner,
            BlendModelKey model, long generation, double ticks, BlendResourceId controller,
            LocomotionRules rules, LocomotionInputs inputs, List<AnimationV2Command> supplied) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(instance, "instance"); Objects.requireNonNull(model, "model");
        Objects.requireNonNull(controller, "controller"); Objects.requireNonNull(rules, "rules");
        if (generation < 0 || !Double.isFinite(ticks)) throw new IllegalArgumentException("Invalid rule clock/generation");
        var commands = List.copyOf(supplied);
        for (var command : commands) {
            if (command.controllerId().equals(controller))
                throw new IllegalArgumentException("Locomotion rules exclusively own controller " + controller);
        }
        Capture previous = captures.get(instance);
        if (previous != null && !previous.matches(source, owner, model, generation, controller, rules)) previous = null;
        if (!rules.inputsComplete(inputs)) {
            if (previous == null) captures.put(instance, new Capture(source, owner, model, generation,
                    controller, rules, -1, null, -1, ticks, ticks, null));
            return append(commands, previous == null ? null : previous.command);
        }
        double highTick = previous == null ? ticks : Math.max(previous.highTick, ticks);
        int selected = rules.selectRule(inputs, previous == null ? -1 : previous.selectedRule);
        BlendAnimationKey animation = rules.animationForRule(selected);
        if (previous != null && previous.animation != null && !animation.equals(previous.animation)
                && highTick - previous.changedAt < rules.minimumIntervalTicks()) {
            selected = previous.selectedRule;
            animation = previous.animation;
        }
        boolean changed = previous == null || previous.animation == null || !animation.equals(previous.animation);
        long sequence = previous == null ? 0 : changed ? Math.incrementExact(previous.sequence) : previous.sequence;
        var next = new Capture(source, owner, model, generation, controller, rules, selected, animation,
                sequence, changed ? highTick : previous.changedAt, highTick, changed
                        ? new AnimationV2Command(controller, animation, sequence, 0.0, 1.0) : previous.command);
        captures.put(instance, next);
        return append(commands, next.command);
    }

    private static List<AnimationV2Command> append(List<AnimationV2Command> commands, AnimationV2Command command) {
        if (command == null) return commands;
        var merged = new ArrayList<>(commands);
        merged.add(command);
        return List.copyOf(merged);
    }

    void retireEntity(int id) { captures.keySet().removeIf(key -> key.entityId() == id); }
    void retire(BlendInstanceKey key) { captures.remove(key); }
    void retainGeneration(long generation) { captures.values().removeIf(c -> c.generation != generation); }
    void clear() { captures.clear(); }
    int size() { return captures.size(); }
}
