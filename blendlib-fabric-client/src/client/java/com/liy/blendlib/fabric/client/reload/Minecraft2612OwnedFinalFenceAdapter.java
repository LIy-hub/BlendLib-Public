package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;

/** Version-pinned owner of the one Minecraft 26.1.2 shutdown fence. */
final class Minecraft2612OwnedFinalFenceAdapter
        implements ClientFinalFrameShutdownCoordinator.OwnedFenceAdapter {
    static final String PINNED_MOJANG_RUNTIME_MINECRAFT_CLASS_SHA256 =
            "ac3890b42c594a1ff7bf3c3ec945c85cccbd8cb076711d55a20181ed3dd99a7a";
    static final String PINNED_LOOM_PROCESSED_MINECRAFT_CLASS_SHA256 =
            "85aace61cced0d3388e3c53f8ced84096031d1b94e5cde662f00cc90f1e28d7c";
    private static final String MINECRAFT_CLASS_RESOURCE = "/net/minecraft/client/Minecraft.class";

    private final boolean pinnedAdapterAvailable;

    private Minecraft2612OwnedFinalFenceAdapter(boolean pinnedAdapterAvailable) {
        this.pinnedAdapterAvailable = pinnedAdapterAvailable;
    }

    static Minecraft2612OwnedFinalFenceAdapter createPinned() {
        return new Minecraft2612OwnedFinalFenceAdapter(runtimeMinecraftClassMatchesPin());
    }

    @Override
    public boolean pinnedAdapterAvailable() {
        return pinnedAdapterAvailable;
    }

    @Override
    public void assertOnRenderThread() {
        RenderSystem.assertOnRenderThread();
    }

    @Override
    public ClientFinalFrameShutdownCoordinator.OwnedFence createOwnedFence() {
        if (!pinnedAdapterAvailable) {
            throw new IllegalStateException("Minecraft class does not match the pinned 26.1.2 shutdown adapter");
        }
        RenderSystem.assertOnRenderThread();
        MinecraftOwnedFence ownedFence = new MinecraftOwnedFence();
        GpuFence fence = RenderSystem.getDevice().createCommandEncoder().createFence();
        if (fence == null) {
            throw new IllegalStateException("Minecraft returned no owned shutdown fence");
        }
        ownedFence.takeOwnership(fence);
        return ownedFence;
    }

    static boolean matchesPinnedMinecraftClass(byte[] classBytes) {
        return matchesPinnedMinecraftClassSha256(sha256(classBytes));
    }

    static boolean matchesPinnedMinecraftClassSha256(String classSha256) {
        return PINNED_MOJANG_RUNTIME_MINECRAFT_CLASS_SHA256.equals(classSha256)
                || PINNED_LOOM_PROCESSED_MINECRAFT_CLASS_SHA256.equals(classSha256);
    }

    private static boolean runtimeMinecraftClassMatchesPin() {
        try (InputStream input = Minecraft.class.getResourceAsStream(MINECRAFT_CLASS_RESOURCE)) {
            return input != null && matchesPinnedMinecraftClass(input.readAllBytes());
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class MinecraftOwnedFence
            implements ClientFinalFrameShutdownCoordinator.OwnedFence {
        private GpuFence fence;
        private boolean closed;

        private void takeOwnership(GpuFence ownedFence) {
            if (fence != null) {
                throw new IllegalStateException("Owned shutdown fence was already assigned");
            }
            fence = ownedFence;
        }

        @Override
        public boolean awaitCompletionZeroTimeout() {
            RenderSystem.assertOnRenderThread();
            if (closed) {
                throw new IllegalStateException("Owned shutdown fence is already closed");
            }
            GpuFence fence = requireOwnedFence();
            return fence.awaitCompletion(0L);
        }

        @Override
        public void closeOnRenderThread() {
            RenderSystem.assertOnRenderThread();
            if (!closed) {
                GpuFence fence = requireOwnedFence();
                fence.close();
                closed = true;
            }
        }

        private GpuFence requireOwnedFence() {
            if (fence == null) {
                throw new IllegalStateException("Owned shutdown fence was not assigned");
            }
            return fence;
        }
    }
}
