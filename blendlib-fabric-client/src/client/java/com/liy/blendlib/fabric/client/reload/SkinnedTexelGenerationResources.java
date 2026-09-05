package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance.StaticSource;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Objects;

/**
 * One exact D1 completed-resource leaf for a skinned source texel stream and its frozen vertex/index bind state.
 *
 * <p>The leaf owns exactly three native buffers and exposes only {@link #bindTo(RenderPass)}. Native handles never
 * escape into the submission/host boundary. D1 remains the sole lifecycle authority and can retry a failed physical
 * close because every unsuccessfully closed owned buffer remains retained by this leaf.</p>
 */
final class SkinnedTexelGenerationResources extends CompletedGenerationResourceSet.ResourceLeaf {
    interface BufferBindings extends AutoCloseable {
        void bindTo(RenderPass pass);

        @Override
        void close();
    }

    private final X7GpuGenerationKey key;
    private final StaticSource staticSource;
    private final int vertexCount;
    private final int indexCount;
    private final int vertexBytes;
    private final int indexBytes;
    private final int sourceBytes;
    private BufferBindings bindings;
    private State state = State.OPEN;

    SkinnedTexelGenerationResources(
            X7GpuGenerationKey key,
            StaticSource staticSource,
            int vertexCount,
            int indexCount,
            int vertexBytes,
            int indexBytes,
            int sourceBytes,
            BufferBindings bindings) {
        this.key = Objects.requireNonNull(key, "key");
        if (key.vertexFormat() != X7GpuVertexFormat.POSITION_NORMAL_UV_F32
                || key.indexType() != X7GpuIndexType.UINT32_LE
                || key.primitiveMode() != X7PrimitiveMode.TRIANGLES) {
            throw new IllegalArgumentException("The skinned texel leaf requires the frozen strict-v1 triangle layout");
        }
        if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0
                || vertexBytes != Math.multiplyExact(vertexCount, key.vertexFormat().strideBytes())
                || indexBytes != Math.multiplyExact(indexCount, key.indexType().bytesPerIndex())
                || sourceBytes != Math.multiplyExact(vertexCount, X7SkinnedTexelProvenance.SOURCE_RECORD_BYTES)) {
            throw new IllegalArgumentException("The skinned texel leaf byte/count identities are not exact");
        }
        this.staticSource = Objects.requireNonNull(staticSource, "staticSource");
        if (vertexCount != staticSource.vertexCount()
                || indexCount != staticSource.indexCount()
                || vertexBytes != staticSource.vertexByteCount()
                || indexBytes != staticSource.indexByteCount()
                || sourceBytes != staticSource.sourceByteCount()) {
            throw new IllegalArgumentException("The skinned texel leaf must retain every exact D1 static upload stream");
        }
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.vertexBytes = vertexBytes;
        this.indexBytes = indexBytes;
        this.sourceBytes = sourceBytes;
        this.bindings = Objects.requireNonNull(bindings, "bindings");
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

    int sourceByteCount() {
        return sourceBytes;
    }

    boolean matchesExactly(StaticSource candidate) {
        return staticSource == Objects.requireNonNull(candidate, "candidate");
    }

    /** Current Stage A may match only the D1-owned immutable geometry/source identity, never a transient palette. */
    boolean matchesCurrentFrame(X7SkinnedTexelProvenance currentFrame) {
        return staticSource.matchesExactly(Objects.requireNonNull(currentFrame, "currentFrame"));
    }

    synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    synchronized void bindTo(RenderPass pass) {
        if (state != State.OPEN || bindings == null) {
            throw new IllegalStateException("The skinned texel D1 leaf is no longer drawable");
        }
        bindings.bindTo(Objects.requireNonNull(pass, "pass"));
    }

    @Override
    int physicalResourceCount() {
        return 3;
    }

    @Override
    long physicalByteCount() {
        return Math.addExact(Math.addExact((long) vertexBytes, (long) indexBytes), (long) sourceBytes);
    }

    @Override
    void closeSynchronouslyAfterVerifiedD1Completion() {
        BufferBindings owned;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.CLOSING) {
                throw new IllegalStateException("The skinned texel D1 leaf close is already in progress");
            }
            state = State.CLOSING;
            owned = bindings;
        }
        try {
            owned.close();
        } catch (Throwable failure) {
            synchronized (this) {
                state = State.OPEN;
            }
            CompletedGenerationResourceSet.throwUnchecked(failure);
        }
        synchronized (this) {
            bindings = null;
            state = State.CLOSED;
        }
        // The leaf is the one D1 owner of static geometry/influence/source bytes. Current frame palettes are not D1
        // resources and are released by the one submitted frame completion path instead.
        staticSource.close();
    }

    static BufferBindings minecraft(GpuBuffer vertex, GpuBuffer index, GpuBuffer sourceTexel) {
        return new MinecraftBufferBindings(vertex, index, sourceTexel);
    }

    private enum State {
        OPEN,
        CLOSING,
        CLOSED
    }

    private static final class MinecraftBufferBindings implements BufferBindings {
        private GpuBuffer vertex;
        private GpuBuffer index;
        private GpuBuffer sourceTexel;

        private MinecraftBufferBindings(GpuBuffer vertex, GpuBuffer index, GpuBuffer sourceTexel) {
            this.vertex = Objects.requireNonNull(vertex, "vertex");
            this.index = Objects.requireNonNull(index, "index");
            this.sourceTexel = Objects.requireNonNull(sourceTexel, "sourceTexel");
        }

        @Override
        public synchronized void bindTo(RenderPass pass) {
            if (vertex == null || index == null || sourceTexel == null
                    || vertex.isClosed() || index.isClosed() || sourceTexel.isClosed()) {
                throw new IllegalStateException("The skinned texel GPU binding set is closed");
            }
            pass.setUniform("SkinSourceBytes", sourceTexel);
            pass.setVertexBuffer(0, vertex);
            pass.setIndexBuffer(index, VertexFormat.IndexType.INT);
        }

        @Override
        public synchronized void close() {
            RenderSystem.assertOnRenderThread();
            Throwable failure = null;
            failure = closeOne(sourceTexel, BufferSlot.SOURCE, failure);
            failure = closeOne(index, BufferSlot.INDEX, failure);
            failure = closeOne(vertex, BufferSlot.VERTEX, failure);
            if (failure != null) {
                CompletedGenerationResourceSet.throwUnchecked(failure);
            }
        }

        private Throwable closeOne(GpuBuffer buffer, BufferSlot slot, Throwable failure) {
            if (buffer == null) {
                return failure;
            }
            try {
                buffer.close();
                switch (slot) {
                    case VERTEX -> vertex = null;
                    case INDEX -> index = null;
                    case SOURCE -> sourceTexel = null;
                }
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            return failure;
        }
    }

    private enum BufferSlot {
        VERTEX,
        INDEX,
        SOURCE
    }
}
