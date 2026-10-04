package com.liy.blendlib.core.animation.rules;

import static com.liy.blendlib.core.animation.rules.LocomotionRuleParserTest.parse;
import static com.liy.blendlib.core.animation.rules.LocomotionRuleParserTest.rule;
import static com.liy.blendlib.core.animation.rules.LocomotionRuleParserTest.sidecar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocomotionRulesTest {
    private static final LocomotionRules RULES = parse(sidecar("""
            {"animation":"fixture:run","conditions":[
                {"input":"grounded","equals":true},
                {"input":"speed","enter_min":0.7,"exit_min":0.5}]},
            {"animation":"fixture:walk","conditions":[
                {"input":"grounded","equals":true},
                {"input":"speed","enter_min":0.2,"exit_min":0.1}]}
            """));

    @Test
    void retainsCurrentRuleThroughJitterAndUsesInclusiveThresholds() {
        assertEquals(-1, RULES.selectRule(inputs(true, 0.199), -1));
        assertEquals(1, RULES.selectRule(inputs(true, 0.2), -1));
        for (double jitter : new double[] {0.199, 0.201, 0.1, 0.15}) {
            assertEquals(1, RULES.selectRule(inputs(true, jitter), 1));
        }
        assertEquals(-1, RULES.selectRule(inputs(true, 0.099), 1));
        assertEquals(0, RULES.selectRule(inputs(true, 0.7), 1));
        assertEquals(0, RULES.selectRule(inputs(true, 0.5), 0));
        assertEquals(1, RULES.selectRule(inputs(true, 0.499), 0));
    }

    @Test
    void earlierRulesPreemptWithEntryThresholdsAndLaterRulesCannotPreempt() {
        assertEquals(1, RULES.selectRule(inputs(true, 0.699), 1));
        assertEquals(0, RULES.selectRule(inputs(true, 0.7), 1));
        assertEquals(0, RULES.selectRule(inputs(true, 0.6), 0));
        assertEquals(0, RULES.selectRule(inputs(true, 1), -1));
        assertEquals(-1, RULES.selectRule(inputs(false, 1), 0));
        assertEquals(BlendAnimationKey.parse("fixture:idle"), RULES.animationForRule(-1));
        assertEquals(BlendAnimationKey.parse("fixture:run"), RULES.animationForRule(0));
        assertEquals(BlendAnimationKey.parse("fixture:walk"), RULES.animationForRule(1));
    }

    @Test
    void appliesMaximumHysteresisAndConjunctiveRangeConditions() {
        LocomotionRules maximum = parse(sidecar(rule("fixture:walk", """
                {"input":"speed","enter_max":-0.7,"exit_max":-0.5},
                {"input":"speed","enter_min":-1,"exit_min":-1.1}
                """)));
        assertEquals(0, maximum.selectRule(inputs(true, -0.7), -1));
        assertEquals(-1, maximum.selectRule(inputs(true, -0.699), -1));
        assertEquals(0, maximum.selectRule(inputs(true, -0.5), 0));
        assertEquals(-1, maximum.selectRule(inputs(true, -0.499), 0));
        assertEquals(-1, maximum.selectRule(inputs(true, -1.01), -1));
        assertEquals(0, maximum.selectRule(inputs(true, -1.1), 0));
        assertEquals(-1, maximum.selectRule(inputs(true, -1.101), 0));
    }

    @Test
    void sameAnimationRulesRetainDistinctRuleIdentity() {
        LocomotionRules rules = parse(sidecar(
                rule("fixture:walk", "{\"input\":\"speed\",\"enter_min\":0.7,\"exit_min\":0.5}") + ","
                + rule("fixture:walk", "{\"input\":\"speed\",\"enter_min\":0.2,\"exit_min\":0.1}")));
        assertEquals(1, rules.selectRule(inputs(true, 0.3), -1));
        assertEquals(0, rules.selectRule(inputs(true, 0.7), 1));
        assertEquals(rules.animationForRule(0), rules.animationForRule(1));
        assertEquals(0, rules.selectRule(inputs(true, 0.6), 0));
    }

    @Test
    void requiresEveryReferencedInputIncludingCurrentlyInactiveRules() {
        assertTrue(RULES.inputsComplete(inputs(true, 0.5)));
        for (LocomotionInputs invalid : List.of(
                new LocomotionInputs(Map.of(), Map.of()),
                new LocomotionInputs(Map.of("grounded", true), Map.of()),
                new LocomotionInputs(Map.of(), Map.of("speed", 0.5)),
                new LocomotionInputs(Map.of("speed", true), Map.of("grounded", 0.5)),
                new LocomotionInputs(Map.of("grounded", true, "speed", true), Map.of("speed", 0.5)))) {
            assertFalse(RULES.inputsComplete(invalid));
            assertThrows(IllegalArgumentException.class, () -> RULES.selectRule(invalid, 0));
        }
        assertFalse(RULES.inputsComplete(null));
        LocomotionRules lowerPriorityInput = parse(sidecar(
                rule("fixture:run", "{\"input\":\"speed\",\"enter_min\":0.7,\"exit_min\":0.5}") + ","
                + rule("fixture:walk", "{\"input\":\"other\",\"equals\":true}")));
        assertFalse(lowerPriorityInput.inputsComplete(inputs(true, 1)));
        assertThrows(IllegalArgumentException.class, () -> lowerPriorityInput.selectRule(inputs(true, 1), 0));
    }

    @Test
    void nonFiniteAndNullInputsAreCapturedAsInvalidWithoutThrowing() {
        for (double number : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            LocomotionInputs inputs = inputs(true, number);
            assertFalse(inputs.valid());
            assertFalse(RULES.inputsComplete(inputs));
            assertThrows(IllegalArgumentException.class, () -> RULES.selectRule(inputs, 0));
        }
        Map<String, Double> nullNumber = new HashMap<>();
        nullNumber.put("speed", null);
        LocomotionInputs missingNumber = new LocomotionInputs(Map.of("grounded", true), nullNumber);
        assertFalse(missingNumber.valid());
        Map<String, Boolean> nullBoolean = new HashMap<>();
        nullBoolean.put("grounded", null);
        assertFalse(new LocomotionInputs(nullBoolean, Map.of("speed", 1.0)).valid());
    }

    @Test
    void snapshotsMapsAndExposesOnlyImmutableCollections() {
        Map<String, Boolean> booleans = new HashMap<>(Map.of("grounded", true));
        Map<String, Double> numbers = new HashMap<>(Map.of("speed", 0.7));
        LocomotionInputs captured = new LocomotionInputs(booleans, numbers);
        booleans.put("grounded", false);
        numbers.put("speed", Double.NaN);
        numbers.put("other", 1.0);
        assertTrue(captured.valid());
        assertEquals(Map.of("grounded", true), captured.booleans());
        assertEquals(Map.of("speed", 0.7), captured.numbers());
        assertEquals(0, RULES.selectRule(captured, -1));
        assertThrows(UnsupportedOperationException.class, () -> captured.booleans().put("grounded", false));
        assertThrows(UnsupportedOperationException.class, () -> captured.numbers().clear());
        List<LocomotionCondition> conditions = new ArrayList<>(List.of(new LocomotionCondition.BooleanEquals("g", true)));
        LocomotionRule rule = new LocomotionRule(BlendAnimationKey.parse("fixture:walk"), conditions);
        conditions.clear();
        assertEquals(1, rule.conditions().size());
    }

    @Test
    void capsCombinedInputCountAndValidatesNamesBeforeUse() {
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        Map<String, Double> numbers = new LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            booleans.put("b" + index, true);
            numbers.put("n" + index, 1.0);
        }
        assertTrue(new LocomotionInputs(booleans, numbers).valid());
        numbers.put("extra", 1.0);
        assertThrows(IllegalArgumentException.class, () -> new LocomotionInputs(booleans, numbers));
        for (String name : List.of("", "a".repeat(65), "2speed", "speed.value", "\u00e9")) {
            assertThrows(IllegalArgumentException.class, () -> new LocomotionInputs(Map.of(name, true), Map.of()));
        }
    }

    @Test
    void rejectsInvalidRuleIndicesInsteadOfGuessingState() {
        for (int index : new int[] {-2, 2, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> RULES.selectRule(inputs(true, 0.5), index));
            assertThrows(IllegalArgumentException.class, () -> RULES.animationForRule(index));
        }
    }

    private static LocomotionInputs inputs(boolean grounded, double speed) {
        return new LocomotionInputs(Map.of("grounded", grounded), Map.of("speed", speed));
    }
}
