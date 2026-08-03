package com.liy.blendlib.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiBytecodeBoundaryTest {
    @Test
    void apiBytecodeContainsNoPlatformCoreRenderOrIoLeaks() throws IOException {
        Path classRoot = Path.of(System.getProperty("blendlib.projectDir"), "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classRoot), () -> "Missing API classes: " + classRoot);
        List<String> forbiddenTokens = List.of(
                "net/minecraft/",
                "net/fabricmc/",
                "com/liy/blendlib/core/",
                "com/liy/blendlib/fabric/",
                "java/nio/file/",
                "java/lang/reflect/",
                "RenderType",
                "RenderPipeline",
                "Identifier");
        try (var paths = Files.walk(classRoot)) {
            for (Path classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                String constantPoolText = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String forbiddenToken : forbiddenTokens) {
                    assertFalse(constantPoolText.contains(forbiddenToken),
                            () -> classFile + " leaks forbidden token " + forbiddenToken);
                }
            }
        }
    }
}
