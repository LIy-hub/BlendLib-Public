package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class X7ResourceIslandBoundaryTest {
    private static final String B3660BB = "b3660bb6007ba89847461f5f0cc3c43f4d0d7478";
    private static final String CLIENT_ARTIFACT = "minecraft-clientOnly-043a8b3edf";
    private static final String CLIENT_VERSION = "26.1.2";
    private static final String POM_SHA256 = "653dae8bc25eca9f552b164b4eb44a13c6120a6dbcaa2ceb1ce5221d228d8e28";
    private static final String RENDER_SYSTEM_SHA256 = "4de685baf0cd591e0d11f26369d56d5ea648930c612d2e3f5b2fe2c4d1943890";
    private static final String GPU_DEVICE_SHA256 = "d701cb85ffc7c30135c7efc8efbc338856311b4a77482b70071464d45ff3a4c3";
    private static final String GPU_BUFFER_SHA256 = "5c31547251f508408fc2196cabc6cbb5d1656259fb2770d316c837de13d9b52e";
    private static final String RENDER_PASS_SHA256 = "eddcde89fa7e534ce164ffa2ed4b1fd3ea1e694a020e392ed7c9f92c5ebc0906";
    private static final String INVENTORY_RESOURCE = "/x7/t2a1/b3660bb-retained-public-class-inventory.txt";
    private static final String DESCRIPTOR_RESOURCE = "/x7/t2a1/b3660bb-retained-public-javap-protected-s.txt";

    @Test
    void movedResourceIslandAndBridgeStayPackagePrivate() {
        for (Class<?> type : List.of(
                CompletedGenerationResourceSet.class,
                CompletedGenerationResourceSet.ResourceLeaf.class,
                CompletedGenerationResourceSet.PhysicalLeafClose.class,
                CompletedGenerationResourceSet.CleanupFailureSelector.class,
                CompletedGenerationResourceSet.ClaimMove.class,
                CompletedGenerationResourceSet.D1Adoption.class,
                X7GenerationResourceBridge.class,
                X7GenerationResourceBridge.AuthoritativeGenerationInventory.class,
                X7GenerationResourceBridge.FrozenSelectionSet.class,
                X7GenerationResourceBridge.FrozenSelection.class,
                X7GenerationResourceBridge.ResourceAttempt.class,
                X7GenerationResourceBridge.Composition.class,
                X7GenerationResourceBridge.Composition.Route.class,
                X7ImmutableGeometryView.class,
                X7GeometryStaging.class,
                X7GeometryStagingLimits.class,
                X7GpuBuffer.class,
                X7GpuDevice.class,
                X7Minecraft2612GpuDevice.class,
                X7GpuResourceFactory.class,
                X7GpuResourceFactory.Attempt.class,
                X7SharedGeometryResources.class,
                X7GpuGenerationKey.class,
                X7GpuVertexFormat.class,
                X7GpuIndexType.class,
                X7PrimitiveMode.class)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(type.getModifiers()), type.getName());
            assertFalse(java.lang.reflect.Modifier.isProtected(type.getModifiers()), type.getName());
        }
    }

    @Test
    void obsoleteB1LifecycleTypesAreAbsentAndNoProductionSourceReferencesThem() throws IOException {
        for (String fileName : List.of(
                "X7GpuGeneration.java",
                "X7GpuGenerationLease.java",
                "X7GenerationHoldKind.java",
                "X7GpuCloseScheduler.java",
                "X7RenderOwner.java",
                "X7Minecraft2612RenderOwner.java",
                "X7GpuGenerationDiagnostics.java")) {
            assertFalse(Files.exists(sourcePath("render", "x7gpu", fileName)), fileName);
        }

        String source = sourceTree();
        for (String forbidden : List.of(
                "X7GpuGeneration",
                "X7GpuGenerationLease",
                "X7GenerationHoldKind",
                "X7GpuCloseScheduler",
                "X7RenderOwner",
                "X7Minecraft2612RenderOwner",
                "X7GpuGenerationDiagnostics")) {
            assertFalse(java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(forbidden) + "\\b")
                    .matcher(source).find(), forbidden);
        }
    }

    @Test
    void deprecatedDirectAttachmentShimIsDeletedFromTheEntireProductionIsland() throws IOException {
        String aggregate = Files.readString(sourcePath("reload", "CompletedGenerationResourceSet.java"));
        String bridge = Files.readString(sourcePath("reload", "X7GenerationResourceBridge.java"));
        String owner = Files.readString(sourcePath("reload", "ClientGenerationResourceOwner.java"));
        assertTrue(aggregate.contains("tryClaimCallerOwnedCompleteForD1"));
        assertTrue(aggregate.contains("tryAdoptClaimOwnedCompleteByD1"));
        assertFalse(bridge.contains("tryMoveCallerOwnershipToD1"));
        assertFalse(bridge.contains("tryClaimCallerOwnedCompleteForD1"));
        assertFalse(owner.contains("tryMoveCallerOwnershipToD1"));

        long productionCallers;
        try (Stream<Path> paths = Files.walk(sourcePath())) {
            productionCallers = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(X7ResourceIslandBoundaryTest::readUnchecked)
                    .mapToLong(text -> occurrences(text, ".tryMoveCallerOwnershipToD1("))
                    .sum();
        }
        assertEquals(0L, productionCallers, "the pre-CAS D1 adoption path is the only ownership route");
    }

    @Test
    void bridgeNeverAllocatesOrPublishesAndTheResourceFactoryRemainsPolicyBlind() throws IOException {
        String bridge = Files.readString(sourcePath("reload", "X7GenerationResourceBridge.java"));
        for (String forbidden : List.of(
                "X7GpuResourceFactory.prepare(",
                "X7Minecraft2612GpuDevice.fromRenderSystem(",
                "new X7Minecraft2612GpuDevice",
                "GpuDevice",
                "RenderSystem",
                "PendingGenerationTransaction",
                "ClientGenerationResourceOwner",
                "ClientModelRegistry",
                "tryMoveCallerOwnershipToD1")) {
            assertFalse(bridge.contains(forbidden), forbidden);
        }
        assertTrue(bridge.contains("publishExactLeafPolicies"));
        assertTrue(bridge.contains("X7GenerationPerformancePlan\n"
                + "                    .prepare(binding, X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady())\n"
                + "                    .publish();"));
        assertTrue(bridge.contains("freezeCanonicalSelections"));
        assertTrue(bridge.contains("authoritativeInventory"));
        assertTrue(bridge.contains("exactGeneration.handles()"));
        assertTrue(bridge.contains("CompleteClassificationProof"));
        assertFalse(bridge.contains("CanonicalGeometry"));
        assertFalse(bridge.contains("canonicalGeometries"));
        String factory = Files.readString(sourcePath("reload", "X7GpuResourceFactory.java"));
        for (String forbidden : List.of("BackendChoice", "FallbackReason", "X7GenerationPerformancePlan")) {
            assertFalse(factory.contains(forbidden), forbidden);
        }
    }

    @Test
    void exactMinecraftPomClassHashesAndMethodDescriptorsMatchTheReviewed2612Artifact() throws IOException {
        byte[] pomBytes = Files.readAllBytes(clientPom());
        String pom = new String(pomBytes, StandardCharsets.UTF_8);
        assertEquals(POM_SHA256, sha256(pomBytes));
        assertTrue(pom.contains("<groupId>net.minecraft</groupId>"));
        assertTrue(pom.contains("<artifactId>" + CLIENT_ARTIFACT + "</artifactId>"));
        assertTrue(pom.contains("<version>" + CLIENT_VERSION + "</version>"));

        byte[] renderSystemBytes = classEntryBytes(RenderSystem.class);
        byte[] deviceBytes = classEntryBytes(GpuDevice.class);
        byte[] bufferBytes = classEntryBytes(GpuBuffer.class);
        byte[] renderPassBytes = classEntryBytes(RenderPass.class);
        assertEquals(RENDER_SYSTEM_SHA256, sha256(renderSystemBytes));
        assertEquals(GPU_DEVICE_SHA256, sha256(deviceBytes));
        assertEquals(GPU_BUFFER_SHA256, sha256(bufferBytes));
        assertEquals(RENDER_PASS_SHA256, sha256(renderPassBytes));

        Map<String, Set<String>> renderSystem = methodDescriptors(renderSystemBytes);
        assertMethod(renderSystem, "assertOnRenderThread", "()V");
        assertMethod(renderSystem, "queueFencedTask", "(Ljava/lang/Runnable;)V");
        assertMethod(renderSystem, "getDevice", "()Lcom/mojang/blaze3d/systems/GpuDevice;");
        assertMethod(renderSystem, "tryGetDevice", "()Lcom/mojang/blaze3d/systems/GpuDevice;");

        Map<String, Set<String>> device = methodDescriptors(deviceBytes);
        assertMethod(device, "createBuffer", "(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;");

        Map<String, Set<String>> buffer = methodDescriptors(bufferBytes);
        assertMethod(buffer, "isClosed", "()Z");
        assertMethod(buffer, "close", "()V");

        Map<String, Set<String>> renderPass = methodDescriptors(renderPassBytes);
        assertMethod(renderPass, "drawIndexed", "(IIII)V");
        assertMethod(renderPass, "drawMultipleIndexed", "(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Ljava/util/Collection;Ljava/lang/Object;)V");
        assertMethod(renderPass, "close", "()V");
    }

    @Test
    void resourceIslandCompiledBytecodeHasNoForbiddenRawImplementationReferences() throws Exception {
        List<Class<?>> types = new ArrayList<>(List.of(
                X7GeometryStaging.class,
                X7GpuResourceFactory.class,
                X7GpuResourceFactory.Attempt.class,
                X7Minecraft2612GpuDevice.class,
                X7GenerationResourceBridge.class,
                CompletedGenerationResourceSet.class));
        types.add(Class.forName("com.liy.blendlib.fabric.client.render.x7gpu.X7StaticBatchPlanner"));
        types.add(Class.forName("com.liy.blendlib.fabric.client.render.x7gpu.X7CpuSkinnedUploadCandidate"));
        for (Class<?> type : types) {
            String bytecode = new String(classEntryBytes(type), StandardCharsets.ISO_8859_1);
            for (String forbidden : List.of(
                    "org/lwjgl/",
                    "com/mojang/blaze3d/opengl",
                    "com/mojang/blaze3d/vertex/VertexBuffer",
                    "java/io/",
                    "java/nio/file/",
                    "java/lang/reflect/",
                    "java/util/concurrent/",
                    "java/lang/Thread")) {
                assertFalse(bytecode.contains(forbidden), type.getName() + ": " + forbidden);
            }
        }
    }

    @Test
    void b3660bbRetainedPublicDescriptorsRemainExactWithinCompositeInventory() throws Exception {
        Path classes = clientClassesRoot();
        List<String> expectedInventory = resourceLines(INVENTORY_RESOURCE);
        List<String> actualInventory = publicClassNames(classes);
        assertTrue(actualInventory.containsAll(expectedInventory), "b3660bb retained public compiled-class inventory");

        String expectedManifest = resourceText(DESCRIPTOR_RESOURCE);
        String actualManifest = descriptorManifest(classes, expectedInventory);
        for (String approvedAlphaAddition : List.of(
                "  public com.liy.blendlib.fabric.client.animation.ClientAnimationPoseSnapshot applyPoseModifier(com.liy.blendlib.fabric.client.animation.ClientAnimationPoseSnapshot, com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext, com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier);\n"
                        + "    descriptor: (Lcom/liy/blendlib/fabric/client/animation/ClientAnimationPoseSnapshot;Lcom/liy/blendlib/fabric/client/animation/runtime/ClientAnimationPoseContext;Lcom/liy/blendlib/fabric/client/animation/runtime/ClientAnimationPoseModifier;)Lcom/liy/blendlib/fabric/client/animation/ClientAnimationPoseSnapshot;\n\n",
                "  public java.util.Optional<com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeResult> extract(com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput, com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier);\n"
                        + "    descriptor: (Lcom/liy/blendlib/fabric/client/animation/runtime/SkinnedAnimationRuntimeInput;Lcom/liy/blendlib/fabric/client/animation/runtime/ClientAnimationPoseModifier;)Ljava/util/Optional;\n\n",
                "  public com.liy.blendlib.fabric.client.entity.BlendEntityRendererBuilder<E> poseModifier(com.liy.blendlib.fabric.client.entity.BlendEntityPoseModifier<? super E>);\n"
                        + "    descriptor: (Lcom/liy/blendlib/fabric/client/entity/BlendEntityPoseModifier;)Lcom/liy/blendlib/fabric/client/entity/BlendEntityRendererBuilder;\n\n",
                "  public com.liy.blendlib.fabric.client.entity.BlendEntityRendererBuilder<E> rootRotation(com.liy.blendlib.fabric.client.entity.BlendEntityRootRotationSelector<? super E>);\n"
                        + "    descriptor: (Lcom/liy/blendlib/fabric/client/entity/BlendEntityRootRotationSelector;)Lcom/liy/blendlib/fabric/client/entity/BlendEntityRendererBuilder;\n\n")) {
            assertTrue(actualManifest.contains(approvedAlphaAddition), approvedAlphaAddition);
            actualManifest = actualManifest.replace(approvedAlphaAddition, "");
        }
        assertEquals(expectedManifest, actualManifest, "b3660bb javap -protected -s descriptor manifest");
        for (String requiredPin : List.of(
                "public final class com.liy.blendlib.fabric.client.reload.ClientModelRegistry",
                "public final class com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration",
                "public final class com.liy.blendlib.fabric.client.reload.ClientModelReloadListener",
                "descriptor: (Lcom/liy/blendlib/fabric/client/reload/ClientModelRegistry;)V")) {
            assertTrue(expectedManifest.contains(requiredPin), requiredPin);
        }
    }

    @Test
    void nonClientCompiledOutputsCannotLeakResourceIslandGpuOrFenceTypes() throws IOException {
        Path repositoryRoot = projectDirectory().getParent();
        for (String module : List.of("blendlib-api", "blendlib-core", "blendlib-fabric-common")) {
            Path output = repositoryRoot.resolve(module).resolve("build").resolve("classes").resolve("java").resolve("main");
            assertTrue(Files.isDirectory(output), "compiled output missing for " + module);
            assertNoLeakage(output, module);
        }
        Path serverModule = repositoryRoot.resolve("blendlib-fabric-server");
        assertFalse(Files.exists(serverModule), "this repository has no server module; server compiled-output scan is N/A");
    }

    private static void assertNoLeakage(Path output, String module) throws IOException {
        try (Stream<Path> paths = Files.walk(output)) {
            for (Path classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                String bytecode = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of(
                        "com/liy/blendlib/fabric/client/reload/CompletedGenerationResourceSet",
                        "com/liy/blendlib/fabric/client/reload/X7GenerationResourceBridge",
                        "com/liy/blendlib/fabric/client/reload/X7GpuDevice",
                        "com/liy/blendlib/fabric/client/reload/X7GpuBuffer",
                        "com/liy/blendlib/fabric/client/reload/X7Minecraft2612GpuDevice",
                        "com/liy/blendlib/fabric/client/reload/X7GpuGeneration",
                        "com/liy/blendlib/fabric/client/reload/ClientFinalFrameShutdownCoordinator",
                        "com/mojang/blaze3d/")) {
                    assertFalse(bytecode.contains(forbidden), module + ": " + classFile.getFileName() + ": " + forbidden);
                }
            }
        }
    }

    private static Path sourcePath(String... relative) {
        Path root = projectDirectory().resolve("src").resolve("client").resolve("java")
                .resolve("com").resolve("liy").resolve("blendlib").resolve("fabric").resolve("client");
        for (String segment : relative) {
            root = root.resolve(segment);
        }
        return root;
    }

    private static Path projectDirectory() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }

    private static Path clientClassesRoot() {
        Path classes = projectDirectory().resolve("build").resolve("classes").resolve("java").resolve("client");
        assertTrue(Files.isDirectory(classes), "fabric-client compiled class root is missing");
        return classes;
    }

    private static Path clientPom() {
        return projectDirectory().getParent().resolve(".gradle")
                .resolve("loom-cache")
                .resolve("minecraftMaven")
                .resolve("net")
                .resolve("minecraft")
                .resolve(CLIENT_ARTIFACT)
                .resolve(CLIENT_VERSION)
                .resolve(CLIENT_ARTIFACT + "-" + CLIENT_VERSION + ".pom");
    }

    private static String sourceTree() throws IOException {
        try (Stream<Path> paths = Files.walk(sourcePath())) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(X7ResourceIslandBoundaryTest::readUnchecked)
                    .reduce("", String::concat);
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read X7 production source: " + path, exception);
        }
    }

    private static long occurrences(String text, String needle) {
        long count = 0L;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static byte[] classEntryBytes(Class<?> type) throws IOException {
        String name = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing class entry: " + name);
            }
            return input.readAllBytes();
        }
    }

    private static void assertMethod(Map<String, Set<String>> methods, String name, String descriptor) {
        assertTrue(methods.getOrDefault(name, Set.of()).contains(descriptor), name + descriptor);
    }

    private static Map<String, Set<String>> methodDescriptors(byte[] classBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("not a JVM class entry");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            String[] utf8 = readUtf8ConstantPool(input);
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            skipUnsignedShortEntries(input);
            skipMembers(input);
            int methodCount = input.readUnsignedShort();
            Map<String, Set<String>> methods = new HashMap<>();
            for (int index = 0; index < methodCount; index++) {
                input.readUnsignedShort();
                String name = utf8[input.readUnsignedShort()];
                String descriptor = utf8[input.readUnsignedShort()];
                methods.computeIfAbsent(name, ignored -> new HashSet<>()).add(descriptor);
                skipAttributes(input);
            }
            return methods;
        }
    }

    private static List<String> publicClassNames(Path classesRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(classesRoot)) {
            return paths.filter(path -> path.toString().endsWith(".class"))
                    .filter(X7ResourceIslandBoundaryTest::isPublicClass)
                    .map(path -> className(classesRoot, path))
                    .sorted()
                    .toList();
        }
    }

    private static boolean isPublicClass(Path classFile) {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(classFile))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("not a JVM class entry");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            readUtf8ConstantPool(input);
            return (input.readUnsignedShort() & java.lang.reflect.Modifier.PUBLIC) != 0;
        } catch (IOException exception) {
            throw new IllegalStateException("could not inspect class access flags: " + classFile, exception);
        }
    }

    private static String className(Path classesRoot, Path classFile) {
        String relative = classesRoot.relativize(classFile).toString().replace('\\', '.').replace('/', '.');
        return relative.substring(0, relative.length() - ".class".length());
    }

    private static String descriptorManifest(Path classesRoot, List<String> classNames) throws Exception {
        Path javap = javapExecutable();
        StringBuilder manifest = new StringBuilder()
                .append("# baseline=").append(B3660BB).append('\n')
                .append("# javap=-protected -s\n")
                .append("# public-class-count=").append(classNames.size()).append("\n\n");
        for (int start = 0; start < classNames.size(); start += 24) {
            int end = Math.min(start + 24, classNames.size());
            List<String> command = new ArrayList<>();
            command.add(javap.toString());
            command.add("-protected");
            command.add("-s");
            command.add("-classpath");
            command.add(classesRoot.toString());
            command.addAll(classNames.subList(start, end));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            manifest.append(normalizeJavap(output));
        }
        return manifest.toString();
    }

    private static Path javapExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javap.exe" : "javap";
        Path javap = Path.of(System.getProperty("java.home"), "bin", executable);
        assertTrue(Files.isRegularFile(javap), "JDK javap is required for the compiled ABI manifest: " + javap);
        return javap;
    }

    private static String normalizeJavap(String output) {
        String normalized = output.replace("\r\n", "\n").strip();
        return normalized.isEmpty() ? "" : normalized + "\n";
    }

    private static List<String> resourceLines(String resource) throws IOException {
        return resourceText(resource).lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private static String resourceText(String resource) throws IOException {
        try (InputStream input = X7ResourceIslandBoundaryTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing test resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String[] readUtf8ConstantPool(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int index = 1; index < count; index++) {
            switch (input.readUnsignedByte()) {
                case 1 -> utf8[index] = input.readUTF();
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
                default -> throw new IOException("unexpected class constant-pool tag");
            }
        }
        return utf8;
    }

    private static void skipUnsignedShortEntries(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort();
        }
    }

    private static void skipMembers(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            skipAttributes(input);
        }
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort();
            input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
