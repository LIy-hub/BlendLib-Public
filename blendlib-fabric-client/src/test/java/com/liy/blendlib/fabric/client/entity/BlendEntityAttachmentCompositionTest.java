package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlendEntityAttachmentCompositionTest {
    private static final BlendResourceId SOCKET = BlendResourceId.parse("assembly:socket");

    @Test
    void emptyCaptureReusesImmutableEmptyResult() {
        var first = BlendEntityAttachmentComposition.capture(snapshot("first", 1));
        var second = BlendEntityAttachmentComposition.capture(snapshot("second", 2));
        assertSame(first, second);
        assertTrue(first.attachments().isEmpty());
        assertTrue(first.diagnostics().isEmpty());
    }

    @Test
    void capturesThreeLevelsUsingFinalSocketsRootsUnitsAndOffsetsExactlyOnce() {
        float half = (float)Math.sqrt(.5);
        var character = snapshot("character", 42, new Transform(new Vec3(2, 3, 4),
                new Quaternion(0, half, 0, half), new Vec3(2, 2, 2)), 1/16f, RenderVisibility.VISIBLE);
        var weapon = snapshot("weapon", 42, new Transform(new Vec3(1, 2, 0),
                new Quaternion(0, 0, half, half), new Vec3(3, 3, 3)), 1/8f, RenderVisibility.VISIBLE);
        var ornament = snapshot("ornament", 42, new Transform(new Vec3(1, 0, 0), Quaternion.IDENTITY, Vec3.ONE),
                1/4f, RenderVisibility.VISIBLE).withLighting(123, 456).withMaterialAppearance(Map.of("unknown", MaterialSlotAppearance.unchanged()));
        // These transforms are the captured final modified socket poses, not bind/rest poses.
        var hand = socket(character, new Transform(new Vec3(16, 2, 0), Quaternion.IDENTITY, Vec3.ONE));
        var mount = socket(weapon, new Transform(new Vec3(0, 8, 0), Quaternion.IDENTITY, Vec3.ONE));
        var offset = new BlendEntitySocketPose(.5, 1, 0, BlendEntityRotation.IDENTITY, 2);
        var nested = BlendEntityAttachment.at(mount, offset, ornament);
        var weaponFrame = weapon.withAttachments(List.of(nested));
        var direct = BlendEntityAttachment.at(hand, offset, weaponFrame);
        var assembly = character.withAttachments(List.of(direct));
        var composition = BlendEntityAttachmentComposition.capture(assembly);
        assertEquals(2, composition.attachments().size());
        assertTrue(composition.diagnostics().isEmpty());
        assertSame(weaponFrame, composition.attachments().get(0).snapshot());
        assertSame(ornament, composition.attachments().get(1).snapshot());
        assertEquals(123, composition.attachments().get(1).snapshot().packedLight());
        assertEquals(456, composition.attachments().get(1).snapshot().packedOverlay());
        assertEquals(List.of("unknown"), composition.attachments().get(1).snapshot().unknownMaterialSlots());
        PoseStack expected = new PoseStack();
        BlendEntityAttachmentSubmitter.apply(expected, direct.placement());
        BlendEntityAttachmentSubmitter.apply(expected, direct.offset());
        BlendEntityAttachmentSubmitter.apply(expected, nested.placement());
        BlendEntityAttachmentSubmitter.apply(expected, nested.offset());
        PoseStack actual = new PoseStack();
        var flat = composition.attachments().get(1);
        BlendEntityAttachmentSubmitter.apply(actual, flat.placement());
        BlendEntityAttachmentSubmitter.apply(actual, flat.offset());
        assertTrue(expected.last().pose().equals(actual.last().pose(), 1e-4f));
        // The child's backend applies its own root/units only after the flattened placement.
        expected.translate(1, 0, 0); expected.scale(.25f, .25f, .25f);
        actual.translate(ornament.rootTransform().translation().x(), ornament.rootTransform().translation().y(), ornament.rootTransform().translation().z());
        actual.scale(ornament.handle().unitsToBlocksScale(), ornament.handle().unitsToBlocksScale(), ornament.handle().unitsToBlocksScale());
        assertTrue(expected.last().pose().transformPosition(new Vector3f(4, 2, 1))
                .equals(actual.last().pose().transformPosition(new Vector3f(4, 2, 1)), 1e-4f));
        assertSame(assembly.culling(), character.culling(), "capture cannot retroactively expand vanilla pre-extraction bounds");
    }

    @Test
    void sameModelDirectChildrenAndSharedInstancesRemainLegalAndCountPerOccurrence() {
        var leaf = snapshot("same", 42);
        var shared = snapshot("same", 42).withAttachments(List.of(at(leaf)));
        var root = snapshot("same", 42).withAttachments(List.of(at(shared), at(shared)));
        var result = BlendEntityAttachmentComposition.capture(root);
        assertEquals(List.of(shared, leaf, shared, leaf), result.attachments().stream().map(BlendEntityAttachment::snapshot).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.attachments().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    }

    @Test
    void limitsApplyToAggregateOccurrencesAndEightEdgesBeforeStatePublication() {
        var leaf = snapshot("leaf", 42);
        var group = snapshot("group", 42).withAttachments(Collections.nCopies(31, at(leaf)));
        var exact = snapshot("root", 42).withAttachments(List.of(at(group), at(group)));
        assertEquals(64, BlendEntityAttachmentComposition.capture(exact).attachments().size());
        var overflow = exact.withAttachments(List.of(at(group), at(group), at(leaf)));
        var state = new BlendEntityRenderState();
        state.setSnapshot(exact);
        var before = state.attachmentComposition();
        assertThrows(IllegalArgumentException.class, () -> state.setSnapshot(overflow));
        assertSame(exact, state.snapshotOrNull());
        assertSame(before, state.attachmentComposition());
        var chain = leaf;
        for (int n = 0; n < 8; n++) chain = snapshot("chain" + n, 42).withAttachments(List.of(at(chain)));
        assertEquals(8, BlendEntityAttachmentComposition.capture(chain).attachments().size());
        var tooDeep = snapshot("top", 42).withAttachments(List.of(at(chain)));
        assertThrows(IllegalArgumentException.class, () -> BlendEntityAttachmentComposition.capture(tooDeep));
    }

    @Test
    void staleSubtreeIsSkippedWithDiagnosticAndValidSiblingsSurviveReload() {
        var stale = snapshot("stale", 41).withAttachments(List.of(at(snapshot("descendant", 42))));
        var valid = snapshot("valid", 42);
        var root = snapshot("root", 42).withAttachments(List.of(at(stale), at(valid)));
        var result = BlendEntityAttachmentComposition.capture(root);
        assertEquals(List.of(valid), result.attachments().stream().map(BlendEntityAttachment::snapshot).toList());
        assertEquals(List.of(new BlendEntityAttachmentComposition.Diagnostic(
                BlendEntityAttachmentComposition.Reason.STALE_GENERATION, stale.handle().modelKey(), 42, 41)), result.diagnostics());
        var refreshed = snapshot("root", 43).withAttachments(List.of(at(snapshot("valid", 43))));
        var state = new BlendEntityRenderState();
        state.setSnapshot(root);
        state.setSnapshot(refreshed);
        assertTrue(state.attachmentComposition().diagnostics().isEmpty());
        assertEquals(42, result.attachments().getFirst().snapshot().generation(), "old captures remain stable CPU snapshots");
    }

    @Test
    void missingPlaceholderRetainsDiagnosticsAndChildOwnedAppearance() {
        var handle = new MissingModelRenderHandle(BlendModelKey.parse("assembly:missing"), 42);
        var missing = new ModelRenderSnapshot(handle, Transform.IDENTITY, 15, 16, -1,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        var result = BlendEntityAttachmentComposition.capture(snapshot("root", 42).withAttachments(List.of(at(missing))));
        assertSame(missing, result.attachments().getFirst().snapshot());
        assertEquals(BlendEntityAttachmentComposition.Reason.MISSING_MODEL, result.diagnostics().getFirst().reason());
    }

    @Test
    void customMissingHandleUsesInterfaceDiagnosticContract() {
        var ordinary = snapshot("custom", 42).handle();
        ModelRenderHandle decorated = new ModelRenderHandle() {
            public BlendModelKey modelKey() { return ordinary.modelKey(); }
            public long generation() { return ordinary.generation(); }
            public Bounds bounds() { return ordinary.bounds(); }
            public float unitsToBlocksScale() { return ordinary.unitsToBlocksScale(); }
            public List<PreparedRenderPrimitive> primitives() { return ordinary.primitives(); }
            public Transform nodeTransform(int index) { return ordinary.nodeTransform(index); }
            public boolean missingModel() { return true; }
        };
        var missing = new ModelRenderSnapshot(decorated, Transform.IDENTITY, 0, 0, -1,
                RenderVisibility.VISIBLE, new CullingMetadata(decorated.bounds(), true));
        var result = BlendEntityAttachmentComposition.capture(snapshot("root", 42).withAttachments(List.of(at(missing))));
        assertEquals(BlendEntityAttachmentComposition.Reason.MISSING_MODEL, result.diagnostics().getFirst().reason());
        assertSame(missing, result.attachments().getFirst().snapshot());
    }

    @Test
    void wholeFrameVisibilitySuppressesDescendantsButMaterialSelectionDoesNot() {
        var leaf = snapshot("leaf", 42);
        var hidden = snapshot("hidden", 42, Transform.IDENTITY, 1, RenderVisibility.CULLED)
                .withAttachments(List.of(at(leaf)));
        var result = BlendEntityAttachmentComposition.capture(snapshot("root", 42).withAttachments(List.of(at(hidden))));
        assertEquals(1, result.attachments().size()); // facade receives hidden child, and suppresses it as before
        var slots = snapshot("slots", 42).withMaterialAppearance(Map.of("slot", new MaterialSlotAppearance(0xffffff, false)))
                .withAttachments(List.of(at(leaf)));
        assertEquals(2, BlendEntityAttachmentComposition.capture(snapshot("root", 42).withAttachments(List.of(at(slots)))).attachments().size());
        assertTrue(BlendEntityAttachmentComposition.capture(snapshot("root", 42, Transform.IDENTITY, 1, RenderVisibility.CULLED)
                .withAttachments(List.of(at(slots)))).attachments().isEmpty());
    }

    @Test
    void repeatedSubmitReadsOnlyCapturedFlatFramesAndRestoresStack() {
        var leaf = snapshot("leaf", 42);
        var branch = snapshot("branch", 42).withAttachments(List.of(at(leaf)));
        var root = snapshot("root", 42).withAttachments(List.of(at(branch)));
        var state = new BlendEntityRenderState(); state.setSnapshot(root);
        var submitted = new ArrayList<ModelRenderSnapshot>();
        var renderer = new BlendRenderer((frame, context) -> submitted.add(frame));
        var stack = new PoseStack(); var original = new Matrix4f(stack.last().pose());
        var collector = (SubmitNodeCollector)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> null);
        for (int n = 0; n < 2; n++) BlendEntityAttachmentSubmitter.submit(state.attachmentComposition().attachments(), renderer, stack, collector);
        assertEquals(List.of(branch, leaf, branch, leaf), submitted);
        assertEquals(original, stack.last().pose());
    }

    @Test
    void corruptedIdentityCycleIsBoundedAndRejectedBeforeSubmit() throws Exception {
        var root = snapshot("root", 42);
        // Production objects are immutable; hostile/corrupt graphs must still fail boundedly.
        var field = ModelRenderSnapshot.class.getDeclaredField("attachments"); field.setAccessible(true);
        field.set(root, List.of(at(root)));
        assertThrows(IllegalArgumentException.class, () -> BlendEntityAttachmentComposition.capture(root));
    }

    @Test
    void compositionRetainsDoublePrecisionAndRejectsOverflow() {
        var parent = new BlendEntitySocketPose(30_000_000.125, 0, 0, BlendEntityRotation.IDENTITY, 1);
        assertEquals(30_000_000.25, BlendEntityAttachmentComposition.compose(parent,
                new BlendEntitySocketPose(.125, 0, 0, BlendEntityRotation.IDENTITY, 1)).x());
        assertThrows(IllegalArgumentException.class, () -> BlendEntityAttachmentComposition.compose(
                new BlendEntitySocketPose(Double.MAX_VALUE, 0, 0, BlendEntityRotation.IDENTITY, Float.MAX_VALUE),
                new BlendEntitySocketPose(Double.MAX_VALUE, 0, 0, BlendEntityRotation.IDENTITY, 2)));
    }

    @Test
    void publicAbiPinsCompositionAndExistingDirectChildApi() throws Exception {
        assertEquals("(Lcom/liy/blendlib/fabric/client/render/ModelRenderSnapshot;)Lcom/liy/blendlib/fabric/client/entity/BlendEntityAttachmentComposition;",
                descriptor(BlendEntityAttachmentComposition.class.getMethod("capture", ModelRenderSnapshot.class)));
        assertEquals("()Ljava/util/List;", descriptor(BlendEntityAttachmentComposition.class.getMethod("attachments")));
        assertEquals("()Ljava/util/List;", descriptor(BlendEntityAttachmentComposition.class.getMethod("diagnostics")));
        assertEquals("()Lcom/liy/blendlib/fabric/client/entity/BlendEntityAttachmentComposition;",
                descriptor(BlendEntityRenderState.class.getMethod("attachmentComposition")));
        assertEquals("(Ljava/util/List;Lcom/liy/blendlib/fabric/client/api/BlendRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
                descriptor(BlendEntityAttachmentSubmitter.class.getMethod("submit", List.class, BlendRenderer.class, PoseStack.class, SubmitNodeCollector.class)));
        assertEquals(BlendEntityAttachmentComposition.class, BlendEntityAttachmentComposition.class.getMethod("capture", ModelRenderSnapshot.class).getReturnType());
        assertEquals(List.class, BlendEntityAttachmentComposition.class.getMethod("attachments").getReturnType());
        assertEquals(List.class, BlendEntityAttachmentComposition.class.getMethod("diagnostics").getReturnType());
        assertEquals(BlendEntityAttachmentComposition.class, BlendEntityRenderState.class.getMethod("attachmentComposition").getReturnType());
        assertNotNull(BlendEntityAttachment.class.getConstructor(BlendEntitySocketPose.class, BlendEntitySocketPose.class, ModelRenderSnapshot.class));
        assertNotNull(BlendEntityAttachmentSubmitter.class.getMethod("submit", List.class, BlendRenderer.class, PoseStack.class, SubmitNodeCollector.class));
    }

    private static String descriptor(java.lang.reflect.Method method) {
        return java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
    }

    private static BlendEntitySocket socket(ModelRenderSnapshot frame, Transform finalPose) {
        var request = new BlendEntitySnapshotRequest(frame.handle().modelKey(), .5f, 0, 1, 0, 0, 0, 1, true, 1);
        return BlendEntitySockets.capture(request, new ClientSkinnedExtractionFrame(frame, Map.of(SOCKET, finalPose))).socket(SOCKET).orElseThrow();
    }
    private static BlendEntityAttachment at(ModelRenderSnapshot snapshot) {
        return new BlendEntityAttachment(BlendEntitySocketPose.IDENTITY, BlendEntitySocketPose.IDENTITY, snapshot);
    }
    private static ModelRenderSnapshot snapshot(String name, long generation) {
        return snapshot(name, generation, Transform.IDENTITY, 1, RenderVisibility.VISIBLE);
    }
    private static ModelRenderSnapshot snapshot(String name, long generation, Transform root, float units, RenderVisibility visibility) {
        ModelRenderHandle handle = new ModelRenderHandle() {
            public BlendModelKey modelKey() { return BlendModelKey.parse("assembly:" + name); }
            public long generation() { return generation; }
            public Bounds bounds() { return new Bounds(Vec3.ZERO, Vec3.ONE); }
            public float unitsToBlocksScale() { return units; }
            public List<PreparedRenderPrimitive> primitives() { return List.of(); }
            public Transform nodeTransform(int index) { return Transform.IDENTITY; }
            public boolean missingModel() { return false; }
        };
        return new ModelRenderSnapshot(handle, root, 0, 0, -1, visibility, new CullingMetadata(handle.bounds(), true));
    }
}
