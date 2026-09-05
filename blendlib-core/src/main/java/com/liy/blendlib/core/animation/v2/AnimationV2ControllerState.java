package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable controller state with one full-pose clip for each declared controller layer. */
public final class AnimationV2ControllerState {
    private final BlendAnimationKey key;
    private final AnimationV2PlaybackMode playbackMode;
    private final double speed;
    private final double transitionSeconds;
    private final BlendAnimationKey next;
    private final Map<BlendResourceId, AnimationV2Clip> clipsByLayer;
    private final double durationSeconds;

    public AnimationV2ControllerState(
            BlendAnimationKey key,
            AnimationV2PlaybackMode playbackMode,
            double speed,
            double transitionSeconds,
            BlendAnimationKey next,
            Map<BlendResourceId, AnimationV2Clip> clipsByLayer) {
        this.key = Objects.requireNonNull(key, "key");
        AnimationV2Limits.requireCanonicalIdLength(this.key.value(), "state key");
        this.playbackMode = Objects.requireNonNull(playbackMode, "playbackMode");
        AnimationV2Limits.requireSpeed(speed, "state speed");
        if (!Double.isFinite(transitionSeconds)
                || transitionSeconds < 0.0D
                || transitionSeconds > AnimationV2Limits.MAX_TRANSITION_SECONDS) {
            throw new IllegalArgumentException("transitionSeconds must be finite and in [0, "
                    + AnimationV2Limits.MAX_TRANSITION_SECONDS + "]");
        }
        if (playbackMode != AnimationV2PlaybackMode.ONCE && next != null) {
            throw new IllegalArgumentException("only ONCE states may declare next");
        }
        if (next != null) {
            AnimationV2Limits.requireCanonicalIdLength(next.value(), "next state key");
        }
        this.speed = speed;
        this.transitionSeconds = transitionSeconds;
        this.next = next;
        Objects.requireNonNull(clipsByLayer, "clipsByLayer");
        if (clipsByLayer.isEmpty()) {
            throw new IllegalArgumentException("each controller state must declare at least one layer clip");
        }
        LinkedHashMap<BlendResourceId, AnimationV2Clip> copied = new LinkedHashMap<>();
        int expectedBones = -1;
        double expectedDuration = -1.0D;
        for (Map.Entry<BlendResourceId, AnimationV2Clip> entry : clipsByLayer.entrySet()) {
            BlendResourceId layerId = Objects.requireNonNull(entry.getKey(), "layerId");
            AnimationV2Clip clip = Objects.requireNonNull(entry.getValue(), "clip");
            AnimationV2Limits.requireCanonicalIdLength(layerId.value(), "layer id");
            if (copied.putIfAbsent(layerId, clip) != null) {
                throw new IllegalArgumentException("duplicate layer clip id: " + layerId);
            }
            if (expectedBones == -1) {
                expectedBones = clip.boneCount();
                expectedDuration = clip.durationSeconds();
            } else if (clip.boneCount() != expectedBones || Math.abs(clip.durationSeconds() - expectedDuration) > AnimationV2Limits.EPSILON) {
                throw new IllegalArgumentException("all clips in one controller state must share bone count and duration");
            }
        }
        this.clipsByLayer = Collections.unmodifiableMap(copied);
        this.durationSeconds = expectedDuration;
    }

    public BlendAnimationKey key() {
        return key;
    }

    public AnimationV2PlaybackMode playbackMode() {
        return playbackMode;
    }

    public double speed() {
        return speed;
    }

    public double transitionSeconds() {
        return transitionSeconds;
    }

    public BlendAnimationKey next() {
        return next;
    }

    public Map<BlendResourceId, AnimationV2Clip> clipsByLayer() {
        return clipsByLayer;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    public AnimationV2Clip clip(BlendResourceId layerId) {
        AnimationV2Clip clip = clipsByLayer.get(Objects.requireNonNull(layerId, "layerId"));
        if (clip == null) {
            throw new IllegalArgumentException("state does not declare a clip for layer: " + layerId);
        }
        return clip;
    }
}
