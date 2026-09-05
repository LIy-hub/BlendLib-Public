package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable strict descriptor animation-state declaration.
 *
 * <p><strong>Stable boundary:</strong> this is serialized authoring data only. The runtime remains
 * responsible for immutable animation snapshots and does not execute arbitrary generated behavior.</p>
 *
 * @param stateId canonical state identity
 * @param clip non-blank GLB animation clip name
 * @param loop whether playback loops
 * @param speed positive finite playback multiplier
 * @param blendSeconds finite non-negative transition blend time
 * @param next optional canonical successor state identity
 * @param events immutable visual-only event list
 */
public record DatagenAnimationState(
        BlendResourceId stateId,
        String clip,
        boolean loop,
        double speed,
        double blendSeconds,
        Optional<BlendResourceId> next,
        List<DatagenAnimationEvent> events) {
    /**
     * Validates a strict descriptor animation state.
     */
    public DatagenAnimationState {
        stateId = Objects.requireNonNull(stateId, "stateId");
        clip = Objects.requireNonNull(clip, "clip");
        if (clip.isBlank()) {
            throw new IllegalArgumentException("clip must not be blank");
        }
        if (!Double.isFinite(speed) || speed <= 0.0 || speed > 64.0) {
            throw new IllegalArgumentException("speed must be finite in (0,64]");
        }
        if (!Double.isFinite(blendSeconds) || blendSeconds < 0.0) {
            throw new IllegalArgumentException("blendSeconds must be finite and non-negative");
        }
        next = Objects.requireNonNull(next, "next");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("events must not contain null");
        }
        DatagenLimits.requireAtMost(
                "animation state events", events.size(), DatagenLimits.MAX_VISUAL_EVENTS_PER_STATE);
    }
}
