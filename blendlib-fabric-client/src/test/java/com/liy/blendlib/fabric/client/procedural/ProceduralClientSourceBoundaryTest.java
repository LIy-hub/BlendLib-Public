package com.liy.blendlib.fabric.client.procedural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProceduralClientSourceBoundaryTest {
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "import net.minecraft.",
            "import net.fabricmc.",
            "import org.lwjgl.",
            "import com.mojang.blaze3d.",
            "import java.io.",
            "import java.nio.",
            "java.net.",
            "animationv2instanceruntime",
            "clientanimationv2runtime",
            ".advance(",
            ".install(",
            ".receiveintent(",
            "minecraft.getinstance",
            "network",
            "payload",
            "provider discovery",
            "service loader"
    );

    @Test
    void proceduralClientPackageStaysPureSnapshotConsumerWithNoPlatformIoOrRuntimeMutation() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java",
                "com", "liy", "blendlib", "fabric", "client", "procedural");
        assertTrue(Files.isDirectory(sourceRoot));
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> sourceFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sourceFiles.isEmpty());
            for (Path sourceFile : sourceFiles) {
                String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_REFERENCES) {
                    assertFalse(source.contains(forbidden), () -> sourceFile.getFileName() + " must not reference " + forbidden);
                }
                assertFalse(source.matches("(?s).*?(?m:^\\s*(?:public|protected|private)?\\s*static\\s+(?!final\\b)[^\\r\\n;(]+;).*"),
                        () -> sourceFile.getFileName() + " must not declare mutable global state");
            }
        }
    }

    @Test
    void extensionAndPresentationSeamsAreExplicitAndVoidWhereTheyDispatch() throws NoSuchMethodException {
        assertEquals(ClientIkResult.class, ExperimentalClientIkSolver.class.getDeclaredMethod(
                "solve", ClientIkRequest.class).getReturnType());
        assertEquals(0, ClientIkRequest.class.getConstructors().length,
                "callers must build IK requests from a frozen rig snapshot, not inject transforms");
        assertEquals(0, ClientIkResult.class.getConstructors().length,
                "callers must not manufacture effectful IK directives");
        assertEquals(void.class, ProceduralVisualEventListener.class.getDeclaredMethod(
                "present", com.liy.blendlib.core.procedural.ResolvedProceduralVisualEvent.class).getReturnType());
        assertEquals(void.class, ProceduralAttachmentPresentationResolver.class.getDeclaredMethod(
                "present", ProceduralAttachmentPresentation.class).getReturnType());
    }
}
