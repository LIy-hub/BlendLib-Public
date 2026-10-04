package com.liy.blendlib.core.animation.rules;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.json.StrictJsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict, bounded version-one parser for optional locomotion rule sidecars. */
public final class LocomotionRuleParser {
    public static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final int MAX_ERROR_LENGTH = 240;
    private static final StrictJsonParser.Limits JSON_LIMITS = new StrictJsonParser.Limits(
            5, 4, LocomotionRules.MAX_RULES, MAX_INPUT_BYTES, 2048, MAX_INPUT_BYTES, MAX_INPUT_BYTES);
    private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "default", "minimum_interval_ticks", "rules");
    private static final Set<String> RULE_FIELDS = Set.of("animation", "conditions");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("input", "equals");
    private static final Set<String> MINIMUM_FIELDS = Set.of("input", "enter_min", "exit_min");
    private static final Set<String> MAXIMUM_FIELDS = Set.of("input", "enter_max", "exit_max");

    private LocomotionRuleParser() {
    }

    /**
     * Parses UTF-8 JSON and verifies every target is a declared loop without a next state.
     * Invalid sidecars throw {@link IllegalArgumentException} with a bounded message.
     */
    public static LocomotionRules parse(byte[] bytes, AnimationDefinition animation) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(animation, "animation");
        try {
            return parseRules(object(StrictJsonParser.parse(bytes, JSON_LIMITS), "root"), animation);
        } catch (IllegalArgumentException exception) {
            String message = "Invalid locomotion rules: " + exception.getMessage();
            if (message.length() > MAX_ERROR_LENGTH) {
                message = message.substring(0, MAX_ERROR_LENGTH - 3) + "...";
            }
            throw new IllegalArgumentException(message);
        }
    }

    private static LocomotionRules parseRules(JsonObject root, AnimationDefinition animation) {
        fields(root, ROOT_FIELDS, "root");
        if (integer(root.get("schema_version"), "schema_version") != 1) {
            throw new IllegalArgumentException("schema_version must be 1");
        }
        BlendAnimationKey defaultAnimation = target(root.get("default"), animation);
        int interval = root.containsKey("minimum_interval_ticks")
                ? integer(root.get("minimum_interval_ticks"), "minimum_interval_ticks") : 0;
        JsonArray ruleValues = array(root.get("rules"), "rules");
        List<LocomotionRule> rules = new ArrayList<>(ruleValues.size());
        for (JsonValue value : ruleValues.values()) {
            JsonObject rule = object(value, "rule");
            fields(rule, RULE_FIELDS, "rule");
            BlendAnimationKey target = target(rule.get("animation"), animation);
            JsonArray conditionValues = array(rule.get("conditions"), "conditions");
            if (conditionValues.size() > LocomotionRule.MAX_CONDITIONS) {
                throw new IllegalArgumentException("Locomotion rule exceeds the 8-condition limit");
            }
            List<LocomotionCondition> conditions = new ArrayList<>(conditionValues.size());
            for (JsonValue condition : conditionValues.values()) {
                conditions.add(condition(object(condition, "condition")));
            }
            rules.add(new LocomotionRule(target, conditions));
        }
        return new LocomotionRules(defaultAnimation, interval, rules);
    }

    private static LocomotionCondition condition(JsonObject condition) {
        String input = string(condition.get("input"), "input");
        if (condition.containsKey("equals")) {
            fields(condition, BOOLEAN_FIELDS, "boolean condition");
            if (!(condition.get("equals") instanceof JsonBoolean expected)) {
                throw new IllegalArgumentException("equals must be a boolean");
            }
            return new LocomotionCondition.BooleanEquals(input, expected.value());
        }
        if (condition.containsKey("enter_min") || condition.containsKey("exit_min")) {
            fields(condition, MINIMUM_FIELDS, "minimum condition");
            return new LocomotionCondition.Minimum(input,
                    finite(condition.get("enter_min"), "enter_min"), finite(condition.get("exit_min"), "exit_min"));
        }
        fields(condition, MAXIMUM_FIELDS, "maximum condition");
        return new LocomotionCondition.Maximum(input,
                finite(condition.get("enter_max"), "enter_max"), finite(condition.get("exit_max"), "exit_max"));
    }

    private static BlendAnimationKey target(JsonValue value, AnimationDefinition definition) {
        BlendAnimationKey key = BlendAnimationKey.parse(string(value, "animation target"));
        AnimationStateDefinition state = definition.states().get(key.resourceId());
        if (state == null || !state.loop() || state.nextState() != null) {
            throw new IllegalArgumentException("Every locomotion target must be a declared continuous loop without next");
        }
        return key;
    }

    private static void fields(JsonObject value, Set<String> allowed, String label) {
        if (!allowed.containsAll(value.values().keySet())) {
            throw new IllegalArgumentException("Unknown field in locomotion " + label);
        }
    }

    private static JsonObject object(JsonValue value, String label) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return object;
    }

    private static JsonArray array(JsonValue value, String label) {
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        return array;
    }

    private static String string(JsonValue value, String label) {
        if (!(value instanceof JsonString string)) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        return string.value();
    }

    private static int integer(JsonValue value, String label) {
        if (!(value instanceof JsonNumber number) || !number.raw().matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException(label + " must be an integer");
        }
        return number.asIntExact();
    }

    private static double finite(JsonValue value, String label) {
        if (!(value instanceof JsonNumber number) || !Double.isFinite(number.asDouble())) {
            throw new IllegalArgumentException(label + " must be a finite number");
        }
        return number.asDouble();
    }
}
