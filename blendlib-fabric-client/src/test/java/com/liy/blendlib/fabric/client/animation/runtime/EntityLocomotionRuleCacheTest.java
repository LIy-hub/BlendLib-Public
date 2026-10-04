package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.rules.*;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntityLocomotionRuleCacheTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("locomotion:actor");
    private static final BlendResourceId BASE = BlendResourceId.parse("locomotion:base");
    private static final BlendResourceId UPPER = BlendResourceId.parse("locomotion:upper");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("locomotion:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("locomotion:walk");
    private static final BlendAnimationKey RUN = BlendAnimationKey.parse("locomotion:run");
    private static final BlendInstanceKey.Entity INSTANCE = BlendInstanceKey.entity("session", 42);
    private final EntityLocomotionRuleCache cache = new EntityLocomotionRuleCache();
    private final Object source = new EqualIdentity(1);
    private final Object owner = new EqualIdentity(42);
    private record EqualIdentity(int id) {}

    @Test
    void inclusiveThresholdJitterUsesCurrentExitAndEarlierEntryPriority() {
        var rules = rules(0, standardRules());
        var idle = command(rules, 0, 0);
        assertEquals(IDLE, idle.animationKey());
        var walk = command(rules, 1, 1);
        assertEquals(WALK, walk.animationKey());
        assertEquals(idle.sequence() + 1, walk.sequence());
        assertSame(walk, command(rules, 2, 0.8));
        assertSame(walk, command(rules, 3, 0.5), "exit threshold is inclusive");
        var stopped = command(rules, 4, Math.nextDown(0.5));
        assertEquals(IDLE, stopped.animationKey());
        var running = command(rules, 5, 3);
        assertEquals(RUN, running.animationKey(), "earlier rule preempts a matching walk rule");
        assertSame(running, command(rules, 6, 2), "run exit threshold is inclusive");
        assertEquals(WALK, command(rules, 7, Math.nextDown(2.0)).animationKey());
        assertEquals(0.0, running.requestedPlayheadSeconds());
        assertEquals(1.0, running.playbackSpeed());
    }

    @Test
    void minimumIntervalHoldsSelectedStateRatherThanDebouncingCandidateAndRollbackCannotLowerWatermark() {
        var rules = rules(10, standardRules());
        var idle = command(rules, 100, 0);
        assertSame(idle, command(rules, 109, 3));
        assertSame(idle, command(rules, 50, 3));
        assertSame(idle, command(rules, 109.5, 1));
        var walk = command(rules, 110, 1);
        assertEquals(WALK, walk.animationKey(), "new candidate need not persist for the interval");
        assertSame(walk, command(rules, 80, 3));
        assertSame(walk, command(rules, 119.999, 3));
        assertEquals(RUN, command(rules, 120, 3).animationKey());
    }

    @Test
    void ruleIdentityCanChangeWithoutRestartOrResettingSelectedAnimationHold() {
        var rules = rules(10, """
                {"animation":"locomotion:walk","conditions":[{"input":"speed","enter_min":3,"exit_min":2}]},
                {"animation":"locomotion:walk","conditions":[{"input":"speed","enter_min":1,"exit_min":0.5}]}
                """);
        var walk = command(rules, 0, 1);
        assertSame(walk, command(rules, 5, 3));
        assertSame(walk, command(rules, 9, 0));
        assertEquals(IDLE, command(rules, 10, 0).animationKey(), "same-target rule transition must not restart hold");
        var next = command(rules, 20, 3);
        assertSame(next, command(rules, 21, 1.5));
        assertSame(next, command(rules, 30, 0.6), "lower rule now owns its 0.5 exit threshold");
    }

    @Test
    void invalidSnapshotsPreserveLastCommandAndFirstInvalidFrameStillBindsIdentity() {
        var rules = rules(0, standardRules());
        assertTrue(capture(rules, 0, new LocomotionInputs(Map.of(), Map.of()), List.of()).isEmpty());
        assertEquals(1, cache.size());
        var walk = command(rules, 1, 1);
        for (var bad : List.of(new LocomotionInputs(Map.of(), Map.of()),
                new LocomotionInputs(Map.of("speed", true), Map.of()),
                inputs(Double.NaN), inputs(Double.POSITIVE_INFINITY))) {
            assertSame(walk, capture(rules, 20, bad, List.of()).getFirst());
        }
        assertEquals(walk.sequence() + 1, command(rules, 21, 3).sequence());
    }

    @Test
    void replayPreservesCommandIdentityAndOtherControllersAndRejectsCompetitionAtomically() {
        var rules = rules(0, standardRules());
        var upper = new AnimationV2Command(UPPER, WALK, 90, 0.5, 2);
        var first = capture(rules, 1, inputs(1), List.of(upper));
        assertSame(upper, first.getFirst());
        var selected = first.getLast();
        assertSame(selected, capture(rules, 20, inputs(1), List.of(upper)).getLast());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        var competing = new AnimationV2Command(BASE, RUN, 999, 0.8, 1);
        assertThrows(IllegalArgumentException.class,
                () -> capture(rules, 30, inputs(3), List.of(upper, competing)));
        assertSame(selected, capture(rules, 31, inputs(1), List.of()).getFirst());
        assertEquals(selected.sequence() + 1, command(rules, 32, 3).sequence());
    }

    @Test
    void sourceOwnerConnectionModelGenerationControllerAndRulesIdentitiesNeverInheritSelection() {
        for (String changed : List.of("source", "owner", "connection", "model", "generation", "controller", "rules")) {
            cache.clear();
            var rules = rules(200, standardRules());
            var previous = command(rules, 100, 3);
            var changedSource = changed.equals("source") ? new EqualIdentity(1) : source;
            var changedOwner = changed.equals("owner") ? new EqualIdentity(42) : owner;
            var key = changed.equals("connection") ? BlendInstanceKey.entity("another-session", 42) : INSTANCE;
            var model = changed.equals("model") ? BlendModelKey.parse("locomotion:another") : MODEL;
            var controller = changed.equals("controller") ? UPPER : BASE;
            var nextRules = changed.equals("rules") ? rules(200, standardRules()) : rules;
            var next = cache.capture(key, changedSource, changedOwner, model,
                    changed.equals("generation") ? 2 : 1, 101, controller, nextRules, inputs(0), List.of()).getFirst();
            assertEquals(IDLE, next.animationKey(), changed);
            assertEquals(0, next.sequence(), changed);
            assertNotSame(previous, next, changed);
        }
    }

    @Test
    void sharedSourceSeparateEntitiesAndLifecycleRetirementRemainIndependent() {
        var rules = rules(200, standardRules());
        var first = command(rules, 10, 3);
        var secondKey = BlendInstanceKey.entity("session", 43);
        var second = cache.capture(secondKey, source, owner, MODEL, 1, 10, BASE, rules, inputs(0), List.of()).getFirst();
        assertSame(first, command(rules, 11, 0));
        assertEquals(IDLE, second.animationKey());
        cache.retireEntity(42);
        assertEquals(1, cache.size());
        assertEquals(IDLE, command(rules, 12, 0).animationKey());
        cache.retire(INSTANCE);
        assertEquals(1, cache.size());
        cache.retainGeneration(2);
        assertEquals(0, cache.size());
        command(rules, 13, 3);
        cache.clear();
        assertEquals(0, cache.size());
    }

    private AnimationV2Command command(LocomotionRules rules, double tick, double speed) {
        return capture(rules, tick, inputs(speed), List.of()).getFirst();
    }

    private List<AnimationV2Command> capture(LocomotionRules rules, double tick, LocomotionInputs inputs,
            List<AnimationV2Command> commands) {
        return cache.capture(INSTANCE, source, owner, MODEL, 1, tick, BASE, rules, inputs, commands);
    }

    static LocomotionInputs inputs(double speed) { return new LocomotionInputs(Map.of(), Map.of("speed", speed)); }

    static String standardRules() {
        return """
                {"animation":"locomotion:run","conditions":[{"input":"speed","enter_min":3,"exit_min":2}]},
                {"animation":"locomotion:walk","conditions":[{"input":"speed","enter_min":1,"exit_min":0.5}]}
                """;
    }

    static LocomotionRules rules(int interval, String entries) {
        return LocomotionRuleParser.parse(("{\"schema_version\":1,\"default\":\"locomotion:idle\","
                + "\"minimum_interval_ticks\":" + interval + ",\"rules\":[" + entries + "]}")
                .getBytes(StandardCharsets.UTF_8), definition());
    }

    static AnimationDefinition definition() {
        return new AnimationDefinition(IDLE.resourceId(), Map.of(
                IDLE.resourceId(), state("idle"), WALK.resourceId(), state("walk"), RUN.resourceId(), state("run")));
    }

    private static AnimationStateDefinition state(String clip) {
        return new AnimationStateDefinition(clip, true, 1, 0, null, List.of());
    }
}
