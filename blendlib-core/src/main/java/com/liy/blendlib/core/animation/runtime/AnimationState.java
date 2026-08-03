package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable v1 state configuration for the sole full-body controller. */
public final class AnimationState {
    private final BlendAnimationKey key;
    private final AnimationClip clip;
    private final CompiledAnimationClip compiledClip;
    private final boolean loop;
    private final double speed;
    private final double blendSeconds;
    private final BlendAnimationKey next;
    private final List<AnimationVisualEvent> events;

    public AnimationState(
            BlendAnimationKey key,
            AnimationClip clip,
            boolean loop,
            double speed,
            double blendSeconds,
            BlendAnimationKey next,
            List<AnimationVisualEvent> events) {
        this.key = Objects.requireNonNull(key, "key");
        this.clip = Objects.requireNonNull(clip, "clip");
        this.compiledClip = CompiledAnimationClip.compile(clip);
        if (!Double.isFinite(speed) || speed <= 0.0 || speed > BlendAssetLimits.MAX_ANIMATION_SPEED) {
            throw new IllegalArgumentException("Animation speed must be finite, positive, and at most "
                    + BlendAssetLimits.MAX_ANIMATION_SPEED);
        }
        if (!Double.isFinite(blendSeconds) || blendSeconds < 0.0) {
            throw new IllegalArgumentException("Animation blendSeconds must be finite and non-negative");
        }
        this.loop = loop;
        this.speed = speed;
        this.blendSeconds = blendSeconds;
        this.next = next;
        List<AnimationVisualEvent> orderedEvents = new ArrayList<>(Objects.requireNonNull(events, "events"));
        if (orderedEvents.size() > BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE) {
            throw new IllegalArgumentException("Animation state visual-event limit exceeded");
        }
        for (AnimationVisualEvent event : orderedEvents) {
            Objects.requireNonNull(event, "event");
        }
        orderedEvents.sort(Comparator.comparingDouble(AnimationVisualEvent::timeSeconds));
        this.events = List.copyOf(orderedEvents);
    }

    public BlendAnimationKey key() {
        return key;
    }

    public AnimationClip clip() {
        return clip;
    }

    public boolean loop() {
        return loop;
    }

    public double speed() {
        return speed;
    }

    public double blendSeconds() {
        return blendSeconds;
    }

    public BlendAnimationKey next() {
        return next;
    }

    public List<AnimationVisualEvent> events() {
        return events;
    }

    CompiledAnimationClip compiledClip() {
        return compiledClip;
    }
}
