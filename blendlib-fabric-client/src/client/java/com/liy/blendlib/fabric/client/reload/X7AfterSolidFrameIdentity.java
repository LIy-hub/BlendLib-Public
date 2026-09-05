package com.liy.blendlib.fabric.client.reload;

/** Immutable value-only target for one future AFTER_SOLID_FEATURES callback; it retains no Minecraft callback data. */
record X7AfterSolidFrameIdentity(long afterSolidEpoch, long renderThreadId, Phase phase) {
    enum Phase {
        AFTER_SOLID_FEATURES
    }

    X7AfterSolidFrameIdentity {
        if (afterSolidEpoch <= 0L) {
            throw new IllegalArgumentException("afterSolidEpoch must be positive");
        }
        if (renderThreadId <= 0L) {
            throw new IllegalArgumentException("renderThreadId must be positive");
        }
        phase = java.util.Objects.requireNonNull(phase, "phase");
    }
}
