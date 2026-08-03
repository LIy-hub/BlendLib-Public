package com.liy.blendlib.fabric.client.animation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAnimationSourceBoundaryTest {
    private static final List<String> CONTROLLER_FORBIDDEN_REFERENCES = List.of(
            "import java.io.",
            "import java.nio.file.",
            "import com.liy.blendlib.core.asset.",
            "import com.liy.blendlib.core.loader.",
            "import com.liy.blendlib.core.glb.",
            "import com.liy.blendlib.core.json.",
            "import com.liy.blendlib.fabric.client.reload.",
            "import com.liy.blendlib.fabric.client.render.",
            "import com.liy.blendlib.fabric.client.entity.",
            "import net.minecraft.",
            "import net.fabricmc.",
            "import org.lwjgl.",
            "import com.mojang.blaze3d.",
            "minecraft.getinstance",
            "modelasset",
            "modelrendersnapshot",
            "modelrenderhandle",
            "modelassetloader",
            "assetresolver",
            "glbreader",
            "strictjsonparser",
            "resourcemanager",
            "submit"
    );

    private static final List<String> EXTRACTION_FORBIDDEN_REFERENCES = List.of(
            "import java.io.",
            "import java.nio.file.",
            "import com.liy.blendlib.core.asset.",
            "import com.liy.blendlib.core.loader.",
            "import com.liy.blendlib.core.glb.",
            "import com.liy.blendlib.core.json.",
            "import net.minecraft.",
            "import net.fabricmc.",
            "import org.lwjgl.",
            "import com.mojang.blaze3d.",
            "minecraft.getinstance",
            "modelassetloader",
            "assetresolver",
            "glbreader",
            "strictjsonparser",
            "resourcemanager",
            "submit"
    );

    @Test
    void controllerAndCachePackageStaysIndependentOfAssetsReloadAndRendering() throws IOException {
        Path animationSources = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "animation"
        );
        assertTrue(Files.isDirectory(animationSources));

        try (Stream<Path> paths = Files.list(animationSources)) {
            List<Path> sourceFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertFalse(sourceFiles.isEmpty());

            for (Path sourceFile : sourceFiles) {
                String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
                for (String forbidden : CONTROLLER_FORBIDDEN_REFERENCES) {
                    assertFalse(
                            source.contains(forbidden),
                            () -> sourceFile.getFileName() + " must not reference " + forbidden
                    );
                }
            }
        }
    }

    @Test
    void extractionBridgeStaysFreeOfResourceLoadingPlatformAndSubmitWork() throws IOException {
        Path extractionSources = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "animation", "extract"
        );
        assertTrue(Files.isDirectory(extractionSources));

        try (Stream<Path> paths = Files.walk(extractionSources)) {
            List<Path> sourceFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertFalse(sourceFiles.isEmpty());

            for (Path sourceFile : sourceFiles) {
                String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
                for (String forbidden : EXTRACTION_FORBIDDEN_REFERENCES) {
                    assertFalse(
                            source.contains(forbidden),
                            () -> sourceFile.getFileName() + " must not reference " + forbidden
                    );
                }
            }
        }
    }
}
