package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Objects;

/**
 * One direct-static vertex/index pair that is a normal D1 completed-resource leaf.
 *
 * <p>This candidate owns no policy map, generation lease, or retry scheduler.  Its one physical pair must be put
 * into the canonical completed set by a later integration owner; D1 alone invokes its close hook after a verified
 * completion receipt releases the submitted child.</p>
 */
final class StaticDirectGenerationResources extends CompletedGenerationResourceSet.ResourceLeaf {
    interface BufferBindings extends AutoCloseable {
        void bind(RenderPass pass);

        @Override
        void close();
    }

    private final X7GpuGenerationKey key;
    private final int vertexCount;
    private final int indexCount;
    private final int vertexBytes;
    private final int indexBytes;
    private BufferBindings buffers;
    private State state = State.OPEN;

    StaticDirectGenerationResources(
            X7GpuGenerationKey key,
            int vertexCount,
            int indexCount,
            int vertexBytes,
            int indexBytes,
            BufferBindings buffers) {
        this.key = Objects.requireNonNull(key, "key");
        if (key.vertexFormat() != X7GpuVertexFormat.POSITION_NORMAL_UV_F32
                || key.indexType() != X7GpuIndexType.UINT32_LE
                || key.primitiveMode() != X7PrimitiveMode.TRIANGLES) {
            throw new IllegalArgumentException("The direct-static leaf requires the frozen strict-v1 triangle layout");
        }
        if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("The direct-static leaf requires non-empty indexed triangles");
        }
        if (vertexBytes != Math.multiplyExact(vertexCount, key.vertexFormat().strideBytes())
                || indexBytes != Math.multiplyExact(indexCount, key.indexType().bytesPerIndex())) {
            throw new IllegalArgumentException("The direct-static leaf byte counts must match its frozen GPU layout");
        }
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.vertexBytes = vertexBytes;
        this.indexBytes = indexBytes;
        this.buffers = Objects.requireNonNull(buffers, "buffers");
    }

    @Override
    X7GpuGenerationKey key() {
        return key;
    }

    int vertexCount() {
        return vertexCount;
    }

    int indexCount() {
        return indexCount;
    }

    synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    synchronized void bindTo(RenderPass pass) {
        if (state != State.OPEN || buffers == null) {
            throw new IllegalStateException("The direct-static D1 leaf is no longer drawable");
        }
        buffers.bind(Objects.requireNonNull(pass, "pass"));
    }

    @Override
    int physicalResourceCount() {
        return 2;
    }

    @Override
    long physicalByteCount() {
        return Math.addExact((long) vertexBytes, (long) indexBytes);
    }

    @Override
    void closeSynchronouslyAfterVerifiedD1Completion() {
        BufferBindings owned;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.CLOSING) {
                throw new IllegalStateException("The direct-static D1 leaf close is already in progress");
            }
            state = State.CLOSING;
            owned = buffers;
        }
        try {
            owned.close();
            synchronized (this) {
                buffers = null;
                state = State.CLOSED;
            }
        } catch (Throwable failure) {
            synchronized (this) {
                state = State.OPEN;
            }
            CompletedGenerationResourceSet.throwUnchecked(failure);
        }
    }

    static BufferBindings minecraft(GpuBuffer vertex, GpuBuffer index) {
        return new MinecraftBufferBindings(vertex, index);
    }

    private enum State {
        OPEN,
        CLOSING,
        CLOSED
    }

    private static final class MinecraftBufferBindings implements BufferBindings {
        private GpuBuffer vertex;
        private GpuBuffer index;

        private MinecraftBufferBindings(GpuBuffer vertex, GpuBuffer index) {
            this.vertex = Objects.requireNonNull(vertex, "vertex");
            this.index = Objects.requireNonNull(index, "index");
        }

        @Override
        public synchronized void bind(RenderPass pass) {
            if (vertex == null || index == null || vertex.isClosed() || index.isClosed()) {
                throw new IllegalStateException("The direct-static GPU buffer pair is closed");
            }
            pass.setVertexBuffer(0, vertex);
            pass.setIndexBuffer(index, VertexFormat.IndexType.INT);
        }

        @Override
        public synchronized void close() {
            RenderSystem.assertOnRenderThread();
            GpuBuffer ownedIndex = index;
            GpuBuffer ownedVertex = vertex;
            Throwable failure = null;
            try {
                if (ownedIndex != null) {
                    ownedIndex.close();
                    index = null;
                }
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                if (ownedVertex != null) {
                    ownedVertex.close();
                    vertex = null;
                }
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                CompletedGenerationResourceSet.throwUnchecked(failure);
            }
        }
    }
}
