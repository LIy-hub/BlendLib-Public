package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.AnimationAdvance;
import com.liy.blendlib.core.animation.runtime.AnimationController;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.LocalPose;

import java.util.Objects;
import java.util.Optional;

/**
 * Mutable extraction-side animation state for one concrete instance model binding and generation.
 *
 * <p>Instances are owned by {@link ClientAnimationInstanceRegistry}; callers replace an
 * instance instead of reusing it when its model binding or generation changes.</p>
 */
public final class ClientAnimationInstance {
    private final BlendInstanceKey key;
    private final BlendModelKey modelKey;
    private final long generation;
    private final AnimationController controller;
    private LocalPose latestPose;

    ClientAnimationInstance(
            BlendInstanceKey key,
            BlendModelKey modelKey,
            long generation,
            AnimationControllerDefinition definition
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.controller = new AnimationController(key, Objects.requireNonNull(definition, "definition"));
    }

    public BlendInstanceKey key() {
        return key;
    }

    /** Returns the semantic model identity bound to this instance state. */
    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public AnimationController controller() {
        return controller;
    }

    public AnimationAdvance advance(double deltaSeconds) {
        return controller.advance(deltaSeconds);
    }

    public void rememberPose(LocalPose pose) {
        latestPose = Objects.requireNonNull(pose, "pose");
    }

    public Optional<LocalPose> latestPose() {
        return Optional.ofNullable(latestPose);
    }
}
