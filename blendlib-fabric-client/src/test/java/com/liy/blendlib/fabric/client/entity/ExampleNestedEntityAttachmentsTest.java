package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiles and executes the actual public consumer source, not a copy of its algorithm. */
class ExampleNestedEntityAttachmentsTest {
    @TempDir static Path output;
    private static URLClassLoader consumerLoader;
    private static Method capture;
    private static final BlendResourceId HAND = BlendResourceId.parse("example:hand");
    private static final BlendResourceId MOUNT = BlendResourceId.parse("example:ornament_mount");

    @BeforeAll
    static void compileActualConsumerSource() throws Exception {
        Path root = Path.of(System.getProperty("blendlib.projectDir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("examples/nested-entity-attachments"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Repository root must contain the isolated consumer fixture");
        Path source = root.resolve("examples/nested-entity-attachments/src/main/java/com/liy/blendlib/examples/attachments/ExampleNestedEntityAttachments.java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Consumer fixture compilation requires a JDK");
        var paths = new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        // Gradle/Fabric test workers can expose dependencies through their loader rather than java.class.path.
        for (Class<?> type : List.of(BlendModelKey.class, ModelRenderSnapshot.class,
                BlendEntityAttachmentComposition.class, Transform.class)) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        for (ClassLoader loader = ExampleNestedEntityAttachmentsTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
                }
            }
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-implicit:none", "--source-path", "", "--class-path",
                            String.join(File.pathSeparator, paths), "-d", output.toString()),
                    null, files.getJavaFileObjects(source.toFile())).call();
            assertTrue(success, () -> "Consumer source failed to compile: " + diagnostics.getDiagnostics());
        }
        consumerLoader = new URLClassLoader(new URL[] {output.toUri().toURL()}, ExampleNestedEntityAttachmentsTest.class.getClassLoader());
        Class<?> example = Class.forName("com.liy.blendlib.examples.attachments.ExampleNestedEntityAttachments", true, consumerLoader);
        capture = example.getMethod("capture", ModelRenderSnapshot.class, BlendEntitySockets.class,
                ModelRenderSnapshot.class, BlendEntitySockets.class, ModelRenderSnapshot.class);
    }

    @AfterAll
    static void closeConsumerLoader() throws Exception {
        if (consumerLoader != null) consumerLoader.close();
    }

    @Test
    void actualConsumerDrawsWeaponAndOrnamentAtFinalSocketPlacements() throws Exception {
        var character = snapshot("character", root(2, 2), 1 / 16F);
        var weapon = snapshot("weapon", root(1, 3), 1 / 8F);
        var ornament = snapshot("ornament", Transform.IDENTITY, 1 / 4F).withLighting(123, 456);
        var hand = sockets(character, HAND, new Vec3(16, 0, 0));
        var mount = sockets(weapon, MOUNT, new Vec3(0, 8, 0));
        var result = (BlendEntityAttachmentComposition) capture.invoke(null, character, hand, weapon, mount, ornament);
        assertEquals(2, result.attachments().size());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(weapon.handle().modelKey(), result.attachments().get(0).snapshot().handle().modelKey());
        assertSame(ornament, result.attachments().get(1).snapshot());
        assertEquals(123, result.attachments().get(1).snapshot().packedLight());
        assertEquals(456, result.attachments().get(1).snapshot().packedOverlay());
        var origins = new ArrayList<Vector3f>();
        var renderer = new BlendRenderer((snapshot, context) ->
                origins.add(context.poseStack().last().pose().transformPosition(new Vector3f())));
        var collector = (SubmitNodeCollector) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Test renderer must not call collector");
                });
        var stack = new PoseStack();
        BlendEntityAttachmentSubmitter.submit(result.attachments(), renderer, stack, collector);
        assertEquals(2, origins.size());
        assertEquals(4, origins.get(0).x(), 1e-5);
        assertEquals(0, origins.get(0).y(), 1e-5);
        assertEquals(6, origins.get(1).x(), 1e-5);
        assertEquals(6.3, origins.get(1).y(), 1e-5);
        assertEquals(0, origins.get(1).z(), 1e-5);
        assertEquals(Transform.IDENTITY, ornament.rootTransform());
    }

    @Test
    void actualConsumerRejectsMissingRequiredSocketAndStaleSocketCapture() {
        var character = snapshot("character", Transform.IDENTITY, 1);
        var weapon = snapshot("weapon", Transform.IDENTITY, 1);
        var ornament = snapshot("ornament", Transform.IDENTITY, 1);
        var hand = sockets(character, HAND, Vec3.ZERO);
        var mount = sockets(weapon, MOUNT, Vec3.ZERO);
        assertConsumerRejects(character, new BlendEntitySockets(42, Map.of()), weapon, mount, ornament,
                "Required example socket is absent");
        assertConsumerRejects(character, hand, weapon, new BlendEntitySockets(42, Map.of()), ornament,
                "Required example socket is absent");
        assertConsumerRejects(character, new BlendEntitySockets(41, hand.sockets()), weapon, mount, ornament,
                "Final sockets must match");
        assertConsumerRejects(character, hand, weapon, new BlendEntitySockets(41, mount.sockets()), ornament,
                "Final sockets must match");
    }

    private static void assertConsumerRejects(ModelRenderSnapshot character, BlendEntitySockets hand,
            ModelRenderSnapshot weapon, BlendEntitySockets mount, ModelRenderSnapshot ornament, String message) {
        var thrown = assertThrows(InvocationTargetException.class,
                () -> capture.invoke(null, character, hand, weapon, mount, ornament));
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains(message));
    }

    private static Transform root(float translation, float scale) {
        return new Transform(new Vec3(translation, 0, 0), Quaternion.IDENTITY, new Vec3(scale, scale, scale));
    }

    private static BlendEntitySockets sockets(ModelRenderSnapshot snapshot, BlendResourceId key, Vec3 translation) {
        var request = new BlendEntitySnapshotRequest(snapshot.handle().modelKey(), 0, 0, 0, 0, 0, 0, 0, true, 0);
        return BlendEntitySockets.capture(request, new ClientSkinnedExtractionFrame(snapshot,
                Map.of(key, new Transform(translation, Quaternion.IDENTITY, Vec3.ONE))));
    }

    private static ModelRenderSnapshot snapshot(String name, Transform root, float units) {
        var handle = new ModelRenderHandle() {
            public BlendModelKey modelKey() { return BlendModelKey.parse("consumer:" + name); }
            public long generation() { return 42; }
            public Bounds bounds() { return new Bounds(Vec3.ZERO, Vec3.ONE); }
            public float unitsToBlocksScale() { return units; }
            public List<PreparedRenderPrimitive> primitives() { return List.of(); }
            public Transform nodeTransform(int index) { return Transform.IDENTITY; }
            public boolean missingModel() { return false; }
        };
        return new ModelRenderSnapshot(handle, root, 0, 0, -1, RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), false));
    }
}
