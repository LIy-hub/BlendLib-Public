package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable per-instance implementation of BlendLib v1's sole full-body animation controller.
 *
 * <p>The controller owns no asset loading or rendering state. A caller advances it
 * during extraction, samples an immutable pose, and later submits only that pose-derived snapshot.</p>
 */
public final class AnimationController {
    private static final double EPSILON = 1.0e-8;

    private final BlendInstanceKey instanceKey;
    private final AnimationControllerDefinition definition;
    private final List<AnimationVisualEvent> pendingVisualEvents = new ArrayList<>();

    private AnimationState current;
    private AnimationState previous;
    private double currentTimeSeconds;
    private double previousTimeSeconds;
    private double blendElapsedSeconds;
    private double blendDurationSeconds;
    private long lastSequence = -1L;

    public AnimationController(BlendInstanceKey instanceKey, AnimationControllerDefinition definition) {
        this.instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.current = definition.initialStateDefinition();
        enqueueEntryEvents(current, pendingVisualEvents);
    }

    public BlendInstanceKey instanceKey() {
        return instanceKey;
    }

    public BlendAnimationKey currentState() {
        return current.key();
    }

    public BlendAnimationKey previousState() {
        return previous == null ? null : previous.key();
    }

    public double currentTimeSeconds() {
        return currentTimeSeconds;
    }

    public double previousTimeSeconds() {
        return previousTimeSeconds;
    }

    public long lastSequence() {
        return lastSequence;
    }

    /** Explicitly restarts a declared state and applies its configured entry cross-fade. */
    public void trigger(BlendAnimationKey stateKey) {
        AnimationState target = definition.state(stateKey);
        requireVisualEventCapacity(pendingVisualEvents.size(), entryEventCount(target));
        transitionTo(target, 0.0, true, null);
    }

    /**
     * Applies a sequenced semantic correction. Stale corrections are ignored;
     * only a serious same-state time drift exceeds the caller-specified snap threshold.
     */
    public AnimationCorrectionResult applyCorrection(AnimationCorrection correction) {
        Objects.requireNonNull(correction, "correction");
        if (correction.sequence() <= lastSequence) {
            return AnimationCorrectionResult.STALE_DROPPED;
        }
        AnimationState target = definition.state(correction.animationKey());
        double targetTime = normalizeTime(target, correction.timeSeconds());
        return applyResolvedCorrection(correction, target, targetTime);
    }

    /**
     * Applies a correction whose time is elapsed controller-timeline time from the declared origin state.
     *
     * <p>Unlike {@link #applyCorrection(AnimationCorrection)}, this method resolves non-loop {@code next}
     * transitions and each visited state's descriptor speed before correcting the current local clip time.
     * Resolution is event-free: a late tracking replay never re-emits historical presentation events. Positive-
     * duration deterministic {@code next} cycles are skipped with closed-form arithmetic, while structural work
     * remains under the same transition budget as a normal advance.</p>
     */
    public AnimationCorrectionResult applyTimelineCorrection(AnimationCorrection correction) {
        Objects.requireNonNull(correction, "correction");
        if (correction.sequence() <= lastSequence) {
            return AnimationCorrectionResult.STALE_DROPPED;
        }
        TimelinePosition position = resolveTimeline(correction.animationKey(), correction.timeSeconds());
        pendingVisualEvents.clear();
        return applyResolvedCorrection(correction, position.state(), position.localTimeSeconds());
    }

    private AnimationCorrectionResult applyResolvedCorrection(
            AnimationCorrection correction, AnimationState target, double targetTime) {
        lastSequence = correction.sequence();

        if (!target.key().equals(current.key())) {
            transitionTo(target, targetTime, false, null);
            return AnimationCorrectionResult.APPLIED_BLEND;
        }

        double drift = timeDistance(current, currentTimeSeconds, targetTime);
        if (drift > correction.snapThresholdSeconds()) {
            currentTimeSeconds = targetTime;
            previous = null;
            previousTimeSeconds = 0.0;
            blendElapsedSeconds = 0.0;
            blendDurationSeconds = 0.0;
            return AnimationCorrectionResult.APPLIED_SNAP;
        }
        if (drift > EPSILON) {
            transitionTo(target, targetTime, false, null);
        }
        return AnimationCorrectionResult.APPLIED_BLEND;
    }

    private TimelinePosition resolveTimeline(BlendAnimationKey originKey, double controllerTimeSeconds) {
        AnimationState state = definition.state(originKey);
        if (controllerTimeSeconds <= EPSILON) {
            return new TimelinePosition(state, 0.0);
        }

        double remaining = controllerTimeSeconds;
        double pathTimeSeconds = 0.0;
        int transitions = 0;
        Map<BlendAnimationKey, Double> visitsAtStateStart = new HashMap<>();
        visitsAtStateStart.put(state.key(), 0.0);
        while (remaining > EPSILON) {
            if (state.loop()) {
                double duration = state.clip().durationSeconds();
                if (duration <= EPSILON) {
                    return new TimelinePosition(state, 0.0);
                }
                return new TimelinePosition(
                        state, normalizeTime(state, checkedLocalAdvance(remaining, state.speed())));
            }

            double duration = state.clip().durationSeconds();
            if (duration > EPSILON) {
                double controllerUntilEnd = duration / state.speed();
                double step = Math.min(remaining, controllerUntilEnd);
                double localTime = Math.min(duration, checkedLocalAdvance(step, state.speed()));
                boolean reachesEnd = localTime >= duration - EPSILON
                        && controllerUntilEnd <= step + EPSILON;
                if (!reachesEnd) {
                    return new TimelinePosition(state, localTime);
                }
                if (state.next() == null) {
                    return new TimelinePosition(state, duration);
                }
                remaining = Math.max(0.0, remaining - step);
                pathTimeSeconds = checkedLocalEnd(pathTimeSeconds, step);
            } else if (state.next() == null) {
                return new TimelinePosition(state, 0.0);
            }

            if (transitions >= BlendAssetLimits.MAX_STATE_TRANSITIONS_PER_ADVANCE) {
                throw advanceLimit("timeline state-transition budget of "
                        + BlendAssetLimits.MAX_STATE_TRANSITIONS_PER_ADVANCE + " exceeded");
            }
            transitions++;
            state = definition.state(state.next());
            if (remaining <= EPSILON) {
                return new TimelinePosition(state, 0.0);
            }

            Double priorPathTime = visitsAtStateStart.putIfAbsent(state.key(), pathTimeSeconds);
            if (priorPathTime != null) {
                double cycleSeconds = pathTimeSeconds - priorPathTime;
                if (!Double.isFinite(cycleSeconds) || cycleSeconds <= EPSILON) {
                    throw advanceLimit("timeline next cycle has no positive duration");
                }
                double reduced = remaining % cycleSeconds;
                if (reduced <= EPSILON) {
                    return new TimelinePosition(state, 0.0);
                }
                remaining = reduced;
                pathTimeSeconds = 0.0;
                visitsAtStateStart.clear();
                visitsAtStateStart.put(state.key(), 0.0);
            }
        }
        return new TimelinePosition(state, 0.0);
    }

    /** Advances the controller and returns only presentation-only events crossed by this interval. */
    public AnimationAdvance advance(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            throw new IllegalArgumentException("Advance delta must be finite and non-negative");
        }
        preflightAdvance(deltaSeconds);
        List<AnimationVisualEvent> events = new ArrayList<>(pendingVisualEvents);
        pendingVisualEvents.clear();

        double remaining = deltaSeconds;
        int zeroDurationTransitions = 0;
        while (remaining > EPSILON) {
            if (current.loop()) {
                advanceLoop(current, remaining, events);
                advancePrevious(remaining);
                advanceBlend(remaining);
                remaining = 0.0;
                break;
            }

            double duration = current.clip().durationSeconds();
            if (duration <= EPSILON) {
                if (current.next() == null) {
                    advancePrevious(remaining);
                    advanceBlend(remaining);
                    remaining = 0.0;
                    break;
                }
                if (++zeroDurationTransitions > definition.states().size()) {
                    throw new IllegalStateException("Non-loop animation next chain cannot cycle through zero-duration clips");
                }
                transitionTo(definition.state(current.next()), 0.0, true, events);
                continue;
            }

            double localRemaining = Math.max(0.0, duration - currentTimeSeconds);
            if (localRemaining <= EPSILON) {
                currentTimeSeconds = duration;
                if (current.next() == null) {
                    advancePrevious(remaining);
                    advanceBlend(remaining);
                    remaining = 0.0;
                    break;
                }
                transitionTo(definition.state(current.next()), 0.0, true, events);
                continue;
            }

            double realUntilEnd = localRemaining / current.speed();
            double step = Math.min(remaining, realUntilEnd);
            double before = currentTimeSeconds;
            currentTimeSeconds = Math.min(duration, before + step * current.speed());
            emitNonLoopEvents(current, before, currentTimeSeconds, events);
            advancePrevious(step);
            advanceBlend(step);
            remaining -= step;

            if (currentTimeSeconds >= duration - EPSILON && realUntilEnd <= step + EPSILON) {
                currentTimeSeconds = duration;
                if (current.next() != null) {
                    transitionTo(definition.state(current.next()), 0.0, true, events);
                }
            }
        }
        return new AnimationAdvance(current.key(), currentTimeSeconds, events);
    }

    /**
     * Proves that an advance fits every fixed runtime budget before mutating controller state or
     * allocating the returned event list. Loop crossings and event occurrences are counted with
     * closed-form arithmetic, so a hostile delta cannot turn this validation into a per-cycle walk.
     */
    private void preflightAdvance(double deltaSeconds) {
        AdvanceBudget budget = new AdvanceBudget(pendingVisualEvents.size());
        checkedLocalAdvance(deltaSeconds, BlendAssetLimits.MAX_ANIMATION_SPEED);
        if (previous != null) {
            double previousAdvance = Math.min(deltaSeconds,
                    Math.max(0.0, blendDurationSeconds - blendElapsedSeconds));
            checkedLocalEnd(previousTimeSeconds, checkedLocalAdvance(previousAdvance, previous.speed()));
        }
        AnimationState simulatedState = current;
        double simulatedTime = currentTimeSeconds;
        double remaining = deltaSeconds;

        while (remaining > EPSILON) {
            if (simulatedState.loop()) {
                double duration = simulatedState.clip().durationSeconds();
                if (duration <= EPSILON) {
                    return;
                }
                double localAdvance = checkedLocalAdvance(remaining, simulatedState.speed());
                double end = checkedLocalEnd(simulatedTime, localAdvance);
                budget.addLoopCycles(Math.floor(end / duration));
                for (AnimationVisualEvent event : simulatedState.events()) {
                    budget.addVisualEvents(loopEventOccurrenceCount(event, simulatedTime, end, duration));
                }
                return;
            }

            double duration = simulatedState.clip().durationSeconds();
            if (duration <= EPSILON) {
                if (simulatedState.next() == null) {
                    return;
                }
                simulatedState = preflightTransition(simulatedState.next(), budget);
                simulatedTime = 0.0;
                continue;
            }

            double localRemaining = Math.max(0.0, duration - simulatedTime);
            if (localRemaining <= EPSILON) {
                if (simulatedState.next() == null) {
                    return;
                }
                simulatedState = preflightTransition(simulatedState.next(), budget);
                simulatedTime = 0.0;
                continue;
            }

            double realUntilEnd = localRemaining / simulatedState.speed();
            double step = Math.min(remaining, realUntilEnd);
            double before = simulatedTime;
            simulatedTime = Math.min(duration, checkedLocalEnd(before,
                    checkedLocalAdvance(step, simulatedState.speed())));
            budget.addVisualEvents(nonLoopEventCount(simulatedState, before, simulatedTime));
            remaining -= step;

            if (simulatedTime >= duration - EPSILON && realUntilEnd <= step + EPSILON) {
                simulatedTime = duration;
                if (simulatedState.next() != null) {
                    simulatedState = preflightTransition(simulatedState.next(), budget);
                    simulatedTime = 0.0;
                }
            }
        }
    }

    private AnimationState preflightTransition(BlendAnimationKey targetKey, AdvanceBudget budget) {
        budget.addTransition();
        AnimationState target = definition.state(targetKey);
        budget.addVisualEvents(entryEventCount(target));
        return target;
    }

    /** Samples the controller's current immutable local pose, including any configured smoothstep blend. */
    public LocalPose sample(PoseSampler sampler) {
        Objects.requireNonNull(sampler, "sampler");
        LocalPose currentPose = sampler.sample(current, currentTimeSeconds);
        if (previous == null) {
            return currentPose;
        }
        LocalPose previousPose = sampler.sample(previous, previousTimeSeconds);
        return sampler.blend(previousPose, currentPose, smoothstep(blendProgress()));
    }

    /** v1 cross-fade easing function. */
    public static double smoothstep(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("Smoothstep amount must be finite and in [0, 1]");
        }
        return amount * amount * (3.0 - 2.0 * amount);
    }

    private void transitionTo(
            AnimationState target, double targetTime, boolean emitEntryEvents, List<AnimationVisualEvent> immediateEvents) {
        AnimationState source = current;
        double sourceTime = currentTimeSeconds;
        current = Objects.requireNonNull(target, "target");
        currentTimeSeconds = normalizeTime(current, targetTime);
        blendDurationSeconds = current.blendSeconds();
        blendElapsedSeconds = 0.0;
        if (blendDurationSeconds <= EPSILON) {
            previous = null;
            previousTimeSeconds = 0.0;
        } else {
            previous = source;
            previousTimeSeconds = sourceTime;
        }
        if (emitEntryEvents) {
            List<AnimationVisualEvent> destination = immediateEvents == null ? pendingVisualEvents : immediateEvents;
            enqueueEntryEvents(current, destination);
        }
    }

    private void advanceLoop(AnimationState state, double realSeconds, List<AnimationVisualEvent> events) {
        double duration = state.clip().durationSeconds();
        if (duration <= EPSILON) {
            currentTimeSeconds = 0.0;
            return;
        }
        double localAdvance = checkedLocalAdvance(realSeconds, state.speed());
        double start = currentTimeSeconds;
        double end = checkedLocalEnd(start, localAdvance);
        int occurrenceTotal = 0;
        for (AnimationVisualEvent event : state.events()) {
            occurrenceTotal += Math.toIntExact(loopEventOccurrenceCount(event, start, end, duration));
        }
        List<ScheduledEvent> scheduled = new ArrayList<>(occurrenceTotal);
        for (AnimationVisualEvent event : state.events()) {
            if (event.timeSeconds() > duration + EPSILON) {
                continue;
            }
            double occurrence = firstOccurrenceAfter(event.timeSeconds(), start, duration);
            long occurrenceCount = loopEventOccurrenceCount(event, start, end, duration);
            for (long index = 0; index < occurrenceCount; index++) {
                scheduled.add(new ScheduledEvent(occurrence + index * duration, event));
            }
        }
        scheduled.sort(Comparator.comparingDouble(ScheduledEvent::timeSeconds));
        for (ScheduledEvent event : scheduled) {
            events.add(event.event());
        }
        currentTimeSeconds = end % duration;
        if (currentTimeSeconds < EPSILON || duration - currentTimeSeconds < EPSILON) {
            currentTimeSeconds = 0.0;
        }
    }

    private static double firstOccurrenceAfter(double eventTime, double start, double duration) {
        double cycles = Math.floor((start - eventTime) / duration) + 1.0;
        return eventTime + Math.max(0.0, cycles) * duration;
    }

    private static long loopEventOccurrenceCount(
            AnimationVisualEvent event, double start, double end, double duration) {
        if (event.timeSeconds() > duration + EPSILON) {
            return 0L;
        }
        double first = firstOccurrenceAfter(event.timeSeconds(), start, duration);
        if (first > end + EPSILON) {
            return 0L;
        }
        return (long) Math.floor((end + EPSILON - first) / duration) + 1L;
    }

    private static void emitNonLoopEvents(
            AnimationState state, double before, double after, List<AnimationVisualEvent> destination) {
        double duration = state.clip().durationSeconds();
        for (AnimationVisualEvent event : state.events()) {
            if (event.timeSeconds() > duration + EPSILON) {
                continue;
            }
            if (event.timeSeconds() > before + EPSILON && event.timeSeconds() <= after + EPSILON) {
                destination.add(event);
            }
        }
    }

    private static int nonLoopEventCount(AnimationState state, double before, double after) {
        int count = 0;
        double duration = state.clip().durationSeconds();
        for (AnimationVisualEvent event : state.events()) {
            if (event.timeSeconds() <= duration + EPSILON
                    && event.timeSeconds() > before + EPSILON
                    && event.timeSeconds() <= after + EPSILON) {
                count++;
            }
        }
        return count;
    }

    private void advancePrevious(double realSeconds) {
        if (previous != null) {
            double remainingBlendSeconds = Math.max(0.0, blendDurationSeconds - blendElapsedSeconds);
            previousTimeSeconds = advanceStateTime(
                    previous, previousTimeSeconds, Math.min(realSeconds, remainingBlendSeconds));
        }
    }

    private void advanceBlend(double realSeconds) {
        if (previous == null) {
            return;
        }
        blendElapsedSeconds = Math.min(blendDurationSeconds, blendElapsedSeconds + realSeconds);
        if (blendElapsedSeconds >= blendDurationSeconds - EPSILON) {
            previous = null;
            previousTimeSeconds = 0.0;
        }
    }

    private double blendProgress() {
        if (previous == null || blendDurationSeconds <= EPSILON) {
            return 1.0;
        }
        return Math.min(1.0, blendElapsedSeconds / blendDurationSeconds);
    }

    private static void enqueueEntryEvents(AnimationState state, List<AnimationVisualEvent> destination) {
        for (AnimationVisualEvent event : state.events()) {
            if (event.timeSeconds() <= EPSILON) {
                destination.add(event);
            }
        }
    }

    private static int entryEventCount(AnimationState state) {
        int count = 0;
        for (AnimationVisualEvent event : state.events()) {
            if (event.timeSeconds() <= EPSILON) {
                count++;
            }
        }
        return count;
    }

    private static double checkedLocalAdvance(double realSeconds, double speed) {
        double result = realSeconds * speed;
        if (!Double.isFinite(result)) {
            throw advanceLimit("scaled local time is not finite");
        }
        return result;
    }

    private static double checkedLocalEnd(double start, double localAdvance) {
        double result = start + localAdvance;
        if (!Double.isFinite(result)) {
            throw advanceLimit("local time endpoint is not finite");
        }
        return result;
    }

    private static void requireVisualEventCapacity(long existing, long additional) {
        if (additional < 0L || existing > BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE - additional) {
            throw advanceLimit("visual-event budget of "
                    + BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE + " exceeded");
        }
    }

    private static IllegalStateException advanceLimit(String detail) {
        return new IllegalStateException("Animation advance limit exceeded: " + detail);
    }

    private static double advanceStateTime(AnimationState state, double currentTime, double realSeconds) {
        return normalizeTime(state, checkedLocalEnd(currentTime, checkedLocalAdvance(realSeconds, state.speed())));
    }

    private static double normalizeTime(AnimationState state, double timeSeconds) {
        double duration = state.clip().durationSeconds();
        if (duration <= EPSILON) {
            return 0.0;
        }
        if (!state.loop()) {
            return Math.min(duration, Math.max(0.0, timeSeconds));
        }
        double normalized = timeSeconds % duration;
        if (normalized < EPSILON || duration - normalized < EPSILON) {
            return 0.0;
        }
        return normalized;
    }

    private static double timeDistance(AnimationState state, double left, double right) {
        double difference = Math.abs(left - right);
        double duration = state.clip().durationSeconds();
        if (!state.loop() || duration <= EPSILON) {
            return difference;
        }
        return Math.min(difference, duration - difference);
    }

    private static final class AdvanceBudget {
        private int loopCycles;
        private int transitions;
        private int visualEvents;

        private AdvanceBudget(int pendingVisualEvents) {
            addVisualEvents(pendingVisualEvents);
        }

        private void addLoopCycles(double crossedCycles) {
            if (!Double.isFinite(crossedCycles) || crossedCycles < 0.0
                    || crossedCycles > BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE - loopCycles) {
                throw advanceLimit("loop-cycle budget of "
                        + BlendAssetLimits.MAX_LOOP_CYCLES_PER_ADVANCE + " exceeded");
            }
            loopCycles += (int) crossedCycles;
        }

        private void addTransition() {
            if (transitions >= BlendAssetLimits.MAX_STATE_TRANSITIONS_PER_ADVANCE) {
                throw advanceLimit("state-transition budget of "
                        + BlendAssetLimits.MAX_STATE_TRANSITIONS_PER_ADVANCE + " exceeded");
            }
            transitions++;
        }

        private void addVisualEvents(long additional) {
            requireVisualEventCapacity(visualEvents, additional);
            visualEvents += (int) additional;
        }
    }

    private record ScheduledEvent(double timeSeconds, AnimationVisualEvent event) {
    }

    private record TimelinePosition(AnimationState state, double localTimeSeconds) {
        private TimelinePosition {
            state = Objects.requireNonNull(state, "state");
            if (!Double.isFinite(localTimeSeconds) || localTimeSeconds < 0.0) {
                throw new IllegalArgumentException("Timeline local time must be finite and non-negative");
            }
        }
    }
}
