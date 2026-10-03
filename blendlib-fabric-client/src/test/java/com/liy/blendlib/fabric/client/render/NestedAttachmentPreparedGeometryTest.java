package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.entity.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NestedAttachmentPreparedGeometryTest {
    @Test
    void rigidAndCpuSkinnedChildrenKeepExactPreparedPalettesAndFinalSockets() {
        var key = BlendModelKey.parse("assembly:skinned");
        var skinAsset = asset(key, 7, true, "body");
        var skinHandle = SkinnedRenderHandle.prepare(key, skinAsset);
        var nodes = NodePalette.from(new LocalPose(Map.of(0, Transform.IDENTITY, 1,
                new Transform(new Vec3(0, 3, 0), Quaternion.IDENTITY, Vec3.ONE))), skinAsset.nodes());
        var skinPalette = SkinPalette.from(skinAsset.skeleton().skins().getFirst(), nodes);
        var cpu = SkinnedRenderSnapshot.capture(skinHandle, List.of(CpuSkinner.skin(
                skinHandle.skinnedPrimitives().getFirst().geometry(), skinPalette)));
        var skinned = ModelRenderSnapshot.skinned(skinHandle, Transform.IDENTITY, 123, 456, -1,
                RenderVisibility.VISIBLE, new CullingMetadata(skinHandle.bounds(), true), cpu)
                .withMaterialAppearance(Map.of("body", new MaterialSlotAppearance(0x00ff00, false)));
        var rigidKey = BlendModelKey.parse("assembly:rigid");
        var rigidHandle = StaticRigidRenderHandle.prepare(rigidKey, asset(rigidKey, 7, false, "metal"));
        var rigid = ModelRenderSnapshot.rigid(rigidHandle, Transform.IDENTITY, 789, 321, -1,
                RenderVisibility.VISIBLE, new CullingMetadata(rigidHandle.bounds(), true),
                Map.of(0, new Transform(new Vec3(5, 0, 0), Quaternion.IDENTITY, Vec3.ONE)))
                .withMaterialAppearance(Map.of("metal", new MaterialSlotAppearance(0xff0000, true)));
        var request = new BlendEntitySnapshotRequest(key, .5f, 0, 1, 0, 0, 0, 1, true, 1);
        var socketId = BlendResourceId.parse("assembly:hand");
        var finalSocket = new Transform(new Vec3(0, 3, 0), Quaternion.IDENTITY, Vec3.ONE);
        var sockets = BlendEntitySockets.capture(request, new ClientSkinnedExtractionFrame(skinned, Map.of(socketId, finalSocket)));
        var assembly = skinned.withAttachments(List.of(BlendEntityAttachment.at(sockets.socket(socketId).orElseThrow(), rigid)));
        // Same skinned capture is also a grandchild on a separately captured finite tree.
        var weapon = rigid.withAttachments(List.of(new BlendEntityAttachment(BlendEntitySocketPose.IDENTITY,
                BlendEntitySocketPose.IDENTITY, skinned)));
        var nested = assembly.withAttachments(List.of(BlendEntityAttachment.at(sockets.socket(socketId).orElseThrow(), weapon)));
        var result = BlendEntityAttachmentComposition.capture(nested);
        assertEquals(2, result.attachments().size());
        assertSame(rigid.rigidNodePalette(), result.attachments().get(0).snapshot().rigidNodePalette());
        assertSame(cpu, result.attachments().get(1).snapshot().skinnedRenderSnapshot());
        assertSame(skinHandle, result.attachments().get(1).snapshot().handle());
        assertEquals(3, result.attachments().get(0).placement().y());
        assertEquals(3, result.attachments().get(1).placement().y());
        assertEquals(new MaterialSlotAppearance(0x00ff00, false), result.attachments().get(1).snapshot().materialAppearance(0));
        assertEquals(new MaterialSlotAppearance(0xff0000, true), result.attachments().get(0).snapshot().materialAppearance(0));
        assertEquals(123, result.attachments().get(1).snapshot().packedLight());
        assertEquals(321, result.attachments().get(0).snapshot().packedOverlay());
    }

    private static ModelAsset asset(BlendModelKey key, long generation, boolean skinned, String... slots) {
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
        return new ModelAsset(key.resourceId(), key.descriptorResourceId(), generation,
                skinned ? ModelProfile.SKINNED_V1 : ModelProfile.RIGID_V1, 1,
                materials, null, nodes, List.of(0), primitives, skeleton, List.of(), new SocketTable(Map.of()),
                Bounds.fromPositions(new float[]{0,0,0, 1,1,1}), List.of());
    }
}
