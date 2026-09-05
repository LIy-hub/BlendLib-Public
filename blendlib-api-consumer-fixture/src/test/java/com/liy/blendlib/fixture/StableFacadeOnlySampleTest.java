package com.liy.blendlib.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.api.PlaybackMode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class StableFacadeOnlySampleTest {
    @Test
    void ordinarySampleCompilesAgainstOnlyStableFacadeAndKeys() {
        assertEquals(HostKind.ENTITY, StableFacadeOnlySample.entitySpecification().hostKind());
        assertEquals(ApiConsumerFixture.MODEL, StableFacadeOnlySample.entitySpecification().model());
        assertEquals(HostKind.ITEM, StableFacadeOnlySample.itemSpecification().hostKind());
        assertEquals(PlaybackMode.LOOP,
                StableFacadeOnlySample.itemSpecification().animationSource().requestFor("stable-only-item").playbackMode());
    }

    @Test
    void stableFacadePublicMethodsDoNotExposeExperimentalSpiTypes() {
        for (Method method : BlendLib.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(method.getReturnType().getPackageName().contains(".spi.experimental"), method::toString);
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(parameterType.getPackageName().contains(".spi.experimental"), method::toString);
            }
        }
    }

    @Test
    void stableOnlySampleSourceHasNoExperimentalSpiImport() throws Exception {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java",
                "com", "liy", "blendlib", "fixture", "StableFacadeOnlySample.java");
        assertFalse(Files.readString(source).contains(".spi.experimental"));
    }

    @Test
    void stableOnlySampleCompilesAsOneSourceAgainstOnlyThePublishedApiJar() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("blendlib.projectDir"));
        Path source = projectDirectory.resolve(Path.of("src", "main", "java", "com", "liy", "blendlib",
                "fixture", "StableFacadeOnlySample.java"));
        Path apiLibraries = projectDirectory.resolve(Path.of("..", "blendlib-api", "build", "libs")).normalize();
        List<Path> apiJars;
        try (var paths = Files.list(apiLibraries)) {
            apiJars = paths.filter(path -> path.getFileName().toString().matches("blendlib-api-.+\\.jar"))
                    .filter(path -> !path.getFileName().toString().contains("-sources"))
                    .filter(path -> !path.getFileName().toString().contains("-javadoc"))
                    .toList();
        }
        assertEquals(1, apiJars.size(), () -> "Expected one published API runtime JAR in " + apiLibraries);
        Path output = Files.createTempDirectory("blendlib-stable-only-javac-");
        try {
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertTrue(compiler != null, "Java 25 compiler is required for the standalone fixture gate");
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
                boolean compiled = compiler.getTask(
                        null,
                        files,
                        diagnostics,
                        List.of(
                                "--release", "25",
                                "-proc:none",
                                "-implicit:none",
                                "--class-path", apiJars.getFirst().toString(),
                                "--source-path", "",
                                "-d", output.toString()),
                        null,
                        files.getJavaFileObjects(source.toFile()))
                        .call();
                assertTrue(compiled, () -> "Stable-only standalone javac failed: " + diagnostics.getDiagnostics());
            }
            assertTrue(Files.isRegularFile(output.resolve(Path.of(
                    "com", "liy", "blendlib", "fixture", "StableFacadeOnlySample.class"))));
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
