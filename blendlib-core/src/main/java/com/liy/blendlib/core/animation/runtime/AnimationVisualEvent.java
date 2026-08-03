package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * A time point emitted only for client presentation work such as sound,
 * particles, trails, or socket-attached effects.
 *
 * <p>This value carries no gameplay authority and must never be interpreted as
 * a damage, collision, item-consumption, drop, or hit-detection instruction.</p>
 */
public record AnimationVisualEvent(double timeSeconds, BlendResourceId eventKey) {
    public AnimationVisualEvent {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Visual event time must be finite and non-negative");
        }
        eventKey = Objects.requireNonNull(eventKey, "eventKey");
    }
}
