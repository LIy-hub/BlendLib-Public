package com.liy.blendlib.fixture.localmaven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalMavenRcConsumerBoundaryTest {
    @Test
    void publicRcApiIsUsableWithoutAnImplementationImport() {
        assertEquals("local_maven_consumer:blank_model", LocalMavenRcConsumerEntrypoint.MODEL.value());
        assertEquals("local_maven_consumer:idle", LocalMavenRcConsumerEntrypoint.IDLE.value());
    }

    @Test
    void sourceStaysOnThePublicSemanticSurface() throws IOException {
        String source = Files.readString(mainSource("LocalMavenRcConsumerEntrypoint.java"));

        assertFalse(source.contains("com.liy.blendlib.core."));
        assertFalse(source.contains("com.liy.blendlib.fabric.common."));
        assertFalse(source.contains("com.liy.blendlib.fabric.client."));
        assertFalse(source.contains(".impl."));
    }

    @Test
    void poseModifierProbeUsesOnlyTheOuterMavenAdapterSurface() throws IOException {
        String source = Files.readString(mainSource("LocalMavenPoseModifierCompileProbe.java"));
        String build = Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"), "build.gradle.kts"));

        assertTrue(source.contains(".poseModifier("));
        assertTrue(source.contains(".rootRotation("));
        assertTrue(source.contains("context.rig().requireNodeIndex("));
        assertTrue(source.contains("BlendEntityRotation.normalized("));
        assertTrue(source.contains("basePose.withRotation("));
        assertFalse(source.contains("com.liy.blendlib.core."));
        assertFalse(source.contains("com.liy.blendlib.fabric.common."));
        assertFalse(source.contains(".impl."));
        assertTrue(build.contains("implementation(blendLibCoordinate)"));
        assertFalse(build.contains("implementation(project("));
        assertFalse(build.contains("blendlib-core"));
    }

    private static Path mainSource(String fileName) {
        return Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "main", "java", "com", "liy", "blendlib", "fixture", "localmaven",
                fileName);
    }
}
