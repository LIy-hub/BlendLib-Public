package com.liy.blendlib.fabric.client.render.x7gpu;

import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.SkinnedMeshTopology;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Pose-scoped CPU skinning bytes retained as an unowned draw-domain candidate.
 *
 * <p>The canonical reload resource island owns typed allocation staging. This class remains in
 * {@code render.x7gpu} because it is only a CPU-skinned candidate: it neither creates a resource
 * leaf nor exposes a reload-owner type. A later, separately scoped bridge may consume its fully
 * packed bytes before any allocation; this tranche performs no allocation, upload, or draw.</p>
 */
final class X7CpuSkinnedUploadCandidate implements AutoCloseable {
    enum Kind {
        CPU_SKINNED_UPLOAD_CANDIDATE
    }

    record Limits(int maxVertices, int maxIndices, int maxVertexBytes, int maxIndexBytes) {
        static final Limits STRICT_V1 = new Limits(1_000_000, 3_000_000, 32_000_000, 12_000_000);

        Limits {
            if (maxVertices <= 0 || maxIndices <= 0 || maxVertexBytes <= 0 || maxIndexBytes <= 0) {
                throw new IllegalArgumentException("X7 CPU-skinned candidate limits must be positive");
            }
        }
    }

    private PackedGeometry packedGeometry;
    private boolean transferred;

    private X7CpuSkinnedUploadCandidate(PackedGeometry packedGeometry) {
        this.packedGeometry = Objects.requireNonNull(packedGeometry, "packedGeometry");
    }

    static X7CpuSkinnedUploadCandidate fromCpuSkinnerResult(CpuSkinnedMesh mesh, Limits limits) {
        CpuSkinnedMesh checkedMesh = Objects.requireNonNull(mesh, "mesh");
        SkinnedMeshTopology topology = checkedMesh.topology();
        float[] positions = checkedMesh.positions();
        float[] normals = checkedMesh.normals();
        float[] texCoords = topology.texCoords();
        int[] indices = topology.indices();
        if (checkedMesh.vertexCount() != topology.vertexCount()) {
            throw new IllegalArgumentException("CPU-skinned output and topology vertex counts disagree");
        }
        return new X7CpuSkinnedUploadCandidate(PackedGeometry.pack(
                positions, normals, texCoords, indices, Objects.requireNonNull(limits, "limits")));
    }

    Kind kind() {
        return Kind.CPU_SKINNED_UPLOAD_CANDIDATE;
    }

    boolean claimsGpuSkinning() {
        return false;
    }

    synchronized PackedGeometry transferPackedGeometryForFutureBridge() {
        if (transferred || packedGeometry == null) {
            throw new IllegalStateException("CPU-skinned X7 candidate has already transferred or closed");
        }
        transferred = true;
        PackedGeometry result = packedGeometry;
        packedGeometry = null;
        return result;
    }

    synchronized boolean isClosed() {
        return packedGeometry == null;
    }

    @Override
    public synchronized void close() {
        if (packedGeometry != null) {
            packedGeometry.close();
            packedGeometry = null;
        }
    }

    /** Immutable-layout CPU bytes, not a resource leaf and not an allocation permission. */
    static final class PackedGeometry implements AutoCloseable {
        private final int vertexCount;
        private final int indexCount;
        private ByteBuffer vertexBytes;
        private ByteBuffer indexBytes;
        private boolean closed;

        private PackedGeometry(int vertexCount, int indexCount, ByteBuffer vertexBytes, ByteBuffer indexBytes) {
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.vertexBytes = vertexBytes;
            this.indexBytes = indexBytes;
        }

        private static PackedGeometry pack(
                float[] positions, float[] normals, float[] texCoords, int[] indices, Limits limits) {
            float[] checkedPositions = Objects.requireNonNull(positions, "positions");
            float[] checkedNormals = Objects.requireNonNull(normals, "normals");
            float[] checkedTexCoords = Objects.requireNonNull(texCoords, "texCoords");
            int[] checkedIndices = Objects.requireNonNull(indices, "indices");
            if (checkedPositions.length == 0 || checkedPositions.length % 3 != 0
                    || checkedNormals.length != checkedPositions.length
                    || checkedTexCoords.length != (checkedPositions.length / 3) * 2
                    || checkedIndices.length == 0 || checkedIndices.length % 3 != 0) {
                throw new IllegalArgumentException("CPU-skinned upload candidate has invalid attribute cardinality");
            }
            int vertices = checkedPositions.length / 3;
            if (vertices > limits.maxVertices() || checkedIndices.length > limits.maxIndices()) {
                throw new IllegalArgumentException("CPU-skinned upload candidate exceeds its bounded staging limits");
            }
            int vertexByteCount = checkedByteCount(vertices, 8 * Float.BYTES, limits.maxVertexBytes(), "vertex");
            int indexByteCount = checkedByteCount(checkedIndices.length, Integer.BYTES, limits.maxIndexBytes(), "index");
            ByteBuffer verticesOut = ByteBuffer.allocateDirect(vertexByteCount).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer indicesOut = ByteBuffer.allocateDirect(indexByteCount).order(ByteOrder.LITTLE_ENDIAN);
            for (int vertex = 0; vertex < vertices; vertex++) {
                int position = vertex * 3;
                int uv = vertex * 2;
                verticesOut.putFloat(finite(checkedPositions[position], "positionX"));
                verticesOut.putFloat(finite(checkedPositions[position + 1], "positionY"));
                verticesOut.putFloat(finite(checkedPositions[position + 2], "positionZ"));
                verticesOut.putFloat(finite(checkedNormals[position], "normalX"));
                verticesOut.putFloat(finite(checkedNormals[position + 1], "normalY"));
                verticesOut.putFloat(finite(checkedNormals[position + 2], "normalZ"));
                verticesOut.putFloat(finite(checkedTexCoords[uv], "u"));
                verticesOut.putFloat(finite(checkedTexCoords[uv + 1], "v"));
            }
            for (int index : checkedIndices) {
                if (index < 0 || index >= vertices) {
                    throw new IllegalArgumentException("CPU-skinned upload candidate index is outside its vertex range");
                }
                indicesOut.putInt(index);
            }
            verticesOut.flip();
            indicesOut.flip();
            return new PackedGeometry(vertices, checkedIndices.length, verticesOut, indicesOut);
        }

        int vertexCount() {
            return vertexCount;
        }

        int indexCount() {
            return indexCount;
        }

        synchronized ByteBuffer vertexBytesForFutureBridge() {
            requireOpen();
            return readOnlyLittleEndian(vertexBytes);
        }

        synchronized ByteBuffer indexBytesForFutureBridge() {
            requireOpen();
            return readOnlyLittleEndian(indexBytes);
        }

        synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                vertexBytes = null;
                indexBytes = null;
            }
        }

        private static ByteBuffer readOnlyLittleEndian(ByteBuffer source) {
            ByteBuffer copy = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            copy.position(0);
            return copy;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("CPU-skinned candidate bytes have already been released");
            }
        }

        private static int checkedByteCount(int count, int width, int maximum, String label) {
            final long bytes;
            try {
                bytes = Math.multiplyExact((long) count, (long) width);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("X7 CPU-skinned " + label + " byte count overflow", exception);
            }
            if (bytes > maximum || bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("X7 CPU-skinned " + label + " bytes exceed the bounded limit");
            }
            return (int) bytes;
        }

        private static float finite(float value, String component) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("CPU-skinned upload candidate " + component + " must be finite");
            }
            return value;
        }
    }
}
