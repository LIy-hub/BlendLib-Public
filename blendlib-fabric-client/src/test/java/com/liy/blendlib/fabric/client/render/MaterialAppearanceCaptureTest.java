package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachment;
import com.liy.blendlib.fabric.client.entity.BlendEntitySocketPose;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaterialAppearanceCaptureTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("appearance:fixture");
    private static final MaterialSlotAppearance RED = new MaterialSlotAppearance(0xff0000, true);
    private static final MaterialSlotAppearance HIDDEN = new MaterialSlotAppearance(0xffffff, false);

    @Test
    void capturesMutableSelectionWithoutLeakingAcrossInstancesOrAuthoredMaterials() {
        var handle = rigid(1, "body", "eye", "body");
        var authored = snapshot(handle);
        var mutable = new HashMap<>(Map.of("body", RED, "eye", HIDDEN));
        var first = authored.withMaterialAppearance(mutable);
        mutable.clear();
        mutable.put("body", HIDDEN);
        var second = authored.withMaterialAppearance(mutable);
        assertEquals(RED, first.materialAppearance(0));
        assertEquals(HIDDEN, first.materialAppearance(1));
        assertEquals(RED, first.materialAppearance(2));
        assertEquals(HIDDEN, second.materialAppearance(0));
        assertEquals(MaterialSlotAppearance.unchanged(), second.materialAppearance(1));
        assertEquals(MaterialSlotAppearance.unchanged(), authored.materialAppearance(0));
        assertSame(handle, first.handle());
        assertSame(handle.primitives(), second.handle().primitives());
        assertEquals(List.of("body", "eye", "body"), handle.materialSlots());
        assertThrows(UnsupportedOperationException.class, () -> handle.materialSlots().set(0, "other"));
    }

    @Test
    void reloadRebindsByExactNameAndRemovedSlotsFallBackAtomicallyWithSortedDiagnostics() {
        var selection = Map.of("body", RED, "eye", HIDDEN);
        var first = snapshot(rigid(1, "body", "eye")).withMaterialAppearance(selection);
        var reordered = snapshot(rigid(2, "eye", "body")).withMaterialAppearance(selection);
        assertEquals(RED, first.materialAppearance(0));
        assertEquals(HIDDEN, reordered.materialAppearance(0));
        assertEquals(RED, reordered.materialAppearance(1));
        var removed = snapshot(rigid(3, "body")).withMaterialAppearance(selection);
        assertEquals(List.of("eye"), removed.unknownMaterialSlots());
        assertEquals(MaterialSlotAppearance.unchanged(), removed.materialAppearance(0));
        var unknown = first.withMaterialAppearance(Map.of("body", RED, "z", RED, "Body", HIDDEN));
        assertEquals(List.of("Body", "z"), unknown.unknownMaterialSlots());
        assertEquals(MaterialSlotAppearance.unchanged(), unknown.materialAppearance(0));
        assertEquals(MaterialSlotAppearance.unchanged(), unknown.materialAppearance(1));
        assertThrows(UnsupportedOperationException.class, () -> unknown.unknownMaterialSlots().clear());
    }

    @Test
    void exactHandleIdentityFencesEvenSameKeyAndGeneration() {
        var handle = rigid(7, "body");
        var capture = MaterialAppearanceSnapshot.capture(handle, Map.of("body", RED));
        assertDoesNotThrow(() -> capture.requireCompatible(handle));
        assertThrows(IllegalArgumentException.class, () -> capture.requireCompatible(rigid(7, "body")));
        assertThrows(IllegalArgumentException.class, () -> capture.requireCompatible(rigid(8, "body")));
    }

    @Test
    void diagnosticHandlesRemainAuthoredAndInvalidSelectionsFailFast() {
        var missing = snapshot(new MissingModelRenderHandle(KEY, 3));
        var captured = missing.withMaterialAppearance(Map.of("body", HIDDEN));
        assertTrue(captured.unknownMaterialSlots().isEmpty());
        assertSame(missing.handle(), captured.handle());
        for (int i = 0; i < missing.handle().primitives().size(); i++)
            assertEquals(MaterialSlotAppearance.unchanged(), captured.materialAppearance(i));
        var frame = snapshot(rigid(1, "body"));
        assertThrows(NullPointerException.class, () -> frame.withMaterialAppearance(null));
        assertThrows(IllegalArgumentException.class, () -> frame.withMaterialAppearance(Map.of(" ", RED)));
        Map<String, MaterialSlotAppearance> nullValue = new HashMap<>();
        nullValue.put("body", null);
        assertThrows(NullPointerException.class, () -> frame.withMaterialAppearance(nullValue));
        assertThrows(IllegalArgumentException.class, () -> new MaterialSlotAppearance(0xff123456, true));
        assertThrows(IllegalArgumentException.class, () -> new MaterialSlotAppearance(-1, true));
        assertEquals(new MaterialSlotAppearance(0xffffff, true), MaterialSlotAppearance.unchanged());
    }

    @Test
    void copiesKeepRigidPaletteGeometryAttachmentsAndAllFrameInputs() {
        var handle = rigid(4, "body");
        var base = ModelRenderSnapshot.rigid(handle, Transform.IDENTITY, 123, 456, 0x80706050,
                RenderVisibility.CULLED, new CullingMetadata(handle.bounds(), true), Map.of(0, Transform.IDENTITY));
        var attachment = new BlendEntityAttachment(BlendEntitySocketPose.IDENTITY, BlendEntitySocketPose.IDENTITY,
                snapshot(rigid(4, "eye")));
        var captured = base.withAttachments(List.of(attachment)).withMaterialAppearance(Map.of("body", RED));
        var copied = captured.withLighting(789, 321).withAttachments(captured.attachments());
        assertSame(base.rigidNodePalette(), copied.rigidNodePalette());
        assertSame(handle, copied.handle());
        assertSame(base.rootTransform(), copied.rootTransform());
        assertSame(base.culling(), copied.culling());
        assertSame(base.visibility(), copied.visibility());
        assertEquals(base.tintArgb(), copied.tintArgb());
        assertEquals(789, copied.packedLight());
        assertEquals(321, copied.packedOverlay());
        assertSame(attachment, copied.attachments().getFirst());
        assertEquals(RED, copied.materialAppearance(0));
        assertEquals(123, captured.packedLight());
        assertEquals(456, captured.packedOverlay());
    }

    @Test
    void copiesKeepSkinnedOutputAndPresentationSocketInEitherOrder() {
        var asset = asset(5, true, "body");
        var handle = SkinnedRenderHandle.prepare(KEY, asset);
        var skin = asset.skeleton().skins().getFirst();
        var palette = SkinPalette.from(skin, NodePalette.from(
                new LocalPose(Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY)), asset.nodes()));
        var output = SkinnedRenderSnapshot.capture(handle, List.of(
                CpuSkinner.skin(handle.skinnedPrimitives().getFirst().geometry(), palette)));
        var base = ModelRenderSnapshot.skinned(handle, Transform.IDENTITY, 12, 34, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), output);
        var socket = new Transform(new Vec3(1, 2, 3), Quaternion.IDENTITY, Vec3.ONE);
        for (var frame : List.of(
                base.withPresentationSocketTransform(socket).withMaterialAppearance(Map.of("body", RED)),
                base.withMaterialAppearance(Map.of("body", RED)).withPresentationSocketTransform(socket))) {
            var copy = frame.withLighting(56, 78).withAttachments(List.of());
            assertSame(output, copy.skinnedRenderSnapshot());
            assertSame(socket, copy.presentationSocketTransformOrNull());
            assertSame(handle, copy.handle());
            assertEquals(RED, copy.materialAppearance(0));
            assertEquals(List.of("body"), handle.materialSlots());
        }
    }

    private static ModelRenderSnapshot snapshot(ModelRenderHandle handle) {
        return new ModelRenderSnapshot(handle, Transform.IDENTITY, 123, 456, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
    }

    private static StaticRigidRenderHandle rigid(long generation, String... slots) {
        return StaticRigidRenderHandle.prepare(KEY, asset(generation, false, slots));
    }

    private static ModelAsset asset(long generation, boolean skinned, String... slots) {
        var nodes = skinned ? List.of(
                new ModelNode(0, "mesh", Transform.IDENTITY, List.of(1), 0, 0, false),
                new ModelNode(1, "joint", Transform.IDENTITY, List.of(), -1, -1, false))
                : List.of(new ModelNode(0, "mesh", Transform.IDENTITY, List.of(), 0, -1, false));
        var materials = new HashMap<String, MaterialDefinition>();
        var primitives = new ArrayList<ModelPrimitive>();
        for (String slot : slots) {
            materials.put(slot, new MaterialDefinition(BlendResourceId.parse("appearance:textures/test.png"),
                    MaterialDefinition.Mode.OPAQUE, false, false, null));
            var mesh = new MeshPrimitive(slot, new float[]{0,0,0, 1,0,0, 0,1,0},
                    new float[]{0,0,1, 0,0,1, 0,0,1}, new float[]{0,0, 1,0, 0,1}, new int[]{0,1,2},
                    skinned ? new int[12] : null,
                    skinned ? new float[]{1,0,0,0, 1,0,0,0, 1,0,0,0} : null);
            primitives.add(new ModelPrimitive(0, 0, primitives.size(), mesh));
        }
        var skeleton = skinned ? new Skeleton(List.of(new Skin("skin", 1, List.of(1),
                new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}))) : null;
        return new ModelAsset(KEY.resourceId(), KEY.descriptorResourceId(), generation,
                skinned ? ModelProfile.SKINNED_V1 : ModelProfile.RIGID_V1, 1,
                materials, null, nodes, List.of(0), primitives, skeleton, List.of(), new SocketTable(Map.of()),
                Bounds.fromPositions(new float[]{0,0,0, 1,1,1}), List.of());
    }
}
