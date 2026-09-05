package com.liy.blendlib.fabric.client.reload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * One explicitly owned pair of direct, deterministic CPU upload buffers.
 *
 * <p>All B1 bytes use {@link ByteOrder#LITTLE_ENDIAN}; direct storage improves upload handoff but
 * never changes this declared layout. Closing this object drops its only buffer references after a
 * successful or failed allocation transaction. It does not use cleaners, reflection, or native
 * implementation APIs.</p>
 */
final class X7GeometryStaging implements AutoCloseable {
    private final int vertexCount;
    private final int indexCount;
    private ByteBuffer vertexBytes;
    private ByteBuffer indexBytes;
    private boolean closed;

    private X7GeometryStaging(int vertexCount, int indexCount, ByteBuffer vertexBytes, ByteBuffer indexBytes) {
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.vertexBytes = vertexBytes;
        this.indexBytes = indexBytes;
    }

    static X7GeometryStaging pack(X7ImmutableGeometryView geometry, X7GeometryStagingLimits limits) {
        X7ImmutableGeometryView checkedGeometry = Objects.requireNonNull(geometry, "geometry");
        X7GeometryStagingLimits checkedLimits = Objects.requireNonNull(limits, "limits");
        int vertices = checkedGeometry.vertexCount();
        int indices = checkedGeometry.indexCount();
        if (vertices <= 0 || vertices > checkedLimits.maxVertices()) {
            throw new IllegalArgumentException("X7 geometry vertex count is outside the bounded staging range");
        }
        if (indices <= 0 || indices % 3 != 0 || indices > checkedLimits.maxIndices()) {
            throw new IllegalArgumentException("X7 geometry index count must be bounded strict-v1 triangles");
        }
        int vertexByteCount = checkedByteCount(vertices, X7GpuVertexFormat.POSITION_NORMAL_UV_F32.strideBytes(), checkedLimits.maxVertexBytes(), "vertex");
        int indexByteCount = checkedByteCount(indices, X7GpuIndexType.UINT32_LE.bytesPerIndex(), checkedLimits.maxIndexBytes(), "index");

        ByteBuffer verticesOut = ByteBuffer.allocateDirect(vertexByteCount).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer indicesOut = ByteBuffer.allocateDirect(indexByteCount).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < vertices; vertex++) {
            verticesOut.putFloat(finite(checkedGeometry.positionX(vertex), "positionX"));
            verticesOut.putFloat(finite(checkedGeometry.positionY(vertex), "positionY"));
            verticesOut.putFloat(finite(checkedGeometry.positionZ(vertex), "positionZ"));
            verticesOut.putFloat(finite(checkedGeometry.normalX(vertex), "normalX"));
            verticesOut.putFloat(finite(checkedGeometry.normalY(vertex), "normalY"));
            verticesOut.putFloat(finite(checkedGeometry.normalZ(vertex), "normalZ"));
            verticesOut.putFloat(finite(checkedGeometry.u(vertex), "u"));
            verticesOut.putFloat(finite(checkedGeometry.v(vertex), "v"));
        }
        for (int indexOffset = 0; indexOffset < indices; indexOffset++) {
            int index = checkedGeometry.index(indexOffset);
            if (index < 0 || index >= vertices) {
                throw new IllegalArgumentException("X7 geometry index is outside its vertex range");
            }
            indicesOut.putInt(index);
        }
        verticesOut.flip();
        indicesOut.flip();
        return new X7GeometryStaging(vertices, indices, verticesOut, indicesOut);
    }

    int vertexCount() {
        return vertexCount;
    }

    int indexCount() {
        return indexCount;
    }

    int vertexByteCount() {
        return X7GpuVertexFormat.POSITION_NORMAL_UV_F32.strideBytes() * vertexCount;
    }

    int indexByteCount() {
        return X7GpuIndexType.UINT32_LE.bytesPerIndex() * indexCount;
    }

    X7GpuVertexFormat vertexFormat() {
        return X7GpuVertexFormat.POSITION_NORMAL_UV_F32;
    }

    X7GpuIndexType indexType() {
        return X7GpuIndexType.UINT32_LE;
    }

    synchronized ByteBuffer vertexBytesForUpload() {
        requireOpen();
        return readOnlyUploadView(vertexBytes);
    }

    synchronized ByteBuffer indexBytesForUpload() {
        requireOpen();
        return readOnlyUploadView(indexBytes);
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        vertexBytes = null;
        indexBytes = null;
    }

    private static ByteBuffer readOnlyUploadView(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        copy.position(0);
        return copy;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("X7 direct CPU staging has already been released");
        }
    }

    private static int checkedByteCount(int count, int width, int maximum, String label) {
        final long bytes;
        try {
            bytes = Math.multiplyExact((long) count, (long) width);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("X7 " + label + " staging byte count overflow", exception);
        }
        if (bytes > maximum || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("X7 " + label + " staging byte count exceeds its bounded allocation limit");
        }
        return (int) bytes;
    }

    private static float finite(float value, String component) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("X7 geometry " + component + " must be finite");
        }
        return value;
    }
}
