package com.liy.blendlib.showcase.perf.scene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class P7BenchmarkSceneCommandContractsTest {
    @Test
    void benchmarkHostsAreReachableOnlyThroughTheExplicitAdministratorCommand() throws IOException {
        String entrypoint = source("src/main/java/com/liy/blendlib/showcase/BlendLibShowcaseEntrypoint.java");
        String command = source("src/main/java/com/liy/blendlib/showcase/perf/scene/P7BenchmarkCommands.java");
        String spawner = source("src/main/java/com/liy/blendlib/showcase/perf/scene/P7BenchmarkSceneSpawner.java");

        assertTrue(entrypoint.contains("P7BenchmarkCommands.register();"));
        assertFalse(entrypoint.contains("P7BenchmarkSceneSpawner.spawn("));
        assertFalse(entrypoint.contains("P7BenchmarkSceneSpawner.clear("));
        assertTrue(command.contains("Commands.literal(\"blendlib_showcase\")"));
        assertTrue(command.contains("Commands.literal(\"p7\")"));
        assertTrue(command.contains("Commands.literal(\"spawn\")"));
        assertTrue(command.contains("Permissions.COMMANDS_ADMIN"));
        assertTrue(command.contains("P7BenchmarkSceneSpawner.spawn(source.getLevel())"));
        assertTrue(spawner.contains("if (existing.total() != 0)"));
        assertTrue(spawner.contains("P7ReferenceScenario.RIGID_INSTANCE_COUNT"));
        assertTrue(spawner.contains("P7ReferenceScenario.SKINNED_INSTANCE_COUNT"));
    }

    @Test
    void benchmarkServerPathDoesNotLeakClientOrModelImplementationDependencies() throws IOException {
        String serverPath = String.join("\n", List.of(
                source("src/main/java/com/liy/blendlib/showcase/entity/P7BenchmarkHostEntity.java"),
                source("src/main/java/com/liy/blendlib/showcase/entity/P7BenchmarkRigidEntity.java"),
                source("src/main/java/com/liy/blendlib/showcase/entity/P7BenchmarkSkinnedEntity.java"),
                source("src/main/java/com/liy/blendlib/showcase/perf/scene/P7BenchmarkScenePlan.java"),
                source("src/main/java/com/liy/blendlib/showcase/perf/scene/P7BenchmarkSceneSpawner.java"),
                source("src/main/java/com/liy/blendlib/showcase/perf/scene/P7BenchmarkCommands.java")));

        for (String forbidden : List.of(
                "net.minecraft.client.",
                "com.liy.blendlib.fabric.client",
                "com.liy.blendlib.core.",
                "ModelAsset",
                "ResourceManager",
                "Minecraft.getInstance",
                "org.lwjgl",
                "glBind",
                "glDraw")) {
            assertFalse(serverPath.contains(forbidden), forbidden);
        }
        assertTrue(serverPath.contains("P7_BENCHMARK_RIGID"));
        assertTrue(serverPath.contains("P7_BENCHMARK_SKINNED"));
    }

    @Test
    void isolatedGradlePreparationNeverTargetsNormalShowcaseRuns() throws IOException {
        String gradle = source("build.gradle.kts");

        assertTrue(gradle.contains("create(\"p7BenchmarkClient\")"));
        assertTrue(gradle.contains("runDir(\"run/p7-benchmark\")"));
        assertTrue(gradle.contains("prepareP7BenchmarkClientRun"));
        assertTrue(gradle.contains("verifyP7BenchmarkClientRun"));
        assertTrue(gradle.contains("resourcepacks/blendlib-p7-reference"));
        assertTrue(gradle.contains("tasks.named(\"runP7BenchmarkClient\")"));
        assertTrue(gradle.contains("property(\"blendlib.showcase.p7.enabled\", \"true\")"));
        assertTrue(gradle.contains("blendlib.showcase.p7.output"));
        assertTrue(gradle.contains("run/p7-benchmark/benchmark-results"));
    }

    private static String source(String relativePath) throws IOException {
        Path project = Path.of(System.getProperty("blendlib.projectDir"));
        return Files.readString(project.resolve(relativePath));
    }
}
