package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * One caller-owned, generation-scoped typed vertex/index buffer pair.
 *
 * <p>The pair owns buffers only. It never closes Minecraft's {@code GpuDevice}. It is a completed
 * aggregate leaf, not an independent generation, lease, fence, or retry authority; the canonical
 * aggregate decides when a verified D1 completion may invoke its synchronous close.</p>
 */
final class X7SharedGeometryResources extends CompletedGenerationResourceSet.ResourceLeaf {
    private final X7GpuGenerationKey key;
    private final X7GpuVertexFormat vertexFormat;
    private final X7GpuIndexType indexType;
    private final X7PrimitiveMode primitiveMode;
    private final int vertexCount;
    private final int indexCount;
    private final int vertexBytes;
    private final int indexBytes;
    private X7GpuBuffer vertexBuffer;
    private X7GpuBuffer indexBuffer;
    private BufferState vertexState = BufferState.AVAILABLE;
    private BufferState indexState = BufferState.AVAILABLE;
    private boolean closed;

    X7SharedGeometryResources(
            X7GpuGenerationKey key,
            X7GpuVertexFormat vertexFormat,
            X7GpuIndexType indexType,
            X7PrimitiveMode primitiveMode,
            int vertexCount,
            int indexCount,
            int vertexBytes,
            int indexBytes,
            X7GpuBuffer vertexBuffer,
            X7GpuBuffer indexBuffer) {
        this.key = Objects.requireNonNull(key, "key");
        this.vertexFormat = Objects.requireNonNull(vertexFormat, "vertexFormat");
        this.indexType = Objects.requireNonNull(indexType, "indexType");
        this.primitiveMode = Objects.requireNonNull(primitiveMode, "primitiveMode");
        if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0 || vertexBytes <= 0 || indexBytes <= 0) {
            throw new IllegalArgumentException("X7 shared geometry resource counts must describe non-empty triangles");
        }
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.vertexBytes = vertexBytes;
        this.indexBytes = indexBytes;
        this.vertexBuffer = Objects.requireNonNull(vertexBuffer, "vertexBuffer");
        this.indexBuffer = Objects.requireNonNull(indexBuffer, "indexBuffer");
        if (key.vertexFormat() != vertexFormat || key.indexType() != indexType || key.primitiveMode() != primitiveMode) {
            throw new IllegalArgumentException("X7 shared geometry resources must retain their exact complete key formats");
        }
    }

    @Override
    X7GpuGenerationKey key() {
        return key;
    }

    X7GpuVertexFormat vertexFormat() {
        return vertexFormat;
    }

    X7GpuIndexType indexType() {
        return indexType;
    }

    X7PrimitiveMode primitiveMode() {
        return primitiveMode;
    }

    int vertexCount() {
        return vertexCount;
    }

    int indexCount() {
        return indexCount;
    }

    int vertexBytes() {
        return vertexBytes;
    }

    int indexBytes() {
        return indexBytes;
    }

    @Override
    int physicalResourceCount() {
        return 2;
    }

    @Override
    long physicalByteCount() {
        return Math.addExact((long) vertexBytes, (long) indexBytes);
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /** Called only by a canonical aggregate after D1 has verified close completion. */
    @Override
    void closeSynchronouslyAfterVerifiedD1Completion() {
        X7GpuBuffer vertex = claim(BufferSlot.VERTEX);
        X7GpuBuffer index = claim(BufferSlot.INDEX);
        Throwable failure = null;
        failure = closeClaimed(BufferSlot.VERTEX, vertex, failure);
        failure = closeClaimed(BufferSlot.INDEX, index, failure);
        synchronized (this) {
            if (vertexState == BufferState.RELEASED && indexState == BufferState.RELEASED) {
                closed = true;
            }
        }
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    private synchronized X7GpuBuffer claim(BufferSlot slot) {
        if (closed || state(slot) != BufferState.AVAILABLE) {
            return null;
        }
        setState(slot, BufferState.CLOSING);
        return buffer(slot);
    }

    private Throwable closeClaimed(BufferSlot slot, X7GpuBuffer buffer, Throwable priorFailure) {
        if (buffer == null) {
            return priorFailure;
        }
        try {
            // The owner token, rather than a possibly-throwing isClosed query, governs close attempts.
            buffer.close();
            markReleased(slot);
        } catch (Throwable failure) {
            markRetryable(slot);
            return append(priorFailure, failure);
        }
        return priorFailure;
    }

    private synchronized void markReleased(BufferSlot slot) {
        if (state(slot) != BufferState.CLOSING) {
            return;
        }
        setState(slot, BufferState.RELEASED);
        setBuffer(slot, null);
    }

    private synchronized void markRetryable(BufferSlot slot) {
        if (state(slot) == BufferState.CLOSING) {
            setState(slot, BufferState.AVAILABLE);
        }
    }

    private synchronized BufferState state(BufferSlot slot) {
        return slot == BufferSlot.VERTEX ? vertexState : indexState;
    }

    private synchronized void setState(BufferSlot slot, BufferState state) {
        if (slot == BufferSlot.VERTEX) {
            vertexState = state;
        } else {
            indexState = state;
        }
    }

    private synchronized X7GpuBuffer buffer(BufferSlot slot) {
        return slot == BufferSlot.VERTEX ? vertexBuffer : indexBuffer;
    }

    private synchronized void setBuffer(BufferSlot slot, X7GpuBuffer buffer) {
        if (slot == BufferSlot.VERTEX) {
            vertexBuffer = buffer;
        } else {
            indexBuffer = buffer;
        }
    }

    private static Throwable append(Throwable primary, Throwable addition) {
        if (primary == null) {
            return addition;
        }
        if (primary != addition) {
            primary.addSuppressed(addition);
        }
        return primary;
    }

    private static void rethrowUnchecked(Throwable failure) {
        X7SharedGeometryResources.<RuntimeException>throwUnchecked0(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked0(Throwable failure) throws T {
        throw (T) failure;
    }

    private enum BufferSlot {
        VERTEX,
        INDEX
    }

    private enum BufferState {
        AVAILABLE,
        CLOSING,
        RELEASED
    }
}
