package com.liy.blendlib.core.animation.rules;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.List;
import java.util.Objects;

/** One ordered, all-conditions-match locomotion rule. Empty conditions match unconditionally. */
public record LocomotionRule(BlendAnimationKey animation, List<LocomotionCondition> conditions) {
    public static final int MAX_CONDITIONS = 8;

    public LocomotionRule {
        animation = Objects.requireNonNull(animation, "animation");
        Objects.requireNonNull(conditions, "conditions");
        if (conditions.size() > MAX_CONDITIONS) {
            throw new IllegalArgumentException("Locomotion rule exceeds the 8-condition limit");
        }
        conditions = List.copyOf(conditions);
    }

    boolean matches(LocomotionInputs inputs, boolean exiting) {
        for (LocomotionCondition condition : conditions) {
            if (!condition.matches(inputs, exiting)) {
                return false;
            }
        }
        return true;
    }
}
