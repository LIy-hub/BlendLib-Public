package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import com.liy.blendlib.fabric.client.render.X7DeferredSubmissionEndpoint;
import org.junit.jupiter.api.Test;

class BlendLibClientEntrypointLifecycleTest {
    @Test
    void productionEndpointCannotAcceptAnArbitraryAdmissionGateway() throws ReflectiveOperationException {
        assertTrue(Modifier.isFinal(X7T3cClientGateway.class.getModifiers()));
        assertEquals(0, X7T3cClientGateway.class.getConstructors().length);
        assertTrue(Modifier.isPrivate(X7T3cClientGateway.class.getDeclaredConstructors()[0].getModifiers()));
        assertEquals(
                X7DeferredSubmissionEndpoint.class,
                X7DeferredSubmissionEndpoint.class
                        .getMethod("bootstrap")
                        .getReturnType());
        assertThrows(
                NoSuchMethodException.class,
                () -> X7DeferredSubmissionEndpoint.class.getMethod("bootstrap", X7T3cClientGateway.class),
                "a package test gateway must never be accepted by the public production bootstrap");
        assertFalse(Arrays.stream(X7DeferredSubmissionEndpoint.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("Gateway")));
    }

    @Test
    void entrypointRegistersCurrentFabricLifecycleEventsForTheAnimationLifecycle() throws IOException {
        Path source = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "client",
                "java",
                "com",
                "liy",
                "blendlib",
                "fabric",
                "client",
                "BlendLibClientEntrypoint.java");
        String entrypoint = Files.readString(source);

        assertTrue(entrypoint.contains("ClientModelRegistry.createMinecraft2612Client()"));
        assertTrue(entrypoint.contains("X7DeferredSubmissionEndpoint.bootstrap();"));
        assertFalse(entrypoint.contains("X7T3cClientGateway"));
        assertTrue(entrypoint.contains("X7Minecraft2612PassOwnerHost.production(X7_DEFERRED_SUBMISSION_ENDPOINT)"));
        assertTrue(entrypoint.contains("X7_PASS_OWNER_HOST.install()"));
        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME::onActiveGeneration"));
        assertTrue(entrypoint.contains("ClientPlayConnectionEvents.INIT.register"));
        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME.onPlayInit"));
        assertTrue(entrypoint.contains("ANIMATION_SYNC.onPlayInit"));
        assertTrue(entrypoint.contains("ClientPlayConnectionEvents.DISCONNECT.register"));
        assertTrue(entrypoint.contains("X7_DEFERRED_SUBMISSION_ENDPOINT.onReloadOrWorldLeave()"));
        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME.onWorldDisconnect"));
        assertTrue(entrypoint.contains("ANIMATION_SYNC.onDisconnect"));
        assertTrue(entrypoint.contains("ClientAnimationPayloadReceivers.register(ANIMATION_SYNC)"));
        assertTrue(entrypoint.contains("ClientTickEvents.END_CLIENT_TICK.register"));
        assertTrue(entrypoint.contains("ClientEntityEvents.ENTITY_UNLOAD.register"));
        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME.onEntityUnload"));
        assertTrue(entrypoint.contains("ANIMATION_SYNC.onEntityUnload"));
        assertTrue(entrypoint.contains("ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register"));
        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME.onBlockEntityUnload"));
        assertTrue(entrypoint.contains("ANIMATION_SYNC.onBlockEntityUnload"));
        assertTrue(entrypoint.contains("ClientAnimationLifecycleBridge"));
        assertTrue(entrypoint.contains("SkinnedAnimationRuntime"));
        assertTrue(entrypoint.contains("BlendLibClientServices.initialize("));
        String stoppingRegistration = "ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {";
        assertTrue(entrypoint.contains(stoppingRegistration));
        assertTrue(entrypoint.contains("MODEL_REGISTRY.close();"));
        assertTrue(entrypoint.contains("catch (Throwable failure)"));
        assertTrue(entrypoint.contains("vanilla teardown will continue"));
        assertTrue(entrypoint.indexOf(stoppingRegistration) < entrypoint.indexOf("BlendLibClientServices.initialize("));
    }
}
