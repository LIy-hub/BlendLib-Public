package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Immutable visual-only animation event declaration for a strict descriptor.
 *
 * <p><strong>Stable boundary:</strong> events describe visual scheduling only. Generated data does
 * not grant gameplay authority, network behavior, or runtime side effects to an event.</p>
 *
 * @param timeSeconds non-negative finite time in the referenced clip
 * @param eventId canonical visual event identity
 */
public record DatagenAnimationEvent(double timeSeconds, BlendResourceId eventId) {
    /**
     * Validates an immutable visual event declaration.
     */
    public DatagenAnimationEvent {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        eventId = Objects.requireNonNull(eventId, "eventId");
    }
}
