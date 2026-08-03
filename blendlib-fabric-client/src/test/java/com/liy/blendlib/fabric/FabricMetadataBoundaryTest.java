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
    }
}
