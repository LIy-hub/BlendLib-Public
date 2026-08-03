package com.liy.blendlib.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreSourceBoundaryTest {
    @Test
    void coreSourcesDoNotReferenceMinecraftOrFabric() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot), () -> "Missing source root: " + sourceRoot);

        List<String> forbiddenTokens = List.of("net.minecraft.", "net.fabricmc.", "net/minecraft/", "net/fabricmc/");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(sourceFile);
                for (String forbiddenToken : forbiddenTokens) {
                    assertFalse(
                            source.contains(forbiddenToken),
                            () -> sourceFile + " must not contain " + forbiddenToken);
                }
            }
        }
    }
}
