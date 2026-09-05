package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the X4 client source and production-output boundary without probing Showcase outputs. */
class X4ServerClassloadBoundaryTest {
    private static final String X4_HOST_SENTINEL = "X4HostAdapter.class";

    @Test
    void x4ClientProductionBytecodeContainsNoServerCommonOrParsingReference() throws IOException {
        Path hostOutput = Path.of(
                System.getProperty("blendlib.projectDir"),
                "build/classes/java/client/com/liy/blendlib/fabric/client/host");
        scanHostProductionBytecode(hostOutput);
    }

    @Test
    void emptyClientOutputCannotPassTheX4BytecodeBoundaryScan(@TempDir Path temporary) throws IOException {
        assertThrows(AssertionError.class, () -> scanHostProductionBytecode(temporary));
    }

    @Test
    void clientOutputWithoutTheX4HostSentinelCannotPassTheBytecodeBoundaryScan(@TempDir Path temporary)
            throws IOException {
        writeClass(temporary, "X4HostAdapters.class", new byte[] {0});
        assertThrows(AssertionError.class, () -> scanHostProductionBytecode(temporary));
    }

    @Test
    void clientOutputWithOnlyUnrelatedClassesCannotPassTheX4BytecodeBoundaryScan(@TempDir Path temporary)
            throws IOException {
        writeClass(temporary, "com/example/Unrelated.class", new byte[] {0});
        assertThrows(AssertionError.class, () -> scanHostProductionBytecode(temporary));
    }

    private static void scanHostProductionBytecode(Path hostOutput) throws IOException {
        assertTrue(Files.isDirectory(hostOutput), "X4 client production output must exist: " + hostOutput);
        int scanned = 0;
        boolean sentinelScanned = false;
        try (Stream<Path> classes = Files.walk(hostOutput)) {
            for (Path classFile : classes.filter(path -> path.toString().endsWith(".class")).toList()) {
                String byteText = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String forbidden : new String[] {
                        "com/liy/blendlib/fabric/common/",
                        "net/minecraft/server/",
                        "net/fabricmc/api/ModInitializer",
                        "java/io/",
                        "java/net/",
                        "java/nio/file/",
                        "com/liy/blendlib/core/glb/GlbReader",
                        "com/liy/blendlib/core/descriptor/DescriptorDecoder"}) {
                    assertFalse(byteText.contains(forbidden), () -> classFile + " references " + forbidden);
                }
                scanned++;
                if (hostOutput.relativize(classFile).toString().replace('\\', '/').equals(X4_HOST_SENTINEL)) {
                    sentinelScanned = true;
                }
            }
        }
        assertTrue(scanned > 0,
                "X4 client production output must contain at least one scanned class: " + hostOutput);
        assertTrue(sentinelScanned,
                "X4 client production output must contain and scan key sentinel " + X4_HOST_SENTINEL + ": " + hostOutput);
    }

    private static void writeClass(Path root, String relativeName, byte[] bytes) throws IOException {
        Path target = root.resolve(relativeName);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    @Test
    void x4SourceContainsNoServerOrCommonEntrypointImports() throws IOException {
        Path hostSources = Path.of(System.getProperty("blendlib.projectDir"), "src/client/java/com/liy/blendlib/fabric/client/host");
        try (Stream<Path> paths = Files.walk(hostSources)) {
            String source = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(X4ServerClassloadBoundaryTest::readUnchecked)
                    .reduce("", String::concat);
            for (String forbidden : new String[] {
                    "net.minecraft.server",
                    "com.liy.blendlib.fabric.common.",
                    "net.fabricmc.api.ModInitializer",
                    "java.io.",
                    "java.net.",
                    "java.nio.file.",
                    "ResourceManager",
                    "GlbReader",
                    "DescriptorDecoder",
                    "StrictJsonParser"}) {
                assertFalse(source.contains(forbidden), forbidden);
            }
            assertTrue(source.contains("ModelRenderSnapshot"));
            assertTrue(source.contains("PlatformAdapter"));
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read X4 source " + path, exception);
        }
    }

}
