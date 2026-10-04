package com.liy.blendlib.core.animation.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocomotionRuleParserTest {
    private static final String EMPTY = "{\"schema_version\":1,\"default\":\"fixture:idle\",\"rules\":[]}";

    @Test
    void parsesImmutableOrderedTypedRulesAndDefaultsTheIntervalToZero() {
        LocomotionRules rules = parse(sidecar("""
                {"animation":"fixture:run","conditions":[
                    {"input":"grounded","equals":true},
                    {"input":"speed","enter_min":0.6,"exit_min":0.4}]},
                {"animation":"fixture:walk","conditions":[
                    {"input":"speed","enter_max":0.3,"exit_max":0.5}]}
                """));
        assertEquals(BlendAnimationKey.parse("fixture:idle"), rules.defaultAnimation());
        assertEquals(0, rules.minimumIntervalTicks());
        assertEquals(2, rules.rules().size());
        assertEquals(BlendAnimationKey.parse("fixture:run"), rules.rules().getFirst().animation());
        assertEquals(new LocomotionCondition.BooleanEquals("grounded", true), rules.rules().getFirst().conditions().getFirst());
        assertEquals(new LocomotionCondition.Minimum("speed", 0.6, 0.4), rules.rules().getFirst().conditions().get(1));
        assertInstanceOf(LocomotionCondition.Maximum.class, rules.rules().get(1).conditions().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> rules.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> rules.rules().getFirst().conditions().clear());
    }

    @Test
    void permitsDefaultOnlyAndExplicitlyUnconditionalRules() {
        assertTrue(parse(EMPTY).rules().isEmpty());
        LocomotionRules unconditional = parse(sidecar(rule("fixture:walk", "")));
        assertEquals(0, unconditional.selectRule(new LocomotionInputs(Map.of(), Map.of()), -1));
    }

    @Test
    void rejectsUnknownFieldsAtEverySchemaLevelAndMixedConditionKinds() {
        invalid(EMPTY.replace("\"rules\":[]", "\"rules\":[],\"unknown\":true"));
        invalid(sidecar("{\"animation\":\"fixture:walk\",\"conditions\":[],\"unknown\":0}"));
        for (String condition : List.of(
                "{\"input\":\"speed\",\"equals\":true,\"unknown\":0}",
                "{\"input\":\"speed\",\"equals\":true,\"enter_min\":1}",
                "{\"input\":\"speed\",\"enter_min\":1,\"exit_min\":0,\"enter_max\":2}",
                "{\"input\":\"speed\",\"enter_max\":1,\"exit_max\":2,\"unknown\":0}")) {
            invalid(sidecar(rule("fixture:walk", condition)));
        }
    }

    @Test
    void rejectsDuplicateFieldsIncludingEscapedEquivalentNames() {
        invalid(EMPTY.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"));
        invalid(sidecar("{\"animation\":\"fixture:walk\",\"animation\":\"fixture:run\",\"conditions\":[]}"));
        invalid(sidecar(rule("fixture:walk", "{\"input\":\"grounded\",\"input\":\"other\",\"equals\":true}")));
        invalid(sidecar(rule("fixture:walk", "{\"input\":\"grounded\",\"\\u0069nput\":\"other\",\"equals\":true}")));
    }

    @Test
    void requiresAllNonOptionalFieldsAndCompleteThresholdPairs() {
        for (String field : List.of("\"schema_version\":1,", "\"default\":\"fixture:idle\",", ",\"rules\":[]")) {
            invalid(EMPTY.replace(field, ""));
        }
        invalid(sidecar("{\"conditions\":[]}"));
        invalid(sidecar("{\"animation\":\"fixture:walk\"}"));
        for (String condition : List.of("{}", "{\"equals\":true}", "{\"input\":\"x\"}",
                "{\"input\":\"x\",\"enter_min\":1}", "{\"input\":\"x\",\"exit_min\":0}",
                "{\"input\":\"x\",\"enter_max\":0}", "{\"input\":\"x\",\"exit_max\":1}")) {
            invalid(sidecar(rule("fixture:walk", condition)));
        }
    }

    @Test
    void requiresExactVersionAndIntervalIntegerTokensInRange() {
        for (String version : List.of("0", "2", "-1", "1.0", "1e0", "\"1\"", "true", "null", "[]", "{}")) {
            invalid(EMPTY.replace("\"schema_version\":1", "\"schema_version\":" + version));
        }
        assertEquals(0, parse(withInterval("0")).minimumIntervalTicks());
        assertEquals(200, parse(withInterval("200")).minimumIntervalTicks());
        for (String interval : List.of("-1", "201", "0.0", "1e1", "\"5\"", "true", "null", "[]", "{}",
                "2147483648", "99999999999999999999", "1\u0661", "-\u0660")) {
            invalid(withInterval(interval));
        }
    }

    @Test
    void rejectsWrongContainerAndFieldTypesRatherThanCoercingThem() {
        for (String root : List.of("[]", "null", "true", "0", "\"root\"")) {
            invalid(root);
        }
        for (String wrong : List.of("null", "true", "1", "[]", "{}")) {
            invalid(EMPTY.replace("\"fixture:idle\"", wrong));
        }
        for (String wrong : List.of("null", "false", "0", "{}", "\"rules\"")) {
            invalid(EMPTY.replace("\"rules\":[]", "\"rules\":" + wrong));
            invalid(sidecar("{\"animation\":\"fixture:walk\",\"conditions\":" + wrong + "}"));
        }
        for (String wrong : List.of("null", "false", "0", "[]", "\"condition\"")) {
            invalid(sidecar(wrong));
            invalid(sidecar(rule("fixture:walk", wrong)));
        }
        for (String wrong : List.of("null", "1", "\"true\"", "[]", "{}")) {
            invalid(sidecar(rule("fixture:walk", "{\"input\":\"x\",\"equals\":" + wrong + "}")));
        }
        for (String wrong : List.of("null", "true", "\"1.0\"", "[]", "{}")) {
            invalid(sidecar(rule("fixture:walk", "{\"input\":\"x\",\"enter_min\":" + wrong + ",\"exit_min\":0}")));
        }
        invalid(sidecar(rule("fixture:walk", "{\"input\":true,\"equals\":true}")));
    }

    @Test
    void rejectsAbsentMalformedNonLoopAndNextTargetsForBothDefaultAndRules() {
        for (String target : List.of("fixture:missing", "fixture:attack", "fixture:chained", "idle", "fixture:../idle")) {
            invalid(EMPTY.replace("fixture:idle", target));
            invalid(sidecar(rule(target, "")));
        }
    }

    @Test
    void rejectsNonfiniteOrReversedThresholdsAndAcceptsEqualFiniteBounds() {
        for (String condition : List.of(
                "{\"input\":\"x\",\"enter_min\":0,\"exit_min\":1}",
                "{\"input\":\"x\",\"enter_max\":1,\"exit_max\":0}",
                "{\"input\":\"x\",\"enter_min\":1e999,\"exit_min\":0}",
                "{\"input\":\"x\",\"enter_min\":1,\"exit_min\":-1e999}",
                "{\"input\":\"x\",\"enter_max\":-1e999,\"exit_max\":0}",
                "{\"input\":\"x\",\"enter_max\":1,\"exit_max\":1e999}",
                "{\"input\":\"x\",\"enter_min\":NaN,\"exit_min\":0}")) {
            invalid(sidecar(rule("fixture:walk", condition)));
        }
        parse(sidecar(rule("fixture:walk", "{\"input\":\"x\",\"enter_min\":-1.2e3,\"exit_min\":-1200}")));
        parse(sidecar(rule("fixture:walk", "{\"input\":\"x\",\"enter_max\":1.2e3,\"exit_max\":1200}")));
    }

    @Test
    void enforcesExactRuleAndConditionCountUpperBounds() {
        String oneRule = rule("fixture:walk", bool("grounded"));
        assertEquals(32, parse(sidecar(String.join(",", java.util.Collections.nCopies(32, oneRule)))).rules().size());
        invalid(sidecar(String.join(",", java.util.Collections.nCopies(33, oneRule))));
        assertEquals(8, parse(sidecar(rule("fixture:walk", String.join(",", java.util.Collections.nCopies(8, bool("g"))))))
                .rules().getFirst().conditions().size());
        invalid(sidecar(rule("fixture:walk", String.join(",", java.util.Collections.nCopies(9, bool("g"))))));
    }

    @Test
    void enforcesUniqueInputKeyCountAndOneConsistentTypePerKey() {
        parse(sidecar(rulesWithInputs(32)));
        invalid(sidecar(rulesWithInputs(33)));
        invalid(sidecar(rule("fixture:walk", bool("x")) + ","
                + rule("fixture:run", "{\"input\":\"x\",\"enter_min\":1,\"exit_min\":0}")));
    }

    @Test
    void acceptsOnlyBoundedAsciiInputIdentifiers() {
        for (String name : List.of("a", "_", "Speed_2", "a".repeat(64))) {
            parse(sidecar(rule("fixture:walk", bool(name))));
        }
        for (String name : List.of("", "a".repeat(65), "2speed", "a-b", "a.b", "a b", "speed/x", "\u00e9", "a\u0660")) {
            invalid(sidecar(rule("fixture:walk", bool(name))));
        }
    }

    @Test
    void enforcesUtf8DepthSyntaxAndExactByteBoundaryWithBoundedErrors() {
        byte[] exactLimit = (EMPTY + " ".repeat(LocomotionRuleParser.MAX_INPUT_BYTES - EMPTY.length()))
                .getBytes(StandardCharsets.UTF_8);
        LocomotionRuleParser.parse(exactLimit, definition());
        assertThrows(IllegalArgumentException.class,
                () -> LocomotionRuleParser.parse(java.util.Arrays.copyOf(exactLimit, exactLimit.length + 1), definition()));
        assertThrows(IllegalArgumentException.class,
                () -> LocomotionRuleParser.parse(new byte[] {(byte) 0xc3, (byte) 0x28}, definition()));
        invalid(EMPTY + " {}");
        invalid(EMPTY.replace("\"rules\":[]", "\"rules\":[[[[[[[[]]]]]]]]") );
        invalid(EMPTY.replace("\"schema_version\":1", "\"schema_version\":01"));
        invalid(EMPTY.replace("[]", "[],"));
        String longKey = "x".repeat(10_000);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"" + longKey + "\":0,\"" + longKey + "\":1}"));
        assertTrue(error.getMessage().length() <= 240);
    }

    private static String withInterval(String interval) {
        return EMPTY.replace("\"rules\":[]", "\"minimum_interval_ticks\":" + interval + ",\"rules\":[]");
    }

    private static String rulesWithInputs(int count) {
        List<String> rules = new ArrayList<>();
        for (int first = 0; first < count; first += 8) {
            List<String> conditions = new ArrayList<>();
            for (int input = first; input < Math.min(count, first + 8); input++) {
                conditions.add(bool("input" + input));
            }
            rules.add(rule("fixture:walk", String.join(",", conditions)));
        }
        return String.join(",", rules);
    }

    private static String bool(String input) {
        return "{\"input\":\"" + input + "\",\"equals\":true}";
    }

    static String rule(String animation, String conditions) {
        return "{\"animation\":\"" + animation + "\",\"conditions\":[" + conditions + "]}";
    }

    static String sidecar(String rules) {
        return EMPTY.replace("\"rules\":[]", "\"rules\":[" + rules + "]");
    }

    static LocomotionRules parse(String json) {
        return LocomotionRuleParser.parse(json.getBytes(StandardCharsets.UTF_8), definition());
    }

    private static void invalid(String json) {
        assertThrows(IllegalArgumentException.class, () -> parse(json), json);
    }

    private static AnimationDefinition definition() {
        Map<BlendResourceId, AnimationStateDefinition> states = new LinkedHashMap<>();
        for (String name : List.of("idle", "walk", "run")) {
            states.put(BlendResourceId.parse("fixture:" + name), new AnimationStateDefinition(name, true, 1, 0, null, List.of()));
        }
        states.put(BlendResourceId.parse("fixture:attack"), new AnimationStateDefinition("attack", false, 1, 0, null, List.of()));
        states.put(BlendResourceId.parse("fixture:chained"),
                new AnimationStateDefinition("chained", true, 1, 0, BlendResourceId.parse("fixture:idle"), List.of()));
        return new AnimationDefinition(BlendResourceId.parse("fixture:idle"), states);
    }
}
