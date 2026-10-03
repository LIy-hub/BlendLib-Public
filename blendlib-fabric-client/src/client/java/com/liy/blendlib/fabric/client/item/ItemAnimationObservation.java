package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable point-in-time item status. No stack, controller, handle or resource is retained.
 * storedSeconds is the raw playhead at the last control/sample operation, not a clock projection.
 * It can exceed clip duration after seek. lastSample describes only the last successful extraction,
 * and can differ from current controls after play, pause, seek, stop or speed changes.
 * sampleCurrentGeneration says only whether that historical sample belongs to the current model
 * generation; it does not promise that controls or the pose are current, or that submit succeeded.
 */
public record ItemAnimationObservation(
        BlendAnimationKey animation, ItemAnimationPlayback.Mode mode, double speed, boolean playing,
        double storedSeconds, Optional<Sample> lastSample, boolean sampleCurrentGeneration) {
    public ItemAnimationObservation {
        Objects.requireNonNull(animation, "animation");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(lastSample, "lastSample");
        requireTime(speed, "speed");
        requireTime(storedSeconds, "storedSeconds");
        if (sampleCurrentGeneration && lastSample.isEmpty()) {
            throw new IllegalArgumentException("A current sample must exist");
        }
    }

    /** Raw clip seconds handed to successful extraction, not wall time or a rendered-frame promise. */
    public record Sample(BlendModelKey model, BlendAnimationKey animation, long generation,
            double seconds, double durationSeconds) {
        public Sample {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(animation, "animation");
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
            requireTime(seconds, "seconds");
            requireTime(durationSeconds, "durationSeconds");
        }
    }

    private static void requireTime(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and non-negative");
    }
}
