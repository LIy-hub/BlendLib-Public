package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnimationV2SourceBoundaryTest {
    @Test
    void hotRuntimeRemainsPureAndUsesQueueOwnerAndAtomicSnapshotBoundaries() throws IOException {
        Path source = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java", "com", "liy",
                "blendlib", "core", "animation", "v2", "AnimationV2InstanceRuntime.java");
        String text = Files.readString(source).toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of(
                "import java.io.",
                "import java.nio.",
                "net.minecraft.",
                "net.fabricmc.",
                "com.liy.blendlib.core.loader.",
                "com.liy.blendlib.core.glb.",
                "com.liy.blendlib.core.json.",
                "resourcemanager",
                "assetresolver")) {
            assertFalse(text.contains(forbidden), () -> "runtime must not reference " + forbidden);
        }
        assertTrue(text.contains("arrayblockingqueue"));
        assertTrue(text.contains("atomicreference"));
        assertTrue(text.contains("claimowner"));
        assertTrue(text.contains("advanceatframe"));
        assertTrue(text.contains("max_ingress_queue_per_instance"));

        int evaluateStart = text.indexOf("private animationv2evaluationsnapshot evaluate");
        int evaluateEnd = text.indexOf("private void claimowner", evaluateStart);
        assertTrue(evaluateStart >= 0 && evaluateEnd > evaluateStart);
        String evaluate = text.substring(evaluateStart, evaluateEnd);
        assertTrue(evaluate.contains("sampleinto("));
        assertTrue(evaluate.contains("animationv2pose.takeownership"));
        assertFalse(evaluate.contains("layersample"));
        assertFalse(evaluate.contains("new arraylist"));
        assertFalse(evaluate.contains("new animationv2pose"));
    }
}
