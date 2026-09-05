package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/** Static/public-bytecode proof for the T3b Minecraft 26.1.2 direct-static candidate boundary. */
class StaticDirectMinecraft2612ContractTest {
    @Test
    void candidateBuildsTheFrozen32ByteF32D1VertexLayout() {
        RenderPipeline pipeline = StaticDirectPipeline.buildCandidate();
        VertexFormat format = pipeline.getVertexFormat();

        assertEquals(StaticDirectPipeline.VERTEX_STRIDE_BYTES, format.getVertexSize());
        assertEquals(StaticDirectPipeline.POSITION_OFFSET_BYTES, format.getOffset(VertexFormatElement.POSITION));
        assertEquals(StaticDirectPipeline.NORMAL_OFFSET_BYTES, format.getOffset(StaticDirectPipeline.F32_NORMAL));
        assertEquals(StaticDirectPipeline.UV0_OFFSET_BYTES, format.getOffset(VertexFormatElement.UV0));
        assertEquals(List.of(VertexFormatElement.POSITION, StaticDirectPipeline.F32_NORMAL, VertexFormatElement.UV0),
                format.getElements());
        assertEquals(List.of("Position", "Normal", "UV0"), format.getElementAttributeNames());
        assertEquals(VertexFormatElement.Type.FLOAT, StaticDirectPipeline.F32_NORMAL.type());
        assertEquals(3, StaticDirectPipeline.F32_NORMAL.count());
        assertFalse(StaticDirectPipeline.F32_NORMAL.normalized());
        assertFalse(format.contains(VertexFormatElement.NORMAL));
        assertEquals(StaticDirectPipeline.NORMAL_OFFSET_BYTES, format.getOffsetsByElement()[StaticDirectPipeline.F32_NORMAL.id()]);
        assertTrue(pipeline.getSamplers().containsAll(List.of("Sampler0", "Sampler2")));
        assertTrue(pipeline.getUniforms().stream().anyMatch(uniform -> uniform.name().equals("X7StaticInstances")));
    }

    @Test
    void publicRenderPassBytecodeAndNativeBoundaryPinAllFourIndexedArguments() throws Exception {
        RenderPassClassFile renderPass = RenderPassClassFile.read(classBytes());
        assertEquals(List.of("baseVertex", "firstIndex", "indexCount", "instanceCount"),
                renderPass.parameterNames("drawIndexed", "(IIII)V"));

        FakeBuffers buffers = new FakeBuffers();
        StaticDirectGenerationResources resources = new StaticDirectGenerationResources(
                X7GpuTestFixtures.key(), 3, 3, 3 * StaticDirectPipeline.VERTEX_STRIDE_BYTES, 3 * Integer.BYTES, buffers);
        int[] actual = {-1, -1, -1, -1};
        StaticDirectDrawExecutor.issueIndexedDraw(
                (baseVertex, firstIndex, indexCount, instanceCount) -> {
                    actual[0] = baseVertex;
                    actual[1] = firstIndex;
                    actual[2] = indexCount;
                    actual[3] = instanceCount;
                },
                resources,
                2);
        assertArrayEquals(new int[] {0, 0, 3, 2}, actual);
        assertThrows(IllegalArgumentException.class,
                () -> StaticDirectDrawExecutor.issueIndexedDraw((a, b, c, d) -> { }, resources, 0));
    }

    @Test
    void shadersDeclareExactInstanceNormalAndVanillaLightmapInputs() throws IOException {
        Path projectDir = Path.of(System.getProperty("blendlib.projectDir"));
        Path shaderRoot = projectDir.resolve("src/client/resources/assets/blendlib/shaders/core");
        String vertex = Files.readString(shaderRoot.resolve("x7_static_direct.vsh"));
        String fragment = Files.readString(shaderRoot.resolve("x7_static_direct.fsh"));
        String pipeline = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/reload/StaticDirectPipeline.java"));
        String pose = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/reload/StaticDirectPoseSnapshot.java"));

        assertTrue(vertex.contains("#moj_import <minecraft:light.glsl>"));
        assertTrue(vertex.contains("#moj_import <minecraft:sample_lightmap.glsl>"));
        assertTrue(vertex.contains("mat3 NormalMatrix"));
        assertTrue(vertex.contains("Instances[gl_InstanceID]"));
        assertTrue(vertex.contains("instance.NormalMatrix * Normal"));
        assertTrue(vertex.contains("sample_lightmap(Sampler2, ivec2(instance.PackedLight.xy))"));
        assertTrue(fragment.contains("lightMapColor"));
        assertTrue(fragment.contains("apply_fog"));
        assertFalse(vertex.contains("ModelViewMat"));
        assertFalse(fragment.contains("lightStrength"));
        assertFalse(fragment.contains("PackedLight.x"));
        assertFalse(pipeline.contains(".register("));
        assertFalse(pose.contains("import com.mojang.blaze3d.vertex.PoseStack"));
        assertFalse(pose.contains("PoseStack pose"));
        assertTrue(pose.contains("new Matrix4f"));
        assertTrue(pose.contains("new Matrix3f"));
    }

    @Test
    void shaderUsesOneFrozenViewPositionForClipAndBothFogDistances() throws IOException {
        Path projectDir = Path.of(System.getProperty("blendlib.projectDir"));
        String vertex = Files.readString(projectDir.resolve(
                "src/client/resources/assets/blendlib/shaders/core/x7_static_direct.vsh"));

        assertTrue(vertex.contains("vec4 viewPosition = instance.ModelView * vec4(Position, 1.0);"));
        assertTrue(vertex.contains("gl_Position = ProjMat * viewPosition;"));
        assertTrue(vertex.contains("fog_spherical_distance(viewPosition.xyz)"));
        assertTrue(vertex.contains("fog_cylindrical_distance(viewPosition.xyz)"));
        assertFalse(vertex.contains("fog_spherical_distance(Position)"));
        assertFalse(vertex.contains("fog_cylindrical_distance(Position)"));

        // CPU VertexConsumer receives this same final affine pose result, not the model-local source position.
        Vector4f localPosition = new Vector4f(1.0F, 2.0F, 3.0F, 1.0F);
        Vector4f viewPosition = new Matrix4f().translation(11.0F, -3.0F, 17.0F)
                .scale(2.0F, 3.0F, 4.0F)
                .transform(new Vector4f(localPosition));
        assertEquals(13.0F, viewPosition.x());
        assertEquals(3.0F, viewPosition.y());
        assertEquals(29.0F, viewPosition.z());
        float localSphericalDistance = localPosition.lengthSquared();
        float transformedSphericalDistance = viewPosition.x() * viewPosition.x()
                + viewPosition.y() * viewPosition.y()
                + viewPosition.z() * viewPosition.z();
        float localCylindricalDistance = localPosition.x() * localPosition.x()
                + localPosition.z() * localPosition.z();
        float transformedCylindricalDistance = viewPosition.x() * viewPosition.x()
                + viewPosition.z() * viewPosition.z();
        assertFalse(localSphericalDistance == transformedSphericalDistance);
        assertFalse(localCylindricalDistance == transformedCylindricalDistance);
    }

    @Test
    void packedLightUsesOnlyExactVanillaLightmapLanesAndFailsClosedOtherwise() {
        assertEquals(0x00B000A0, StaticDirectPackedLight.requireExactlyRepresentable(0x00B000A0));
        assertEquals(0x00F000F0, StaticDirectPackedLight.requireExactlyRepresentable(0x00F000F0));
        assertThrows(IllegalArgumentException.class,
                () -> StaticDirectPackedLight.requireExactlyRepresentable(0x00B000A1));
        assertThrows(IllegalArgumentException.class,
                () -> StaticDirectPackedLight.requireExactlyRepresentable(0x010000A0));
    }

    @Test
    void resourceAssemblyFailureRollsBackTheAllocatedD1BufferBindingAndStaging() {
        X7GeometryStaging staging = X7GpuTestFixtures.staging();
        FakeBuffers buffers = new FakeBuffers();
        IllegalStateException assemblyFailure = new IllegalStateException("assembly failed");
        StaticDirectResourceFactory.Attempt attempt = StaticDirectResourceFactory.prepare(
                X7GpuTestFixtures.key(),
                staging,
                new StaticDirectResourceFactory.BufferAllocator() {
                    @Override
                    public void assertOnRenderThread() {
                    }

                    @Override
                    public StaticDirectGenerationResources.BufferBindings allocate(
                            X7GpuGenerationKey key, X7GeometryStaging ownedStaging) {
                        assertFalse(ownedStaging.isClosed());
                        return buffers;
                    }
                },
                (key, ownedStaging, allocated) -> {
                    throw assemblyFailure;
                });

        assertFalse(attempt.succeeded());
        assertEquals(assemblyFailure, attempt.failureOrNull());
        assertEquals(1, buffers.closeCalls);
        assertTrue(staging.isClosed());
    }

    private static byte[] classBytes() throws IOException {
        try (InputStream input = RenderPass.class.getResourceAsStream("RenderPass.class")) {
            assertNotNull(input, "RenderPass.class resource");
            return input.readAllBytes();
        }
    }

    private static final class FakeBuffers implements StaticDirectGenerationResources.BufferBindings {
        private int closeCalls;

        @Override
        public void bind(RenderPass pass) {
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class RenderPassClassFile {
        private final Object[] constantPool;
        private final List<Method> methods;

        private RenderPassClassFile(Object[] constantPool, List<Method> methods) {
            this.constantPool = constantPool;
            this.methods = methods;
        }

        static RenderPassClassFile read(byte[] source) throws IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(source))) {
                assertEquals(0xCAFEBABE, input.readInt(), "class magic");
                input.readUnsignedShort();
                input.readUnsignedShort();
                Object[] constantPool = readConstantPool(input);
                input.readUnsignedShort();
                input.readUnsignedShort();
                input.readUnsignedShort();
                skipInterfaces(input);
                skipMembers(input);
                return new RenderPassClassFile(constantPool, readMethods(input, constantPool));
            }
        }

        List<String> parameterNames(String name, String descriptor) {
            return methods.stream()
                    .filter(method -> method.name.equals(name) && method.descriptor.equals(descriptor))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing public RenderPass method " + name + descriptor))
                    .parameterNames;
        }

        private static Object[] readConstantPool(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            Object[] pool = new Object[count];
            for (int index = 1; index < count; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> pool[index] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> {
                        input.readLong();
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        input.readUnsignedShort();
                        input.readUnsignedShort();
                    }
                    case 15 -> {
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                    }
                    default -> throw new IOException("Unsupported class constant-pool tag");
                }
            }
            return pool;
        }

        private static void skipInterfaces(DataInputStream input) throws IOException {
            for (int index = 0, count = input.readUnsignedShort(); index < count; index++) {
                input.readUnsignedShort();
            }
        }

        private static void skipMembers(DataInputStream input) throws IOException {
            for (int index = 0, count = input.readUnsignedShort(); index < count; index++) {
                input.readUnsignedShort();
                input.readUnsignedShort();
                input.readUnsignedShort();
                skipAttributes(input, null);
            }
        }

        private static List<Method> readMethods(DataInputStream input, Object[] pool) throws IOException {
            List<Method> methods = new ArrayList<>();
            for (int index = 0, count = input.readUnsignedShort(); index < count; index++) {
                input.readUnsignedShort();
                String name = utf8(pool, input.readUnsignedShort());
                String descriptor = utf8(pool, input.readUnsignedShort());
                List<String> parameterNames = new ArrayList<>();
                int attributes = input.readUnsignedShort();
                for (int attribute = 0; attribute < attributes; attribute++) {
                    String attributeName = utf8(pool, input.readUnsignedShort());
                    int length = input.readInt();
                    if ("MethodParameters".equals(attributeName)) {
                        int parameterCount = input.readUnsignedByte();
                        for (int parameter = 0; parameter < parameterCount; parameter++) {
                            parameterNames.add(utf8(pool, input.readUnsignedShort()));
                            input.readUnsignedShort();
                        }
                        int remaining = length - 1 - parameterCount * 4;
                        if (remaining > 0) {
                            input.skipNBytes(remaining);
                        }
                    } else {
                        input.skipNBytes(length);
                    }
                }
                methods.add(new Method(name, descriptor, List.copyOf(parameterNames)));
            }
            skipAttributes(input, pool);
            return List.copyOf(methods);
        }

        private static void skipAttributes(DataInputStream input, Object[] ignored) throws IOException {
            for (int index = 0, count = input.readUnsignedShort(); index < count; index++) {
                input.readUnsignedShort();
                input.skipNBytes(input.readInt());
            }
        }

        private static String utf8(Object[] pool, int index) {
            return pool[index] instanceof String value ? value : null;
        }

        private record Method(String name, String descriptor, List<String> parameterNames) {
        }
    }
}
