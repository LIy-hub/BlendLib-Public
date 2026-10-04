package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class StaticMorphEntitySnapshotFactoryTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("static_morph:actor");
    private static final BlendResourceId SOCKET = BlendResourceId.parse("static_morph:hand");
    private static final Transform ROOT = new Transform(
            new Vec3(3, 4, 5), Quaternion.IDENTITY, new Vec3(2, 2, 2));
    private static final Transform REST_SOCKET = new Transform(
            new Vec3(16, 8, 0), Quaternion.IDENTITY, new Vec3(3, 3, 3));

    @Test
    void capturesCallbacksOnceInOrderWithTheSameImmutableRestPoseSockets() {
        var request = request();
        var frame = frame(42);
        var child = snapshot("child", 42);
        var calls = new ArrayList<String>();
        var observed = new AtomicReference<BlendEntitySockets>();

        // The capture helper never reads the entity; no Minecraft bootstrap is needed here.
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request, frame,
                (entity, callbackRequest, sockets) -> {
                    calls.add("sockets");
                    assertNull(entity);
                    assertSame(request, callbackRequest);
                    observed.set(sockets);
                    assertThrows(UnsupportedOperationException.class, () -> sockets.sockets().clear());
                },
                (entity, callbackRequest, sockets) -> {
                    calls.add("attachments");
                    assertNull(entity);
                    assertSame(request, callbackRequest);
                    assertSame(observed.get(), sockets);
                    return List.of(BlendEntityAttachment.at(sockets.socket(SOCKET).orElseThrow(), child));
                },
                () -> {
                    calls.add("current");
                    return true;
                }).orElseThrow();

        assertEquals(List.of("current", "sockets", "current", "attachments", "current"), calls);
        var sockets = observed.get();
        assertEquals(42, sockets.generation());
        var socket = sockets.socket(SOCKET).orElseThrow();
        assertEquals(16, socket.modelSpace().x());
        assertEquals(8, socket.modelSpace().y());
        assertEquals(5, socket.entitySpace().x());
        assertEquals(5, socket.entitySpace().y());
        assertEquals(5, socket.entitySpace().z());
        assertEquals(30_000_005.125, socket.worldSpace().x());
        assertEquals(75.25, socket.worldSpace().y());
        assertEquals(-29_999_995.875, socket.worldSpace().z());
        assertEquals(.375f, socket.entitySpace().scale());
        assertEquals(6, socket.attachmentPlacement().scale());
        assertSame(frame.renderSnapshot().handle(), result.handle());
        assertSame(frame.renderSnapshot().rootTransform(), result.rootTransform());
        assertSame(frame.renderSnapshot().culling(), result.culling());
        assertEquals(frame.renderSnapshot().packedLight(), result.packedLight());
        assertEquals(frame.renderSnapshot().packedOverlay(), result.packedOverlay());
        assertEquals(frame.renderSnapshot().tintArgb(), result.tintArgb());
        assertEquals(frame.renderSnapshot().visibility(), result.visibility());
        assertEquals(List.of(BlendEntityAttachment.at(socket, child)), result.attachments());
        assertTrue(frame.renderSnapshot().attachments().isEmpty());
    }

    @Test
    void noCallbacksReuseTheCapturedSnapshotAfterCheckingCurrent() {
        var frame = frame(42);
        var checks = new AtomicInteger();
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(
                null, request(), frame, null, null, () -> checks.incrementAndGet() == 1);
        assertSame(frame.renderSnapshot(), result.orElseThrow());
        assertEquals(1, checks.get());
        assertTrue(StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(
                null, request(), frame, null, null, () -> false).isEmpty());
    }

    @Test
    void socketObserverWorksWithoutAnAttachmentProvider() {
        var frame = frame(42);
        var calls = new AtomicInteger();
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame,
                (entity, request, sockets) -> {
                    calls.incrementAndGet();
                    assertTrue(sockets.socket(SOCKET).isPresent());
                }, null, () -> true).orElseThrow();
        assertEquals(1, calls.get());
        assertSame(frame.renderSnapshot(), result);
    }

    @Test
    void attachmentProviderWorksWithoutASocketObserver() {
        var calls = new AtomicInteger();
        var child = snapshot("child", 42);
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame(42),
                null, (entity, request, sockets) -> {
                    calls.incrementAndGet();
                    return List.of(BlendEntityAttachment.at(sockets.socket(SOCKET).orElseThrow(), child));
                }, () -> true).orElseThrow();
        assertEquals(1, calls.get());
        assertSame(child, result.attachments().getFirst().snapshot());
    }

    @Test
    void staleBeforeCaptureSkipsBothCallbacksAndSocketCapture() {
        // Null capture inputs prove the initial fence runs before any socket work.
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, null, null,
                (entity, request, sockets) -> fail("Stale extraction must not publish sockets"),
                (entity, request, sockets) -> fail("Stale extraction must not request attachments"),
                () -> false);
        assertTrue(result.isEmpty());
    }

    @Test
    void invalidationInSocketObserverSkipsAttachmentProviderAndDiscardsTheFrame() {
        var current = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame(42),
                (entity, request, sockets) -> {
                    calls.incrementAndGet();
                    current.set(false);
                },
                (entity, request, sockets) -> fail("Do not call the provider after socket invalidation"),
                current::get);
        assertTrue(result.isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void invalidationInSocketOnlyObserverStillDiscardsTheFrame() {
        var current = new AtomicBoolean(true);
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame(42),
                (entity, request, sockets) -> current.set(false), null, current::get);
        assertTrue(result.isEmpty());
    }

    @Test
    void invalidationInAttachmentProviderDiscardsItsCapturedChildren() {
        var frame = frame(42);
        var current = new AtomicBoolean(true);
        var calls = new ArrayList<String>();
        var result = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame,
                (entity, request, sockets) -> calls.add("sockets"),
                (entity, request, sockets) -> {
                    calls.add("attachments");
                    current.set(false);
                    return List.of(BlendEntityAttachment.at(sockets.socket(SOCKET).orElseThrow(),
                            snapshot("child", 42)));
                }, current::get);
        assertTrue(result.isEmpty());
        assertEquals(List.of("sockets", "attachments"), calls);
        assertTrue(frame.renderSnapshot().attachments().isEmpty());
    }

    @Test
    void retainedSocketsAndAttachmentsStayImmutableAcrossSourceMutationAndLaterCaptures() {
        var sourceSockets = new LinkedHashMap<BlendResourceId, Transform>();
        sourceSockets.put(SOCKET, REST_SOCKET);
        var frame = new ClientSkinnedExtractionFrame(root(42), sourceSockets);
        var sourceAttachments = new ArrayList<BlendEntityAttachment>();
        var child = snapshot("old_child", 42);
        var retainedSockets = new ArrayList<BlendEntitySockets>();
        var old = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame,
                (entity, request, sockets) -> retainedSockets.add(sockets),
                (entity, request, sockets) -> {
                    sourceAttachments.add(BlendEntityAttachment.at(sockets.socket(SOCKET).orElseThrow(), child));
                    return sourceAttachments;
                }, () -> true).orElseThrow();
        var retainedAttachment = old.attachments().getFirst();

        sourceSockets.clear();
        sourceAttachments.clear();
        var replacement = snapshot("new_child", 43);
        sourceAttachments.add(at(replacement));
        var next = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame(43),
                (entity, request, sockets) -> retainedSockets.add(sockets),
                (entity, request, sockets) -> sourceAttachments, () -> true).orElseThrow();
        sourceAttachments.clear();

        assertEquals(42, old.generation());
        assertEquals(42, retainedSockets.getFirst().generation());
        assertTrue(retainedSockets.getFirst().socket(SOCKET).isPresent());
        assertEquals(REST_SOCKET, frame.socketTransform(SOCKET).orElseThrow());
        assertEquals(List.of(retainedAttachment), old.attachments());
        assertSame(child, old.attachments().getFirst().snapshot());
        assertEquals(43, next.generation());
        assertEquals(43, retainedSockets.get(1).generation());
        assertNotSame(retainedSockets.getFirst(), retainedSockets.get(1));
        assertSame(replacement, next.attachments().getFirst().snapshot());
        assertThrows(UnsupportedOperationException.class, () -> old.attachments().clear());
        assertThrows(UnsupportedOperationException.class, () -> next.attachments().clear());
        assertThrows(UnsupportedOperationException.class, () -> retainedSockets.getFirst().sockets().clear());
    }

    @Test
    void repeatedCompositionSkipsStaleSubtreesWithoutRecallingCallbacksOrLifecycleChecks() {
        var stale = snapshot("stale", 41).withAttachments(List.of(at(snapshot("skipped", 42))));
        var leaf = snapshot("leaf", 42);
        var valid = snapshot("valid", 42).withAttachments(List.of(at(leaf)));
        var callbacks = new ArrayList<String>();
        var extractionComplete = new AtomicBoolean();
        var captured = StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(null, request(), frame(42),
                (entity, request, sockets) -> callbacks.add("sockets"),
                (entity, request, sockets) -> {
                    callbacks.add("attachments");
                    var socket = sockets.socket(SOCKET).orElseThrow();
                    return List.of(BlendEntityAttachment.at(socket, stale), BlendEntityAttachment.at(socket, valid));
                }, () -> {
                    assertFalse(extractionComplete.get(), "Composition must not consult the extraction lifecycle");
                    return true;
                }).orElseThrow();
        extractionComplete.set(true);

        for (int repeat = 0; repeat < 3; repeat++) {
            var composition = BlendEntityAttachmentComposition.capture(captured);
            assertEquals(List.of(valid, leaf), composition.attachments().stream()
                    .map(BlendEntityAttachment::snapshot).toList());
            assertEquals(List.of(new BlendEntityAttachmentComposition.Diagnostic(
                    BlendEntityAttachmentComposition.Reason.STALE_GENERATION,
                    stale.handle().modelKey(), 42, 41)), composition.diagnostics());
            assertEquals(List.of("sockets", "attachments"), callbacks);
            assertThrows(UnsupportedOperationException.class, () -> composition.attachments().clear());
        }
        assertEquals(List.of(stale, valid), captured.attachments().stream()
                .map(BlendEntityAttachment::snapshot).toList());
    }

    @Test
    void rejectsNullAttachmentListsAndElementsDuringCapture() {
        assertThrows(NullPointerException.class, () -> StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(
                null, request(), frame(42), null, (entity, request, sockets) -> null, () -> true));
        var attachments = new ArrayList<BlendEntityAttachment>();
        attachments.add(null);
        assertThrows(NullPointerException.class, () -> StaticMorphEntitySnapshotFactory.<Entity>captureAccessories(
                null, request(), frame(42), null, (entity, request, sockets) -> attachments, () -> true));
    }

    private static BlendEntitySnapshotRequest request() {
        return new BlendEntitySnapshotRequest(MODEL, .5f, 123, 12,
                30_000_000.125, 70.25, -30_000_000.875, 42, true, 1);
    }

    private static ClientSkinnedExtractionFrame frame(long generation) {
        return new ClientSkinnedExtractionFrame(root(generation), Map.of(SOCKET, REST_SOCKET));
    }

    private static ModelRenderSnapshot root(long generation) {
        return snapshot(MODEL, generation, ROOT, 1 / 16f);
    }

    private static ModelRenderSnapshot snapshot(String name, long generation) {
        return snapshot(BlendModelKey.parse("static_morph:" + name), generation, Transform.IDENTITY, 1);
    }

    private static ModelRenderSnapshot snapshot(BlendModelKey model, long generation, Transform root, float units) {
        ModelRenderHandle handle = new ModelRenderHandle() {
            public BlendModelKey modelKey() { return model; }
            public long generation() { return generation; }
            public Bounds bounds() { return new Bounds(Vec3.ZERO, Vec3.ONE); }
            public float unitsToBlocksScale() { return units; }
            public List<PreparedRenderPrimitive> primitives() { return List.of(); }
            public Transform nodeTransform(int index) { return Transform.IDENTITY; }
            public boolean missingModel() { return false; }
        };
        return new ModelRenderSnapshot(handle, root, 123, 456, 0xff123456,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
    }

    private static BlendEntityAttachment at(ModelRenderSnapshot snapshot) {
        return new BlendEntityAttachment(BlendEntitySocketPose.IDENTITY, BlendEntitySocketPose.IDENTITY, snapshot);
    }
}
