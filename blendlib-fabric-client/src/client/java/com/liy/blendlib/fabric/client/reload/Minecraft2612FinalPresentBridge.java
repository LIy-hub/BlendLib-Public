package com.liy.blendlib.fabric.client.reload;

/**
 * Internal cross-package bridge for the dedicated client Mixin package.
 *
 * <p>This type is public only because Mixin packages must not contain ordinary helper classes. It is not a supported
 * BlendLib API.
 */
public final class Minecraft2612FinalPresentBridge {
    private Minecraft2612FinalPresentBridge() { }

    /** Forwards the callback immediately before vanilla's original final present. */
    public static void beforeOriginalPresent(boolean clientRunning) {
        Minecraft2612FinalFrameShutdownHooks.beforeOriginalPresent(clientRunning);
    }

    /** Forwards the callback immediately after vanilla's original final present. */
    public static void afterOriginalPresent() {
        Minecraft2612FinalFrameShutdownHooks.afterOriginalPresent();
    }
}
