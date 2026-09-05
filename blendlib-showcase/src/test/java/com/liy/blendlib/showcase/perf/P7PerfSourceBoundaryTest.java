package com.liy.blendlib.showcase.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class P7PerfSourceBoundaryTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("import\\s+(com\\.liy\\.blendlib\\.[\\w.]+);");

    @Test
    void p7ClientHarnessUsesOnlyThePublicClientFacade() throws IOException {
        String source = Files.readString(clientSource("P7ClientMeasurementHarness.java"));
        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.common.",
                "com.liy.blendlib.fabric.client.reload.",
                "com.liy.blendlib.fabric.client.animation.",
                "com.liy.blendlib.fabric.client.render.",
                "com.liy.blendlib.fabric.client.network.",
                "Minecraft.getInstance",
                "org.lwjgl")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        Matcher matcher = BLENDLIB_IMPORT.matcher(source);
        while (matcher.find()) {
            String importedType = matcher.group(1);
            assertTrue(
                    importedType.startsWith("com.liy.blendlib.showcase.perf.")
                            || importedType.equals("com.liy.blendlib.fabric.client.api.BlendLibClientServices")
                            || importedType.equals("com.liy.blendlib.fabric.client.api.ClientRegistryView"),
                    () -> "P7 client harness escaped the public client facade: " + importedType);
        }
        assertTrue(source.contains("BlendLibClientServices.models().snapshot()"));
    }

    @Test
    void p7ScenarioAndGeneratorDoNotUseRenderOrLoaderImplementations() throws IOException {
        Path sourceRoot = projectRoot().resolve("src/main/java/com/liy/blendlib/showcase/perf");
        String combined;
        try (var paths = Files.walk(sourceRoot)) {
            combined = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .reduce("", String::concat);
        }
        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.",
                "org.lwjgl",
                "glBind",
                "glDraw")) {
            assertFalse(combined.contains(forbidden), forbidden);
        }
        assertTrue(combined.contains("not wired into"));
        assertTrue(combined.contains("JFR or profiler"));
    }

    private static Path clientSource(String fileName) {
        return projectRoot().resolve("src/client/java/com/liy/blendlib/showcase/perf").resolve(fileName);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new AssertionError("Unable to read " + path, exception);
        }
    }
}
