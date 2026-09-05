package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.render.ModelRenderBackend;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ClientModelRegistryRenderOwnerBootstrapTest {
    @Test
    void publicRegistrySurfaceAddsOnlyTheNoArgumentTrusted2612Factory() throws Exception {
        assertEquals(1, ClientModelRegistry.class.getConstructors().length);
        assertEquals(0, ClientModelRegistry.class.getConstructor().getParameterCount());

        List<Method> publicStaticMethods = Arrays.stream(ClientModelRegistry.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .toList();
        assertEquals(1, publicStaticMethods.size());
        Method factory = publicStaticMethods.getFirst();
        assertEquals("createMinecraft2612Client", factory.getName());
        assertEquals(ClientModelRegistry.class, factory.getReturnType());
        assertEquals(0, factory.getParameterCount());

        Set<String> exposedTypes = Stream.concat(
                        Arrays.stream(ClientModelRegistry.class.getConstructors())
                                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())),
                        Arrays.stream(ClientModelRegistry.class.getMethods())
                                .flatMap(method -> Stream.concat(
                                        Stream.of(method.getReturnType()),
                                        Arrays.stream(method.getParameterTypes()))))
                .map(Class::getName)
                .collect(Collectors.toSet());
        for (String forbidden : List.of(
                "java.lang.Runnable",
                "java.util.concurrent.Executor",
                "com.mojang.blaze3d.systems.GpuDevice",
                "com.mojang.blaze3d.buffers.GpuBuffer",
                "com.mojang.blaze3d.systems.RenderPass",
                "com.mojang.blaze3d.systems.RenderSystem",
                CompletedGenerationResourceSet.class.getName(),
                CompletedGenerationResourceSet.PhysicalLeafClose.class.getName(),
                ClientGenerationResourceOwner.RenderOwnerCallbacks.class.getName())) {
            assertFalse(exposedTypes.contains(forbidden), forbidden);
        }

        assertTrue(Arrays.stream(ClientModelRegistry.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("attachCompleteResourceSetOnRenderThread")));
        assertTrue(Arrays.stream(ClientGenerationResourceOwner.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("attachCompleteResourceSetOnRenderThread")));
        assertFalse(Modifier.isPublic(CompletedGenerationResourceSet.class.getModifiers()));
        assertTrue(Arrays.stream(CompletedGenerationResourceSet.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(CompletedGenerationResourceSet.class.getDeclaredMethods())
                .noneMatch(method -> Modifier.isPublic(method.getModifiers())
                        || Modifier.isProtected(method.getModifiers())));
        assertTrue(Arrays.stream(CompletedGenerationResourceSet.class.getDeclaredClasses())
                .noneMatch(type -> Modifier.isPublic(type.getModifiers())
                        || Modifier.isProtected(type.getModifiers())));

        assertFalse(Modifier.isPublic(Minecraft2612GenerationRenderOwner.class.getModifiers()));
        assertTrue(Arrays.stream(Minecraft2612GenerationRenderOwner.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
        for (Class<?> port : Minecraft2612GenerationRenderOwner.class.getDeclaredClasses()) {
            assertFalse(Modifier.isPublic(port.getModifiers()), port.getName());
        }
    }

    @Test
    void publicTrustedFactoryReturnsFreshRegistriesWithIsolatedPublicationAndCloseState() {
        ClientModelRegistry first = ClientModelRegistry.createMinecraft2612Client();
        ClientModelRegistry second = ClientModelRegistry.createMinecraft2612Client();
        assertNotSame(first, second);

        ModelRegistryGeneration firstGeneration = ModelRegistryGeneration.empty(1L);
        ModelRegistryGeneration secondGeneration = ModelRegistryGeneration.empty(2L);
        assertSame(firstGeneration, first.publish(firstGeneration));
        assertSame(secondGeneration, second.publish(secondGeneration));
        assertSame(firstGeneration, first.current());
        assertSame(secondGeneration, second.current());

        first.close();

        assertTrue(firstGeneration.isRetired());
        assertFalse(secondGeneration.isRetired());
        assertSame(secondGeneration, second.current());
        ModelRegistryGeneration secondReplacement = ModelRegistryGeneration.empty(3L);
        assertSame(secondReplacement, second.publish(secondReplacement));
        ModelRegistryGeneration firstAfterClose = ModelRegistryGeneration.empty(4L);
        assertThrows(IllegalStateException.class, () -> first.publish(firstAfterClose));
        assertFalse(firstAfterClose.isRetired(), "cutoff rejects before a CPU-only transaction can claim ownership");
        assertSame(firstGeneration, first.current());
        assertSame(secondReplacement, second.current());
    }

    @Test
    void productionServiceBootstrapInstallsOnePrimaryHookOwnerAndRejectsAnotherRegistry()
            throws ReflectiveOperationException {
        ClientModelRegistry primary = ClientModelRegistry.createMinecraft2612Client();
        ClientModelRegistry rejected = ClientModelRegistry.createMinecraft2612Client();
        ModelRenderBackend backend = (snapshot, context) -> { };

        BlendLibClientServices.initialize(primary, backend);
        BlendLibClientServices.initialize(primary, backend);

        Field coordinatorField = ClientModelRegistry.class.getDeclaredField("shutdownCoordinator");
        coordinatorField.setAccessible(true);
        assertNotNull(coordinatorField.get(primary));
        assertNull(coordinatorField.get(rejected));
        assertThrows(
                IllegalStateException.class,
                () -> BlendLibClientServices.initialize(rejected, backend));
        assertNull(coordinatorField.get(rejected));
    }

    @Test
    void productionFactoryPinsTheTrustedMinecraftHandoffAssertionAndFenceApis() throws IOException {
        String registrySource = Files.readString(sourcePath("reload", "ClientModelRegistry.java"));
        String ownerSource = Files.readString(sourcePath("reload", "Minecraft2612GenerationRenderOwner.java"));

        assertTrue(registrySource.contains("public ClientModelRegistry()"));
        assertTrue(registrySource.contains("public static ClientModelRegistry createMinecraft2612Client()"));
        assertTrue(registrySource.contains("Minecraft2612GenerationRenderOwner.create()"));
        assertFalse(registrySource.contains("Minecraft2612ClientHolder"));
        assertTrue(ownerSource.contains("Minecraft.getInstance()::execute"));
        assertTrue(ownerSource.contains("RenderSystem::assertOnRenderThread"));
        assertTrue(ownerSource.contains("RenderSystem::queueFencedTask"));
        for (String forbidden : List.of("GpuDevice", "GpuBuffer", "RenderPass", "java.util.concurrent.Executor")) {
            assertFalse(ownerSource.contains(forbidden), forbidden);
        }
    }

    @Test
    void injectedOwnerSupportsInlineAndDelayedMinecraftExecuteSemantics() {
        verifyHandoff(true);
        verifyHandoff(false);
    }

    private static void verifyHandoff(boolean inline) {
        FakePlatform platform = new FakePlatform(inline);
        Minecraft2612GenerationRenderOwner owner = new Minecraft2612GenerationRenderOwner(
                platform::execute, platform::assertOnRenderThread, platform::queueFencedTask);
        AtomicInteger closeCount = new AtomicInteger();

        assertThrows(IllegalStateException.class, owner::assertOnRenderThread);
        platform.events.clear();
        owner.handoffToRenderThread(() -> {
            owner.assertOnRenderThread();
            owner.queueFencedTask(() -> {
                owner.assertOnRenderThread();
                closeCount.incrementAndGet();
            });
        });

        assertEquals(inline ? List.of("execute", "assert", "fence") : List.of("execute"), platform.events);
        assertEquals(0, closeCount.get());
        if (!inline) {
            platform.runNextHandoff();
            assertEquals(List.of("execute", "assert", "fence"), platform.events);
        }
        platform.runNextFence();
        assertEquals(1, closeCount.get());
        assertEquals(List.of("execute", "assert", "fence", "assert"), platform.events);
        assertTrue(platform.handoffs.isEmpty());
        assertTrue(platform.fences.isEmpty());
    }

    private static Path sourcePath(String packageName, String fileName) {
        return Path.of(System.getProperty("blendlib.projectDir"))
                .resolve("src/client/java/com/liy/blendlib/fabric/client")
                .resolve(packageName)
                .resolve(fileName);
    }

    private static final class FakePlatform {
        private final boolean inline;
        private final ArrayDeque<Runnable> handoffs = new ArrayDeque<>();
        private final ArrayDeque<Runnable> fences = new ArrayDeque<>();
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();
        private boolean renderThread;

        private FakePlatform(boolean inline) {
            this.inline = inline;
        }

        private void execute(Runnable task) {
            events.add("execute");
            if (inline) {
                runAsRenderThread(task);
            } else {
                handoffs.addLast(task);
            }
        }

        private void assertOnRenderThread() {
            events.add("assert");
            if (!renderThread) {
                throw new IllegalStateException("not the fake render thread");
            }
        }

        private void queueFencedTask(Runnable task) {
            events.add("fence");
            if (!renderThread) {
                throw new IllegalStateException("fence queued off the fake render thread");
            }
            fences.addLast(task);
        }

        private void runNextHandoff() {
            runAsRenderThread(handoffs.removeFirst());
        }

        private void runNextFence() {
            runAsRenderThread(fences.removeFirst());
        }

        private void runAsRenderThread(Runnable task) {
            boolean previous = renderThread;
            renderThread = true;
            try {
                task.run();
            } finally {
                renderThread = previous;
            }
        }
    }
}
