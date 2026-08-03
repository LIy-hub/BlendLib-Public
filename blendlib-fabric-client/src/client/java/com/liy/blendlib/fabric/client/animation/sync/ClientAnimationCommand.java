package com.liy.blendlib.fabric.client.animation.sync;

import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.Objects;

/** Immutable incoming semantic animation command, still independent from renderer and model bindings. */
public record ClientAnimationCommand(ClientAnimationTarget target, SyncedAnimationState animation) {
    public ClientAnimationCommand {
        target = Objects.requireNonNull(target, "target");
        animation = Objects.requireNonNull(animation, "animation");
    }
}
