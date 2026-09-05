package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class X7SourceBoundaryTest {
    private static final Set<String> POLICY_SOURCE_FILES = Set.of(
            "X7AnimationWorkPolicy.java",
            "X7BudgetPolicy.java",
            "X7CullingPolicy.java",
            "X7GenerationPerformancePlan.java",
            "X7LodPolicy.java",
            "X7PolicyMetricsCollector.java");
    private static final List<String> FORBIDDEN_SOURCE_REFERENCES = List.of(
            "import net.minecraft.",
            "import net.fabricmc.",
            "import com.mojang.blaze3d.",
            "import org.lwjgl.",
            "import java.io.",
            "import java.nio.file.",
            "import java.lang.reflect.",
            "serviceloader",
            "class.forname",
            "new thread",
            "threadlocal",
            "executor",
            "provider discovery");
    private static final List<String> FORBIDDEN_BYTECODE_REFERENCES = List.of(
            "net/minecraft/",
            "net/fabricmc/",
            "com/mojang/blaze3d/",
            "org/lwjgl/",
            "java/io/",
            "java/nio/file/",
            "java/lang/reflect/",
            "java/util/ServiceLoader",
            "java/lang/Thread",
            "java/util/concurrent/Executor");

    @Test
    void x7FoundationStaysReloadPrivateAndHasNoPlatformOrDiscoveryDependencies() throws IOException {
        Path project = Path.of(System.getProperty("blendlib.projectDir"));
        Path sourceRoot = project.resolve(Path.of(
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "reload"));
        List<Path> sourceFiles = POLICY_SOURCE_FILES.stream().map(sourceRoot::resolve).toList();

        assertTrue(sourceFiles.stream().allMatch(Files::isRegularFile));
        Path formerSourceRoot = project.resolve(Path.of(
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "perf", "x7"));
        if (Files.isDirectory(formerSourceRoot)) {
            try (Stream<Path> paths = Files.list(formerSourceRoot)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".java")),
                        "the policy foundation must have one reload-private source home");
            }
        }
        for (Path sourceFile : sourceFiles) {
            String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
            assertFalse(source.contains("public class") || source.contains("public record") || source.contains("public enum"),
                    () -> sourceFile + " must not expose a public X7 type");
            for (String forbidden : FORBIDDEN_SOURCE_REFERENCES) {
                assertFalse(source.contains(forbidden), () -> sourceFile + " must not reference " + forbidden);
            }
        }

        Path classRoot = project.resolve(Path.of(
                "build", "classes", "java", "client", "com", "liy", "blendlib", "fabric", "client", "reload"));
        List<Path> classFiles = classFiles(classRoot).stream()
                .filter(X7SourceBoundaryTest::isPolicyClassOrNestedClassFile)
                .toList();
        for (String policySourceFile : POLICY_SOURCE_FILES) {
            String classFileName = policyClassName(policySourceFile) + ".class";
            assertTrue(classFiles.stream().anyMatch(path -> path.getFileName().toString().equals(classFileName)),
                    () -> classFileName + " must exist");
        }
        for (Path classFile : classFiles) {
            String bytecode = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (String forbidden : FORBIDDEN_BYTECODE_REFERENCES) {
                assertFalse(bytecode.contains(forbidden), () -> classFile + " must not link " + forbidden);
            }
        }
    }

    private static boolean isPolicyClassOrNestedClassFile(Path classFile) {
        String classFileName = classFile.getFileName().toString();
        return POLICY_SOURCE_FILES.stream().map(X7SourceBoundaryTest::policyClassName).anyMatch(policyClassName ->
                classFileName.equals(policyClassName + ".class") || classFileName.startsWith(policyClassName + "$"));
    }

    private static String policyClassName(String policySourceFile) {
        return policySourceFile.substring(0, policySourceFile.length() - ".java".length());
    }

    private static List<Path> classFiles(Path classRoot) throws IOException {
        assertTrue(Files.isDirectory(classRoot));
        try (Stream<Path> paths = Files.walk(classRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".class")).toList();
        }
    }
}
