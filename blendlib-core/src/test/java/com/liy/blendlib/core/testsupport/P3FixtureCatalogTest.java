package com.liy.blendlib.core.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class P3FixtureCatalogTest {
    @Test
    void glbFixturesAreDeterministicDetachedAndBounded() {
        for (P3FixtureCatalog.GlbFixture fixture : P3FixtureCatalog.GlbFixture.values()) {
            byte[] first = P3FixtureCatalog.glb(fixture);
            byte[] second = P3FixtureCatalog.glb(fixture);

            assertNotSame(first, second, fixture::name);
            assertArrayEquals(first, second, fixture::name);
            assertTrue(first.length > 0, fixture::name);
            assertTrue(first.length <= P3FixtureCatalog.MAX_FIXTURE_BYTES, fixture::name);

            first[0] ^= 0x01;
            assertArrayEquals(second, P3FixtureCatalog.glb(fixture), fixture::name);
        }
    }

    @Test
    void validTriangleHasACompleteGlbV2Container() {
        byte[] bytes = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(0x46546C67, header.getInt());
        assertEquals(2, header.getInt());
        assertEquals(bytes.length, header.getInt());
        int jsonLength = header.getInt();
        assertEquals(0x4E4F534A, header.getInt());
        assertTrue(jsonLength > 0);
        assertEquals(0, jsonLength % 4);
        assertTrue(12 + 8 + jsonLength < bytes.length);
    }

    @Test
    void malformedFixturesEncodeTheirNamedViolation() {
        byte[] invalidHeader = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.INVALID_HEADER);
        assertNotEquals(0x46546C67, littleEndianInt(invalidHeader, 0));

        byte[] declaredLength = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.DECLARED_LENGTH_MISMATCH);
        assertNotEquals(declaredLength.length, littleEndianInt(declaredLength, 8));

        byte[] chunkOutOfBounds = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.CHUNK_OUT_OF_BOUNDS);
        assertTrue(12 + 8 + littleEndianInt(chunkOutOfBounds, 12) > chunkOutOfBounds.length);

        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.ACCESSOR_OUT_OF_BOUNDS))
                .contains("\"count\":4"));
        ByteBuffer invalidIndex = ByteBuffer.wrap(binaryChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.INVALID_INDEX)))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, Short.toUnsignedInt(invalidIndex.getShort(100)));
        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.REQUIRED_EXTENSION))
                .contains("\"extensionsRequired\""));
        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.NODE_CYCLE))
                .contains("\"children\":[1]"));

        ByteBuffer nonfinite = ByteBuffer.wrap(binaryChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.NONFINITE_TRANSFORM)))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(Float.isNaN(nonfinite.getFloat(112)));
        ByteBuffer nonmonotonic = ByteBuffer.wrap(binaryChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.NONMONOTONIC_ANIMATION)))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.0F, nonmonotonic.getFloat(104));
        assertEquals(0.0F, nonmonotonic.getFloat(108));

        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.LIMIT_NODE_COUNT))
                .contains("fixture-node-4096"));
        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.LIMIT_SKIN_JOINTS))
                .contains("fixture-node-512"));
        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.LIMIT_CLIP_COUNT))
                .contains("fixture-clip-256"));
        assertTrue(jsonChunk(P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.LIMIT_HIERARCHY_DEPTH))
                .contains("depth-256"));
    }

    @Test
    void fixturesDeclareOnlyFrozenOrStableDiagnosticFamilies() {
        for (P3FixtureCatalog.GlbFixture fixture : P3FixtureCatalog.GlbFixture.values()) {
            P3FixtureCatalog.FixtureMetadata metadata = fixture.metadata();
            assertFalse(metadata.id().isBlank(), fixture::name);
            assertFalse(metadata.expectedDiagnosticFamily().isBlank(), fixture::name);
            if (metadata.valid()) {
                assertEquals("NONE", metadata.expectedDiagnosticFamily(), fixture::name);
            } else {
                assertTrue(metadata.expectedDiagnosticFamily().startsWith("BLENDLIB-"), fixture::name);
            }
        }
    }

    @Test
    void descriptorFixturesArePresentAsSelfAuthoredClasspathText() throws IOException {
        ClassLoader classLoader = P3FixtureCatalog.class.getClassLoader();
        for (P3FixtureCatalog.DescriptorFixture fixture : P3FixtureCatalog.DescriptorFixture.values()) {
            try (InputStream stream = classLoader.getResourceAsStream(fixture.resourcePath())) {
                assertNotNull(stream, fixture.resourcePath());
                String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("\"format_version\""), fixture.resourcePath());
                assertTrue(text.contains("\"materials\""), fixture.resourcePath());
                assertFalse(fixture.metadata().description().isBlank(), fixture::name);
            }
        }
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }

    private static String jsonChunk(byte[] glb) {
        int jsonLength = littleEndianInt(glb, 12);
        assertEquals(0x4E4F534A, littleEndianInt(glb, 16));
        return new String(glb, 20, jsonLength, StandardCharsets.UTF_8);
    }

    private static byte[] binaryChunk(byte[] glb) {
        int jsonLength = littleEndianInt(glb, 12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = littleEndianInt(glb, binaryHeader);
        assertEquals(0x004E4942, littleEndianInt(glb, binaryHeader + 4));
        byte[] binary = new byte[binaryLength];
        System.arraycopy(glb, binaryHeader + 8, binary, 0, binaryLength);
        return binary;
    }
}
