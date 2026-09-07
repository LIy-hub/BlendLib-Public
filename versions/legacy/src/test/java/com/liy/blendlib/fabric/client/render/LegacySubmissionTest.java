package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.compat.LegacySubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;

/** Uses target Minecraft's real PoseStack, RenderType and VertexConsumer default transforms. */
class LegacySubmissionTest {
    @BeforeAll static void bootstrapLegacyRegistries() {
        // In 1.21.1, RenderType's static glint layers initialize Items through ItemRenderer.
        if ("1.21.1".equals(System.getProperty("blendlib.test.minecraft"))) {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        }
    }
    @Test void missingModelEmitsBothTrianglesAsQuadsAndRestoresCallerPose() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(BlendModelKey.parse("test:missing"), 1);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(handle, Transform.IDENTITY, 0, 0,
                0xFFFFFFFF, RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        PoseStack poses = new PoseStack();
        poses.translate(2, 3, 4);
        RecordingVertices vertices = new RecordingVertices();
        List<Object> requestedLayers = new ArrayList<>();
        LegacySubmitNodeCollector collector = new LegacySubmitNodeCollector(type -> {
            requestedLayers.add(type);
            return vertices;
        });
        new Minecraft2612StaticRigidRenderBackend().submit(snapshot, new RenderSubmissionContext(poses, collector));
        assertEquals(2, requestedLayers.size());
        assertTrue(requestedLayers.stream().allMatch(java.util.Objects::nonNull));
        assertEquals(8, vertices.positions.size());
        assertArrayEquals(new float[] {1.75f, 2.75f, 4f}, vertices.positions.getFirst(), 0.00001f);
        assertArrayEquals(vertices.positions.get(2), vertices.positions.get(3));
        assertEquals(2f, poses.last().pose().m30());
        assertEquals(3f, poses.last().pose().m31());
        assertEquals(4f, poses.last().pose().m32());
        assertEquals(8, vertices.normalCount);
        assertEquals(8, vertices.lightCount);
    }
    @Test void culledSnapshotDoesNotAcquireAnyLegacyVertexBuffer() {
        MissingModelRenderHandle handle = new MissingModelRenderHandle(BlendModelKey.parse("test:culled"), 2);
        ModelRenderSnapshot snapshot = new ModelRenderSnapshot(handle, Transform.IDENTITY, 0, 0,
                0xFFFFFFFF, RenderVisibility.CULLED, new CullingMetadata(handle.bounds(), false));
        LegacySubmitNodeCollector collector = new LegacySubmitNodeCollector(type -> {
            fail("Culled snapshots must not acquire a buffer"); return null;
        });
        new Minecraft2612StaticRigidRenderBackend().submit(snapshot, new RenderSubmissionContext(new PoseStack(), collector));
    }
    private static final class RecordingVertices implements VertexConsumer {
        final List<float[]> positions = new ArrayList<>();
        int normalCount;
        int lightCount;
        @Override public VertexConsumer addVertex(float x, float y, float z) { positions.add(new float[] {x,y,z}); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { lightCount++; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { normalCount++; return this; }
    }
}
