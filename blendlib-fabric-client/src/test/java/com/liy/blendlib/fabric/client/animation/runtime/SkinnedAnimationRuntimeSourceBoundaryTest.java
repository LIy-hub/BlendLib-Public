package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SkinnedAnimationRuntimeSourceBoundaryTest {
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "import java.io.",
            "import java.nio.file.",
            "import com.liy.blendlib.core.asset.",
            "import com.liy.blendlib.core.loader.",
            "import com.liy.blendlib.core.glb.",
            "import com.liy.blendlib.core.json.",
            "clientmodelreloadlistener",
            "modelassetloader",
            "assetresolver",
            "glbreader",
            "strictjsonparser",
            "resourcemanager",
            "net.minecraft.",
            "net.fabricmc.",
            "org.lwjgl.",
            "com.mojang.blaze3d.",
            "minecraft.getinstance",
            "payload",
            "packet",
            "network",
            "server",
            "submit"
    );

    @Test
    void runtimePackageStaysExtractionOnlyAndUsesThePreparedSkinnedBridge() throws IOException {
        Path runtimeSources = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "animation", "runtime");
        assertTrue(Files.isDirectory(runtimeSources));

        try (Stream<Path> paths = Files.walk(runtimeSources)) {
            List<Path> sourceFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sourceFiles.isEmpty());
            for (Path sourceFile : sourceFiles) {
                String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_REFERENCES) {
                    assertFalse(source.contains(forbidden), () -> sourceFile.getFileName() + " must not reference " + forbidden);
                }
            }
        }

        String runtime = Files.readString(runtimeSources.resolve("SkinnedAnimationRuntime.java"));
        assertTrue(runtime.contains("ClientSkinnedExtractionBridge.extract"));
        assertTrue(runtime.contains("AnimationControllerDefinition.fromModelAsset"));
        assertTrue(runtime.contains("PoseSampler.fromModelAsset"));
    }
}
