package com.liy.blendlib.fabric.client.animation.v2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ClientAnimationV2SourceBoundaryTest {
    @Test
    void v2ClientRuntimeHasNoPlatformLifecycleReloadRenderOrAssetImports() throws IOException {
        Path sourceDirectory = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com",
                "liy", "blendlib", "fabric", "client", "animation", "v2");
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sources.isEmpty());
            for (Path source : sources) {
                String lower = Files.readString(source).toLowerCase(java.util.Locale.ROOT);
                for (String forbidden : List.of(
                        "import net.minecraft.",
                        "import net.fabricmc.",
                        "import java.io.",
                        "import java.nio.",
                        "import com.liy.blendlib.fabric.client.render.",
                        "import com.liy.blendlib.fabric.client.reload.",
                        "import com.liy.blendlib.fabric.common.network.",
                        "import com.liy.blendlib.core.loader.",
                        "import com.liy.blendlib.core.glb.")) {
                    assertFalse(lower.contains(forbidden), () -> source.getFileName() + " must not import " + forbidden);
                }
            }
        }
        String runtime = Files.readString(sourceDirectory.resolve("ClientAnimationV2Runtime.java")).toLowerCase(java.util.Locale.ROOT);
        assertTrue(runtime.contains("arrayblockingqueue"));
        assertTrue(runtime.contains("atomicreference"));
        assertTrue(runtime.contains("claimowner"));
        assertTrue(runtime.contains("animationintentreconciler"));
        assertTrue(runtime.contains("advanceatframe"));
        assertTrue(runtime.contains("rebindgeneration"));
        assertTrue(runtime.contains("max_ingress_queue_per_instance"));
        assertFalse(runtime.contains("incoming.clear"));
    }
}
