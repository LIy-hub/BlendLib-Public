package com.liy.blendlib.fabric.client.animation.v2;

/** Immutable adapter-supplied client clock sample. */
public record ClientAnimationV2FrameTime(long gameTick, float partialTick) {
    public ClientAnimationV2FrameTime {
        if (!Float.isFinite(partialTick) || partialTick < 0.0F || partialTick > 1.0F) {
            throw new IllegalArgumentException("partialTick must be finite and in [0, 1]");
        }
    }
}
