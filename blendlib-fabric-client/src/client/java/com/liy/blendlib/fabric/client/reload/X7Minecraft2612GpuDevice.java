package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Minecraft 26.1.2-only wrapper over the verified typed Blaze3D allocation API.
 *
 * <p>This class owns only buffer wrappers. The game-owned {@link GpuDevice} is never closed by
 * X7. Actual render-pass ownership, drawing, and model/reload wiring remain integration work.</p>
 */
final class X7Minecraft2612GpuDevice implements X7GpuDevice {
    private final GpuDevice device;

    private X7Minecraft2612GpuDevice(GpuDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    static X7Minecraft2612GpuDevice fromRenderSystem() {
        RenderSystem.assertOnRenderThread();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            device = RenderSystem.getDevice();
        }
        return new X7Minecraft2612GpuDevice(Objects.requireNonNull(device, "RenderSystem device"));
    }

    @Override
    public void assertOnRenderThread() {
        RenderSystem.assertOnRenderThread();
    }

    @Override
    public X7GpuBuffer allocateVertexUpload(String debugLabel, ByteBuffer bytes) {
        return createBuffer(debugLabel, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, bytes);
    }

    @Override
    public X7GpuBuffer allocateIndexUpload(String debugLabel, ByteBuffer bytes) {
        return createBuffer(debugLabel, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX, bytes);
    }

    private X7GpuBuffer createBuffer(String debugLabel, int usage, ByteBuffer bytes) {
        RenderSystem.assertOnRenderThread();
        String label = Objects.requireNonNull(debugLabel, "debugLabel");
        if (label.isBlank()) {
            throw new IllegalArgumentException("X7 GPU buffer debug label must not be blank");
        }
        ByteBuffer upload = Objects.requireNonNull(bytes, "bytes").duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (!upload.isDirect()) {
            throw new IllegalArgumentException("X7 typed GPU upload requires direct staging bytes");
        }
        GpuBuffer buffer = device.createBuffer(() -> label, usage, upload);
        return new MinecraftBuffer(Objects.requireNonNull(buffer, "GpuDevice.createBuffer result"));
    }

    private static final class MinecraftBuffer implements X7GpuBuffer {
        private final GpuBuffer delegate;

        private MinecraftBuffer(GpuBuffer delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void close() {
            RenderSystem.assertOnRenderThread();
            delegate.close();
        }
    }
}
