package com.liy.blendlib.fabric.common.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Owner-thread reconciliation store for one exact instance scope and one frozen controller whitelist.
 *
 * <p>Commands are first sorted by their entire semantic contents and individually fenced to this exact scope before
 * they enter complete {@code controllerId + sequence} groups. This means callback arrival order cannot choose a
 * winner for a conflicting sequence, and a foreign-scope command cannot affect an active group. The whitelist is
 * checked before any watermark or retained value is created, so hostile controller ids cannot grow this store.</p>
 */
public final class AnimationIntentReconciler {
    private AnimationIntentScope activeScope;
    private Set<BlendResourceId> allowedControllers;
    private final Map<BlendResourceId, Long> sequenceWatermarks = new LinkedHashMap<>();
    private final Map<BlendResourceId, AnimationIntent> acceptedByController = new LinkedHashMap<>();
    private final Map<BlendResourceId, AnimationIntent> persistentByController = new LinkedHashMap<>();

    public AnimationIntentReconciler(AnimationIntentScope activeScope, Set<BlendResourceId> allowedControllers) {
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.allowedControllers = checkedControllers(allowedControllers);
    }

    public Optional<AnimationIntentScope> activeScope() {
        return Optional.ofNullable(activeScope);
    }

    /**
     * Retires all semantic state for a changed connection, world, or instance. Generation-only callers should use
     * {@link #rebindGeneration(AnimationIntentScope, Set)} so they explicitly opt into same-identity retention.
     */
    public void rescope(AnimationIntentScope replacement, Set<BlendResourceId> replacementControllers) {
        activeScope = Objects.requireNonNull(replacement, "replacement");
        allowedControllers = checkedControllers(replacementControllers);
        sequenceWatermarks.clear();
        acceptedByController.clear();
        persistentByController.clear();
    }

    /**
     * Carries accepted semantic intents through a resource-generation replacement only when the session, world, and
     * typed instance identity are unchanged. Retained values are rebound to the new scope and filtered before later
     * plan validation; no stale scope can inherit across a session/world/instance change.
     */
    public List<AnimationIntent> rebindGeneration(
            AnimationIntentScope replacement, Set<BlendResourceId> replacementControllers) {
        Objects.requireNonNull(replacement, "replacement");
        Set<BlendResourceId> checked = checkedControllers(replacementControllers);
        if (activeScope == null || !activeScope.sameSessionWorldInstance(replacement)) {
            rescope(replacement, checked);
            return List.of();
        }

        Map<BlendResourceId, Long> reboundWatermarks = new LinkedHashMap<>();
        Map<BlendResourceId, AnimationIntent> reboundAccepted = new LinkedHashMap<>();
        Map<BlendResourceId, AnimationIntent> reboundPersistent = new LinkedHashMap<>();
        for (BlendResourceId controllerId : checked) {
            Long watermark = sequenceWatermarks.get(controllerId);
            if (watermark != null) {
                reboundWatermarks.put(controllerId, watermark);
            }
            AnimationIntent accepted = acceptedByController.get(controllerId);
            if (accepted != null) {
                reboundAccepted.put(controllerId, accepted.rebindScope(replacement));
            }
            AnimationIntent persistent = persistentByController.get(controllerId);
            if (persistent != null) {
                reboundPersistent.put(controllerId, persistent.rebindScope(replacement));
            }
        }
        activeScope = replacement;
        allowedControllers = checked;
        replace(sequenceWatermarks, reboundWatermarks);
        replace(acceptedByController, reboundAccepted);
        replace(persistentByController, reboundPersistent);
        return acceptedIntents();
    }

    /** Drops all scope-bound state after disconnect or teardown. */
    public void disconnect() {
        activeScope = null;
        allowedControllers = Set.of();
        sequenceWatermarks.clear();
        acceptedByController.clear();
        persistentByController.clear();
    }

    /** Reconciles one already-deserialized semantic intent with no local-plan predicate. */
    public AnimationIntentReconciliationResult reconcile(AnimationIntent intent) {
        return reconcileBatch(List.of(intent), ignored -> true).getFirst();
    }

    /**
     * Reconciles a bounded owner-side batch. The supplied predicate validates every candidate against the currently
     * frozen plan before any persistent/accepted retention occurs.
     */
    public List<AnimationIntentReconciliationResult> reconcileBatch(
            List<AnimationIntent> intents, Predicate<AnimationIntent> planValidator) {
        Objects.requireNonNull(intents, "intents");
        Objects.requireNonNull(planValidator, "planValidator");
        if (intents.size() > AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE) {
            throw new IllegalArgumentException("reconciliation batch exceeds the fixed v2 ingress capture bound");
        }
        List<AnimationIntent> sorted = new ArrayList<>(intents.size());
        for (AnimationIntent intent : intents) {
            sorted.add(Objects.requireNonNull(intent, "intent"));
        }
        sorted.sort(AnimationIntent.CANONICAL_ORDER);
        List<AnimationIntentReconciliationResult> results = new ArrayList<>(Collections.nCopies(sorted.size(), null));
        List<IndexedIntent> scoped = new ArrayList<>(sorted.size());
        for (int index = 0; index < sorted.size(); index++) {
            AnimationIntent intent = sorted.get(index);
            if (activeScope == null || !intent.scope().equals(activeScope)) {
                results.set(index, result(AnimationIntentReconciliationOutcome.SCOPE_REJECTED, intent));
            } else {
                scoped.add(new IndexedIntent(index, intent));
            }
        }
        for (int start = 0; start < scoped.size();) {
            AnimationIntent first = scoped.get(start).intent();
            int end = start + 1;
            boolean identical = true;
            while (end < scoped.size() && sameControllerSequence(first, scoped.get(end).intent())) {
                identical &= first.equals(scoped.get(end).intent());
                end++;
            }
            reconcileGroup(scoped, start, end, identical, planValidator, results);
            start = end;
        }
        return List.copyOf(results);
    }

    /** Returns accepted values in canonical order for owner-side local scheduling. */
    public List<AnimationIntent> acceptedIntents() {
        return sortedCopy(acceptedByController);
    }

    /** Returns persistent values in canonical order for late tracking or local rebind replay. */
    public List<AnimationIntent> persistentReplay() {
        return sortedCopy(persistentByController);
    }

    public int retainedControllerCount() {
        return acceptedByController.size();
    }

    /**
     * Revalidates accepted intents against a replacement frozen plan. Rejected values are retired before an adapter
     * can replay them and their sequence watermarks remain, preventing an older callback from reviving them.
     */
    public List<AnimationIntentReconciliationResult> revalidateAccepted(Predicate<AnimationIntent> planValidator) {
        Objects.requireNonNull(planValidator, "planValidator");
        List<AnimationIntentReconciliationResult> results = new ArrayList<>();
        for (AnimationIntent intent : acceptedIntents()) {
            if (!planValidator.test(intent)) {
                retire(intent.controllerId(), intent.sequence());
                results.add(result(AnimationIntentReconciliationOutcome.PLAN_REJECTED, intent));
            }
        }
        return List.copyOf(results);
    }

    /** Stops an accepted persistent value from being replayed when a local plan cannot consume it. */
    public void suppressPersistentReplay(AnimationIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent.equals(persistentByController.get(intent.controllerId()))) {
            persistentByController.remove(intent.controllerId());
        }
    }

    private void reconcileGroup(
            List<IndexedIntent> scoped,
            int start,
            int end,
            boolean identical,
            Predicate<AnimationIntent> planValidator,
            List<AnimationIntentReconciliationResult> results) {
        AnimationIntent first = scoped.get(start).intent();
        if (!allowedControllers.contains(first.controllerId())) {
            addGroup(results, scoped, start, end, AnimationIntentReconciliationOutcome.CONTROLLER_REJECTED);
            return;
        }
        Long watermark = sequenceWatermarks.get(first.controllerId());
        if (watermark != null && first.sequence() < watermark) {
            addGroup(results, scoped, start, end, AnimationIntentReconciliationOutcome.STALE_DROPPED);
            return;
        }
        if (!identical) {
            retire(first.controllerId(), first.sequence());
            addGroup(results, scoped, start, end, AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED);
            return;
        }
        if (!planValidator.test(first)) {
            retire(first.controllerId(), first.sequence());
            addGroup(results, scoped, start, end, AnimationIntentReconciliationOutcome.PLAN_REJECTED);
            return;
        }

        AnimationIntent previous = acceptedByController.get(first.controllerId());
        if (watermark != null && first.sequence() == watermark) {
            if (previous != null && previous.equals(first)) {
                addGroup(results, scoped, start, end, AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED);
            } else {
                retire(first.controllerId(), first.sequence());
                addGroup(results, scoped, start, end, AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED);
            }
            return;
        }

        sequenceWatermarks.put(first.controllerId(), first.sequence());
        acceptedByController.put(first.controllerId(), first);
        if (first.persistent()) {
            persistentByController.put(first.controllerId(), first);
        } else {
            persistentByController.remove(first.controllerId());
        }
        results.set(scoped.get(start).sortedIndex(), result(AnimationIntentReconciliationOutcome.ACCEPTED, first));
        for (int index = start + 1; index < end; index++) {
            IndexedIntent duplicate = scoped.get(index);
            results.set(
                    duplicate.sortedIndex(),
                    result(AnimationIntentReconciliationOutcome.DUPLICATE_DROPPED, duplicate.intent()));
        }
    }

    private void retire(BlendResourceId controllerId, long sequence) {
        Long watermark = sequenceWatermarks.get(controllerId);
        if (watermark != null && sequence < watermark) {
            throw new IllegalStateException("stale sequence reached a destructive reconciliation path");
        }
        sequenceWatermarks.put(controllerId, sequence);
        acceptedByController.remove(controllerId);
        persistentByController.remove(controllerId);
    }

    private static void addGroup(
            List<AnimationIntentReconciliationResult> results,
            List<IndexedIntent> intents,
            int start,
            int end,
            AnimationIntentReconciliationOutcome outcome) {
        for (int index = start; index < end; index++) {
            IndexedIntent intent = intents.get(index);
            results.set(intent.sortedIndex(), result(outcome, intent.intent()));
        }
    }

    private static boolean sameControllerSequence(AnimationIntent left, AnimationIntent right) {
        return left.controllerId().equals(right.controllerId()) && left.sequence() == right.sequence();
    }

    private static List<AnimationIntent> sortedCopy(Map<BlendResourceId, AnimationIntent> values) {
        List<AnimationIntent> replay = new ArrayList<>(values.values());
        replay.sort(AnimationIntent.CANONICAL_ORDER);
        return List.copyOf(replay);
    }

    private static Set<BlendResourceId> checkedControllers(Set<BlendResourceId> controllers) {
        Objects.requireNonNull(controllers, "controllers");
        if (controllers.size() > AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE) {
            throw new IllegalArgumentException("controller whitelist exceeds the fixed v2 instance limit");
        }
        LinkedHashSet<BlendResourceId> checked = new LinkedHashSet<>();
        for (BlendResourceId controller : controllers) {
            checked.add(Objects.requireNonNull(controller, "controller"));
        }
        return Set.copyOf(checked);
    }

    private static <K, V> void replace(Map<K, V> target, Map<K, V> replacement) {
        target.clear();
        target.putAll(replacement);
    }

    private static AnimationIntentReconciliationResult result(
            AnimationIntentReconciliationOutcome outcome, AnimationIntent intent) {
        return new AnimationIntentReconciliationResult(outcome, intent);
    }

    private record IndexedIntent(int sortedIndex, AnimationIntent intent) {}
}
