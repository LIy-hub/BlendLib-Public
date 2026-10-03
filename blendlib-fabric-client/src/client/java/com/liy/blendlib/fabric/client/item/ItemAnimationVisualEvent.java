package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import java.util.Objects;

/** Immutable descriptor marker from a successful item extraction; not proof of display or gameplay authority. */
public record ItemAnimationVisualEvent(
        BlendModelKey model, BlendAnimationKey animation, long generation, AnimationVisualEvent event) {
    public ItemAnimationVisualEvent {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(animation, "animation");
        Objects.requireNonNull(event, "event");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
    }
}
