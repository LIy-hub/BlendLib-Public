package com.liy.blendlib.fabric.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.MissingModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

class ClientAdapterContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("adapter_test:models/rigid");

    @Test
    void lookupBuildsMissingHandleOnlyOutsideSubmitAndPreservesImmutableRegistryView() {
        ClientModelLookup lookup = new RegistryBackedModelLookup(new ClientModelRegistry());

        ClientModelView unknown = lookup.resolve(KEY);
        assertFalse(unknown.discovered());
        assertTrue(unknown.missing());
        assertEquals(0L, unknown.generationId());
        assertEquals("BLENDLIB-DESC-002", unknown.primaryDiagnostic().orElseThrow().code());

        ClientRegistryView view = lookup.snapshot();
        assertEquals(0L, view.generationId());
        assertTrue(view.models().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> view.models().put(KEY, unknown));
    }

    @Test
    void lowLevelSubmitPassesOnlyPreparedSnapshotToBackendAndHonorsPreparedCulling() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(KEY, 4L);
        ModelRenderSnapshot visible = snapshot(handle, RenderVisibility.VISIBLE);
        AtomicReference<ModelRenderSnapshot> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        BlendRenderer renderer = new BlendRenderer((snapshot, context) -> {
            calls.incrementAndGet();
            received.set(snapshot);
        });
        PoseStack poseStack = new PoseStack();
        SubmitNodeCollector collector = unusedCollector();

        renderer.submit(visible, poseStack, collector);
        assertEquals(1, calls.get());
        assertSame(visible, received.get());

        renderer.submit(snapshot(handle, RenderVisibility.CULLED), poseStack, collector);
        assertEquals(1, calls.get());
    }

    @Test
    void publicAdapterSourcesDoNotReachCoreLoadersRegistriesOrResourceIoFromSubmit() throws IOException {
        String source = readSourceFile("api", "BlendRenderer.java").concat(readSourceTree("entity"));
        for (String forbidden : new String[] {
                "ModelAssetLoader", "AssetResolver", "GlbReader", "StrictJsonParser", "ResourceManager",
                "java.nio.file.", "java.io.", "Minecraft.getInstance", "ClientModelRegistry"}) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("ModelRenderSnapshot"));
        assertTrue(source.contains("SubmitNodeCollector"));
        for (String publicApiFile : new String[] {"ClientDiagnostic.java", "ClientModelView.java", "ClientRegistryView.java"}) {
            assertFalse(readSourceFile("api", publicApiFile).contains("com.liy.blendlib.core."), publicApiFile);
        }
    }

    private static ModelRenderSnapshot snapshot(MissingModelRenderHandle handle, RenderVisibility visibility) {
        return new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                visibility,
                new CullingMetadata(handle.bounds(), true));
    }

    private static SubmitNodeCollector unusedCollector() {
        return (SubmitNodeCollector) Proxy.newProxyInstance(
                ClientAdapterContractsTest.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("The test backend must not invoke collector method " + method.getName());
                });
    }

    private static String readSourceTree(String packageName) throws IOException {
        Path root = Path.of(System.getProperty("blendlib.projectDir"), "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", packageName);
        try (Stream<Path> paths = Files.walk(root)) {
            StringBuilder combined = new StringBuilder();
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(path));
            }
            return combined.toString();
        }
    }

    private static String readSourceFile(String packageName, String fileName) throws IOException {
        return Files.readString(Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "client",
                "java",
                "com",
                "liy",
                "blendlib",
                "fabric",
                "client",
                packageName,
                fileName));
    }
}
