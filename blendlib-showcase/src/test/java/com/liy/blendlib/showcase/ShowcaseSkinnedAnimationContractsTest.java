package com.liy.blendlib.showcase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShowcaseSkinnedAnimationContractsTest {
    @Test
    void canonicalDescriptorMatchesTheClientSemanticBindingWithoutLeakingItIntoCommonSources() throws IOException {
        Path project = Path.of(System.getProperty("blendlib.projectDir"));
        String descriptor = Files.readString(project.resolve(
                "src/main/resources/assets/blendlib_showcase/blend_models/showcase_animation/showcase_actor.json"));
        String mainSources = readJavaSources("main");

        assertTrue(descriptor.contains("\"profile\": \"blendlib:skinned_v1\""));
        assertTrue(descriptor.contains("\"mesh\": \"blendlib_showcase:models3d/showcase_animation/showcase_actor.glb\""));
        assertTrue(descriptor.contains("\"initial_state\": \"blendlib_showcase:idle\""));
        assertTrue(descriptor.contains("\"blendlib_showcase:idle\""));
        assertTrue(descriptor.contains("\"blendlib_showcase:walk\""));
        assertTrue(descriptor.contains("\"blendlib_showcase:attack\""));
        assertTrue(descriptor.contains("\"next\": \"blendlib_showcase:idle\""));
        assertTrue(descriptor.contains("\"event\": \"blendlib_showcase:attack_whoosh\""));
        assertFalse(mainSources.contains("ShowcaseSkinnedAnimationBinding"));
        assertFalse(mainSources.contains("showcase_animation/showcase_actor"));
        assertFalse(mainSources.contains("com.liy.blendlib.fabric.client"));
    }

    private static String readJavaSources(String sourceSet) throws IOException {
        Path sourceRoot = Path.of(System.getProperty("blendlib.projectDir"), "src", sourceSet, "java");
        StringBuilder combined = new StringBuilder();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(sourceFile));
            }
        }
        return combined.toString();
    }
}
