package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Experimental/internal v2 instance runtime.
 *
 * <p>One owner advances frozen controller state. Callback threads can only offer immutable commands into a bounded
 * queue. Commands are applied at the end of a frame after old state has advanced, so an absolute command playhead is
 * never advanced twice by the same frame interval.</p>
 */
public final class AnimationV2InstanceRuntime {
    private static final Comparator<AnimationV2Command> COMMAND_ORDER = Comparator
            .comparing((AnimationV2Command command) -> command.controllerId().value())
            .thenComparingLong(AnimationV2Command::sequence)
            .thenComparing(command -> command.animationKey().value())
            .thenComparingDouble(AnimationV2Command::requestedPlayheadSeconds)
            .thenComparingDouble(AnimationV2Command::playbackSpeed);
    private static final Comparator<AnimationV2SequenceRejection> REJECTION_ORDER = Comparator
            .comparing((AnimationV2SequenceRejection rejection) -> rejection.controllerId().value())
            .thenComparingLong(AnimationV2SequenceRejection::sequence);
    private static final String NEXT_CYCLE_DETAIL_PREFIX = "automatic next chain revisited ";
    private static final String NEXT_CYCLE_DETAIL_ELLIPSIS = "…";

    private final AnimationV2InstancePlan plan;
    private final ControllerRuntime[] controllersInEvaluationOrder;
    /** Fixed owner-only copy used to prove a whole prospective frame before live state is committed. */
    private final FrameStage frameStage;
    private final ArrayBlockingQueue<AnimationV2Command> incoming = new ArrayBlockingQueue<>(
            AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE);
    /**
     * Callback-owned exceptional-path handshake. Every rejected offer publishes a fresh identity, so an owner can
     * acknowledge only the exact ticket it captured; no finite counter can ABA back to an earlier pending state.
     */
    private final AtomicReference<IngressOverflowTicket> ingressOverflowTicket = new AtomicReference<>();
    private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
    private final AtomicReference<AnimationV2EvaluationSnapshot> latest;
    /** One complete owner-captured snapshot; new callback work remains in ingress until this list drains. */
    private final ArrayList<AnimationV2Command> commandBacklog = new ArrayList<>(
            AnimationV2Limits.MAX_OWNER_COMMAND_BACKLOG_PER_INSTANCE);
    /** Synchronous commands received while a prior snapshot drains; capacity exhaustion is explicit backpressure. */
    private final ArrayList<AnimationV2Command> frameCommandBacklog = new ArrayList<>(
            AnimationV2Limits.MAX_FRAME_COMMANDS_PER_ADVANCE);
    /** Fixed owner scratch: a concurrent ingress suffix arriving after this snapshot belongs to the next frame. */
    private final AnimationV2Command[] ingressPreflightSnapshot = new AnimationV2Command[
            AnimationV2Limits.MAX_INGRESS_QUEUE_PER_INSTANCE];
    /** Reused consumer captures a stable bounded queue prefix without a bulk-array copy. */
    private final IngressCapture ingressCapture = new IngressCapture(ingressPreflightSnapshot);
    private final AnimationV2TransformScratch restScratch = new AnimationV2TransformScratch();
    private final AnimationV2TransformScratch sampleScratch = new AnimationV2TransformScratch();
    private final AnimationV2TransformScratch referenceScratch = new AnimationV2TransformScratch();
    private final AnimationV2TransformScratch resultScratch = new AnimationV2TransformScratch();
    private long revision;

    public AnimationV2InstanceRuntime(AnimationV2InstancePlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.controllersInEvaluationOrder = new ControllerRuntime[plan.controllers().size()];
        ControllerRuntime[] stagedControllers = new ControllerRuntime[plan.controllers().size()];
        LinkedHashMap<BlendResourceId, ControllerRuntime> stagedById = new LinkedHashMap<>();
        int boneCount = plan.boneSchema().boneCount();
        for (int index = 0; index < controllersInEvaluationOrder.length; index++) {
            AnimationV2ControllerDefinition definition = plan.controllers().get(index);
            ControllerRuntime controller = new ControllerRuntime(definition, boneCount);
            controllersInEvaluationOrder[index] = controller;
            ControllerRuntime stagedController = new ControllerRuntime(definition, boneCount);
            stagedControllers[index] = stagedController;
            stagedById.put(definition.id(), stagedController);
        }
        this.frameStage = new FrameStage(stagedControllers, Map.copyOf(stagedById));
        latest = new AtomicReference<>(new AnimationV2EvaluationSnapshot(
                0L, plan.boneSchema().restPose(), Map.of(), List.of()));
    }

    public AnimationV2InstancePlan plan() {
        return plan;
    }

    /** Offers a semantic command without blocking a callback thread. Overflow is explicit and observable. */
    public AnimationV2IngressOutcome enqueue(AnimationV2Command command) {
        AnimationV2Command checked = Objects.requireNonNull(command, "command");
        if (incoming.offer(checked)) {
            return AnimationV2IngressOutcome.QUEUED;
        }
        // This allocation is exceptional only: a normal accepted ingress offer creates no ticket. The set is the
        // producer linearization point; acknowledgement below uses this unique object identity rather than a counter.
        ingressOverflowTicket.set(new IngressOverflowTicket(checked));
        return AnimationV2IngressOutcome.QUEUE_OVERFLOW;
    }

    /** Returns the current immutable observer snapshot without exposing mutable controller state. */
    public AnimationV2EvaluationSnapshot latestSnapshot() {
        return latest.get();
    }

    /**
     * Advances old state then applies commands drained from the bounded callback queue at this frame boundary.
     */
    public AnimationV2EvaluationSnapshot advance(double deltaSeconds) {
        return advanceAtFrame(deltaSeconds, List.of(), List.of());
    }

    /**
     * Advances old state and atomically applies absolute semantic commands at the current frame boundary.
     *
     * <p>Callers using a presentation clock should use this method rather than queueing a command and then advancing
     * it again in the same frame.</p>
     */
    public AnimationV2EvaluationSnapshot advanceAtFrame(double deltaSeconds, List<AnimationV2Command> frameCommands) {
        return advanceAtFrame(deltaSeconds, frameCommands, List.of());
    }

    /**
     * Advances old state and applies complete command groups plus explicit fail-closed sequence revocations.
     *
     * <p>Frame commands are accepted into a bounded owner queue before old state advances. A caller that exceeds that
     * bound receives explicit backpressure; no partial frame group is silently dropped.</p>
     */
    public AnimationV2EvaluationSnapshot advanceAtFrame(
            double deltaSeconds,
            List<AnimationV2Command> frameCommands,
            List<AnimationV2SequenceRejection> frameRejections) {
        claimOwner();
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0D) {
            throw new IllegalArgumentException("advance delta must be finite and non-negative");
        }
        List<AnimationV2SequenceRejection> checkedRejections = checkedFrameRejections(frameRejections);
        List<AnimationV2Command> checkedFrameCommands = checkedFrameCommands(frameCommands);
        double boundedDelta = deltaSeconds;
        if (boundedDelta > AnimationV2Limits.MAX_ADVANCE_SECONDS) {
            boundedDelta = AnimationV2Limits.MAX_ADVANCE_SECONDS;
        }
        // This check must precede every prospective controller or queue mutation. A caller can therefore retry a
        // revision-boundary failure without losing a captured ingress prefix or a synchronous frame command.
        long nextRevision = Math.incrementExact(revision);
        int capturedIngressCount = 0;
        FrameStage stage = frameStage;
        try {
            stage.copyFrom(controllersInEvaluationOrder, commandBacklog, frameCommandBacklog);
            capturedIngressCount = captureIngressPrefix();
            requireOwnerBacklogCapacity(capturedIngressCount, checkedFrameCommands.size());
            appendCommands(stage.frameCommandBacklog, checkedFrameCommands);
            OverflowCapture overflow = captureIngressOverflow();
            AnimationV2Diagnostics diagnostics = new AnimationV2Diagnostics();
            if (boundedDelta != deltaSeconds) {
                diagnostics.add(AnimationV2DiagnosticCode.ADVANCE_DELTA_CLAMPED, null, null, -1,
                        "advance was clamped to " + AnimationV2Limits.MAX_ADVANCE_SECONDS + " seconds");
            }

            // The complete prospective frame is evaluated against fixed owner-only staging. Any ordinary input error
            // leaves every live controller, backlog, revision, and latest snapshot untouched.
            for (ControllerRuntime controller : stage.controllersInEvaluationOrder) {
                controller.beginObserverTraversal();
            }
            for (ControllerRuntime controller : stage.controllersInEvaluationOrder) {
                controller.advance(boundedDelta, diagnostics);
            }
            collectCommands(stage, diagnostics, capturedIngressCount, overflow);
            applyFrameRejections(stage, checkedRejections, diagnostics);
            applyCollectedCommands(stage, diagnostics);
            for (AnimationV2Diagnostic diagnostic : plan.staticDiagnostics()) {
                diagnostics.add(diagnostic.code(), diagnostic.controllerId(), diagnostic.layerId(), diagnostic.boneIndex(), diagnostic.detail());
            }
            AnimationV2EvaluationSnapshot snapshot = evaluate(
                    stage.controllersInEvaluationOrder, diagnostics, nextRevision);
            prepareCommit(stage);
            commitFrame(stage, capturedIngressCount, nextRevision, snapshot, overflow);
            return snapshot;
        } finally {
            Arrays.fill(ingressPreflightSnapshot, 0, capturedIngressCount, null);
            stage.clearRetainedReferences();
        }
    }

    private List<AnimationV2Command> checkedFrameCommands(List<AnimationV2Command> frameCommands) {
        Objects.requireNonNull(frameCommands, "frameCommands");
        if (frameCommands.size() > AnimationV2Limits.MAX_FRAME_COMMANDS_PER_ADVANCE) {
            throw new IllegalArgumentException("frame command batch exceeds the fixed v2 owner backlog bound");
        }
        if (frameCommandBacklog.size() + frameCommands.size() > AnimationV2Limits.MAX_FRAME_COMMANDS_PER_ADVANCE) {
            throw new IllegalStateException("frame command backlog is full; retry after a later owner frame");
        }
        List<AnimationV2Command> checked = new ArrayList<>(frameCommands.size());
        for (AnimationV2Command command : frameCommands) {
            checked.add(Objects.requireNonNull(command, "frameCommand"));
        }
        return checked;
    }

    /** Captures exactly the ingress prefix this owner frame may consume without mutating the callback queue. */
    private int captureIngressPrefix() {
        if (!commandBacklog.isEmpty()) {
            return 0;
        }
        ingressCapture.reset();
        incoming.forEach(ingressCapture);
        if (ingressCapture.overflowed()) {
            ingressCapture.clearCapturedReferences();
            throw new IllegalStateException("v2 ingress capture exceeded its fixed bound");
        }
        return ingressCapture.count();
    }

    /** Appends into an already fixed-capacity owner buffer without bulk-copy allocation. */
    private static void appendCommands(ArrayList<AnimationV2Command> target, List<AnimationV2Command> source) {
        for (int index = 0; index < source.size(); index++) {
            target.add(source.get(index));
        }
    }

    private static void copyObserverSegments(
            ArrayList<AnimationV2ObserverTraversal.Segment> target,
            List<AnimationV2ObserverTraversal.Segment> source) {
        for (int index = 0; index < source.size(); index++) {
            target.add(source.get(index));
        }
    }

    private static void copyObserverPublications(
            ArrayList<AnimationV2ObserverTraversal.Publication> target,
            List<AnimationV2ObserverTraversal.Publication> source) {
        for (int index = 0; index < source.size(); index++) {
            target.add(source.get(index));
        }
    }

    /** Rejects a prospective ingress prefix that would exceed its fixed owner backlog bound. */
    private void requireOwnerBacklogCapacity(int capturedIngressCount, int checkedFrameCommandCount) {
        if (!commandBacklog.isEmpty()) {
            return;
        }
        int planned = capturedIngressCount + frameCommandBacklog.size() + checkedFrameCommandCount;
        if (planned > AnimationV2Limits.MAX_OWNER_COMMAND_BACKLOG_PER_INSTANCE) {
            throw new IllegalStateException("v2 owner command backlog exceeded its fixed bound");
        }
    }

    private static List<AnimationV2SequenceRejection> checkedFrameRejections(
            List<AnimationV2SequenceRejection> frameRejections) {
        Objects.requireNonNull(frameRejections, "frameRejections");
        if (frameRejections.size() > AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE) {
            throw new IllegalArgumentException("frame sequence rejection batch exceeds the fixed v2 controller bound");
        }
        List<AnimationV2SequenceRejection> checked = new ArrayList<>(frameRejections.size());
        for (AnimationV2SequenceRejection rejection : frameRejections) {
            checked.add(Objects.requireNonNull(rejection, "frameRejection"));
        }
        checked.sort(REJECTION_ORDER);
        return checked;
    }

    private OverflowCapture captureIngressOverflow() {
        IngressOverflowTicket observedTicket = ingressOverflowTicket.get();
        if (observedTicket == null) {
            return OverflowCapture.NONE;
        }
        return new OverflowCapture(true, observedTicket);
    }

    private void collectCommands(
            FrameStage stage,
            AnimationV2Diagnostics diagnostics,
            int capturedIngressCount,
            OverflowCapture overflow) {
        if (overflow.isPending()) {
            diagnostics.add(AnimationV2DiagnosticCode.COMMAND_INGRESS_QUEUE_OVERFLOW,
                    overflow.command() == null ? null : overflow.command().controllerId(), null, -1,
                    "bounded command ingress queue rejected at least one command");
        }
        if (stage.commandBacklog.isEmpty()) {
            for (int index = 0; index < capturedIngressCount; index++) {
                stage.commandBacklog.add(ingressPreflightSnapshot[index]);
            }
            appendCommands(stage.commandBacklog, stage.frameCommandBacklog);
            stage.frameCommandBacklog.clear();
        }
    }

    private void applyFrameRejections(
            FrameStage stage,
            List<AnimationV2SequenceRejection> frameRejections,
            AnimationV2Diagnostics diagnostics) {
        for (AnimationV2SequenceRejection rejection : frameRejections) {
            ControllerRuntime controller = stage.controllersById.get(rejection.controllerId());
            if (controller == null) {
                diagnostics.add(AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT, rejection.controllerId(), null, -1,
                        "sequence rejection targets an undeclared controller");
            } else {
                controller.rejectSequence(rejection.sequence());
            }
        }
    }

    private void applyCollectedCommands(FrameStage stage, AnimationV2Diagnostics diagnostics) {
        stage.commandBacklog.sort(COMMAND_ORDER);
        AnimationV2Command deferredFromBacklog = deferredBacklogCommand(stage.commandBacklog);
        if (deferredFromBacklog != null) {
            diagnostics.add(AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED,
                    deferredFromBacklog.controllerId(), null, -1,
                    "bounded group scheduler deferred one or more complete controller sequences");
        }
        int processedEnd = 0;
        int processedGroups = 0;
        for (int start = 0; start < stage.commandBacklog.size();) {
            if (processedGroups >= AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE) {
                break;
            }
            AnimationV2Command first = stage.commandBacklog.get(start);
            int end = start + 1;
            boolean identical = true;
            while (end < stage.commandBacklog.size() && sameControllerSequence(first, stage.commandBacklog.get(end))) {
                identical &= first.equals(stage.commandBacklog.get(end));
                end++;
            }
            FrameGroupMatch frameGroup = matchingFrameCommands(first, stage.frameCommandBacklog);
            identical &= frameGroup.identical();
            if (!identical) {
                diagnostics.add(AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT, first.controllerId(), null, -1,
                        "different semantic commands share one controller sequence and were rejected as a group");
                ControllerRuntime controller = stage.controllersById.get(first.controllerId());
                if (controller != null) {
                    controller.rejectSequence(first.sequence());
                }
            } else {
                if (end - start + frameGroup.count() > 1) {
                    diagnostics.add(AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED, first.controllerId(), null, -1,
                            "identical commands in one frame were coalesced");
                }
                ControllerRuntime controller = stage.controllersById.get(first.controllerId());
                if (controller == null) {
                    diagnostics.add(AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT, first.controllerId(), null, -1,
                            "command targets an undeclared controller");
                } else {
                    controller.apply(first, diagnostics);
                }
            }
            if (frameGroup.count() > 0) {
                removeMatchingFrameCommands(stage.frameCommandBacklog, first);
            }
            processedEnd = end;
            processedGroups++;
            start = end;
        }
        if (processedEnd > 0) {
            stage.commandBacklog.subList(0, processedEnd).clear();
        }
        AnimationV2Command deferred = deferredFromBacklog == null
                ? (!stage.frameCommandBacklog.isEmpty() ? stage.frameCommandBacklog.getFirst() : null)
                : null;
        if (deferred != null) {
            diagnostics.add(AnimationV2DiagnosticCode.COMMAND_DRAIN_BUDGET_EXHAUSTED,
                    deferred.controllerId(), null, -1,
                    "bounded group scheduler deferred one or more complete controller sequences");
        }
    }

    private AnimationV2Command deferredBacklogCommand(List<AnimationV2Command> backlog) {
        int groups = 0;
        for (int start = 0; start < backlog.size();) {
            if (groups >= AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE) {
                return backlog.get(start);
            }
            AnimationV2Command first = backlog.get(start);
            int end = start + 1;
            while (end < backlog.size() && sameControllerSequence(first, backlog.get(end))) {
                end++;
            }
            groups++;
            start = end;
        }
        return null;
    }

    private FrameGroupMatch matchingFrameCommands(AnimationV2Command first, List<AnimationV2Command> stagedFrameCommands) {
        // A later synchronous member is already visible to this owner frame, so it must join this group before publish.
        int count = 0;
        boolean identical = true;
        for (AnimationV2Command frameCommand : stagedFrameCommands) {
            if (sameControllerSequence(first, frameCommand)) {
                count++;
                identical &= first.equals(frameCommand);
            }
        }
        return new FrameGroupMatch(count, identical);
    }

    /** Removes a consumed synchronous command group without a lambda, iterator, or input-sized temporary array. */
    private static void removeMatchingFrameCommands(
            ArrayList<AnimationV2Command> stagedFrameCommands, AnimationV2Command consumedGroup) {
        int retained = 0;
        int size = stagedFrameCommands.size();
        for (int index = 0; index < size; index++) {
            AnimationV2Command command = stagedFrameCommands.get(index);
            if (!sameControllerSequence(consumedGroup, command)) {
                if (retained != index) {
                    stagedFrameCommands.set(retained, command);
                }
                retained++;
            }
        }
        while (stagedFrameCommands.size() > retained) {
            stagedFrameCommands.removeLast();
        }
    }

    private AnimationV2EvaluationSnapshot evaluate(
            ControllerRuntime[] controllers,
            AnimationV2Diagnostics diagnostics,
            long nextRevision) {
        LinkedHashMap<BlendResourceId, AnimationV2ControllerPlayhead> playheads = new LinkedHashMap<>();
        for (ControllerRuntime controller : controllers) {
            playheads.put(controller.definition.id(), controller.playhead());
        }

        int boneCount = plan.boneSchema().boneCount();
        Transform[] composed = new Transform[boneCount];
        for (int bone = 0; bone < boneCount; bone++) {
            restScratch.set(plan.boneSchema().restPose().transform(bone));
            resultScratch.set(restScratch);

            int highestPriority = Integer.MIN_VALUE;
            int winnerCount = 0;
            long winnerControllerBits = 0L;
            int firstWinnerController = -1;
            int firstWinnerLayer = -1;
            double winnerWeightSum = 0.0D;
            double winnerTx = 0.0D;
            double winnerTy = 0.0D;
            double winnerTz = 0.0D;
            double winnerScale = 0.0D;
            double winnerQx = 0.0D;
            double winnerQy = 0.0D;
            double winnerQz = 0.0D;
            double winnerQw = 0.0D;
            int totalContributions = 0;
            int exclusiveController = -1;
            int exclusiveLayer = -1;

            int referencePriority = Integer.MIN_VALUE;
            int referenceController = -1;
            int referenceLayer = -1;
            double referenceWinnerWeightSum = 0.0D;
            for (int controllerIndex = 0; controllerIndex < controllers.length; controllerIndex++) {
                ControllerRuntime controller = controllers[controllerIndex];
                AnimationV2ControllerDefinition definition = controller.definition;
                for (int layerIndex = 0; layerIndex < definition.layerCount(); layerIndex++) {
                    AnimationV2LayerDefinition layer = definition.layers().get(layerIndex);
                    if (layer.mode() != AnimationV2LayerMode.OVERRIDE) {
                        continue;
                    }
                    double weight = (double) layer.weight() * (double) layer.mask().weightAt(bone);
                    if (weight <= 0.0D) {
                        continue;
                    }
                    if (definition.priority() > referencePriority) {
                        referencePriority = definition.priority();
                        referenceController = controllerIndex;
                        referenceLayer = layerIndex;
                        referenceWinnerWeightSum = weight;
                    } else if (definition.priority() == referencePriority) {
                        referenceWinnerWeightSum += weight;
                    }
                }
            }
            if (referenceController >= 0) {
                if (Math.max(0.0D, 1.0D - referenceWinnerWeightSum) > 0.0D) {
                    referenceScratch.set(restScratch);
                } else {
                    controllers[referenceController].sampleInto(referenceLayer, bone, referenceScratch);
                }
                referenceScratch.normalizeRotationOr(0.0F, 0.0F, 0.0F, 1.0F);
                referenceScratch.canonicalizeRotation();
            }

            for (int controllerIndex = 0; controllerIndex < controllers.length; controllerIndex++) {
                ControllerRuntime controller = controllers[controllerIndex];
                AnimationV2ControllerDefinition definition = controller.definition;
                for (int layerIndex = 0; layerIndex < definition.layerCount(); layerIndex++) {
                    AnimationV2LayerDefinition layer = definition.layers().get(layerIndex);
                    if (layer.mode() != AnimationV2LayerMode.OVERRIDE) {
                        continue;
                    }
                    double weight = (double) layer.weight() * (double) layer.mask().weightAt(bone);
                    if (weight <= 0.0D) {
                        continue;
                    }
                    totalContributions++;
                    if (layer.exclusive() && exclusiveController < 0) {
                        exclusiveController = controllerIndex;
                        exclusiveLayer = layerIndex;
                    }
                    int priority = definition.priority();
                    if (priority > highestPriority) {
                        if (winnerCount > 0) {
                            diagnostics.add(AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED,
                                    controllers[firstWinnerController].definition.id(),
                                    controllers[firstWinnerController].definition.layers().get(firstWinnerLayer).id(), bone,
                                    "higher controller priority owns this override bone");
                        }
                        highestPriority = priority;
                        winnerCount = 0;
                        winnerControllerBits = 0L;
                        winnerWeightSum = 0.0D;
                        winnerTx = 0.0D;
                        winnerTy = 0.0D;
                        winnerTz = 0.0D;
                        winnerScale = 0.0D;
                        winnerQx = 0.0D;
                        winnerQy = 0.0D;
                        winnerQz = 0.0D;
                        winnerQw = 0.0D;
                        firstWinnerController = controllerIndex;
                        firstWinnerLayer = layerIndex;
                    } else if (priority < highestPriority) {
                        diagnostics.add(AnimationV2DiagnosticCode.LOWER_PRIORITY_OVERRIDE_SUPPRESSED,
                                definition.id(), layer.id(), bone, "higher controller priority owns this override bone");
                        continue;
                    }
                    controller.sampleInto(layerIndex, bone, sampleScratch);
                    winnerCount++;
                    winnerControllerBits |= 1L << controllerIndex;
                    winnerWeightSum += weight;
                    winnerTx += (double) sampleScratch.tx * weight;
                    winnerTy += (double) sampleScratch.ty * weight;
                    winnerTz += (double) sampleScratch.tz * weight;
                    winnerScale += (double) sampleScratch.scale * weight;
                    float sign = AnimationV2TransformScratch.referenceHemisphereSign(
                            referenceScratch.qx, referenceScratch.qy, referenceScratch.qz, referenceScratch.qw,
                            sampleScratch.qx, sampleScratch.qy, sampleScratch.qz, sampleScratch.qw);
                    winnerQx += (double) sampleScratch.qx * (double) sign * weight;
                    winnerQy += (double) sampleScratch.qy * (double) sign * weight;
                    winnerQz += (double) sampleScratch.qz * (double) sign * weight;
                    winnerQw += (double) sampleScratch.qw * (double) sign * weight;
                }
            }

            if (winnerCount > 0) {
                if (winnerCount > 1) {
                    diagnostics.add(AnimationV2DiagnosticCode.OVERLAPPING_OVERRIDE,
                            controllers[firstWinnerController].definition.id(),
                            controllers[firstWinnerController].definition.layers().get(firstWinnerLayer).id(), bone,
                            "same-priority override contributions are normalized");
                }
                if (Long.bitCount(winnerControllerBits) > 1) {
                    diagnostics.add(AnimationV2DiagnosticCode.COMPETING_CONTROLLER_WRITE,
                            controllers[firstWinnerController].definition.id(),
                            controllers[firstWinnerController].definition.layers().get(firstWinnerLayer).id(), bone,
                            "same-priority controllers overlap deterministically");
                }
                double normalizer = Math.max(1.0D, winnerWeightSum);
                double restWeight = Math.max(0.0D, 1.0D - winnerWeightSum);
                resultScratch.tx = finite((double) restScratch.tx * restWeight + winnerTx / normalizer);
                resultScratch.ty = finite((double) restScratch.ty * restWeight + winnerTy / normalizer);
                resultScratch.tz = finite((double) restScratch.tz * restWeight + winnerTz / normalizer);
                resultScratch.scale = finite((double) restScratch.scale * restWeight + winnerScale / normalizer);
                float restSign = AnimationV2TransformScratch.referenceHemisphereSign(
                        referenceScratch.qx, referenceScratch.qy, referenceScratch.qz, referenceScratch.qw,
                        restScratch.qx, restScratch.qy, restScratch.qz, restScratch.qw);
                resultScratch.qx = finite((double) restScratch.qx * (double) restSign * restWeight
                        + winnerQx / normalizer);
                resultScratch.qy = finite((double) restScratch.qy * (double) restSign * restWeight
                        + winnerQy / normalizer);
                resultScratch.qz = finite((double) restScratch.qz * (double) restSign * restWeight
                        + winnerQz / normalizer);
                resultScratch.qw = finite((double) restScratch.qw * (double) restSign * restWeight
                        + winnerQw / normalizer);
                resultScratch.normalizeRotationOr(
                        referenceScratch.qx, referenceScratch.qy, referenceScratch.qz, referenceScratch.qw);
                resultScratch.canonicalizeRotation();
            }

            for (int controllerIndex = 0; controllerIndex < controllers.length; controllerIndex++) {
                ControllerRuntime controller = controllers[controllerIndex];
                AnimationV2ControllerDefinition definition = controller.definition;
                for (int layerIndex = 0; layerIndex < definition.layerCount(); layerIndex++) {
                    AnimationV2LayerDefinition layer = definition.layers().get(layerIndex);
                    if (layer.mode() != AnimationV2LayerMode.ADDITIVE) {
                        continue;
                    }
                    double weight = (double) layer.weight() * (double) layer.mask().weightAt(bone);
                    if (weight <= 0.0D) {
                        continue;
                    }
                    totalContributions++;
                    if (layer.exclusive() && exclusiveController < 0) {
                        exclusiveController = controllerIndex;
                        exclusiveLayer = layerIndex;
                    }
                    controller.sampleInto(layerIndex, bone, sampleScratch);
                    resultScratch.applyAdditive(restScratch, sampleScratch, weight);
                }
            }
            // ADDITIVE composition may negate an otherwise equivalent quaternion. Canonicalize only after the full
            // product chain so a weight-one relative delta keeps its exact representable sample components.
            resultScratch.canonicalizeRotation();
            if (exclusiveController >= 0 && totalContributions > 1) {
                diagnostics.add(AnimationV2DiagnosticCode.EXCLUSIVE_WRITE_CONFLICT,
                        controllers[exclusiveController].definition.id(),
                        controllers[exclusiveController].definition.layers().get(exclusiveLayer).id(), bone,
                        "an exclusive non-zero layer overlaps another override or additive contribution");
            }
            composed[bone] = resultScratch.toTransform();
        }
        LinkedHashMap<BlendResourceId, AnimationV2ObserverTraversal.ControllerTraversal> traversals = new LinkedHashMap<>();
        for (ControllerRuntime controller : controllers) {
            traversals.put(controller.definition.id(), controller.freezeObserverTraversal(nextRevision));
        }
        return new AnimationV2EvaluationSnapshot(
                nextRevision, AnimationV2Pose.takeOwnership(composed), playheads, diagnostics.freeze(),
                AnimationV2ObserverTraversal.freeze(traversals));
    }

    /**
     * Commits only work that has already produced a complete immutable snapshot. No user-controlled computation runs
     * after this point: queue removal is a stable captured FIFO prefix and every copied value already passed staging.
     */
    private void commitFrame(
            FrameStage stage,
            int capturedIngressCount,
            long nextRevision,
            AnimationV2EvaluationSnapshot snapshot,
            OverflowCapture overflow) {
        for (int index = 0; index < capturedIngressCount; index++) {
            incoming.poll();
        }
        commandBacklog.clear();
        appendCommands(commandBacklog, stage.commandBacklog);
        frameCommandBacklog.clear();
        appendCommands(frameCommandBacklog, stage.frameCommandBacklog);
        for (int index = 0; index < controllersInEvaluationOrder.length; index++) {
            controllersInEvaluationOrder[index].copyStateFrom(stage.controllersInEvaluationOrder[index]);
        }
        revision = nextRevision;
        latest.set(snapshot);
        acknowledgeIngressOverflow(overflow);
    }

    /** Completes every possible capacity growth before the captured ingress prefix is consumed. */
    private void prepareCommit(FrameStage stage) {
        commandBacklog.ensureCapacity(stage.commandBacklog.size());
        frameCommandBacklog.ensureCapacity(stage.frameCommandBacklog.size());
        for (int index = 0; index < controllersInEvaluationOrder.length; index++) {
            controllersInEvaluationOrder[index].prepareCopyFrom(stage.controllersInEvaluationOrder[index]);
        }
    }

    private void acknowledgeIngressOverflow(OverflowCapture overflow) {
        if (!overflow.isPending()) {
            return;
        }
        // A producer suffix gets a distinct ticket. Its reference cannot be cleared by a captured prefix, including
        // after an exact 2^64 sequence of rejected offers or when the same command object is rejected repeatedly.
        ingressOverflowTicket.compareAndSet(overflow.ticket(), null);
    }

    private void claimOwner() {
        Thread current = Thread.currentThread();
        Thread observed = ownerThread.get();
        if (observed == null && ownerThread.compareAndSet(null, current)) {
            return;
        }
        if (ownerThread.get() != current) {
            throw new IllegalStateException("AnimationV2InstanceRuntime advance owner changed");
        }
    }

    private static boolean sameControllerSequence(AnimationV2Command left, AnimationV2Command right) {
        return left.controllerId().equals(right.controllerId()) && left.sequence() == right.sequence();
    }

    /** Builds a diagnostic-safe key display without changing the independently enforced public key limit. */
    private static String nextCycleDetail(BlendAnimationKey key) {
        String value = key.value();
        int maximum = AnimationV2Limits.MAX_IDENTIFIER_UTF16_CODE_UNITS;
        if (NEXT_CYCLE_DETAIL_PREFIX.length() + value.length() <= maximum) {
            return NEXT_CYCLE_DETAIL_PREFIX + value;
        }
        int endExclusive = maximum - NEXT_CYCLE_DETAIL_PREFIX.length() - NEXT_CYCLE_DETAIL_ELLIPSIS.length();
        if (endExclusive > 0 && endExclusive < value.length()
                && Character.isHighSurrogate(value.charAt(endExclusive - 1))
                && Character.isLowSurrogate(value.charAt(endExclusive))) {
            endExclusive--;
        }
        return NEXT_CYCLE_DETAIL_PREFIX + value.substring(0, Math.max(0, endExclusive)) + NEXT_CYCLE_DETAIL_ELLIPSIS;
    }

    private record FrameGroupMatch(int count, boolean identical) {
    }

    /** Unique rejected-offer identity; it is allocated only after a bounded callback offer has already failed. */
    private record IngressOverflowTicket(AnimationV2Command command) {
    }

    private record OverflowCapture(boolean pending, IngressOverflowTicket ticket) {
        private static final OverflowCapture NONE = new OverflowCapture(false, null);

        private boolean isPending() {
            return pending;
        }

        private AnimationV2Command command() {
            return ticket == null ? null : ticket.command();
        }
    }

    /** Fixed reusable ingress visitor; queue growth or a changed traversal contract fails before any live mutation. */
    private static final class IngressCapture implements Consumer<AnimationV2Command> {
        private final AnimationV2Command[] target;
        private int count;
        private boolean overflowed;

        private IngressCapture(AnimationV2Command[] target) {
            this.target = target;
        }

        private void reset() {
            count = 0;
            overflowed = false;
        }

        @Override
        public void accept(AnimationV2Command command) {
            if (count >= target.length) {
                overflowed = true;
                return;
            }
            target[count++] = command;
        }

        private int count() {
            return count;
        }

        private boolean overflowed() {
            return overflowed;
        }

        private void clearCapturedReferences() {
            Arrays.fill(target, 0, count, null);
            count = 0;
        }
    }

    /** Fixed owner-only mutable storage for one complete prospective frame. */
    private static final class FrameStage {
        private final ControllerRuntime[] controllersInEvaluationOrder;
        private final Map<BlendResourceId, ControllerRuntime> controllersById;
        private final ArrayList<AnimationV2Command> commandBacklog = new ArrayList<>(
                AnimationV2Limits.MAX_OWNER_COMMAND_BACKLOG_PER_INSTANCE);
        private final ArrayList<AnimationV2Command> frameCommandBacklog = new ArrayList<>(
                AnimationV2Limits.MAX_FRAME_COMMANDS_PER_ADVANCE);

        private FrameStage(
                ControllerRuntime[] controllersInEvaluationOrder,
                Map<BlendResourceId, ControllerRuntime> controllersById) {
            this.controllersInEvaluationOrder = controllersInEvaluationOrder;
            this.controllersById = controllersById;
        }

        private void copyFrom(
                ControllerRuntime[] sourceControllers,
                List<AnimationV2Command> sourceCommandBacklog,
                List<AnimationV2Command> sourceFrameCommandBacklog) {
            for (int index = 0; index < controllersInEvaluationOrder.length; index++) {
                controllersInEvaluationOrder[index].copyStateFrom(sourceControllers[index]);
            }
            commandBacklog.clear();
            appendCommands(commandBacklog, sourceCommandBacklog);
            frameCommandBacklog.clear();
            appendCommands(frameCommandBacklog, sourceFrameCommandBacklog);
        }

        private void clearRetainedReferences() {
            commandBacklog.clear();
            frameCommandBacklog.clear();
            for (ControllerRuntime controller : controllersInEvaluationOrder) {
                controller.clearStagedReferences();
            }
        }
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalStateException("v2 evaluation produced a non-finite transform component");
        }
        return result;
    }

    /** Mutable controller playhead, accessed only by the instance owner. */
    private static final class ControllerRuntime {
        private final AnimationV2ControllerDefinition definition;
        private final int boneCount;
        private final AnimationV2TransformScratch transitionSourceScratch = new AnimationV2TransformScratch();
        private final AnimationV2TransformScratch frozenBuildScratch = new AnimationV2TransformScratch();
        private final BlendAnimationKey[] nextVisited;
        private int nextVisitedCount;
        private AnimationV2ControllerState current;
        private AnimationV2Clip[] currentClips;
        private AnimationV2ControllerState previous;
        private AnimationV2Clip[] previousClips;
        private AnimationV2Pose[] frozenPreviousPoses;
        private BlendAnimationKey transitionSourceState;
        private double currentTime;
        private double previousTime;
        private double playbackSpeed = 1.0D;
        private double previousPlaybackSpeed = 1.0D;
        private double transitionElapsed;
        private double transitionDuration;
        private long sequenceWatermark = -1L;
        private AnimationV2Command acceptedCommand;
        /** Controller-local observer identities never expose mutable playhead state. */
        private long observerLoopEpoch;
        private long observerOccurrence;
        private final ArrayList<AnimationV2ObserverTraversal.Segment> observerSegments = new ArrayList<>(
                AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE);
        /** Rolling owner-only continuation; each published snapshot receives a deeply immutable copy. */
        private final ArrayList<AnimationV2ObserverTraversal.Publication> observerContinuityPublications = new ArrayList<>(
                AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE);
        private AnimationV2ObserverTraversal.Anchor observerContinuityInitial;
        private AnimationV2ObserverTraversal.Anchor observerContinuityTerminal;
        private boolean observerDiscontinuity;
        private boolean observerTraversalTruncated;

        private ControllerRuntime(AnimationV2ControllerDefinition definition, int boneCount) {
            this.definition = definition;
            this.boneCount = boneCount;
            this.nextVisited = new BlendAnimationKey[AnimationV2Limits.MAX_STATES_PER_CONTROLLER];
            this.current = definition.initialStateDefinition();
            this.currentClips = definition.clipsFor(current);
        }

        /** Copies only owner-mutable state; definition and fixed scratch storage stay local to each runtime copy. */
        private void prepareCopyFrom(ControllerRuntime source) {
            observerSegments.ensureCapacity(source.observerSegments.size());
            observerContinuityPublications.ensureCapacity(source.observerContinuityPublications.size());
        }

        private void copyStateFrom(ControllerRuntime source) {
            current = source.current;
            currentClips = source.currentClips;
            previous = source.previous;
            previousClips = source.previousClips;
            frozenPreviousPoses = source.frozenPreviousPoses;
            transitionSourceState = source.transitionSourceState;
            currentTime = source.currentTime;
            previousTime = source.previousTime;
            playbackSpeed = source.playbackSpeed;
            previousPlaybackSpeed = source.previousPlaybackSpeed;
            transitionElapsed = source.transitionElapsed;
            transitionDuration = source.transitionDuration;
            sequenceWatermark = source.sequenceWatermark;
            acceptedCommand = source.acceptedCommand;
            observerLoopEpoch = source.observerLoopEpoch;
            observerOccurrence = source.observerOccurrence;
            observerSegments.clear();
            copyObserverSegments(observerSegments, source.observerSegments);
            observerContinuityPublications.clear();
            copyObserverPublications(observerContinuityPublications, source.observerContinuityPublications);
            observerContinuityInitial = source.observerContinuityInitial;
            observerContinuityTerminal = source.observerContinuityTerminal;
            observerDiscontinuity = source.observerDiscontinuity;
            observerTraversalTruncated = source.observerTraversalTruncated;
        }

        /** Clears transient references after every prospective frame; the next frame always copies live state afresh. */
        private void clearStagedReferences() {
            Arrays.fill(nextVisited, 0, nextVisitedCount, null);
            nextVisitedCount = 0;
            previous = null;
            previousClips = null;
            frozenPreviousPoses = null;
            transitionSourceState = null;
            acceptedCommand = null;
            observerSegments.clear();
            observerContinuityPublications.clear();
            observerContinuityInitial = null;
            observerContinuityTerminal = null;
            observerDiscontinuity = false;
            observerTraversalTruncated = false;
        }

        private void beginObserverTraversal() {
            observerSegments.clear();
            observerDiscontinuity = false;
            observerTraversalTruncated = false;
        }

        private AnimationV2ObserverTraversal.ControllerTraversal freezeObserverTraversal(long sourceRevision) {
            AnimationV2ObserverTraversal.Anchor currentTerminal = new AnimationV2ObserverTraversal.Anchor(
                    current.key(), currentTime, observerLoopEpoch, observerOccurrence, sourceRevision, sourceRevision);
            if (observerDiscontinuity || observerTraversalTruncated || observerContinuityTerminal == null) {
                // A command/rejection/truncation starts a new owner anchor. Earlier observations deliberately cannot
                // bridge this discontinuity through a later publication.
                observerContinuityPublications.clear();
                observerContinuityInitial = currentTerminal;
                observerContinuityTerminal = currentTerminal;
            } else if (observerSegments.isEmpty()) {
                if (!sameObserverAnchorFacts(observerContinuityTerminal, currentTerminal)) {
                    // A terminal state change without a representable automatic segment is not a trusted bridge.
                    observerContinuityPublications.clear();
                    observerContinuityInitial = currentTerminal;
                    observerContinuityTerminal = currentTerminal;
                } else {
                    AnimationV2ObserverTraversal.Anchor extended = new AnimationV2ObserverTraversal.Anchor(
                            current.key(), currentTime, observerLoopEpoch, observerOccurrence,
                            observerContinuityTerminal.firstRevision(), sourceRevision);
                    observerContinuityTerminal = extended;
                    if (observerContinuityPublications.isEmpty()) {
                        observerContinuityInitial = extended;
                    }
                }
            } else {
                AnimationV2ObserverTraversal.Publication publication = new AnimationV2ObserverTraversal.Publication(
                        sourceRevision, observerContinuityTerminal, currentTerminal, observerSegments);
                observerContinuityPublications.add(publication);
                observerContinuityTerminal = currentTerminal;
                trimObserverContinuity();
            }
            AnimationV2ObserverTraversal.Continuity continuity = new AnimationV2ObserverTraversal.Continuity(
                    observerContinuityInitial, observerContinuityPublications, observerContinuityTerminal);
            return new AnimationV2ObserverTraversal.ControllerTraversal(
                    definition.id(), current.key(), currentTime, observerDiscontinuity, observerTraversalTruncated,
                    observerSegments, continuity);
        }

        private static boolean sameObserverAnchorFacts(
                AnimationV2ObserverTraversal.Anchor first,
                AnimationV2ObserverTraversal.Anchor second) {
            return first.timeline().equals(second.timeline())
                    && Double.compare(first.timeSeconds(), second.timeSeconds()) == 0
                    && first.loopEpoch() == second.loopEpoch()
                    && first.occurrence() == second.occurrence();
        }

        private void trimObserverContinuity() {
            int segmentCount = 0;
            for (AnimationV2ObserverTraversal.Publication publication : observerContinuityPublications) {
                segmentCount += publication.segments().size();
            }
            while (segmentCount > AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE) {
                AnimationV2ObserverTraversal.Publication evicted = observerContinuityPublications.removeFirst();
                segmentCount -= evicted.segments().size();
            }
            if (!observerContinuityPublications.isEmpty()) {
                observerContinuityInitial = observerContinuityPublications.getFirst().startAnchor();
            }
        }

        private void apply(AnimationV2Command command, AnimationV2Diagnostics diagnostics) {
            if (command.sequence() < sequenceWatermark) {
                diagnostics.add(AnimationV2DiagnosticCode.STALE_COMMAND,
                        definition.id(), null, -1, "command sequence is older than the accepted or rejected sequence");
                return;
            }
            if (command.sequence() == sequenceWatermark) {
                boolean duplicate = acceptedCommand != null && command.equals(acceptedCommand);
                if (!duplicate) {
                    rejectSequence(command.sequence());
                }
                diagnostics.add(duplicate
                                ? AnimationV2DiagnosticCode.COMMAND_DUPLICATE_DROPPED
                                : AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT,
                        definition.id(), null, -1,
                        duplicate
                                ? "identical command was already accepted"
                                : "different semantic command reuses an accepted or rejected sequence");
                return;
            }
            AnimationV2ControllerState target;
            try {
                target = definition.state(command.animationKey());
            } catch (IllegalArgumentException exception) {
                rejectSequence(command.sequence());
                diagnostics.add(AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT,
                        definition.id(), null, -1, "command targets an undeclared controller state");
                return;
            }
            if (!AnimationV2Limits.isValidEffectivePlaybackSpeed(target.speed(), command.playbackSpeed())) {
                rejectSequence(command.sequence());
                diagnostics.add(AnimationV2DiagnosticCode.COMMAND_RATE_REJECTED,
                        definition.id(), null, -1, "state speed multiplied by command speed is outside the v2 bound");
                return;
            }
            preflightObserverDiscontinuity();
            sequenceWatermark = command.sequence();
            acceptedCommand = command;
            if (target.key().equals(current.key())) {
                currentTime = normalize(target, command.requestedPlayheadSeconds());
                playbackSpeed = command.playbackSpeed();
                clearTransition();
                markObserverDiscontinuity();
                return;
            }
            transitionTo(target, command.requestedPlayheadSeconds(), command.playbackSpeed());
            markObserverDiscontinuity();
        }

        private void rejectSequence(long sequence) {
            if (sequence < sequenceWatermark) {
                return;
            }
            preflightObserverDiscontinuity();
            sequenceWatermark = sequence;
            acceptedCommand = null;
            resetToInitialState();
            markObserverDiscontinuity();
        }

        private void preflightObserverDiscontinuity() {
            nextObserverOccurrence(observerOccurrence);
        }

        private void resetToInitialState() {
            current = definition.initialStateDefinition();
            currentClips = definition.clipsFor(current);
            currentTime = 0.0D;
            playbackSpeed = 1.0D;
            previousPlaybackSpeed = 1.0D;
            clearTransition();
        }

        private void advance(double realDelta, AnimationV2Diagnostics diagnostics) {
            double remaining = realDelta;
            resetNextVisited();
            addNextVisited(current.key());
            int transitions = 0;
            while (remaining > 0.0D) {
                if (current.playbackMode() == AnimationV2PlaybackMode.LOOP) {
                    advanceLoop(remaining, diagnostics);
                    return;
                }

                double duration = current.durationSeconds();
                double effectiveSpeed = current.speed() * playbackSpeed;
                double untilEnd = duration <= 0.0D
                        ? 0.0D
                        : Math.max(0.0D, duration - currentTime) / effectiveSpeed;
                double candidateTime = currentTime + scaled(remaining, current, playbackSpeed);
                if (duration > 0.0D && candidateTime < duration) {
                    advanceTransition(remaining);
                    recordObserverSegment(currentTime, candidateTime, diagnostics);
                    currentTime = candidateTime;
                    return;
                }

                double step = Math.max(0.0D, Math.min(remaining, untilEnd));
                if (step > 0.0D) {
                    advanceTransition(step);
                    recordObserverSegment(currentTime, duration, diagnostics);
                    currentTime = duration;
                    remaining -= step;
                }
                if (current.playbackMode() != AnimationV2PlaybackMode.ONCE || current.next() == null) {
                    if (remaining > 0.0D) {
                        advanceTransition(remaining);
                    }
                    return;
                }
                BlendAnimationKey next = current.next();
                if (transitions >= AnimationV2Limits.MAX_NEXT_TRANSITIONS_PER_ADVANCE) {
                    diagnostics.add(AnimationV2DiagnosticCode.NEXT_TRANSITION_BUDGET,
                            definition.id(), null, -1, "automatic next transition budget was exhausted");
                    return;
                }
                if (!addNextVisited(next)) {
                    diagnostics.add(AnimationV2DiagnosticCode.NEXT_CYCLE,
                            definition.id(), null, -1, nextCycleDetail(next));
                    return;
                }
                transitions++;
                advanceObserverOccurrence();
                transitionTo(definition.state(next), 0.0D, playbackSpeed);
            }
        }

        /**
         * Records an exact LOOP traversal without making observer work proportional to an arbitrary local-time jump.
         * The controller itself still reaches the same normalized terminal playhead; an observer trace that cannot hold
         * every occurrence is explicitly discarded and marked truncated rather than publishing a partial history.
         */
        private void advanceLoop(double realDelta, AnimationV2Diagnostics diagnostics) {
            double duration = current.durationSeconds();
            if (duration <= 0.0D) {
                advanceTransition(realDelta);
                currentTime = 0.0D;
                return;
            }
            double localDelta = scaled(realDelta, current, playbackSpeed);
            double unnormalizedTerminal = currentTime + localDelta;
            if (!Double.isFinite(unnormalizedTerminal)) {
                throw new IllegalStateException("v2 controller local time overflow");
            }
            double terminal = normalize(current, unnormalizedTerminal);
            long wraps = observerLoopWrapCount(unnormalizedTerminal, duration);
            requireObserverCounterAdvance(observerLoopEpoch, observerOccurrence, wraps);
            advanceTransition(realDelta);
            if (wraps == 0L) {
                recordObserverSegment(currentTime, terminal, diagnostics);
                currentTime = terminal;
                return;
            }

            int availableSegments = remainingObserverSegmentCapacity();
            if (wraps > availableSegments || (terminal > 0.0D && wraps == availableSegments)) {
                advanceObserverCounters(wraps);
                markObserverTraversalTruncated(diagnostics);
                currentTime = terminal;
                return;
            }

            recordObserverSegment(currentTime, duration, diagnostics);
            for (long wrap = 0L; wrap < wraps; wrap++) {
                advanceObserverCounters(1L);
                if (wrap + 1L < wraps) {
                    recordObserverSegment(0.0D, duration, diagnostics);
                }
            }
            if (terminal > 0.0D) {
                recordObserverSegment(0.0D, terminal, diagnostics);
            }
            currentTime = terminal;
        }

        /**
         * Floors the quotient of two exact non-negative binary64 values without first rounding the quotient back to
         * binary64. The common equal-exponent and numerator-smaller cases are direct primitive divisions. A positive
         * exponent delta with a quotient that can still fit in a {@code long} uses at most 116 fixed binary long-
         * division steps; a quotient whose bit width proves it exceeds {@link Long#MAX_VALUE} fails immediately.
         */
        private static long observerLoopWrapCount(double unnormalizedTerminal, double duration) {
            if (!Double.isFinite(unnormalizedTerminal) || unnormalizedTerminal < 0.0D
                    || !Double.isFinite(duration) || duration <= 0.0D) {
                throw loopCountOverflow();
            }
            long numeratorSignificand = binarySignificand(unnormalizedTerminal);
            if (numeratorSignificand == 0L) {
                return 0L;
            }
            long denominatorSignificand = binarySignificand(duration);
            int exponentShift = binaryExponent(unnormalizedTerminal) - binaryExponent(duration);
            if (exponentShift == 0) {
                return numeratorSignificand / denominatorSignificand;
            }
            if (exponentShift < 0) {
                int denominatorShift = -exponentShift;
                int numeratorBits = binaryBitLength(numeratorSignificand);
                if (binaryBitLength(denominatorSignificand) + denominatorShift > numeratorBits) {
                    return 0L;
                }
                return numeratorSignificand / (denominatorSignificand << denominatorShift);
            }

            int numeratorBits = binaryBitLength(numeratorSignificand);
            int denominatorBits = binaryBitLength(denominatorSignificand);
            int shiftedNumeratorBits = numeratorBits + exponentShift;
            if (shiftedNumeratorBits <= denominatorBits) {
                return 0L;
            }
            if (shiftedNumeratorBits - denominatorBits >= Long.SIZE) {
                throw loopCountOverflow();
            }
            return exactShiftedFloorDivide(numeratorSignificand, exponentShift, denominatorSignificand);
        }

        private static long exactShiftedFloorDivide(
                long numeratorSignificand,
                int exponentShift,
                long denominatorSignificand) {
            long quotient = 0L;
            long remainder = 0L;
            int dividendBits = binaryBitLength(numeratorSignificand) + exponentShift;
            for (int bitIndex = dividendBits - 1; bitIndex >= 0; bitIndex--) {
                long dividendBit = bitIndex >= exponentShift
                        ? (numeratorSignificand >>> (bitIndex - exponentShift)) & 1L
                        : 0L;
                remainder = (remainder << 1) | dividendBit;
                boolean quotientBit = remainder >= denominatorSignificand;
                if (quotientBit) {
                    remainder -= denominatorSignificand;
                }
                if (quotient > (Long.MAX_VALUE >>> 1)) {
                    throw loopCountOverflow();
                }
                quotient = (quotient << 1) | (quotientBit ? 1L : 0L);
            }
            return quotient;
        }

        private static long binarySignificand(double value) {
            long bits = Double.doubleToRawLongBits(value);
            long fraction = bits & ((1L << 52) - 1L);
            return ((bits >>> 52) & 0x7ffL) == 0L ? fraction : (1L << 52) | fraction;
        }

        private static int binaryExponent(double value) {
            long bits = Double.doubleToRawLongBits(value);
            int rawExponent = (int) ((bits >>> 52) & 0x7ffL);
            return rawExponent == 0 ? -1074 : rawExponent - 1023 - 52;
        }

        private static int binaryBitLength(long value) {
            return Long.SIZE - Long.numberOfLeadingZeros(value);
        }

        private static void requireObserverCounterAdvance(long loopEpoch, long occurrence, long wraps) {
            try {
                Math.addExact(loopEpoch, wraps);
                Math.addExact(occurrence, wraps);
            } catch (ArithmeticException exception) {
                throw loopCountOverflow();
            }
        }

        private static long nextObserverOccurrence(long occurrence) {
            try {
                return Math.incrementExact(occurrence);
            } catch (ArithmeticException exception) {
                throw loopCountOverflow();
            }
        }

        private static IllegalStateException loopCountOverflow() {
            return new IllegalStateException("v2 controller loop count overflow");
        }

        private void recordObserverSegment(double startExclusive, double endInclusive, AnimationV2Diagnostics diagnostics) {
            if (observerTraversalTruncated || observerDiscontinuity || endInclusive <= startExclusive) {
                return;
            }
            if (observerSegments.size() >= AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE) {
                markObserverTraversalTruncated(diagnostics);
                return;
            }
            observerSegments.add(new AnimationV2ObserverTraversal.Segment(
                    current.key(), observerLoopEpoch, observerOccurrence, startExclusive, endInclusive));
        }

        private int remainingObserverSegmentCapacity() {
            return AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE - observerSegments.size();
        }

        private void markObserverTraversalTruncated(AnimationV2Diagnostics diagnostics) {
            if (observerTraversalTruncated) {
                return;
            }
            observerSegments.clear();
            observerTraversalTruncated = true;
            diagnostics.add(AnimationV2DiagnosticCode.OBSERVER_TRAVERSAL_TRUNCATED,
                    definition.id(), null, -1, "bounded observer traversal omitted a partial automatic path");
        }

        private void markObserverDiscontinuity() {
            long nextOccurrence = nextObserverOccurrence(observerOccurrence);
            observerSegments.clear();
            observerTraversalTruncated = false;
            observerDiscontinuity = true;
            observerOccurrence = nextOccurrence;
        }

        private void advanceObserverOccurrence() {
            observerOccurrence = nextObserverOccurrence(observerOccurrence);
        }

        private void advanceObserverCounters(long wraps) {
            requireObserverCounterAdvance(observerLoopEpoch, observerOccurrence, wraps);
            observerLoopEpoch += wraps;
            observerOccurrence += wraps;
        }

        private void sampleInto(int layerIndex, int boneIndex, AnimationV2TransformScratch target) {
            currentClips[layerIndex].sampleIntoUnchecked(currentTime, boneIndex, target);
            if (!hasTransitionSource()) {
                return;
            }
            if (frozenPreviousPoses != null) {
                transitionSourceScratch.set(frozenPreviousPoses[layerIndex].transform(boneIndex));
            } else {
                previousClips[layerIndex].sampleIntoUnchecked(previousTime, boneIndex, transitionSourceScratch);
            }
            target.setInterpolated(transitionSourceScratch, target, smoothstep(transitionProgress()));
        }

        private AnimationV2ControllerPlayhead playhead() {
            return new AnimationV2ControllerPlayhead(definition.id(), current.key(), currentTime,
                    hasTransitionSource() ? transitionSourceState : null, transitionProgress(),
                    acceptedCommand == null ? -1L : acceptedCommand.sequence());
        }

        private void transitionTo(AnimationV2ControllerState target, double targetTime, double targetPlaybackSpeed) {
            if (hasTransitionSource()) {
                frozenPreviousPoses = freezeCurrentPresentation();
                previous = null;
                previousClips = null;
                transitionSourceState = current.key();
            } else {
                previous = current;
                previousClips = currentClips;
                previousTime = currentTime;
                previousPlaybackSpeed = playbackSpeed;
                frozenPreviousPoses = null;
                transitionSourceState = previous.key();
            }
            current = Objects.requireNonNull(target, "target");
            currentClips = definition.clipsFor(current);
            currentTime = normalize(current, targetTime);
            playbackSpeed = targetPlaybackSpeed;
            transitionElapsed = 0.0D;
            transitionDuration = current.transitionSeconds();
            if (transitionDuration <= AnimationV2Limits.EPSILON) {
                clearTransition();
            }
        }

        private AnimationV2Pose[] freezeCurrentPresentation() {
            AnimationV2Pose[] frozen = new AnimationV2Pose[definition.layerCount()];
            for (int layerIndex = 0; layerIndex < frozen.length; layerIndex++) {
                Transform[] transforms = new Transform[boneCount];
                for (int boneIndex = 0; boneIndex < boneCount; boneIndex++) {
                    sampleInto(layerIndex, boneIndex, frozenBuildScratch);
                    transforms[boneIndex] = frozenBuildScratch.toTransform();
                }
                frozen[layerIndex] = AnimationV2Pose.takeOwnership(transforms);
            }
            return frozen;
        }

        private void advanceTransition(double realDelta) {
            if (!hasTransitionSource()) {
                return;
            }
            double usable = Math.min(realDelta, Math.max(0.0D, transitionDuration - transitionElapsed));
            if (previous != null) {
                previousTime = normalize(previous, previousTime + scaled(usable, previous, previousPlaybackSpeed));
            }
            transitionElapsed = Math.min(transitionDuration, transitionElapsed + realDelta);
            if (transitionElapsed >= transitionDuration - AnimationV2Limits.EPSILON) {
                clearTransition();
            }
        }

        private boolean hasTransitionSource() {
            return previous != null || frozenPreviousPoses != null;
        }

        private void clearTransition() {
            previous = null;
            previousClips = null;
            frozenPreviousPoses = null;
            transitionSourceState = null;
            previousTime = 0.0D;
            transitionElapsed = 0.0D;
            transitionDuration = 0.0D;
        }

        private double transitionProgress() {
            if (!hasTransitionSource() || transitionDuration <= AnimationV2Limits.EPSILON) {
                return 1.0D;
            }
            return Math.min(1.0D, transitionElapsed / transitionDuration);
        }

        private static double smoothstep(double amount) {
            return amount * amount * (3.0D - 2.0D * amount);
        }

        private void resetNextVisited() {
            nextVisitedCount = 0;
        }

        private boolean addNextVisited(BlendAnimationKey key) {
            for (int index = 0; index < nextVisitedCount; index++) {
                if (nextVisited[index].equals(key)) {
                    return false;
                }
            }
            if (nextVisitedCount >= nextVisited.length) {
                return false;
            }
            nextVisited[nextVisitedCount++] = key;
            return true;
        }

        private static double scaled(double realSeconds, AnimationV2ControllerState state, double rate) {
            double result = realSeconds * state.speed() * rate;
            if (!Double.isFinite(result)) {
                throw new IllegalStateException("v2 controller local time overflow");
            }
            return result;
        }

        private static double normalize(AnimationV2ControllerState state, double time) {
            double duration = state.durationSeconds();
            if (duration <= 0.0D) {
                return 0.0D;
            }
            if (state.playbackMode() != AnimationV2PlaybackMode.LOOP) {
                return Math.min(duration, Math.max(0.0D, time));
            }
            double normalized = time % duration;
            if (normalized == 0.0D) {
                return 0.0D;
            }
            return normalized;
        }
    }

}
