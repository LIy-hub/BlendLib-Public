package com.liy.blendlib.fabric;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FabricMetadataBoundaryTest {
    @Test
    void metadataSeparatesCommonAndClientEntrypoints() throws IOException {
        Path metadata = Path.of(
                System.getProperty("blendlib.projectDir"), "src", "main", "resources", "fabric.mod.json");
        String json = Files.readString(metadata);

        assertTrue(json.contains("\"main\""));
        assertTrue(json.contains("com.liy.blendlib.fabric.common.BlendLibCommonEntrypoint"));
        assertTrue(json.contains("\"client\""));
        assertTrue(json.contains("com.liy.blendlib.fabric.client.BlendLibClientEntrypoint"));
        assertTrue(json.contains("\"config\": \"blendlib.client.mixins.json\""));
        assertTrue(json.contains("\"environment\": \"client\""));

        Path clientMixin = Path.of(
                System.getProperty("blendlib.projectDir"), "src", "client", "resources", "blendlib.client.mixins.json");
        assertTrue(Files.isRegularFile(clientMixin));
        assertTrue(Files.readString(clientMixin).contains("Minecraft2612FinalPresentMixin"));
    }
}
