package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class X7ClientMeasurementSnapshotAdapterSourceBoundaryTest {
    @Test
    void clientAdapterOnlyWrapsTheExistingPublicMeasurementSnapshot() throws Exception {
        Path project = Path.of(System.getProperty("blendlib.projectDir"));
        String source = Files.readString(project.resolve(
                "src/client/java/com/liy/blendlib/showcase/perf/x7/X7ClientMeasurementSnapshotAdapter.java"));

        for (String forbidden : List.of(
                "net.minecraft.",
                "org.lwjgl",
                "glBind",
                "glDraw",
                "java.nio.file.",
                "jdk.jfr",
                "beginCapture",
                "completeFrame")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("ClientRenderMeasurementSnapshot"));
        assertTrue(source.contains("ClientAnimationRuntimeMetrics"));
        assertTrue(source.contains("does not start capture"));
    }
}
