package com.liy.blendlib.core.testsupport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Self-authored, dependency-free inputs for strict P3 GLB and descriptor tests.
 *
 * <p>The catalog deliberately has no production-loader dependency and performs no file I/O. Every
 * returned byte array is detached, deterministic, and bounded so a test can mutate it without
 * affecting another test. The expected diagnostic values are stable families unless the frozen
 * error-code document assigns an exact code.
 */
public final class P3FixtureCatalog {
    /** Keeps all generated malformed inputs comfortably below the 64 MiB production asset limit. */
    public static final int MAX_FIXTURE_BYTES = 512 * 1024;

    public static final int MAX_NODES = 4_096;
    public static final int MAX_SKIN_JOINTS = 512;
    public static final int MAX_CLIPS = 256;
    public static final int MAX_HIERARCHY_DEPTH = 256;

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;
    private static final int GLB_HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int TRIANGLE_BUFFER_BYTE_LENGTH = 102;
    private static final int TRIANGLE_BINARY_CHUNK_LENGTH = 104;

    private P3FixtureCatalog() {}

    /** Named GLB inputs for P3 loader tests. */
    public enum GlbFixture {
        VALID_TRIANGLE(
                "valid-triangle",
                true,
                "NONE",
                "Minimal GLB 2 triangle with position, normal, UV0, U16 indices, one material, and one node."),
        INVALID_HEADER(
                "invalid-header",
                false,
                "BLENDLIB-GLB-001",
                "GLB with a non-GLB magic value."),
        DECLARED_LENGTH_MISMATCH(
                "declared-length-mismatch",
                false,
                "BLENDLIB-GLB-001",
                "Header declares more bytes than the physical archive contains."),
        CHUNK_OUT_OF_BOUNDS(
                "chunk-out-of-bounds",
                false,
                "BLENDLIB-GLB",
                "A JSON chunk declares a payload that extends beyond the declared archive length."),
        ACCESSOR_OUT_OF_BOUNDS(
                "accessor-out-of-bounds",
                false,
                "BLENDLIB-GLB-014",
                "POSITION accessor count exceeds its bufferView byte range."),
        INVALID_INDEX(
                "invalid-index",
                false,
                "BLENDLIB-GLB",
                "Triangle index references vertex 3 when only vertices 0 through 2 exist."),
        REQUIRED_EXTENSION(
                "required-extension",
                false,
                "BLENDLIB-EXT-001",
                "Archive declares an unavailable required extension."),
        NODE_CYCLE(
                "node-cycle",
                false,
                "BLENDLIB-SCENE-004",
                "Two nodes form a hierarchy cycle."),
        NONFINITE_TRANSFORM(
                "nonfinite-transform",
                false,
                "BLENDLIB-GLB",
                "Animation translation output contains IEEE-754 NaN."),
        NONMONOTONIC_ANIMATION(
                "nonmonotonic-animation",
                false,
                "BLENDLIB-ANIM-006",
                "Animation input times are 1.0 then 0.0."),
        LIMIT_NODE_COUNT(
                "limit-node-count",
                false,
                "BLENDLIB-LIMIT-001",
                "Archive contains one more node than the frozen 4,096-node hard limit."),
        LIMIT_SKIN_JOINTS(
                "limit-skin-joints",
                false,
                "BLENDLIB-LIMIT-001",
                "Skin lists one more joint than the frozen 512-joint hard limit."),
        LIMIT_CLIP_COUNT(
                "limit-clip-count",
                false,
                "BLENDLIB-LIMIT-001",
                "Archive contains one more animation clip than the frozen 256-clip hard limit."),
        LIMIT_HIERARCHY_DEPTH(
                "limit-hierarchy-depth",
                false,
                "BLENDLIB-LIMIT-001",
                "A node chain exceeds the frozen 256-level hierarchy-depth hard limit.");

        private final FixtureMetadata metadata;

        GlbFixture(String id, boolean valid, String expectedDiagnosticFamily, String description) {
            this.metadata = new FixtureMetadata(id, valid, expectedDiagnosticFamily, description);
        }

        public FixtureMetadata metadata() {
            return metadata;
        }
    }

    /** Self-authored textual descriptors available from {@code src/test/resources}. */
    public enum DescriptorFixture {
        VALID_RIGID("valid-rigid.json", true, "NONE", "Baseline strict rigid descriptor."),
        UNSAFE_FILE_URI("unsafe-file-uri.json", false, "BLENDLIB-DESC", "file URI mesh reference."),
        UNSAFE_ABSOLUTE_PATH("unsafe-absolute-path.json", false, "BLENDLIB-DESC", "Absolute mesh path."),
        UNSAFE_PARENT_PATH("unsafe-parent-path.json", false, "BLENDLIB-DESC", "Parent-traversal mesh path."),
        UNSAFE_NETWORK_URI("unsafe-network-uri.json", false, "BLENDLIB-DESC", "Network URI mesh reference."),
        UNSAFE_TEXTURE_PARENT_PATH(
                "unsafe-texture-parent-path.json", false, "BLENDLIB-DESC", "Parent-traversal texture path."),
        UNSAFE_TEXTURE_FILE_URI(
                "unsafe-texture-file-uri.json", false, "BLENDLIB-DESC", "file URI texture reference."),
        UNKNOWN_TOP_LEVEL_FIELD(
                "unknown-top-level-field.json", false, "BLENDLIB-DESC", "Unknown descriptor top-level field."),
        UNSUPPORTED_PROFILE(
                "unsupported-profile.json", false, "BLENDLIB-DESC", "Unsupported descriptor profile."),
        UNSUPPORTED_FORMAT_VERSION(
                "unsupported-format-version.json", false, "BLENDLIB-DESC-001", "Unsupported descriptor format version.");

        private final String resourcePath;
        private final FixtureMetadata metadata;

        DescriptorFixture(String fileName, boolean valid, String expectedDiagnosticFamily, String description) {
            resourcePath = "p3/fixtures/descriptors/" + fileName;
            metadata = new FixtureMetadata(fileName, valid, expectedDiagnosticFamily, description);
        }

        public String resourcePath() {
            return resourcePath;
        }

        public FixtureMetadata metadata() {
            return metadata;
        }
    }

    /** Stable, non-production expectation metadata for an individual fixture. */
    public record FixtureMetadata(
            String id, boolean valid, String expectedDiagnosticFamily, String description) {
        public FixtureMetadata {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(expectedDiagnosticFamily, "expectedDiagnosticFamily");
            Objects.requireNonNull(description, "description");
            if (id.isBlank() || expectedDiagnosticFamily.isBlank() || description.isBlank()) {
                throw new IllegalArgumentException("Fixture metadata fields must not be blank.");
            }
        }
    }

    /**
     * Returns a fresh byte array for the named fixture.
     *
     * <p>The returned archive is intentionally malformed for every enum constant except
     * {@link GlbFixture#VALID_TRIANGLE}.
     */
    public static byte[] glb(GlbFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        byte[] bytes = switch (fixture) {
            case VALID_TRIANGLE -> validTriangle();
            case INVALID_HEADER -> invalidHeader();
            case DECLARED_LENGTH_MISMATCH -> declaredLengthMismatch();
            case CHUNK_OUT_OF_BOUNDS -> chunkOutOfBounds();
            case ACCESSOR_OUT_OF_BOUNDS -> accessorOutOfBounds();
            case INVALID_INDEX -> invalidIndex();
            case REQUIRED_EXTENSION -> requiredExtension();
            case NODE_CYCLE -> nodeCycle();
            case NONFINITE_TRANSFORM -> animationGlb(AnimationFault.NONFINITE_OUTPUT, 1);
            case NONMONOTONIC_ANIMATION -> animationGlb(AnimationFault.NONMONOTONIC_TIME, 1);
            case LIMIT_NODE_COUNT -> nodeLimit();
            case LIMIT_SKIN_JOINTS -> skinJointLimit();
            case LIMIT_CLIP_COUNT -> animationGlb(AnimationFault.VALID, MAX_CLIPS + 1);
            case LIMIT_HIERARCHY_DEPTH -> hierarchyDepthLimit();
        };
        assertBounded(bytes);
        return bytes.clone();
    }

    private static byte[] validTriangle() {
        return buildGlb(
                triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 3, singleMeshNode(), ""), triangleBinary(2));
    }

    private static byte[] invalidHeader() {
        byte[] bytes = validTriangle();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x0BADF00D);
        return bytes;
    }

    private static byte[] declaredLengthMismatch() {
        byte[] bytes = validTriangle();
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(8, Math.addExact(header.getInt(8), Integer.BYTES));
        return bytes;
    }

    private static byte[] chunkOutOfBounds() {
        ByteBuffer bytes = ByteBuffer.allocate(GLB_HEADER_BYTES + CHUNK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(GLB_MAGIC);
        bytes.putInt(GLB_VERSION);
        bytes.putInt(bytes.capacity());
        bytes.putInt(Integer.BYTES);
        bytes.putInt(JSON_CHUNK_TYPE);
        return bytes.array();
    }

    private static byte[] accessorOutOfBounds() {
        return buildGlb(
                triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 4, singleMeshNode(), ""), triangleBinary(2));
    }

    private static byte[] invalidIndex() {
        return buildGlb(
                triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 3, singleMeshNode(), ""), triangleBinary(3));
    }

    private static byte[] requiredExtension() {
        String extras = ",\"extensionsUsed\":[\"BLENDLIB_fixture_unsupported\"]"
                + ",\"extensionsRequired\":[\"BLENDLIB_fixture_unsupported\"]";
        return buildGlb(
                triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 3, singleMeshNode(), extras), triangleBinary(2));
    }

    private static byte[] nodeCycle() {
        String nodes = "[{\"name\":\"CycleA\",\"mesh\":0,\"children\":[1]},"
                + "{\"name\":\"CycleB\",\"children\":[0]}]";
        return buildGlb(triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 3, nodes, ""), triangleBinary(2));
    }

    private static byte[] nodeLimit() {
        return buildGlb(
                triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 3, namedNodes(MAX_NODES + 1, false), ""),
                triangleBinary(2));
    }

    private static byte[] skinJointLimit() {
        String nodes = namedNodes(MAX_SKIN_JOINTS + 1, true);
        String extras = ",\"skins\":[{\"joints\":" + commaSeparatedIntegers(MAX_SKIN_JOINTS + 1) + "}]";
        return buildGlb(triangleJson(TRIANGLE_BUFFER_BYTE_LENGTH, 3, nodes, extras), triangleBinary(2));
    }

    private static byte[] hierarchyDepthLimit() {
        return buildGlb(
                triangleJson(
                        TRIANGLE_BUFFER_BYTE_LENGTH,
                        3,
                        hierarchyNodes(MAX_HIERARCHY_DEPTH + 1),
                        ""),
                triangleBinary(2));
    }

    private static byte[] animationGlb(AnimationFault fault, int clipCount) {
        if (clipCount < 1) {
            throw new IllegalArgumentException("clipCount must be positive");
        }
        return buildGlb(animatedTriangleJson(clipCount), animationBinary(fault));
    }

    private static String triangleJson(int bufferByteLength, int positionCount, String nodes, String extras) {
        return "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":"
                + bufferByteLength
                + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":72,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":96,\"byteLength\":6}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":"
                + positionCount
                + ",\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"materials\":[{\"name\":\"FixtureMaterial\"}],"
                + "\"meshes\":[{\"name\":\"FixtureMesh\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0,\"mode\":4}]}],"
                + "\"nodes\":"
                + nodes
                + ",\"scenes\":[{\"nodes\":[0]}],\"scene\":0"
                + extras
                + "}";
    }

    private static String animatedTriangleJson(int clipCount) {
        return "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":136}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":72,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":96,\"byteLength\":6},"
                + "{\"buffer\":0,\"byteOffset\":104,\"byteLength\":8},"
                + "{\"buffer\":0,\"byteOffset\":112,\"byteLength\":24}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\",\"min\":[0],\"max\":[1]},"
                + "{\"bufferView\":5,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"}],"
                + "\"materials\":[{\"name\":\"FixtureMaterial\"}],"
                + "\"meshes\":[{\"name\":\"FixtureMesh\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0,\"mode\":4}]}],"
                + "\"nodes\":[{\"name\":\"FixtureNode\",\"mesh\":0}],"
                + "\"scenes\":[{\"nodes\":[0]}],\"scene\":0,\"animations\":"
                + animations(clipCount)
                + "}";
    }

    private static String animations(int clipCount) {
        StringBuilder animations = new StringBuilder("[");
        for (int index = 0; index < clipCount; index++) {
            if (index > 0) {
                animations.append(',');
            }
            animations.append("{\"name\":\"fixture-clip-")
                    .append(index)
                    .append("\",\"samplers\":[{\"input\":4,\"output\":5,\"interpolation\":\"LINEAR\"}],")
                    .append("\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}");
        }
        return animations.append(']').toString();
    }

    private static String singleMeshNode() {
        return "[{\"name\":\"FixtureNode\",\"mesh\":0}]";
    }

    private static String namedNodes(int count, boolean skinFirstNode) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        StringBuilder nodes = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{\"name\":\"fixture-node-").append(index).append('"');
            if (index == 0) {
                nodes.append(",\"mesh\":0");
                if (skinFirstNode) {
                    nodes.append(",\"skin\":0");
                }
            }
            nodes.append('}');
        }
        return nodes.append(']').toString();
    }

    private static String hierarchyNodes(int count) {
        if (count < 2) {
            throw new IllegalArgumentException("hierarchy fixture needs at least two nodes");
        }
        StringBuilder nodes = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{\"name\":\"depth-").append(index).append('"');
            if (index == 0) {
                nodes.append(",\"mesh\":0");
            }
            if (index + 1 < count) {
                nodes.append(",\"children\":[").append(index + 1).append(']');
            }
            nodes.append('}');
        }
        return nodes.append(']').toString();
    }

    private static String commaSeparatedIntegers(int count) {
        StringBuilder values = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                values.append(',');
            }
            values.append(index);
        }
        return values.append(']').toString();
    }

    private static byte[] triangleBinary(int thirdIndex) {
        if (thirdIndex < 0 || thirdIndex > 0xFFFF) {
            throw new IllegalArgumentException("U16 index must be in range");
        }
        ByteBuffer binary = ByteBuffer.allocate(TRIANGLE_BINARY_CHUNK_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        putFloats(binary, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        putFloats(binary, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        putFloats(binary, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        binary.putShort((short) 0).putShort((short) 1).putShort((short) thirdIndex);
        return binary.array();
    }

    private static byte[] animationBinary(AnimationFault fault) {
        byte[] binary = Arrays.copyOf(triangleBinary(2), 136);
        ByteBuffer values = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        values.position(104);
        if (fault == AnimationFault.NONMONOTONIC_TIME) {
            values.putFloat(1.0F).putFloat(0.0F);
        } else {
            values.putFloat(0.0F).putFloat(1.0F);
        }
        if (fault == AnimationFault.NONFINITE_OUTPUT) {
            values.putFloat(Float.NaN).putFloat(0.0F).putFloat(0.0F);
        } else {
            values.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
        }
        values.putFloat(1.0F).putFloat(0.0F).putFloat(0.0F);
        return binary;
    }

    private static void putFloats(ByteBuffer output, float... values) {
        for (float value : values) {
            output.putFloat(value);
        }
    }

    private static byte[] buildGlb(String json, byte[] binary) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(binary, "binary");
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = paddedLength(jsonBytes.length);
        int paddedBinaryLength = paddedLength(binary.length);
        long totalLength = Math.addExact(GLB_HEADER_BYTES, CHUNK_HEADER_BYTES);
        totalLength = Math.addExact(totalLength, paddedJsonLength);
        if (binary.length > 0) {
            totalLength = Math.addExact(totalLength, CHUNK_HEADER_BYTES);
            totalLength = Math.addExact(totalLength, paddedBinaryLength);
        }
        if (totalLength > MAX_FIXTURE_BYTES) {
            throw new IllegalArgumentException("Fixture would exceed bounded test size: " + totalLength);
        }

        ByteBuffer output = ByteBuffer.allocate(Math.toIntExact(totalLength)).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(GLB_MAGIC);
        output.putInt(GLB_VERSION);
        output.putInt(output.capacity());
        putChunk(output, JSON_CHUNK_TYPE, jsonBytes, paddedJsonLength, (byte) 0x20);
        if (binary.length > 0) {
            putChunk(output, BIN_CHUNK_TYPE, binary, paddedBinaryLength, (byte) 0x00);
        }
        if (output.hasRemaining()) {
            throw new IllegalStateException("GLB builder did not fill its declared length");
        }
        return output.array();
    }

    private static int paddedLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        long padded = Math.addExact((long) length, 3L) & ~3L;
        return Math.toIntExact(padded);
    }

    private static void putChunk(ByteBuffer output, int type, byte[] payload, int paddedLength, byte padding) {
        output.putInt(paddedLength);
        output.putInt(type);
        output.put(payload);
        for (int index = payload.length; index < paddedLength; index++) {
            output.put(padding);
        }
    }

    private static void assertBounded(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_FIXTURE_BYTES) {
            throw new IllegalStateException("Fixture size is outside its test-support bound: " + bytes.length);
        }
    }

    private enum AnimationFault {
        VALID,
        NONFINITE_OUTPUT,
        NONMONOTONIC_TIME
    }
}
