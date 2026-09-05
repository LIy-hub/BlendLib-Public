package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class X7GeometryStagingTest {
    @Test
    void packsExactDirectLittleEndianPositionNormalUvAndUint32IndexBytes() {
        try (X7GeometryStaging staging = X7GeometryStaging.pack(
                X7GpuTestFixtures.triangle(), X7GeometryStagingLimits.STRICT_V1)) {
            ByteBuffer vertices = staging.vertexBytesForUpload();
            ByteBuffer indices = staging.indexBytesForUpload();

            assertTrue(vertices.isDirect());
            assertTrue(indices.isDirect());
            assertTrue(vertices.isReadOnly());
            assertTrue(indices.isReadOnly());
            assertEquals(ByteOrder.LITTLE_ENDIAN, vertices.order());
            assertEquals(ByteOrder.LITTLE_ENDIAN, indices.order());
            assertEquals(3 * 8 * Float.BYTES, vertices.remaining());
            assertEquals(3 * Integer.BYTES, indices.remaining());

            assertEquals(1.0f, vertices.getFloat());
            assertEquals(2.0f, vertices.getFloat());
            assertEquals(3.0f, vertices.getFloat());
            assertEquals(0.0f, vertices.getFloat());
            assertEquals(1.0f, vertices.getFloat());
            assertEquals(0.0f, vertices.getFloat());
            assertEquals(0.0f, vertices.getFloat());
            assertEquals(0.0f, vertices.getFloat());
            assertEquals(0, indices.getInt());
            assertEquals(1, indices.getInt());
            assertEquals(2, indices.getInt());
        }
    }

    @Test
    void rejectsInvalidIndicesNonFiniteComponentsAndBoundedByteOverflowBeforeAllocating() {
        X7ImmutableGeometryView invalidIndex = new X7GpuTestFixtures.ArrayGeometry(
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 3});
        assertThrows(IllegalArgumentException.class,
                () -> X7GeometryStaging.pack(invalidIndex, X7GeometryStagingLimits.STRICT_V1));

        X7ImmutableGeometryView nonFinite = new X7GpuTestFixtures.ArrayGeometry(
                new float[] {Float.NaN, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2});
        assertThrows(IllegalArgumentException.class,
                () -> X7GeometryStaging.pack(nonFinite, X7GeometryStagingLimits.STRICT_V1));

        X7ImmutableGeometryView oversized = new X7ImmutableGeometryView() {
            @Override
            public int vertexCount() {
                return Integer.MAX_VALUE;
            }

            @Override
            public int indexCount() {
                return 3;
            }

            @Override public float positionX(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float positionY(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float positionZ(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float normalX(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float normalY(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float normalZ(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float u(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public float v(int vertex) { throw new AssertionError("must not read oversized data"); }
            @Override public int index(int offset) { throw new AssertionError("must not read oversized data"); }
        };
        X7GeometryStagingLimits overflowLimits = new X7GeometryStagingLimits(
                Integer.MAX_VALUE, 3, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> X7GeometryStaging.pack(oversized, overflowLimits));
    }

    @Test
    void closeReleasesCpuOwnerAndPreventsFurtherUploadViews() {
        X7GeometryStaging staging = X7GpuTestFixtures.staging();
        assertFalse(staging.isClosed());
        staging.close();
        assertTrue(staging.isClosed());
        assertThrows(IllegalStateException.class, staging::vertexBytesForUpload);
        staging.close();
    }
}
