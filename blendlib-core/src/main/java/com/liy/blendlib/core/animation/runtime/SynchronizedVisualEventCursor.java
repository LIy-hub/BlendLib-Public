package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import java.util.List;
import java.util.Objects;

/**
 * Per-instance presentation-only marker cursor for an accepted absolute synchronized timeline.
 * This is not a gameplay event scheduler and carries no server authority.
 *
 * <p>Each strictly newer semantic sequence silently establishes a new baseline. Late tracking,
 * corrections and restarts therefore never replay markers preceding that baseline. Within one
 * sequence a monotonic high-water mark prevents repeated extraction and clock rewinds from
 * re-emitting a marker. Markers at the left endpoint are excluded; the right endpoint is included.
 * Older skipped history is dropped; only the most recent controller-time second can catch up.</p>
 *
 * <p>Own this alongside the instance clock and discard it on model/generation/lifecycle changes.
 * It is mutable and must be called from the same extraction thread as its owning instance.</p>
 */
public final class SynchronizedVisualEventCursor {
    public static final double MAX_CATCH_UP_SECONDS = 1.0d;

    private final AnimationController eventController;
    private BlendAnimationKey origin;
    private long sequence = -1L;
    private double highWaterSeconds;
    private boolean active;

    public SynchronizedVisualEventCursor(
            BlendInstanceKey instanceKey, AnimationControllerDefinition definition) {
        eventController = new AnimationController(instanceKey, definition);
    }

    /** Accepts only a strictly newer semantic sequence, without emitting historical markers. */
    public boolean accept(long acceptedSequence, BlendAnimationKey originKey, double controllerTimeSeconds) {
        validate(acceptedSequence, controllerTimeSeconds);
        Objects.requireNonNull(originKey, "originKey");
        if (acceptedSequence <= sequence) {
            return false;
        }
        eventController.synchronizeTimeline(originKey, controllerTimeSeconds);
        origin = originKey;
        sequence = acceptedSequence;
        highWaterSeconds = controllerTimeSeconds;
        active = true;
        return true;
    }

    /**
     * Returns markers crossed since the last successful sample of this accepted sequence.
     * Over-budget catch-up is discarded atomically and cannot be retried by repeated extraction.
     */
    public List<AnimationVisualEvent> advance(long acceptedSequence, double controllerTimeSeconds) {
        validate(acceptedSequence, controllerTimeSeconds);
        if (!active || acceptedSequence != sequence || controllerTimeSeconds <= highWaterSeconds) {
            return List.of();
        }
        double start = Math.max(highWaterSeconds, controllerTimeSeconds - MAX_CATCH_UP_SECONDS);
        eventController.synchronizeTimeline(origin, start);
        List<AnimationVisualEvent> events = eventController.advanceVisualEventsWithinBudget(controllerTimeSeconds - start);
        highWaterSeconds = controllerTimeSeconds;
        return events;
    }

    /**
     * Resumes an inactive accepted sequence without replaying markers from its inactive interval.
     * The owner must first verify that the full semantic command equals the one it accepted.
     * A clock rewind never lowers the retained high-water mark, so previously emitted markers
     * remain consumed. Active, older, and unaccepted sequences cannot change the baseline.
     */
    public boolean resume(long acceptedSequence, double controllerTimeSeconds) {
        validate(acceptedSequence, controllerTimeSeconds);
        if (active || acceptedSequence != sequence) {
            return false;
        }
        highWaterSeconds = Math.max(highWaterSeconds, controllerTimeSeconds);
        active = true;
        return true;
    }

    /** Stops synchronized emission while retaining sequence rejection history and consumed markers. */
    public void deactivate() {
        active = false;
    }

    private static void validate(long sequence, double time) {
        if (sequence < 0L) {
            throw new IllegalArgumentException("Synchronized sequence must be non-negative");
        }
        if (!Double.isFinite(time) || time < 0.0d) {
            throw new IllegalArgumentException("Synchronized timeline time must be finite and non-negative");
        }
    }
}
