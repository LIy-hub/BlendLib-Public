package com.liy.blendlib.showcase.perf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit generator for the P7 reference resource-pack bundle.
 *
 * <p>It is intentionally not wired into {@code processResources}: the 1.5-million-triangle
 * assets must not silently load in ordinary Showcase startup. The explicit Gradle task emits a
 * deterministic bundle that a tester can copy only into an isolated client run before a real
 * capture. No generated output is a performance result.</p>
 */
public final class P7ReferenceAssetGenerator {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;
    private static final int ARRAY_BUFFER = 34_962;
    private static final int ELEMENT_ARRAY_BUFFER = 34_963;
    private static final int FLOAT = 5_126;
    private static final int UNSIGNED_SHORT = 5_123;
    private static final int UNSIGNED_BYTE = 5_121;
    private static final String IDENTITY_TRS = "\"translation\":[0.0,0.0,0.0],\"rotation\":[0.0,0.0,0.0,1.0],\"scale\":[1.0,1.0,1.0]";

    private P7ReferenceAssetGenerator() {
    }

    /** Writes an unbundled resource-pack root to the one explicit output directory. */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: P7ReferenceAssetGenerator <output-resource-pack-root>");
        }
        writeBundle(Path.of(arguments[0]));
    }

    /**
     * Generates the strict GLB payload for one frozen reference asset. The resulting arrays are
     * newly allocated and safe for structural fixture checks; this method is never a render hot
     * path.
     */
    public static GeneratedAsset generate(P7ReferenceScenario.Asset asset) {
        P7ReferenceScenario.Asset checked = Objects.requireNonNull(asset, "asset");
        return switch (checked.kind()) {
            case RIGID -> generateRigid(checked);
            case SKINNED -> generateSkinned(checked);
        };
    }

    /**
     * Emits descriptor, GLB, and manifest files under an isolated resource-pack root. Existing
     * files in that explicit root are replaced only by the generator's own fixed P7 paths.
     */
    public static void writeBundle(Path outputRoot) throws IOException {
        Path root = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();
        Path namespaceRoot = root.resolve("assets").resolve("blendlib_showcase");
        Path descriptorDirectory = namespaceRoot.resolve("blend_models").resolve("p7");
        Path meshDirectory = namespaceRoot.resolve("models3d").resolve("p7");
        Path manifestDirectory = namespaceRoot.resolve("p7");
        Files.createDirectories(descriptorDirectory);
        Files.createDirectories(meshDirectory);
        Files.createDirectories(manifestDirectory);
        Files.writeString(root.resolve("pack.mcmeta"), "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": 84,\n"
                + "    \"min_format\": 84,\n"
                + "    \"max_format\": 84,\n"
                + "    \"description\": \"BlendLib P7 isolated deterministic performance reference fixture\"\n"
                + "  }\n"
                + "}\n", StandardCharsets.UTF_8);
        for (P7ReferenceScenario.Asset asset : scenario.assets()) {
            GeneratedAsset generated = generate(asset);
            String fileStem = fileStem(asset);
            Files.write(meshDirectory.resolve(fileStem + ".glb"), generated.glb());
            Files.writeString(descriptorDirectory.resolve(fileStem + ".json"), descriptorJson(asset), StandardCharsets.UTF_8);
        }
        Files.writeString(manifestDirectory.resolve("reference-scene.json"), scenario.canonicalManifestJson(), StandardCharsets.UTF_8);
    }

    private static GeneratedAsset generateRigid(P7ReferenceScenario.Asset asset) {
        int vertices = Math.multiplyExact(asset.trianglesPerInstance(), 3);
        requireUnsignedShortVertexCount(vertices);
        List<BinaryView> views = new ArrayList<>();
        int offset = 0;
        offset = addView(views, offset, "POSITION", Math.multiplyExact(vertices, 3 * Float.BYTES), ARRAY_BUFFER);
        offset = addView(views, offset, "NORMAL", Math.multiplyExact(vertices, 3 * Float.BYTES), ARRAY_BUFFER);
        offset = addView(views, offset, "TEXCOORD_0", Math.multiplyExact(vertices, 2 * Float.BYTES), ARRAY_BUFFER);
        addView(views, offset, "INDICES", Math.multiplyExact(vertices, Short.BYTES), ELEMENT_ARRAY_BUFFER);
        ByteBuffer binary = ByteBuffer.allocate(totalLength(views)).order(ByteOrder.LITTLE_ENDIAN);
        writePositions(binary, asset.trianglesPerInstance());
        writeNormals(binary, vertices);
        writeUvs(binary, vertices);
        writeSequentialIndices(binary, vertices);
        requireFullyWritten(binary);
        return new GeneratedAsset(asset, wrapGlb(gltfJson(asset, vertices, views), binary.array()), vertices, vertices, 0);
    }

    private static GeneratedAsset generateSkinned(P7ReferenceScenario.Asset asset) {
        int vertices = Math.multiplyExact(asset.trianglesPerInstance(), 3);
        requireUnsignedShortVertexCount(vertices);
        List<BinaryView> views = new ArrayList<>();
        int offset = 0;
        offset = addView(views, offset, "POSITION", Math.multiplyExact(vertices, 3 * Float.BYTES), ARRAY_BUFFER);
        offset = addView(views, offset, "NORMAL", Math.multiplyExact(vertices, 3 * Float.BYTES), ARRAY_BUFFER);
        offset = addView(views, offset, "TEXCOORD_0", Math.multiplyExact(vertices, 2 * Float.BYTES), ARRAY_BUFFER);
        offset = addView(views, offset, "JOINTS_0", Math.multiplyExact(vertices, 4), ARRAY_BUFFER);
        offset = addView(views, offset, "WEIGHTS_0", Math.multiplyExact(vertices, 4 * Float.BYTES), ARRAY_BUFFER);
        offset = addView(views, offset, "INDICES", Math.multiplyExact(vertices, Short.BYTES), ELEMENT_ARRAY_BUFFER);
        offset = addView(views, offset, "INVERSE_BIND", Math.multiplyExact(asset.jointsPerInstance(), 16 * Float.BYTES), 0);
        offset = addView(views, offset, "ANIMATION_TIMES", 2 * Float.BYTES, 0);
        addView(views, offset, "ANIMATION_ROTATIONS", 2 * 4 * Float.BYTES, 0);
        ByteBuffer binary = ByteBuffer.allocate(totalLength(views)).order(ByteOrder.LITTLE_ENDIAN);
        writePositions(binary, asset.trianglesPerInstance());
        writeNormals(binary, vertices);
        writeUvs(binary, vertices);
        writeJoints(binary, vertices, asset.jointsPerInstance());
        writeWeights(binary, vertices);
        writeSequentialIndices(binary, vertices);
        writeIdentityMatrices(binary, asset.jointsPerInstance());
        binary.putFloat(0.0f).putFloat(1.0f);
        binary.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        binary.putFloat(0.0f).putFloat(0.38268343f).putFloat(0.0f).putFloat(0.9238795f);
        requireFullyWritten(binary);
        return new GeneratedAsset(asset, wrapGlb(gltfJson(asset, vertices, views), binary.array()), vertices, vertices,
                asset.jointsPerInstance());
    }

    private static int addView(List<BinaryView> views, int offset, String name, int length, int target) {
        if (length <= 0 || offset % 4 != 0) {
            throw new IllegalArgumentException("P7 generated binary views must be non-empty and four-byte aligned");
        }
        views.add(new BinaryView(name, offset, length, target));
        return Math.addExact(offset, length);
    }

    private static int totalLength(List<BinaryView> views) {
        BinaryView last = views.getLast();
        return Math.addExact(last.offset(), last.length());
    }

    private static void writePositions(ByteBuffer binary, int triangles) {
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(triangles)));
        for (int triangle = 0; triangle < triangles; triangle++) {
            float x = (triangle % columns) / (float) columns;
            float y = (triangle / columns) / (float) columns;
            binary.putFloat(x).putFloat(y).putFloat(0.0f);
            binary.putFloat(x + 0.004f).putFloat(y).putFloat(0.0f);
            binary.putFloat(x).putFloat(y + 0.004f).putFloat(0.0f);
        }
    }

    private static void writeNormals(ByteBuffer binary, int vertices) {
        for (int vertex = 0; vertex < vertices; vertex++) {
            binary.putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        }
    }

    private static void writeUvs(ByteBuffer binary, int vertices) {
        for (int vertex = 0; vertex < vertices; vertex++) {
            binary.putFloat((vertex % 3) * 0.5f).putFloat((vertex / 3 % 2) * 1.0f);
        }
    }

    private static void writeJoints(ByteBuffer binary, int vertices, int joints) {
        for (int vertex = 0; vertex < vertices; vertex++) {
            binary.put((byte) (vertex % joints)).put((byte) 0).put((byte) 0).put((byte) 0);
        }
    }

    private static void writeWeights(ByteBuffer binary, int vertices) {
        for (int vertex = 0; vertex < vertices; vertex++) {
            binary.putFloat(1.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        }
    }

    private static void writeSequentialIndices(ByteBuffer binary, int vertices) {
        for (int vertex = 0; vertex < vertices; vertex++) {
            binary.putShort((short) vertex);
        }
    }

    private static void writeIdentityMatrices(ByteBuffer binary, int count) {
        for (int matrix = 0; matrix < count; matrix++) {
            for (int element = 0; element < 16; element++) {
                binary.putFloat(element % 5 == 0 ? 1.0f : 0.0f);
            }
        }
    }

    private static void requireFullyWritten(ByteBuffer binary) {
        if (binary.hasRemaining()) {
            throw new IllegalStateException("P7 binary writer did not fill its declared GLB buffer");
        }
    }

    private static void requireUnsignedShortVertexCount(int vertices) {
        if (vertices <= 0 || vertices > 65_535) {
            throw new IllegalArgumentException("P7 fixture uses U16 indices and needs 1..65535 vertices");
        }
    }

    private static byte[] wrapGlb(String json, byte[] binary) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int alignedJsonLength = align4(jsonBytes.length);
        int alignedBinaryLength = align4(binary.length);
        int totalLength = Math.addExact(12, Math.addExact(8 + alignedJsonLength, 8 + alignedBinaryLength));
        ByteBuffer output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(GLB_MAGIC).putInt(GLB_VERSION).putInt(totalLength);
        output.putInt(alignedJsonLength).putInt(JSON_CHUNK_TYPE).put(jsonBytes);
        while (output.position() < 20 + alignedJsonLength) {
            output.put((byte) 0x20);
        }
        output.putInt(alignedBinaryLength).putInt(BIN_CHUNK_TYPE).put(binary);
        while (output.hasRemaining()) {
            output.put((byte) 0);
        }
        return output.array();
    }

    private static int align4(int value) {
        return Math.addExact(value, 3) & ~3;
    }

    private static String gltfJson(P7ReferenceScenario.Asset asset, int vertices, List<BinaryView> views) {
        String materialName = materialName(asset);
        StringBuilder builder = new StringBuilder(4_096);
        builder.append('{')
                .append("\"asset\":{\"version\":\"2.0\",\"generator\":\"BlendLib P7 deterministic reference generator\"},")
                .append("\"scene\":0,")
                .append("\"scenes\":[{\"nodes\":");
        if (asset.kind() == P7ReferenceScenario.Kind.RIGID) {
            builder.append("[0]}],");
        } else {
            builder.append("[0,1]}],");
        }
        appendNodes(builder, asset);
        builder.append(',')
                .append("\"meshes\":[{\"name\":\"").append(asset.kind().manifestName()).append("_reference_mesh\",")
                .append("\"primitives\":[{\"attributes\":{")
                .append("\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2");
        int indexAccessor;
        if (asset.kind() == P7ReferenceScenario.Kind.SKINNED) {
            builder.append(",\"JOINTS_0\":3,\"WEIGHTS_0\":4");
            indexAccessor = 5;
        } else {
            indexAccessor = 3;
        }
        builder.append("},\"indices\":").append(indexAccessor).append(",\"material\":0,\"mode\":4}]}],")
                .append("\"materials\":[{\"name\":\"").append(materialName).append("\"}],");
        if (asset.kind() == P7ReferenceScenario.Kind.SKINNED) {
            appendSkin(builder, asset.jointsPerInstance());
            builder.append(',');
            appendAnimation(builder);
            builder.append(',');
        }
        appendBufferViews(builder, views);
        builder.append(',');
        appendAccessors(builder, asset, vertices);
        builder.append(",\"buffers\":[{\"byteLength\":").append(totalLength(views)).append("}]")
                .append('}');
        return builder.toString();
    }

    private static void appendNodes(StringBuilder builder, P7ReferenceScenario.Asset asset) {
        builder.append("\"nodes\":[");
        if (asset.kind() == P7ReferenceScenario.Kind.RIGID) {
            builder.append("{\"name\":\"P7RigidMesh\",\"mesh\":0,").append(IDENTITY_TRS).append('}');
        } else {
            builder.append("{\"name\":\"P7SkinnedMesh\",\"mesh\":0,\"skin\":0,")
                    .append(IDENTITY_TRS).append("},")
                    // The benchmark must never depend on a loader's absent-TRS fallback. Every
                    // weighted joint declares positive uniform identity scale explicitly, keeping
                    // its normal transform invertible before and during the loop clip.
                    .append("{\"name\":\"P7Joint00\",").append(IDENTITY_TRS).append(",\"children\":[");
            for (int joint = 2; joint <= asset.jointsPerInstance(); joint++) {
                builder.append(joint);
                if (joint < asset.jointsPerInstance()) {
                    builder.append(',');
                }
            }
            builder.append("]}");
            for (int joint = 1; joint < asset.jointsPerInstance(); joint++) {
                builder.append(",{\"name\":\"P7Joint");
                if (joint < 10) {
                    builder.append('0');
                }
                builder.append(joint).append("\",").append(IDENTITY_TRS).append('}');
            }
        }
        builder.append(']');
    }

    private static void appendSkin(StringBuilder builder, int joints) {
        builder.append("\"skins\":[{\"name\":\"P7Skin64\",\"skeleton\":1,\"joints\":[");
        for (int joint = 1; joint <= joints; joint++) {
            builder.append(joint);
            if (joint < joints) {
                builder.append(',');
            }
        }
        builder.append("],\"inverseBindMatrices\":6}]");
    }

    private static void appendAnimation(StringBuilder builder) {
        builder.append("\"animations\":[{\"name\":\"p7_loop\",\"samplers\":[{")
                .append("\"input\":7,\"output\":8,\"interpolation\":\"LINEAR\"}],")
                .append("\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"rotation\"}}]}]");
    }

    private static void appendBufferViews(StringBuilder builder, List<BinaryView> views) {
        builder.append("\"bufferViews\":[");
        for (int index = 0; index < views.size(); index++) {
            BinaryView view = views.get(index);
            builder.append("{\"buffer\":0,\"byteOffset\":").append(view.offset())
                    .append(",\"byteLength\":").append(view.length());
            if (view.target() != 0) {
                builder.append(",\"target\":").append(view.target());
            }
            builder.append('}');
            if (index + 1 < views.size()) {
                builder.append(',');
            }
        }
        builder.append(']');
    }

    private static void appendAccessors(StringBuilder builder, P7ReferenceScenario.Asset asset, int vertices) {
        float[] positionMaximum = positionMaximum(vertices / 3);
        builder.append("\"accessors\":[")
                .append(accessorWithBounds(0, FLOAT, vertices, "VEC3", "[0.0,0.0,0.0]",
                        "[" + positionMaximum[0] + "," + positionMaximum[1] + ",0.0]")).append(',')
                .append(accessor(1, FLOAT, vertices, "VEC3")).append(',')
                .append(accessor(2, FLOAT, vertices, "VEC2"));
        if (asset.kind() == P7ReferenceScenario.Kind.RIGID) {
            builder.append(',').append(accessor(3, UNSIGNED_SHORT, vertices, "SCALAR"));
        } else {
            builder.append(',').append(accessor(3, UNSIGNED_BYTE, vertices, "VEC4"))
                    .append(',').append(accessor(4, FLOAT, vertices, "VEC4"))
                    .append(',').append(accessor(5, UNSIGNED_SHORT, vertices, "SCALAR"))
                    .append(',').append(accessor(6, FLOAT, asset.jointsPerInstance(), "MAT4"))
                    .append(',').append(accessorWithBounds(7, FLOAT, 2, "SCALAR", "[0.0]", "[1.0]"))
                    .append(',').append(accessor(8, FLOAT, 2, "VEC4"));
        }
        builder.append(']');
    }

    private static String accessor(int bufferView, int componentType, int count, String type) {
        return "{\"bufferView\":" + bufferView + ",\"componentType\":" + componentType
                + ",\"count\":" + count + ",\"type\":\"" + type + "\"}";
    }

    private static String accessorWithBounds(
            int bufferView, int componentType, int count, String type, String minimum, String maximum) {
        String accessor = accessor(bufferView, componentType, count, type);
        return accessor.substring(0, accessor.length() - 1) + ",\"min\":" + minimum + ",\"max\":" + maximum + "}";
    }

    private static float[] positionMaximum(int triangles) {
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(triangles)));
        int highestColumn = triangles > columns ? columns - 1 : triangles - 1;
        int highestRow = (triangles - 1) / columns;
        return new float[] {
                highestColumn / (float) columns + 0.004f,
                highestRow / (float) columns + 0.004f
        };
    }

    private static String descriptorJson(P7ReferenceScenario.Asset asset) {
        String materialName = materialName(asset);
        StringBuilder builder = new StringBuilder(1_024);
        builder.append("{\n")
                .append("  \"format_version\": 1,\n")
                .append("  \"profile\": \"").append(asset.profile()).append("\",\n")
                .append("  \"units_per_block\": 1.0,\n")
                .append("  \"mesh\": \"blendlib_showcase:").append(asset.generatedGlbPath()).append("\",\n")
                .append("  \"materials\": {\n")
                .append("    \"").append(materialName).append("\": {\n")
                .append("      \"base_color\": \"minecraft:textures/block/stone.png\",\n")
                .append("      \"mode\": \"opaque\",\n")
                .append("      \"double_sided\": false,\n")
                .append("      \"emissive\": false\n")
                .append("    }\n")
                .append("  },\n")
                .append("  \"extensions\": {},\n")
                .append("  \"extensions_used\": [],\n")
                .append("  \"extensions_required\": []");
        if (asset.kind() == P7ReferenceScenario.Kind.SKINNED) {
            builder.append(",\n")
                    .append("  \"animation\": {\n")
                    .append("    \"initial_state\": \"blendlib_showcase:p7_loop\",\n")
                    .append("    \"states\": {\n")
                    .append("      \"blendlib_showcase:p7_loop\": {\n")
                    .append("        \"clip\": \"p7_loop\",\n")
                    .append("        \"loop\": true,\n")
                    .append("        \"speed\": 1.0\n")
                    .append("      }\n")
                    .append("    }\n")
                    .append("  }");
        }
        builder.append("\n}\n");
        return builder.toString();
    }

    private static String materialName(P7ReferenceScenario.Asset asset) {
        return asset.kind() == P7ReferenceScenario.Kind.RIGID ? "P7RigidSurface" : "P7SkinnedSurface";
    }

    private static String fileStem(P7ReferenceScenario.Asset asset) {
        return asset.kind() == P7ReferenceScenario.Kind.RIGID ? "rigid_10k" : "skinned_20k_64j";
    }

    /** Immutable structural observation of an explicit generated asset. */
    public record GeneratedAsset(
            P7ReferenceScenario.Asset contract,
            byte[] glb,
            int vertexCount,
            int indexCount,
            int skinJointCount) {
        public GeneratedAsset {
            contract = Objects.requireNonNull(contract, "contract");
            glb = Objects.requireNonNull(glb, "glb").clone();
            if (vertexCount <= 0 || indexCount <= 0 || skinJointCount < 0) {
                throw new IllegalArgumentException("P7 generated asset counts are invalid");
            }
        }

        @Override
        public byte[] glb() {
            return glb.clone();
        }
    }

    private record BinaryView(String name, int offset, int length, int target) {
        private BinaryView {
            name = Objects.requireNonNull(name, "name");
            if (offset < 0 || length <= 0) {
                throw new IllegalArgumentException("P7 binary view is invalid");
            }
        }
    }
}
