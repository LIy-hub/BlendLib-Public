package com.liy.blendlib.core.animation.rules;

/** A typed, immutable condition with inclusive numeric entry and exit thresholds. */
public sealed interface LocomotionCondition permits LocomotionCondition.BooleanEquals,
        LocomotionCondition.Minimum, LocomotionCondition.Maximum {
    String input();

    /** Tests this condition; missing, wrong-type, or non-finite inputs never match. */
    boolean matches(LocomotionInputs inputs, boolean exiting);

    /** Requires one named boolean to equal the declared value. */
    record BooleanEquals(String input, boolean expected) implements LocomotionCondition {
        public BooleanEquals {
            input = LocomotionInputs.requireInputName(input);
        }

        @Override
        public boolean matches(LocomotionInputs inputs, boolean exiting) {
            Boolean value = inputs.booleans().get(input);
            return inputs.valid() && value != null && value == expected;
        }
    }

    /** Enters at or above {@code enterMin}; remains selected at or above {@code exitMin}. */
    record Minimum(String input, double enterMin, double exitMin) implements LocomotionCondition {
        public Minimum {
            input = LocomotionInputs.requireInputName(input);
            if (!Double.isFinite(enterMin) || !Double.isFinite(exitMin) || exitMin > enterMin) {
                throw new IllegalArgumentException("Locomotion minimum thresholds must be finite with exit_min <= enter_min");
            }
        }

        @Override
        public boolean matches(LocomotionInputs inputs, boolean exiting) {
            Double value = inputs.numbers().get(input);
            return inputs.valid() && value != null && Double.isFinite(value) && value >= (exiting ? exitMin : enterMin);
        }
    }

    /** Enters at or below {@code enterMax}; remains selected at or below {@code exitMax}. */
    record Maximum(String input, double enterMax, double exitMax) implements LocomotionCondition {
        public Maximum {
            input = LocomotionInputs.requireInputName(input);
            if (!Double.isFinite(enterMax) || !Double.isFinite(exitMax) || enterMax > exitMax) {
                throw new IllegalArgumentException("Locomotion maximum thresholds must be finite with enter_max <= exit_max");
            }
        }

        @Override
        public boolean matches(LocomotionInputs inputs, boolean exiting) {
            Double value = inputs.numbers().get(input);
            return inputs.valid() && value != null && Double.isFinite(value) && value <= (exiting ? exitMax : enterMax);
        }
    }
}
