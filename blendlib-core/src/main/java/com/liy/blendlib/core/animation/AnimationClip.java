package com.liy.blendlib.core.animation;

import java.util.List;
import java.util.Objects;

/** Immutable named GLB clip composed of node transform channels. */
public final class AnimationClip {
    private final String name;
    private final List<AnimationChannel> channels;
    private final float durationSeconds;

    public AnimationClip(String name, List<AnimationChannel> channels) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Animation clip name must not be blank");
        }
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        if (this.channels.isEmpty()) {
            throw new IllegalArgumentException("Animation clip must contain at least one channel");
        }
        float duration = 0.0f;
        for (AnimationChannel channel : this.channels) {
            duration = Math.max(duration, channel.durationSeconds());
        }
        this.durationSeconds = duration;
    }

    public String name() {
        return name;
    }

    public List<AnimationChannel> channels() {
        return channels;
    }

    public float durationSeconds() {
        return durationSeconds;
    }
}
