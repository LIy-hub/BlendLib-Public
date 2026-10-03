package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlendEntitySocketsTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("socket_test:actor");
    private static final BlendResourceId SOCKET = BlendResourceId.parse("socket_test:hand");

    @Test
    void capturesFinalModelEntityAndWorldPosesInRenderOrderWithoutLosingWorldPrecision() {
        float half = (float) Math.sqrt(0.5);
        Transform root = new Transform(new Vec3(3, 4, 5), new Quaternion(0, half, 0, half), new Vec3(2, 2, 2));
        Transform socket = new Transform(new Vec3(16, 8, 0), new Quaternion(0, 0, half, half), new Vec3(3, 3, 3));
        var frame = new ClientSkinnedExtractionFrame(snapshot(root, 1 / 16f), Map.of(SOCKET, socket));
        var captured = BlendEntitySockets.capture(request(), frame);
        var value = captured.socket(SOCKET).orElseThrow();
        assertEquals(42, captured.generation());
        assertEquals(16, value.modelSpace().x());
        assertEquals(3, value.entitySpace().x(), 1e-5);
        assertEquals(5, value.entitySpace().y(), 1e-5);
        assertEquals(3, value.entitySpace().z(), 1e-5);
        assertEquals(30_000_003.125, value.worldSpace().x(), 1e-5);
        assertEquals(75.25, value.worldSpace().y(), 1e-5);
        assertEquals(-29_999_997.875, value.worldSpace().z(), 1e-5);
        assertEquals(0.375f, value.entitySpace().scale());
        assertEquals(6f, value.attachmentPlacement().scale());
        Quaternion expected = root.rotation().multiply(socket.rotation());
        var actual = value.worldSpace().rotation();
        assertEquals(expected.x(), actual.x(), 1e-6);
        assertEquals(expected.y(), actual.y(), 1e-6);
        assertEquals(expected.z(), actual.z(), 1e-6);
        assertEquals(expected.w(), actual.w(), 1e-6);
    }

    @Test
    void capturedFrameIsImmutableAndUnknownSocketIsEmpty() {
        var source = new LinkedHashMap<BlendResourceId, Transform>();
        source.put(SOCKET, Transform.IDENTITY);
        var frame = new ClientSkinnedExtractionFrame(snapshot(Transform.IDENTITY, 1), source);
        var sockets = BlendEntitySockets.capture(request(), frame);
        source.clear();
        assertEquals(1, sockets.sockets().size());
        assertTrue(sockets.socket(BlendResourceId.parse("socket_test:missing")).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> sockets.sockets().clear());
        assertThrows(NullPointerException.class, () -> sockets.socket(null));
    }

    @Test
    void attachmentCapturesChildAndDoesNotApplyParentUnitsToChildGeometry() {
        Transform socket = new Transform(new Vec3(16, 0, 0), Quaternion.IDENTITY, Vec3.ONE);
        var sockets = BlendEntitySockets.capture(request(), new ClientSkinnedExtractionFrame(
                snapshot(Transform.IDENTITY, 1 / 16f), Map.of(SOCKET, socket)));
        var child = snapshot(Transform.IDENTITY, 1 / 8f);
        var attachment = BlendEntityAttachment.at(sockets.socket(SOCKET).orElseThrow(),
                new BlendEntitySocketPose(0, 0.5, 0, BlendEntityRotation.IDENTITY, 1), child);
        assertSame(child, attachment.snapshot());
        PoseStack stack = new PoseStack();
        BlendEntityAttachmentSubmitter.apply(stack, attachment.placement());
        BlendEntityAttachmentSubmitter.apply(stack, attachment.offset());
        // Child backend applies its own conversion exactly once after the attachment placement.
        float childUnits = child.handle().unitsToBlocksScale();
        stack.scale(childUnits, childUnits, childUnits);
        Vector3f rendered = stack.last().pose().transformPosition(new Vector3f(8, 0, 0));
        assertEquals(2, rendered.x(), 1e-6);
        assertEquals(0.5, rendered.y(), 1e-6);
        assertEquals(0, rendered.z(), 1e-6);
    }

    @Test
    void submitConsumesCapturedChildAndRestoresStackEvenWhenBackendThrows() {
        var child = snapshot(Transform.IDENTITY, 1);
        var attachment = new BlendEntityAttachment(
                new BlendEntitySocketPose(1, 2, 3, BlendEntityRotation.IDENTITY, 1),
                BlendEntitySocketPose.IDENTITY, child);
        PoseStack stack = new PoseStack();
        var before = new org.joml.Matrix4f(stack.last().pose());
        var collector = (net.minecraft.client.renderer.SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {net.minecraft.client.renderer.SubmitNodeCollector.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected collector call"); });
        var renderer = new com.liy.blendlib.fabric.client.api.BlendRenderer((captured, context) -> {
            assertSame(child, captured);
            Vector3f origin = context.poseStack().last().pose().transformPosition(new Vector3f());
            assertEquals(1, origin.x());
            assertEquals(2, origin.y());
            assertEquals(3, origin.z());
            throw new IllegalStateException("Expected backend failure");
        });
        assertThrows(IllegalStateException.class,
                () -> BlendEntityAttachmentSubmitter.submit(List.of(attachment), renderer, stack, collector));
        assertEquals(before, stack.last().pose());
    }

    @Test
    void rejectsInvalidPublicValues() {
        assertThrows(IllegalArgumentException.class, () -> new BlendEntitySocketPose(Double.NaN, 0, 0, BlendEntityRotation.IDENTITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlendEntitySocketPose(0, 0, 0, BlendEntityRotation.IDENTITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlendEntitySocketPose(0, 0, 0, BlendEntityRotation.IDENTITY, Float.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> BlendEntityAttachment.at(null, snapshot(Transform.IDENTITY, 1)));
    }

    private static BlendEntitySnapshotRequest request() {
        return new BlendEntitySnapshotRequest(MODEL, 0.5f, 0, 12, 30_000_000.125, 70.25, -30_000_000.875, 42, true, 1);
    }

    private static ModelRenderSnapshot snapshot(Transform root, float units) {
        ModelRenderHandle handle = new ModelRenderHandle() {
            public BlendModelKey modelKey() { return MODEL; }
            public long generation() { return 42; }
            public Bounds bounds() { return new Bounds(Vec3.ZERO, Vec3.ONE); }
            public float unitsToBlocksScale() { return units; }
            public List<PreparedRenderPrimitive> primitives() { return List.of(); }
            public Transform nodeTransform(int index) { return Transform.IDENTITY; }
            public boolean missingModel() { return false; }
        };
        return new ModelRenderSnapshot(handle, root, 0, 0, -1, RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
    }
}
