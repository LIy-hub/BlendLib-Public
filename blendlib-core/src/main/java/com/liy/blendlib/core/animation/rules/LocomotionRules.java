package com.liy.blendlib.core.animation.rules;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated immutable visual-only locomotion sidecar, in descending rule priority.
 * State holding and command sequencing belong to the caller, not this pure selector.
 */
public final class LocomotionRules {
    public static final int MAX_RULES = 32;
    public static final int MAX_MINIMUM_INTERVAL_TICKS = 200;

    private final BlendAnimationKey defaultAnimation;
    private final int minimumIntervalTicks;
    private final List<LocomotionRule> rules;
    private final Map<String, Boolean> booleanInputTypes;

    LocomotionRules(BlendAnimationKey defaultAnimation, int minimumIntervalTicks, List<LocomotionRule> rules) {
        this.defaultAnimation = Objects.requireNonNull(defaultAnimation, "defaultAnimation");
        if (minimumIntervalTicks < 0 || minimumIntervalTicks > MAX_MINIMUM_INTERVAL_TICKS) {
            throw new IllegalArgumentException("minimum_interval_ticks must be an integer from 0 to 200");
        }
        this.minimumIntervalTicks = minimumIntervalTicks;
        Objects.requireNonNull(rules, "rules");
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("Locomotion rules exceed the 32-rule limit");
        }
        this.rules = List.copyOf(rules);
        LinkedHashMap<String, Boolean> inputTypes = new LinkedHashMap<>();
        for (LocomotionRule rule : this.rules) {
            for (LocomotionCondition condition : rule.conditions()) {
                boolean booleanType = condition instanceof LocomotionCondition.BooleanEquals;
                Boolean previous = inputTypes.putIfAbsent(condition.input(), booleanType);
                if (previous != null && previous != booleanType) {
                    throw new IllegalArgumentException("Locomotion input cannot have both boolean and numeric conditions");
                }
                if (inputTypes.size() > LocomotionInputs.MAX_INPUT_KEYS) {
                    throw new IllegalArgumentException("Locomotion rules exceed the 32-input-key limit");
                }
            }
        }
        booleanInputTypes = Map.copyOf(inputTypes);
    }

    public BlendAnimationKey defaultAnimation() {
        return defaultAnimation;
    }

    public int minimumIntervalTicks() {
        return minimumIntervalTicks;
    }

    public List<LocomotionRule> rules() {
        return rules;
    }

    /**
     * Requires all referenced inputs, including those in currently inactive rules.
     * Invalid inputs must leave the runtime's last selected state unchanged.
     */
    public boolean inputsComplete(LocomotionInputs inputs) {
        if (inputs == null || !inputs.valid()) {
            return false;
        }
        for (Map.Entry<String, Boolean> entry : booleanInputTypes.entrySet()) {
            if (entry.getValue()) {
                if (inputs.booleans().get(entry.getKey()) == null) {
                    return false;
                }
            } else {
                Double value = inputs.numbers().get(entry.getKey());
                if (value == null || !Double.isFinite(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Selects an ordered rule index, or {@code -1} for the default. Only the current
     * rule uses exit thresholds; every other rule uses entry thresholds. Earlier
     * matching rules preempt the current rule. Invalid inputs are rejected rather
     * than silently switching to the default.
     *
     * @param inputs complete, finite typed input snapshot
     * @param currentRule previously selected rule index, or {@code -1} for default
     * @return selected rule index, or {@code -1}
     */
    public int selectRule(LocomotionInputs inputs, int currentRule) {
        requireRuleIndex(currentRule);
        if (!inputsComplete(inputs)) {
            throw new IllegalArgumentException("Locomotion inputs are missing, wrong-type, or non-finite");
        }
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).matches(inputs, index == currentRule)) {
                return index;
            }
        }
        return -1;
    }

    /** Resolves a selected rule index, with {@code -1} denoting the default. */
    public BlendAnimationKey animationForRule(int ruleIndex) {
        requireRuleIndex(ruleIndex);
        return ruleIndex == -1 ? defaultAnimation : rules.get(ruleIndex).animation();
    }

    private void requireRuleIndex(int ruleIndex) {
        if (ruleIndex < -1 || ruleIndex >= rules.size()) {
            throw new IllegalArgumentException("Locomotion rule index is outside the loaded rules");
        }
    }
}
