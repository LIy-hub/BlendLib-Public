package com.liy.blendlib.fabric.common.animation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerAnimationSyncServiceLifecycleTest {
    @Test
    void fabricLifecycleHooksClearEntityLevelAndServerScopedState() throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java", "com", "liy",
                "blendlib", "fabric", "common", "animation", "ServerAnimationSyncService.java");
        String text = Files.readString(source);

        assertTrue(text.contains("EntityTrackingEvents.START_TRACKING.register"));
        assertTrue(text.contains("ServerEntityEvents.ENTITY_UNLOAD.register"));
        assertTrue(text.contains("ServerLevelEvents.UNLOAD.register"));
        assertTrue(text.contains("ServerLifecycleEvents.SERVER_STOPPED.register"));
        assertTrue(text.contains("states.clearEntity(entity.getUUID())"));
        assertTrue(text.contains("states.clearDimension(dimension)"));
        assertTrue(text.contains("observedBlockTrackers.clear()"));
    }
}
