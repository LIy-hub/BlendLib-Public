package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the public Showcase runtime against reintroducing phase-only commands or entities. */
class PhaseGameplayArtifactsRetiredTest {
    @Test
    void commonEntrypointDoesNotRegisterPhaseCommandsOrEntityTypes() throws IOException {
        Path project = projectRoot();
        String entrypoint = Files.readString(project.resolve(
                "src/main/java/com/liy/blendlib/showcase/BlendLibShowcaseEntrypoint.java"));

        assertFalse(entrypoint.contains("ShowcaseEntities"));
        assertFalse(entrypoint.contains("P7BenchmarkCommands"));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/liy/blendlib/showcase/entity/ShowcaseEntities.java")));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/liy/blendlib/showcase/entity/ShowcaseAnimatedActorEntity.java")));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/liy/blendlib/showcase/entity/P7BenchmarkHostEntity.java")));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/liy/blendlib/showcase/perf/scene/P7BenchmarkCommands.java")));
    }

    @Test
    void clientEntrypointDoesNotMountPhaseEntityRenderersOrCaptureHooks() throws IOException {
        String entrypoint = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/BlendLibShowcaseClientEntrypoint.java"));

        assertFalse(entrypoint.contains("BlendEntityRenderers"));
        assertFalse(entrypoint.contains("P7BenchmarkCaptureController"));
        assertFalse(entrypoint.contains("ClientTickEvents"));
        assertFalse(entrypoint.contains("LevelRenderEvents"));
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }
}
