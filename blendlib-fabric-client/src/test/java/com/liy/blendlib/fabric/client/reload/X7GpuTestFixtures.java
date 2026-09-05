package com.liy.blendlib.fabric.client.reload;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class X7GpuTestFixtures {
    private X7GpuTestFixtures() {
    }

    static X7ImmutableGeometryView triangle() {
        return new ArrayGeometry(
                new float[] {
                    1.0f, 2.0f, 3.0f,
                    4.0f, 5.0f, 6.0f,
                    7.0f, 8.0f, 9.0f
                },
                new float[] {
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f
                },
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2});
    }

    static X7GeometryStaging staging() {
        return X7GeometryStaging.pack(triangle(), X7GeometryStagingLimits.STRICT_V1);
    }

    static X7GpuGenerationKey key() {
        return new X7GpuGenerationKey(
                17L,
                "test:model",
                "test:geometry",
                "solid/test:material",
                0,
                X7GpuVertexFormat.POSITION_NORMAL_UV_F32,
                X7PrimitiveMode.TRIANGLES,
                X7GpuIndexType.UINT32_LE);
    }

    static final class ArrayGeometry implements X7ImmutableGeometryView {
        private final float[] positions;
        private final float[] normals;
        private final float[] texCoords;
        private final int[] indices;

        ArrayGeometry(float[] positions, float[] normals, float[] texCoords, int[] indices) {
            this.positions = positions;
            this.normals = normals;
            this.texCoords = texCoords;
            this.indices = indices;
        }

        @Override
        public int vertexCount() {
            return positions.length / 3;
        }

        @Override
        public int indexCount() {
            return indices.length;
        }

        @Override
        public float positionX(int vertex) {
            return positions[vertex * 3];
        }

        @Override
        public float positionY(int vertex) {
            return positions[vertex * 3 + 1];
        }

        @Override
        public float positionZ(int vertex) {
            return positions[vertex * 3 + 2];
        }

        @Override
        public float normalX(int vertex) {
            return normals[vertex * 3];
        }

        @Override
        public float normalY(int vertex) {
            return normals[vertex * 3 + 1];
        }

        @Override
        public float normalZ(int vertex) {
            return normals[vertex * 3 + 2];
        }

        @Override
        public float u(int vertex) {
            return texCoords[vertex * 2];
        }

        @Override
        public float v(int vertex) {
            return texCoords[vertex * 2 + 1];
        }

        @Override
        public int index(int offset) {
            return indices[offset];
        }
    }

    static final class FakeGpuDevice implements X7GpuDevice {
        boolean renderOwner = true;
        Throwable assertFailure;
        Throwable vertexFailure;
        Throwable indexFailure;
        int assertCalls;
        int vertexCreates;
        int indexCreates;
        ByteBuffer vertexUpload;
        ByteBuffer indexUpload;
        final List<FakeGpuBuffer> buffers = new ArrayList<>();
        Consumer<FakeGpuBuffer> onVertexAllocated;

        @Override
        public void assertOnRenderThread() {
            assertCalls++;
            if (assertFailure != null) {
                throwUnchecked(assertFailure);
            }
            if (!renderOwner) {
                throw new IllegalStateException("fake GPU device was used outside its render owner");
            }
        }

        @Override
        public X7GpuBuffer allocateVertexUpload(String debugLabel, ByteBuffer bytes) {
            assertOnRenderThread();
            vertexCreates++;
            if (vertexFailure != null) {
                throwUnchecked(vertexFailure);
            }
            vertexUpload = bytes.duplicate().order(bytes.order());
            FakeGpuBuffer result = new FakeGpuBuffer(debugLabel);
            buffers.add(result);
            if (onVertexAllocated != null) {
                onVertexAllocated.accept(result);
            }
            return result;
        }

        @Override
        public X7GpuBuffer allocateIndexUpload(String debugLabel, ByteBuffer bytes) {
            assertOnRenderThread();
            indexCreates++;
            if (indexFailure != null) {
                throwUnchecked(indexFailure);
            }
            indexUpload = bytes.duplicate().order(bytes.order());
            FakeGpuBuffer result = new FakeGpuBuffer(debugLabel);
            buffers.add(result);
            return result;
        }
    }

    static final class FakeGpuBuffer implements X7GpuBuffer {
        final String label;
        int closeCalls;
        int isClosedCalls;
        boolean closed;
        Throwable isClosedFailure;
        Throwable closeFailure;

        FakeGpuBuffer(String label) {
            this.label = label;
        }

        @Override
        public boolean isClosed() {
            isClosedCalls++;
            if (isClosedFailure != null) {
                throwUnchecked(isClosedFailure);
            }
            return closed;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throwUnchecked(closeFailure);
            }
            closed = true;
        }
    }

    static void throwUnchecked(Throwable failure) {
        X7GpuTestFixtures.<RuntimeException>throwUnchecked0(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked0(Throwable failure) throws T {
        throw (T) failure;
    }
}
