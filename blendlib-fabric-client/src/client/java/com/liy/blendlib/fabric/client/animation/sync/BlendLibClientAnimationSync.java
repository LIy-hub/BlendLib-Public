package com.liy.blendlib.fabric.client.animation.sync;

/** Client-owned access point for the latest synchronized animation semantics. */
public final class BlendLibClientAnimationSync {
    private static final ClientAnimationSyncRuntime RUNTIME = new ClientAnimationSyncRuntime();

    private BlendLibClientAnimationSync() {
    }

    /** Returns the single client-thread runtime; callers must use it only from the normal client lifecycle. */
    public static ClientAnimationSyncRuntime runtime() {
        return RUNTIME;
    }
}
