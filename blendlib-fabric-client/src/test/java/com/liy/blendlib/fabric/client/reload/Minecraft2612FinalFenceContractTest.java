package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.junit.jupiter.api.Test;

class Minecraft2612FinalFenceContractTest {
    private static final String MOJANG_RUNTIME_MINECRAFT_SHA256 =
            "ac3890b42c594a1ff7bf3c3ec945c85cccbd8cb076711d55a20181ed3dd99a7a";
    private static final String LOOM_PROCESSED_MINECRAFT_SHA256 =
            "85aace61cced0d3388e3c53f8ced84096031d1b94e5cde662f00cc90f1e28d7c";
    private static final String RENDER_SYSTEM_SHA256 =
            "4de685baf0cd591e0d11f26369d56d5ea648930c612d2e3f5b2fe2c4d1943890";
    private static final String GPU_DEVICE_SHA256 =
            "d701cb85ffc7c30135c7efc8efbc338856311b4a77482b70071464d45ff3a4c3";
    private static final String COMMAND_ENCODER_SHA256 =
            "95721afcd36244f420e244e3ddbff600515cdf4c9452877bc5379c5ee606154a";
    private static final String GPU_FENCE_SHA256 =
            "8dee076aa45458d39cb751031caa58e3419d1461a2fea967b4737749dd6dd306";
    private static final String FABRIC_MINECRAFT_MIXIN_SHA256 =
            "bd720cf3f709f6742c2813ec0fa4b0214884cfa38233403a3e89b7393d39ebe2";
    private static final String FABRIC_MINECRAFT_MIXIN_RESOURCE =
            "net/fabricmc/fabric/mixin/event/lifecycle/client/MinecraftMixin.class";

    @Test
    void pinnedMinecraftRenderFrameHasExactlyOneOriginalFlipFrameInvoke() throws IOException {
        byte[] minecraft = classBytes(Minecraft.class);
        assertEquals(LOOM_PROCESSED_MINECRAFT_SHA256, sha256(minecraft));
        assertTrue(Minecraft2612OwnedFinalFenceAdapter.matchesPinnedMinecraftClass(minecraft));
        assertEquals(
                MOJANG_RUNTIME_MINECRAFT_SHA256,
                Minecraft2612OwnedFinalFenceAdapter.PINNED_MOJANG_RUNTIME_MINECRAFT_CLASS_SHA256);
        assertEquals(
                LOOM_PROCESSED_MINECRAFT_SHA256,
                Minecraft2612OwnedFinalFenceAdapter.PINNED_LOOM_PROCESSED_MINECRAFT_CLASS_SHA256);
        assertTrue(Minecraft2612OwnedFinalFenceAdapter.matchesPinnedMinecraftClassSha256(
                MOJANG_RUNTIME_MINECRAFT_SHA256));
        assertTrue(Minecraft2612OwnedFinalFenceAdapter.matchesPinnedMinecraftClassSha256(
                LOOM_PROCESSED_MINECRAFT_SHA256));

        ClassModel model = ClassFile.of().parse(minecraft);
        List<MethodModel> renderFrames = model.methods().stream()
                .filter(method -> method.methodName().equalsString("renderFrame"))
                .filter(method -> method.methodType().equalsString("(Z)V"))
                .toList();
        assertEquals(1, renderFrames.size());
        long flipFrameInvokes = renderFrames.getFirst().code().orElseThrow().elementStream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .filter(invoke -> invoke.owner().asInternalName()
                        .equals("com/mojang/blaze3d/systems/RenderSystem"))
                .filter(invoke -> invoke.name().equalsString("flipFrame"))
                .filter(invoke -> invoke.type().equalsString(
                        "(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
                .count();
        assertEquals(1L, flipFrameInvokes);

        byte[] changed = minecraft.clone();
        changed[changed.length - 1] ^= 1;
        assertFalse(Minecraft2612OwnedFinalFenceAdapter.matchesPinnedMinecraftClass(changed));
    }

    @Test
    void pinnedFenceAndFabricStoppingDescriptorsRemainExact() throws IOException {
        assertPinnedClass(RenderSystem.class, RENDER_SYSTEM_SHA256);
        assertPinnedClass(GpuDevice.class, GPU_DEVICE_SHA256);
        assertPinnedClass(CommandEncoder.class, COMMAND_ENCODER_SHA256);
        assertPinnedClass(GpuFence.class, GPU_FENCE_SHA256);

        assertMethod(RenderSystem.class, "queueFencedTask", "(Ljava/lang/Runnable;)V");
        assertMethod(RenderSystem.class, "executePendingTasks", "()V");
        assertMethod(GpuDevice.class, "createCommandEncoder", "()Lcom/mojang/blaze3d/systems/CommandEncoder;");
        assertMethod(CommandEncoder.class, "createFence", "()Lcom/mojang/blaze3d/buffers/GpuFence;");
        assertMethod(GpuFence.class, "awaitCompletion", "(J)Z");

        byte[] fabricMixin = classEntryFromProtectionDomain(
                ClientLifecycleEvents.class, FABRIC_MINECRAFT_MIXIN_RESOURCE);
        assertEquals(FABRIC_MINECRAFT_MIXIN_SHA256, sha256(fabricMixin));
        String pinnedConstantPool = new String(fabricMixin, StandardCharsets.ISO_8859_1);
        assertTrue(pinnedConstantPool.contains("onStopping"));
        assertTrue(pinnedConstantPool.contains("CLIENT_STOPPING"));
        assertTrue(pinnedConstantPool.contains("destroy"));
    }

    @Test
    void clientOnlyMixinUsesTwoRequiredInjectsAndCannotReplaceOrRepeatPresent() throws IOException {
        String mixin = Files.readString(clientSource("mixin", "Minecraft2612FinalPresentMixin.java"));
        String bridge = Files.readString(clientSource("reload", "Minecraft2612FinalPresentBridge.java"));
        String hooks = Files.readString(clientSource("reload", "Minecraft2612FinalFrameShutdownHooks.java"));
        String coordinator = Files.readString(clientSource("reload", "ClientFinalFrameShutdownCoordinator.java"));
        String adapter = Files.readString(clientSource("reload", "Minecraft2612OwnedFinalFenceAdapter.java"));
        String registry = Files.readString(clientSource("reload", "ClientModelRegistry.java"));
        String services = Files.readString(clientSource("api", "BlendLibClientServices.java"));
        String mixinConfig = Files.readString(clientResource("blendlib.client.mixins.json"));
        String metadata = Files.readString(mainResource("fabric.mod.json"));

        assertEquals(2, occurrences(mixin, "@Inject("));
        assertEquals(2, occurrences(mixin, "require = 1"));
        assertTrue(mixin.contains("method = \"renderFrame(Z)V\""));
        assertTrue(mixin.contains("shift = At.Shift.BEFORE"));
        assertTrue(mixin.contains("shift = At.Shift.AFTER"));
        assertTrue(mixin.contains("ordinal = 0"));
        assertFalse(mixin.contains("@Redirect"));
        assertFalse(mixin.contains("RenderSystem.flipFrame("));
        assertFalse(mixin.contains("CallbackInfoReturnable"));

        assertTrue(mixinConfig.contains("\"required\": true"));
        assertTrue(mixinConfig.contains("\"package\": \"com.liy.blendlib.fabric.client.mixin\""));
        assertFalse(mixinConfig.contains("\"package\": \"com.liy.blendlib.fabric.client.reload\""));
        assertTrue(mixinConfig.contains("\"compatibilityLevel\": \"JAVA_25\""));
        assertTrue(mixinConfig.contains("\"client\""));
        assertTrue(mixinConfig.contains("Minecraft2612FinalPresentMixin"));
        assertTrue(mixinConfig.contains("\"defaultRequire\": 1"));
        assertTrue(metadata.contains("\"config\": \"blendlib.client.mixins.json\""));
        assertTrue(metadata.contains("\"environment\": \"client\""));
        assertTrue(mixin.contains("Minecraft2612FinalPresentBridge.beforeOriginalPresent"));
        assertTrue(mixin.contains("Minecraft2612FinalPresentBridge.afterOriginalPresent"));
        assertFalse(mixin.contains("Minecraft2612FinalFrameShutdownHooks"));
        assertEquals(1, occurrences(bridge, "Minecraft2612FinalFrameShutdownHooks.beforeOriginalPresent"));
        assertEquals(1, occurrences(bridge, "Minecraft2612FinalFrameShutdownHooks.afterOriginalPresent"));
        try (Stream<Path> paths = Files.walk(projectDirectory().getParent())) {
            assertFalse(paths
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> path.getFileName().toString().equals("Minecraft2612FinalPresentMixin.java"))
                    .map(path -> path.toString().replace('\\', '/'))
                    .anyMatch(path -> path.contains("/src/main/") || path.contains("/src/server/")));
        }

        for (String source : List.of(hooks, coordinator, adapter)) {
            assertFalse(source.contains("executePendingTasks("));
            assertFalse(source.contains("GpuDevice.close("));
            assertFalse(source.contains("getDevice().close("));
            assertFalse(source.contains("org.lwjgl."));
        }
        assertTrue(adapter.contains("RenderSystem.getDevice().createCommandEncoder().createFence()"));
        assertTrue(adapter.contains("fence.awaitCompletion(0L)"));
        assertTrue(adapter.contains("fence.close()"));
        assertFalse(registry.contains("Minecraft2612ClientHolder"));
        assertEquals(1, occurrences(registry, "Minecraft2612FinalFrameShutdownHooks.install(coordinator)"));
        assertTrue(registry.contains("installMinecraft2612ShutdownCoordinatorIfEligible();"));
        assertTrue(services.contains("checkedRegistry.installServiceLookup(MANAGED_LOOKUP_BOOTSTRAP)"));
        assertTrue(services.contains("private static synchronized void install("));
    }

    @Test
    void shutdownInfrastructureAndPortsRemainPackagePrivateBehindNarrowPublicMixinBridge() {
        assertFalse(Modifier.isPublic(ClientFinalFrameShutdownCoordinator.class.getModifiers()));
        assertFalse(Modifier.isPublic(Minecraft2612OwnedFinalFenceAdapter.class.getModifiers()));
        assertFalse(Modifier.isPublic(Minecraft2612FinalFrameShutdownHooks.class.getModifiers()));
        assertTrue(Modifier.isPublic(Minecraft2612FinalPresentBridge.class.getModifiers()));
        assertTrue(Modifier.isFinal(Minecraft2612FinalPresentBridge.class.getModifiers()));
        List<Method> bridgeMethods = Arrays.stream(Minecraft2612FinalPresentBridge.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertEquals(2, bridgeMethods.size());
        assertTrue(bridgeMethods.stream().allMatch(method -> Modifier.isStatic(method.getModifiers())));
        assertTrue(bridgeMethods.stream().allMatch(method -> method.getReturnType() == void.class));
        for (Class<?> nested : ClientFinalFrameShutdownCoordinator.class.getDeclaredClasses()) {
            assertFalse(Modifier.isPublic(nested.getModifiers()), nested.getName());
        }

        List<Method> publicStaticMethods = Arrays.stream(ClientModelRegistry.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .toList();
        assertEquals(1, publicStaticMethods.size());
        assertEquals("createMinecraft2612Client", publicStaticMethods.getFirst().getName());
        assertEquals(0, publicStaticMethods.getFirst().getParameterCount());
        assertEquals(ClientModelRegistry.class, publicStaticMethods.getFirst().getReturnType());
        assertTrue(Arrays.stream(ClientModelRegistry.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(type -> type == Runnable.class
                        || type == GpuFence.class
                        || type == GpuDevice.class
                        || type == ClientFinalFrameShutdownCoordinator.class));
    }

    private static void assertPinnedClass(Class<?> type, String expectedHash) throws IOException {
        assertEquals(expectedHash, sha256(classBytes(type)), type.getName());
    }

    private static void assertMethod(Class<?> type, String name, String descriptor) throws IOException {
        ClassModel model = ClassFile.of().parse(classBytes(type));
        assertTrue(model.methods().stream()
                .anyMatch(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor)), type.getName() + "." + name + descriptor);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return input.readAllBytes();
        }
    }

    private static byte[] classEntryFromProtectionDomain(Class<?> anchor, String resource) throws IOException {
        assertNotNull(anchor.getProtectionDomain());
        assertNotNull(anchor.getProtectionDomain().getCodeSource());
        assertNotNull(anchor.getProtectionDomain().getCodeSource().getLocation());
        Path container;
        try {
            container = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException failure) {
            throw new IOException("Invalid protection-domain location for " + anchor.getName(), failure);
        }
        if (Files.isDirectory(container)) {
            Path entry = container.resolve(resource);
            assertTrue(Files.isRegularFile(entry), entry.toString());
            return Files.readAllBytes(entry);
        }
        try (ZipFile jar = new ZipFile(container.toFile())) {
            ZipEntry entry = jar.getEntry(resource);
            assertNotNull(entry, resource + " in " + container);
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static Path clientSource(String packageName, String fileName) {
        return projectDirectory()
                .resolve("src/client/java/com/liy/blendlib/fabric/client")
                .resolve(packageName)
                .resolve(fileName);
    }

    private static Path clientResource(String fileName) {
        return projectDirectory().resolve("src/client/resources").resolve(fileName);
    }

    private static Path mainResource(String fileName) {
        return projectDirectory().resolve("src/main/resources").resolve(fileName);
    }

    private static Path projectDirectory() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
