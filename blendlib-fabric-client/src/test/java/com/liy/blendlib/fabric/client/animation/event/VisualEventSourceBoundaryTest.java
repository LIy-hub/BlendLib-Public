package com.liy.blendlib.fabric.client.animation.event;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEventSourceBoundaryTest {
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "import net.minecraft.",
            "import net.fabricmc.",
            "import org.lwjgl.",
            "import com.mojang.blaze3d.",
            "import java.io.",
            "import java.nio.file.",
            "network",
            "payload",
            "resource",
            "loader",
            "parser",
            "submit",
            "minecraft.getinstance",
            "animationcontroller",
            "clientanimationinstance",
            ".advance(",
            ".trigger(",
            "applycorrection",
            "static final visualeventdispatcher",
            "visualeventdispatcher instance"
    );

    @Test
    void eventPackageStaysIndependentOfPlatformIoAndAnimationMutation() throws IOException {
        Path sourceRoot = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "animation", "event"
        );
        assertTrue(Files.isDirectory(sourceRoot));

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> sourceFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertFalse(sourceFiles.isEmpty());

            for (Path sourceFile : sourceFiles) {
                String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_REFERENCES) {
                    assertFalse(
                            source.contains(forbidden),
                            () -> sourceFile.getFileName() + " must not reference " + forbidden
                    );
                }
            }
        }
    }

    @Test
    void listenerIsVoidAndDispatcherOnlyConsumesImmutableAdvanceEvents() throws NoSuchMethodException, IOException {
        assertEquals(void.class, VisualEventListener.class.getDeclaredMethod(
                "onVisualEvent", BlendInstanceKey.class, AnimationVisualEvent.class).getReturnType());

        Path dispatcherSource = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "animation", "event",
                "VisualEventDispatcher.java");
        String source = Files.readString(dispatcherSource);
        assertTrue(source.contains("for (AnimationVisualEvent visualEvent : advance.visualEvents())"));
        assertTrue(source.contains("listener.onVisualEvent(instanceKey, visualEvent)"));
    }
}
