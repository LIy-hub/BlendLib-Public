package com.liy.blendlib.fabric.client.reload;

/** Static client-only bridge used only by the pinned Minecraft final-present Mixin. */
final class Minecraft2612FinalFrameShutdownHooks {
    private static ClientFinalFrameShutdownCoordinator coordinator;

    private Minecraft2612FinalFrameShutdownHooks() { }

    static synchronized void install(ClientFinalFrameShutdownCoordinator installedCoordinator) {
        if (coordinator != null && coordinator != installedCoordinator) {
            throw new IllegalStateException("The Minecraft 26.1.2 final-present owner is already installed");
        }
        coordinator = installedCoordinator;
    }

    static void beforeOriginalPresent(boolean clientRunning) {
        ClientFinalFrameShutdownCoordinator current = current();
        if (current == null) {
            return;
        }
        try {
            current.beforeOriginalPresent(clientRunning);
        } catch (Throwable failure) {
            failWithoutEscaping(current, failure);
        }
    }

    static void afterOriginalPresent() {
        ClientFinalFrameShutdownCoordinator current = current();
        if (current == null) {
            return;
        }
        try {
            current.afterOriginalPresent();
        } catch (Throwable failure) {
            failWithoutEscaping(current, failure);
        }
    }

    private static synchronized ClientFinalFrameShutdownCoordinator current() {
        return coordinator;
    }

    private static void failWithoutEscaping(
            ClientFinalFrameShutdownCoordinator current, Throwable failure) {
        try {
            current.failUnexpectedly(failure);
        } catch (Throwable ignored) {
            // A Mixin callback must never replace vanilla present/teardown with a BlendLib exception.
        }
    }
}
