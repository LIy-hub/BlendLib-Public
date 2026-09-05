package com.liy.blendlib.fabric.client.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerState;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2InstancePlan;
import com.liy.blendlib.core.animation.v2.AnimationV2InstanceRuntime;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import com.liy.blendlib.core.animation.v2.AnimationV2SequenceRejection;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntent;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentReconciliationOutcome;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentReconciliationResult;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentReconciler;
import com.liy.blendlib.fabric.common.animation.v2.AnimationIntentScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter-independent client owner for v2 semantic ingestion and immutable evaluation snapshots.
 *
 * <p>Callbacks only offer immutable intents to a fixed-size queue. The owner captures the entire bounded queue,
 * fences each intent to the active exact scope, schedules only complete in-scope controller/sequence groups within
 * its fixed per-frame group budget, validates them against the currently frozen plan, then passes due absolute commands to
 * {@link AnimationV2InstanceRuntime#advanceAtFrame(double, List)}. That frame boundary advances old state first and
 * prevents an absolute playhead from consuming the same frame delta twice.</p>
 */
public final class ClientAnimationV2Runtime {
    private static final int MAX_RECONCILIATION_RESULTS_PER_FRAME = AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE
            + AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE + 2;
    private static final double TICKS_PER_SECOND = 20.0D;

    private final ArrayBlockingQueue<AnimationIntent> incoming = new ArrayBlockingQueue<>(
            AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
    private final AtomicBoolean ingressOverflowed = new AtomicBoolean();
    private final AtomicReference<AnimationIntent> lastIngressOverflow = new AtomicReference<>();
    private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
    private final AtomicReference<ClientAnimationV2Snapshot> latest = new AtomicReference<>(ClientAnimationV2Snapshot.empty());
    private final Map<BlendResourceId, Long> appliedSequences = new HashMap<>();
    /** One complete owner-captured queue snapshot; later callback work remains in ingress until this snapshot drains. */
    private final List<AnimationIntent> pendingIncoming = new ArrayList<>(
            AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
    private List<AnimationIntentReconciliationResult> pendingInstallResults = List.of();
    private AnimationV2InstanceRuntime runtime;
    private AnimationIntentReconciler reconciler;
    private AnimationIntentScope activeScope;
    private boolean hasPreviousFrame;
    private long previousGameTick;
    private float previousPartialTick;

    /**
     * Installs a frozen plan. A generation-only replacement preserves accepted semantic values, rebinds their scope,
     * and revalidates them against the new plan. Any session/world/typed-instance change clears retention instead.
     */
    public void install(AnimationV2InstancePlan plan, AnimationIntentScope scope) {
        claimOwner();
        AnimationV2InstancePlan checkedPlan = Objects.requireNonNull(plan, "plan");
        AnimationIntentScope checkedScope = Objects.requireNonNull(scope, "scope");
        AnimationIntentScope previousScope = activeScope;
        runtime = new AnimationV2InstanceRuntime(checkedPlan);
        activeScope = checkedScope;
        appliedSequences.clear();
        if (reconciler == null) {
            reconciler = new AnimationIntentReconciler(checkedScope, checkedPlan.controllerIds());
            hasPreviousFrame = false;
            pendingInstallResults = List.of();
        } else if (previousScope != null && previousScope.sameSessionWorldInstance(checkedScope)) {
            reconciler.rebindGeneration(checkedScope, checkedPlan.controllerIds());
            pendingInstallResults = reconciler.revalidateAccepted(this::isPlanValid);
        } else {
            reconciler.rescope(checkedScope, checkedPlan.controllerIds());
            hasPreviousFrame = false;
            pendingInstallResults = List.of();
        }
        latest.set(new ClientAnimationV2Snapshot(
                Optional.of(runtime.latestSnapshot()), Optional.of(activeScope), pendingInstallResults));
    }

    /** Offers one semantic intent without blocking a callback thread; overflow is explicit to the caller and owner. */
    public ClientAnimationV2IngressOutcome receiveIntent(AnimationIntent intent) {
        AnimationIntent checked = Objects.requireNonNull(intent, "intent");
        if (incoming.offer(checked)) {
            return ClientAnimationV2IngressOutcome.QUEUED;
        }
        lastIngressOverflow.set(checked);
        ingressOverflowed.set(true);
        return ClientAnimationV2IngressOutcome.QUEUE_OVERFLOW;
    }

    /** Returns the most recent immutable publication without exposing owner-thread mutable state. */
    public ClientAnimationV2Snapshot latestSnapshot() {
        return latest.get();
    }

    /** Returns replayable values only while the caller remains on the runtime owner thread. */
    public List<AnimationIntent> persistentReplay() {
        claimOwner();
        return reconciler == null ? List.of() : reconciler.persistentReplay();
    }

    /** Owner-observable retention count used by bounded-ingress diagnostics and tests. */
    public int retainedControllerCount() {
        claimOwner();
        return reconciler == null ? 0 : reconciler.retainedControllerCount();
    }

    /** Advances the installed runtime from an adapter-provided game clock and publishes one immutable result. */
    public ClientAnimationV2Snapshot advance(ClientAnimationV2FrameTime frameTime) {
        claimOwner();
        Objects.requireNonNull(frameTime, "frameTime");
        List<AnimationIntentReconciliationResult> results = new ArrayList<>(MAX_RECONCILIATION_RESULTS_PER_FRAME);
        appendPendingInstallResults(results);
        IncomingDrain drain = drainIncoming();
        if (drain.overflowIntent() != null) {
            addResult(results, AnimationIntentReconciliationOutcome.INGRESS_QUEUE_OVERFLOW, drain.overflowIntent());
        }
        if (drain.deferredIntent() != null) {
            addResult(results, AnimationIntentReconciliationOutcome.DRAIN_BUDGET_EXHAUSTED, drain.deferredIntent());
        }
        if (runtime == null || reconciler == null || activeScope == null) {
            for (AnimationIntent intent : drain.intents()) {
                addResult(results, AnimationIntentReconciliationOutcome.SCOPE_REJECTED, intent);
            }
            ClientAnimationV2Snapshot snapshot = new ClientAnimationV2Snapshot(Optional.empty(), Optional.empty(), results);
            latest.set(snapshot);
            return snapshot;
        }

        for (AnimationIntentReconciliationResult result : reconciler.reconcileBatch(drain.intents(), this::isPlanValid)) {
            addResult(results, result);
        }
        synchronizeAppliedSequences();
        List<AnimationV2Command> dueCommands = dueCommands(frameTime);
        AnimationV2EvaluationSnapshot evaluation = runtime.advanceAtFrame(
                deltaSeconds(frameTime), dueCommands, sequenceRejections(results));
        ClientAnimationV2Snapshot snapshot = new ClientAnimationV2Snapshot(
                Optional.of(evaluation), Optional.of(activeScope), results);
        latest.set(snapshot);
        return snapshot;
    }

    /**
     * Retires active scope retention after teardown. Queued callbacks are intentionally left in place so a later
     * install reports their stale scope rather than silently discarding them.
     */
    public void disconnect() {
        claimOwner();
        if (reconciler != null) {
            reconciler.disconnect();
        }
        runtime = null;
        reconciler = null;
        activeScope = null;
        appliedSequences.clear();
        pendingInstallResults = List.of();
        hasPreviousFrame = false;
        latest.set(ClientAnimationV2Snapshot.empty());
    }

    private IncomingDrain drainIncoming() {
        if (pendingIncoming.isEmpty()) {
            incoming.drainTo(pendingIncoming, AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
            if (pendingIncoming.size() > AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE) {
                throw new IllegalStateException("v2 client intent backlog exceeded its fixed ingress snapshot bound");
            }
        }
        AnimationIntent overflow = ingressOverflowed.getAndSet(false) ? lastIngressOverflow.getAndSet(null) : null;
        List<AnimationIntent> scopeRejected = new ArrayList<>();
        List<AnimationIntent> scoped = new ArrayList<>(pendingIncoming.size());
        for (AnimationIntent intent : pendingIncoming) {
            if (activeScope == null || !intent.scope().equals(activeScope)) {
                scopeRejected.add(intent);
            } else {
                scoped.add(intent);
            }
        }
        pendingIncoming.subList(0, pendingIncoming.size()).clear();
        scoped.sort(AnimationIntent.CANONICAL_ORDER);
        int processedGroups = 0;
        int processedEnd = 0;
        for (int start = 0; start < scoped.size();) {
            if (processedGroups >= AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE) {
                break;
            }
            AnimationIntent first = scoped.get(start);
            int end = start + 1;
            while (end < scoped.size() && sameControllerSequence(first, scoped.get(end))) {
                end++;
            }
            processedEnd = end;
            processedGroups++;
            start = end;
        }
        List<AnimationIntent> scheduled = new ArrayList<>(scopeRejected.size() + processedEnd);
        scheduled.addAll(scopeRejected);
        if (processedEnd > 0) {
            scheduled.addAll(scoped.subList(0, processedEnd));
        }
        pendingIncoming.addAll(scoped.subList(processedEnd, scoped.size()));
        scheduled.sort(AnimationIntent.CANONICAL_ORDER);
        AnimationIntent deferred = !pendingIncoming.isEmpty() ? pendingIncoming.getFirst() : incoming.peek();
        return new IncomingDrain(List.copyOf(scheduled), overflow, deferred);
    }

    private static boolean sameControllerSequence(AnimationIntent left, AnimationIntent right) {
        return left.controllerId().equals(right.controllerId()) && left.sequence() == right.sequence();
    }

    private List<AnimationV2Command> dueCommands(ClientAnimationV2FrameTime frameTime) {
        List<AnimationV2Command> commands = new ArrayList<>();
        for (AnimationIntent intent : reconciler.acceptedIntents()) {
            Long applied = appliedSequences.get(intent.controllerId());
            if (applied != null && applied == intent.sequence()) {
                continue;
            }
            if (!isDue(intent, frameTime)) {
                continue;
            }
            commands.add(toCommand(intent, frameTime));
            appliedSequences.put(intent.controllerId(), intent.sequence());
        }
        return List.copyOf(commands);
    }

    private void synchronizeAppliedSequences() {
        Map<BlendResourceId, Long> accepted = new HashMap<>();
        for (AnimationIntent intent : reconciler.acceptedIntents()) {
            accepted.put(intent.controllerId(), intent.sequence());
        }
        appliedSequences.entrySet().removeIf(entry -> !Objects.equals(entry.getValue(), accepted.get(entry.getKey())));
    }

    private static List<AnimationV2SequenceRejection> sequenceRejections(
            List<AnimationIntentReconciliationResult> results) {
        Map<BlendResourceId, Long> rejected = new LinkedHashMap<>();
        for (AnimationIntentReconciliationResult result : results) {
            if (result.outcome() != AnimationIntentReconciliationOutcome.SEQUENCE_CONFLICT_REJECTED
                    && result.outcome() != AnimationIntentReconciliationOutcome.PLAN_REJECTED) {
                continue;
            }
            rejected.merge(result.intent().controllerId(), result.intent().sequence(), Math::max);
        }
        List<AnimationV2SequenceRejection> rejections = new ArrayList<>(rejected.size());
        for (Map.Entry<BlendResourceId, Long> entry : rejected.entrySet()) {
            rejections.add(new AnimationV2SequenceRejection(entry.getKey(), entry.getValue()));
        }
        rejections.sort((left, right) -> {
            int controller = left.controllerId().value().compareTo(right.controllerId().value());
            return controller != 0 ? controller : Long.compare(left.sequence(), right.sequence());
        });
        return List.copyOf(rejections);
    }

    private boolean isPlanValid(AnimationIntent intent) {
        if (runtime == null || activeScope == null || !intent.scope().equals(activeScope)) {
            return false;
        }
        try {
            AnimationV2ControllerDefinition controller = runtime.plan().controller(intent.controllerId());
            AnimationV2ControllerState state = controller.state(intent.animationKey());
            return AnimationV2Limits.isValidEffectivePlaybackSpeed(state.speed(), intent.speed());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private AnimationV2Command toCommand(AnimationIntent intent, ClientAnimationV2FrameTime frameTime) {
        AnimationV2ControllerDefinition controller = runtime.plan().controller(intent.controllerId());
        AnimationV2ControllerState state = controller.state(intent.animationKey());
        if (!AnimationV2Limits.isValidEffectivePlaybackSpeed(state.speed(), intent.speed())) {
            throw new IllegalArgumentException("semantic intent exceeds the installed plan speed bound");
        }
        double effectiveRate = state.speed() * intent.speed();
        return new AnimationV2Command(
                intent.controllerId(),
                intent.animationKey(),
                intent.sequence(),
                elapsedSeconds(intent.startGameTick(), frameTime) * effectiveRate,
                intent.speed());
    }

    private static boolean isDue(AnimationIntent intent, ClientAnimationV2FrameTime frameTime) {
        return frameTime.gameTick() - intent.startGameTick() >= 0L;
    }

    private double deltaSeconds(ClientAnimationV2FrameTime frameTime) {
        if (!hasPreviousFrame) {
            hasPreviousFrame = true;
            previousGameTick = frameTime.gameTick();
            previousPartialTick = frameTime.partialTick();
            return 0.0D;
        }
        long tickDelta = frameTime.gameTick() - previousGameTick;
        double delta = tickDelta < 0L
                ? 0.0D
                : Math.max(0.0D, ((double) tickDelta + frameTime.partialTick() - previousPartialTick) / TICKS_PER_SECOND);
        previousGameTick = frameTime.gameTick();
        previousPartialTick = frameTime.partialTick();
        return delta;
    }

    private static double elapsedSeconds(long startGameTick, ClientAnimationV2FrameTime frameTime) {
        long tickDelta = frameTime.gameTick() - startGameTick;
        if (tickDelta < 0L) {
            return 0.0D;
        }
        return Math.max(0.0D, ((double) tickDelta + frameTime.partialTick()) / TICKS_PER_SECOND);
    }

    private void appendPendingInstallResults(List<AnimationIntentReconciliationResult> results) {
        for (AnimationIntentReconciliationResult result : pendingInstallResults) {
            addResult(results, result);
        }
        pendingInstallResults = List.of();
    }

    private static void addResult(
            List<AnimationIntentReconciliationResult> results,
            AnimationIntentReconciliationOutcome outcome,
            AnimationIntent intent) {
        addResult(results, new AnimationIntentReconciliationResult(outcome, intent));
    }

    private static void addResult(
            List<AnimationIntentReconciliationResult> results, AnimationIntentReconciliationResult result) {
        if (results.size() >= MAX_RECONCILIATION_RESULTS_PER_FRAME) {
            throw new IllegalStateException("v2 reconciliation result budget was exceeded");
        }
        results.add(result);
    }

    private void claimOwner() {
        Thread current = Thread.currentThread();
        Thread observed = ownerThread.get();
        if (observed == null && ownerThread.compareAndSet(null, current)) {
            return;
        }
        if (ownerThread.get() != current) {
            throw new IllegalStateException("ClientAnimationV2Runtime owner changed");
        }
    }

    private record IncomingDrain(
            List<AnimationIntent> intents,
            AnimationIntent overflowIntent,
            AnimationIntent deferredIntent) {
    }
}
