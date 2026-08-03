package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeHotPathBoundaryTest {
    @Test
    void controllerSamplerSocketAndSkinningSourcesDoNotImportLoadingParsingOrPlatformApis() throws IOException {
        Path sourceRoot = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "main",
                "java",
                "com",
                "liy",
                "blendlib",
                "core",
                "animation",
                "runtime");
        assertTrue(Files.isDirectory(sourceRoot), () -> "Missing runtime source root: " + sourceRoot);
        List<String> sources = List.of(
                "AnimationController.java",
                "PoseSampler.java",
                "SocketWorldTransform.java",
                "CpuSkinner.java",
                "PreparedSkinnedGeometry.java",
                "CpuSkinnedMesh.java",
                "SkinnedMeshTopology.java",
                "CompiledAnimationChannel.java",
                "SkinPalette.java",
                "NodePalette.java",
                "AnimationControllerDefinition.java");
        List<String> forbiddenImports = List.of(
                "import java.io.",
                "import java.nio.file.",
                "import java.nio.channels.",
                "import com.liy.blendlib.core.asset.",
                "import com.liy.blendlib.core.json.",
                "import com.liy.blendlib.core.glb.",
                "import net.minecraft.",
                "import net.fabricmc.",
                "Minecraft.getInstance()");
        for (String sourceName : sources) {
            Path sourcePath = sourceRoot.resolve(sourceName);
            assertTrue(Files.isRegularFile(sourcePath), () -> "Missing runtime source: " + sourcePath);
            String source = Files.readString(sourcePath);
            for (String forbiddenImport : forbiddenImports) {
                assertFalse(source.contains(forbiddenImport), () -> sourcePath + " contains " + forbiddenImport);
            }
        }
    }

    @Test
    void cpuSkinnerUsesPreparedArraysWithoutDecodedPrimitiveCopies() throws IOException {
        Path sourcePath = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "main",
                "java",
                "com",
                "liy",
                "blendlib",
                "core",
                "animation",
                "runtime",
                "CpuSkinner.java");
        String source = Files.readString(sourcePath);
        String outputSource = Files.readString(sourcePath.resolveSibling("CpuSkinnedMesh.java"));
        String topologySource = Files.readString(sourcePath.resolveSibling("SkinnedMeshTopology.java"));

        assertTrue(source.contains("PreparedSkinnedGeometry"));
        assertFalse(source.contains("MeshPrimitive"));
        assertFalse(source.contains(".positions()"));
        assertFalse(source.contains(".normals()"));
        assertFalse(source.contains(".joints()"));
        assertFalse(source.contains(".weights()"));
        assertFalse(source.contains("Arrays.copyOf"));
        assertTrue(outputSource.contains("SkinnedMeshTopology"));
        assertFalse(outputSource.contains("MeshPrimitive"));
        assertFalse(topologySource.contains("java.io."));
        assertFalse(topologySource.contains("java.nio.file."));
        assertFalse(topologySource.contains("com.liy.blendlib.core.asset."));
        assertFalse(topologySource.contains("com.liy.blendlib.core.glb."));
    }
}
