package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.systems.RenderSystem;

/** The 26.1.2 pinned final-present protocol is unavailable on legacy Minecraft. */
final class Minecraft2612OwnedFinalFenceAdapter implements ClientFinalFrameShutdownCoordinator.OwnedFenceAdapter {
    static Minecraft2612OwnedFinalFenceAdapter createPinned() { return new Minecraft2612OwnedFinalFenceAdapter(); }
    @Override public boolean pinnedAdapterAvailable() { return false; }
    @Override public void assertOnRenderThread() { RenderSystem.assertOnRenderThread(); }
    @Override public ClientFinalFrameShutdownCoordinator.OwnedFence createOwnedFence() {
        throw new IllegalStateException("The 26.1.2 final-present protocol is not available on legacy Minecraft");
    }
}
