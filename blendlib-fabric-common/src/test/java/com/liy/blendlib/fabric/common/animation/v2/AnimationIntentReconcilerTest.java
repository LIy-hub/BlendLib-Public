package com.liy.blendlib.fabric.common.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** X2 reconciliation regression probes, including adversarial group and retention boundaries. */
class AnimationIntentReconcilerTest {
    @Test
    void persistentReplayIsCanonicalAndOneShotReplacementRetiresOnlyThatController() {
        AnimationIntentScope scope = scope("session-a", "world-a", 1L);
        AnimationIntentReconciler reconciler = reconciler(scope, "alpha", "beta");
        AnimationIntent beta = intent(scope, "beta", "idle", 10L, 2L, AnimationIntentMode.PERSISTENT);
        AnimationIntent alpha = intent(scope, "alpha", "walk", 20L, 1L, AnimationIntentMode.PERSISTENT);

        assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(beta));
        assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(alpha));
        assertEquals(List.of(alpha, beta), reconciler.persistentReplay());

        AnimationIntent oneShot = intent(scope, "alpha", "attack", 30L, 3L, AnimationIntentMode.ONE_SHOT);
        assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(oneShot));
        assertEquals(List.of(beta), reconciler.persistentReplay());
        assertEquals(2, reconciler.retainedControllerCount());
    }

    @Test
    void fullSemanticControllerSequenceGroupsRejectConflictsIndependentOfArrivalOrder() {
        AnimationIntentScope scope = scope("session-a", "world-a", 1L);
        AnimationIntent idle = intent(scope, "controller", "idle", 10L, 5L, AnimationIntentMode.PERSISTENT);
        AnimationIntent walk = intent(scope, "controller", "walk", 10L, 5L, AnimationIntentMode.PERSISTENT);
        AnimationIntentReconciler forward = reconciler(scope, "controller");
        AnimationIntentReconciler reverse = reconciler(scope, "controller");

        assertEquals(List.of(
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED,
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED), outcomes(forward.reconcileBatch(
                List.of(idle, walk), ignored -> true)));
        assertEquals(List.of(
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED,
                AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED), outcomes(reverse.reconcileBatch(
                List.of(walk, idle), ignored -> true)));
        assertTrue(forward.persistentReplay().isEmpty());
        assertTrue(reverse.persistentReplay().isEmpty());
        assertOutcome(AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED, forward.reconcile(idle));

        AnimationIntentReconciler duplicates = reconciler(scope, "controller");
        assertEquals(List.of(
                AnimationIntentReconciliationOutcome.ACCEPTED,
                AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED), outcomes(duplicates.reconcileBatch(
                List.of(idle, idle), ignored -> true)));
        assertEquals(List.of(idle), duplicates.persistentReplay());

    }

    @Test
    void staleConflictAndPlanFailureCannotRetireNewerPersistentRetention() {
        AnimationIntentScope generationOne = scope("session-a", "world-a", 1L);
        AnimationIntentReconciler reconciler = reconciler(generationOne, "controller");
        AnimationIntent accepted = intent(generationOne, "controller", "run", 0L, 10L, AnimationIntentMode.PERSISTENT);
        assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(accepted));

        AnimationIntent staleIdle = intent(generationOne, "controller", "idle", 0L, 5L, AnimationIntentMode.PERSISTENT);
        AnimationIntent staleWalk = intent(generationOne, "controller", "walk", 0L, 5L, AnimationIntentMode.PERSISTENT);
        assertEquals(List.of(
                AnimationIntentReconciliationOutcome.STALE_DROPPED,
                AnimationIntentReconciliationOutcome.STALE_DROPPED), outcomes(reconciler.reconcileBatch(
                List.of(staleIdle, staleWalk), ignored -> true)));
        AnimationIntent staleInvalid = intent(generationOne, "controller", "invalid", 0L, 6L,
                AnimationIntentMode.PERSISTENT);
        assertOutcome(AnimationIntentReconciliationOutcome.STALE_DROPPED,
                reconciler.reconcileBatch(List.of(staleInvalid), ignored -> false).getFirst());

        assertEquals(List.of(accepted), reconciler.acceptedIntents());
        assertEquals(List.of(accepted), reconciler.persistentReplay());
        assertOutcome(AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED, reconciler.reconcile(accepted));

        AnimationIntentScope generationTwo = scope("session-a", "world-a", 2L);
        AnimationIntent rebound = reconciler.rebindGeneration(generationTwo, Set.of(id("controller"))).getFirst();
        assertEquals(generationTwo, rebound.scope());
        assertEquals(List.of(rebound), reconciler.persistentReplay());
    }

    @Test
    void exactScopeFencePrecedesGroupingPlanValidationAndRetentionMutation() {
        AnimationIntentScope active = scope("session-m", "world-m", 7, 2L);
        AnimationIntentScope reboundScope = scope("session-m", "world-m", 7, 3L);
        AnimationIntent accepted = intent(active, "controller", "walk", 0L, 10L, AnimationIntentMode.PERSISTENT);
        List<ScopeFenceCase> foreignCases = List.of(
                new ScopeFenceCase(scope("session-a", "world-m", 7, 2L), true),
                new ScopeFenceCase(scope("session-z", "world-m", 7, 2L), false),
                new ScopeFenceCase(scope("session-m", "world-a", 7, 2L), true),
                new ScopeFenceCase(scope("session-m", "world-z", 7, 2L), false),
                new ScopeFenceCase(scope("session-m", "world-m", 6, 2L), true),
                new ScopeFenceCase(scope("session-m", "world-m", 8, 2L), false),
                new ScopeFenceCase(scope("session-m", "world-m", 7, 1L), true),
                new ScopeFenceCase(scope("session-m", "world-m", 7, 3L), false));

        for (ScopeFenceCase foreignCase : foreignCases) {
            AnimationIntentReconciler reconciler = reconciler(active, "controller");
            assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(accepted));
            AnimationIntent foreign = intent(foreignCase.scope(), "controller", "run", 0L, 10L,
                    AnimationIntentMode.PERSISTENT);
            assertEquals(foreignCase.sortsBeforeActive(),
                    AnimationIntent.CANONICAL_ORDER.compare(foreign, accepted) < 0);
            int[] planChecks = {0};
            List<AnimationIntentReconciliationResult> results = reconciler.reconcileBatch(
                    List.of(foreign, accepted), ignored -> {
                        planChecks[0]++;
                        return true;
                    });
            List<AnimationIntent> expectedOrder = new ArrayList<>(List.of(foreign, accepted));
            expectedOrder.sort(AnimationIntent.CANONICAL_ORDER);

            assertEquals(expectedOrder, results.stream().map(AnimationIntentReconciliationResult::intent).toList());
            assertEquals(AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED, outcomeFor(results, accepted));
            assertEquals(AnimationIntentReconciliationOutcome.SCOPE_REJECTED, outcomeFor(results, foreign));
            assertEquals(1, planChecks[0]);
            assertEquals(List.of(accepted), reconciler.acceptedIntents());
            assertEquals(List.of(accepted), reconciler.persistentReplay());
            assertEquals(List.of(accepted.rebindScope(reboundScope)),
                    reconciler.rebindGeneration(reboundScope, Set.of(id("controller"))));
            assertEquals(List.of(accepted.rebindScope(reboundScope)), reconciler.persistentReplay());
        }
    }

    @Test
    void whitelistAndPlanValidationHappenBeforeAnyRetentionCanGrow() {
        AnimationIntentScope scope = scope("session-a", "world-a", 1L);
        AnimationIntentReconciler reconciler = reconciler(scope, "allowed");
        AnimationIntent unknown = intent(scope, "unknown", "idle", 0L, 1L, AnimationIntentMode.PERSISTENT);
        assertOutcome(AnimationIntentReconciliationOutcome.CONTROLLER_REJECTED, reconciler.reconcile(unknown));
        assertEquals(0, reconciler.retainedControllerCount());

        AnimationIntent rejectedPlan = intent(scope, "allowed", "idle", 0L, 2L, AnimationIntentMode.PERSISTENT);
        assertOutcome(AnimationIntentReconciliationOutcome.PLAN_REJECTED,
                reconciler.reconcileBatch(List.of(rejectedPlan), ignored -> false).getFirst());
        assertTrue(reconciler.persistentReplay().isEmpty());
        assertOutcome(AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED, reconciler.reconcile(rejectedPlan));

        List<BlendResourceId> tooMany = new ArrayList<>();
        for (int index = 0; index <= AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE; index++) {
            tooMany.add(id("controller" + index));
        }
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntentReconciler(scope, Set.copyOf(tooMany)));
    }

    @Test
    void generationOnlyRebindPreservesRevalidatedSemanticIntentButScopeChangesNeverInherit() {
        AnimationIntentScope initial = scope("session-a", "world-a", 7, 1L);
        AnimationIntent persistent = intent(initial, "controller", "idle", 0L, 10L, AnimationIntentMode.PERSISTENT);
        AnimationIntentReconciler reconciler = reconciler(initial, "controller");
        assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(persistent));

        AnimationIntentScope generationTwo = scope("session-a", "world-a", 7, 2L);
        List<AnimationIntent> rebound = reconciler.rebindGeneration(generationTwo, Set.of(id("controller")));
        AnimationIntent reboundIntent = rebound.getFirst();
        assertEquals(generationTwo, reboundIntent.scope());
        assertEquals(List.of(reboundIntent), reconciler.persistentReplay());
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED, reconciler.reconcile(persistent));
        assertEquals(List.of(AnimationIntentReconciliationOutcome.PLAN_REJECTED), outcomes(
                reconciler.revalidateAccepted(ignored -> false)));
        assertTrue(reconciler.persistentReplay().isEmpty());

        AnimationIntentScope changedSession = scope("session-b", "world-a", 7, 3L);
        assertTrue(reconciler.rebindGeneration(changedSession, Set.of(id("controller"))).isEmpty());
        assertTrue(reconciler.acceptedIntents().isEmpty());
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED, reconciler.reconcile(reboundIntent));
    }

    @Test
    void sessionWorldInstanceAndGenerationFenceIncomingAndDisconnectClearsRetention() {
        AnimationIntentScope active = scope("session-a", "world-a", 7, 1L);
        AnimationIntentReconciler reconciler = reconciler(active, "controller");
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                reconciler.reconcile(intent(scope("session-b", "world-a", 7, 1L), "controller", "idle", 0L, 1L,
                        AnimationIntentMode.PERSISTENT)));
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                reconciler.reconcile(intent(scope("session-a", "world-b", 7, 1L), "controller", "idle", 0L, 1L,
                        AnimationIntentMode.PERSISTENT)));
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                reconciler.reconcile(intent(scope("session-a", "world-a", 8, 1L), "controller", "idle", 0L, 1L,
                        AnimationIntentMode.PERSISTENT)));
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED,
                reconciler.reconcile(intent(scope("session-a", "world-a", 7, 2L), "controller", "idle", 0L, 1L,
                        AnimationIntentMode.PERSISTENT)));

        AnimationIntent current = intent(active, "controller", "idle", 0L, 1L, AnimationIntentMode.PERSISTENT);
        assertOutcome(AnimationIntentReconciliationOutcome.ACCEPTED, reconciler.reconcile(current));
        reconciler.disconnect();
        assertTrue(reconciler.activeScope().isEmpty());
        assertTrue(reconciler.persistentReplay().isEmpty());
        assertOutcome(AnimationIntentReconciliationOutcome.SCOPE_REJECTED, reconciler.reconcile(current));
    }

    @Test
    void semanticBoundaryRejectsInvalidScopeAndIntentValues() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntentScope(" ", id("world"),
                new BlendInstanceKey.Entity("session", 1), 0L));
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntentScope("session", id("world"),
                new BlendInstanceKey.Entity("session", 1), -1L));
        AnimationIntentScope scope = scope("session", "world", 1L);
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntent(scope, id("controller"), key("idle"), 0L,
                -1L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntent(scope, id("controller"), key("idle"), 0L,
                1L, Float.NaN, AnimationIntentMode.ONE_SHOT));
    }

    private static AnimationIntentReconciler reconciler(AnimationIntentScope scope, String... controllerIds) {
        return new AnimationIntentReconciler(scope, java.util.Arrays.stream(controllerIds).map(AnimationIntentReconcilerTest::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static List<AnimationIntentReconciliationOutcome> outcomes(List<AnimationIntentReconciliationResult> results) {
        return results.stream().map(AnimationIntentReconciliationResult::outcome).toList();
    }

    private static AnimationIntentReconciliationOutcome outcomeFor(
            List<AnimationIntentReconciliationResult> results, AnimationIntent intent) {
        return results.stream().filter(result -> result.intent().equals(intent)).findFirst().orElseThrow().outcome();
    }

    private static AnimationIntentScope scope(String session, String world, long generation) {
        return scope(session, world, 7, generation);
    }

    private static AnimationIntentScope scope(String session, String world, int entityId, long generation) {
        return new AnimationIntentScope(session, id(world), new BlendInstanceKey.Entity(session, entityId), generation);
    }

    private static AnimationIntent intent(
            AnimationIntentScope scope,
            String controller,
            String animation,
            long startTick,
            long sequence,
            AnimationIntentMode mode) {
        return new AnimationIntent(scope, id(controller), key(animation), startTick, sequence, 1.0F, mode);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.of("x2common", path);
    }

    private static BlendAnimationKey key(String path) {
        return BlendAnimationKey.of("x2common", path);
    }

    private static void assertOutcome(
            AnimationIntentReconciliationOutcome expected, AnimationIntentReconciliationResult actual) {
        assertEquals(expected, actual.outcome());
        assertEquals(expected == AnimationIntentReconciliationOutcome.ACCEPTED, actual.accepted());
    }

    private record ScopeFenceCase(AnimationIntentScope scope, boolean sortsBeforeActive) {
    }
}
