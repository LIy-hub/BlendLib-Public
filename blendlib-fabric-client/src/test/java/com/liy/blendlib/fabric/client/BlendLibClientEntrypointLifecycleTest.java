package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BlendLibClientEntrypointLifecycleTest {
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

        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME::onActiveGeneration"));
        assertTrue(entrypoint.contains("ClientPlayConnectionEvents.INIT.register"));
        assertTrue(entrypoint.contains("SKINNED_ANIMATION_RUNTIME.onPlayInit"));
        assertTrue(entrypoint.contains("ANIMATION_SYNC.onPlayInit"));
        assertTrue(entrypoint.contains("ClientPlayConnectionEvents.DISCONNECT.register"));
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
    }
}
