package com.liy.blendlib.core.procedural;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;

class ProceduralCoreSourceBoundaryTest {
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "import net.minecraft.",
            "import net.fabricmc.",
            "import org.lwjgl.",
            "import com.mojang.blaze3d.",
            "import java.io.",
            "import java.nio.",
            "java.net.",
            "java.lang.reflect.",
            "serviceloader",
            "clientanimationv2runtime",
            ".advance(",
            "network payload",
            "resourcemanager",
            "strictjsonparser",
            "glbreader"
    );
    private static final Set<String> EXACT_X2_RUNTIME_READER_FILES = Set.of(
            "ProceduralRigRuntime.java",
            "ProceduralVisualEventTimelineRegistry.java");
    private static final List<String> FORBIDDEN_X2_RUNTIME_MUTATORS = List.of(
            "AnimationV2InstanceRuntime.advance",
            "AnimationV2InstanceRuntime.advanceAtFrame",
            "AnimationV2InstanceRuntime.enqueue",
            "AnimationV2InstanceRuntime.submit",
            "AnimationV2InstanceRuntime.intent");

    @Test
    void proceduralCoreRemainsPureJavaSnapshotConsumerWithNoPlatformOrRuntimeMutationPath() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java",
                "com", "liy", "blendlib", "core", "procedural");
        assertTrue(Files.isDirectory(sourceRoot));
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> sourceFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sourceFiles.isEmpty());
            for (Path sourceFile : sourceFiles) {
                String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_REFERENCES) {
                    assertFalse(source.contains(forbidden), () -> sourceFile.getFileName() + " must not reference " + forbidden);
                }
            }
        }
    }

    @Test
    void exactX2RuntimeBindingIsNarrowReadOnlyAndBytecodeCannotAdvanceOrSubmit() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java",
                "com", "liy", "blendlib", "core", "procedural");
        List<Path> sourceFiles;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sourceFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        Set<String> runtimeReaders = sourceFiles.stream()
                .filter(path -> {
                    try {
                        return Files.readString(path).contains("AnimationV2InstanceRuntime");
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(EXACT_X2_RUNTIME_READER_FILES, runtimeReaders,
                "only the X3 constructor binding and private timeline registry may name the mutable X2 runtime type");

        String registrySource = Files.readString(sourceRoot.resolve("ProceduralVisualEventTimelineRegistry.java"));
        java.util.regex.Matcher invocations = Pattern.compile("sourceRuntime\\.(\\w+)\\(").matcher(registrySource);
        Set<String> observedReads = new java.util.LinkedHashSet<>();
        while (invocations.find()) {
            observedReads.add(invocations.group(1));
        }
        assertEquals(Set.of("plan", "latestSnapshot"), observedReads,
                "the provenance registry may read only the frozen X2 plan and latest immutable snapshot");
        String runtimeSource = Files.readString(sourceRoot.resolve("ProceduralRigRuntime.java"));
        for (String source : List.of(registrySource, runtimeSource)) {
            String lowered = source.toLowerCase(Locale.ROOT);
            for (String forbidden : List.of(".advance(", ".advanceatframe(", ".enqueue(", ".submit(", ".intent(")) {
                assertFalse(lowered.contains(forbidden), "X3 runtime binding must never invoke X2 mutation: " + forbidden);
            }
        }

        Path classes = Path.of(System.getProperty("blendlib.projectDir"), "build", "classes", "java", "main");
        String registryBytecode = javap(classes, "com.liy.blendlib.core.procedural.ProceduralVisualEventTimelineRegistry");
        assertTrue(registryBytecode.contains("AnimationV2InstanceRuntime.plan"));
        assertTrue(registryBytecode.contains("AnimationV2InstanceRuntime.latestSnapshot"));
        for (String forbidden : FORBIDDEN_X2_RUNTIME_MUTATORS) {
            assertFalse(registryBytecode.contains(forbidden), "registry bytecode must not invoke X2 mutation: " + forbidden);
        }
        String runtimeBytecode = javap(classes, "com.liy.blendlib.core.procedural.ProceduralRigRuntime");
        for (String forbidden : FORBIDDEN_X2_RUNTIME_MUTATORS) {
            assertFalse(runtimeBytecode.contains(forbidden), "constructor binding bytecode must not invoke X2 mutation: " + forbidden);
        }
    }

    @Test
    void generationClaimRegistryIsBoundedWeakAndNeverCallsBackIntoGraphLocks() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java",
                "com", "liy", "blendlib", "core", "procedural");
        String source = Files.readString(sourceRoot.resolve("ProceduralAttachmentGenerationClaims.java"));
        assertTrue(source.contains("extends WeakReference<ProceduralAttachmentGraph>"),
                "the JVM-global generation index may retain only weak graph references");
        assertFalse(source.contains("Map<Long, ProceduralAttachmentGraph>"),
                "the generation index must not hold graphs strongly through its value type");
        assertFalse(source.contains("ProceduralRigRuntime"),
                "the generation index must never pin a runtime");
        assertFalse(Pattern.compile("\\bgraph\\.\\w+\\s*\\(").matcher(source).find(),
                "the registry owns no graph lock and must never call back into graph methods");
        assertTrue(source.contains("MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS"),
                "the process-wide claim table must have a fixed explicit capacity");
    }

    private static String javap(Path classes, String className) {
        ToolProvider javap = ToolProvider.findFirst("javap")
                .orElseThrow(() -> new AssertionError("the Java toolchain must expose javap for the X3 bytecode boundary assertion"));
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int exit = javap.run(new PrintWriter(output), new PrintWriter(errors),
                "-classpath", classes.toString(), "-c", "-p", className);
        assertEquals(0, exit, () -> "javap failed for " + className + ": " + errors);
        return output.toString();
    }
}
