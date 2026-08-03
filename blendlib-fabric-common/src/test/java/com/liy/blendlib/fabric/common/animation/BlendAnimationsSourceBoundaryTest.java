package com.liy.blendlib.fabric.common.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BlendAnimationsSourceBoundaryTest {
    @Test
    void publicServerFacadeCarriesOnlySemanticAnimationInputsAndNoClientOrModelPayload() throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java", "com", "liy",
                "blendlib", "fabric", "common", "animation", "BlendAnimations.java");
        String text = Files.readString(source);

        assertTrue(text.contains("BlendAnimationKey"));
        assertTrue(text.contains("trigger("));
        assertTrue(text.contains("setPersistent("));
        assertFalse(text.contains("net.minecraft.client."));
        assertFalse(text.contains("BlendModelKey"));
        assertFalse(text.contains("ModelAsset"));
        assertFalse(text.contains("Glb"));
    }
}
