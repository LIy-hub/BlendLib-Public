package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachmentComposition;
import com.liy.blendlib.fabric.client.entity.BlendEntitySocketPose;
import java.util.ArrayList;
import java.util.List;

/** Test-only package bridge; no render internals are added to the shipped API. */
public final class RunnableAttachmentRenderVerification {
    private RunnableAttachmentRenderVerification() { }
    public static MaterialSlotAppearance appearance(ModelRenderSnapshot snapshot, int primitive) { return snapshot.materialAppearance(primitive); }
    public static boolean skinned(ModelRenderSnapshot snapshot) { return snapshot.skinnedRenderSnapshot() != null; }
    public static List<Vec3> positions(ModelRenderSnapshot snapshot) {
        var result = new ArrayList<Vec3>();
        snapshot.skinnedRenderSnapshot().meshes().forEach(mesh -> mesh.emit((x,y,z,nx,ny,nz,u,v) -> result.add(new Vec3(x,y,z))));
        return List.copyOf(result);
    }

    public record Position(double x, double y, double z) { }

    /** Actual prepared triangle vertices, with the backend's root/unit/node order and captured flat placements. */
    public static List<Position> entityPositions(ModelRenderSnapshot root) {
        var result = new ArrayList<Position>(localPositions(root));
        for (var attachment : BlendEntityAttachmentComposition.capture(root).attachments()) {
            for (var p : localPositions(attachment.snapshot())) {
                result.add(apply(attachment.placement(), apply(attachment.offset(), p)));
            }
        }
        return List.copyOf(result);
    }

    private static List<Position> localPositions(ModelRenderSnapshot snapshot) {
        if (snapshot.visibility() != RenderVisibility.VISIBLE) return List.of();
        var result = new ArrayList<Position>();
        float units = snapshot.handle().unitsToBlocksScale();
        var rootUnits = snapshot.rootTransform().compose(new Transform(Vec3.ZERO, Quaternion.IDENTITY,
                new Vec3(units, units, units)));
        var skinned = snapshot.skinnedRenderSnapshot();
        if (skinned != null) {
            for (int i = 0; i < skinned.meshes().size(); i++) {
                if (!snapshot.materialAppearance(i).visible()) continue;
                skinned.meshes().get(i).emit((x,y,z,nx,ny,nz,u,v) -> add(result, rootUnits, x,y,z));
            }
        } else {
            var primitives = snapshot.handle().primitives();
            for (int i = 0; i < primitives.size(); i++) {
                if (!snapshot.materialAppearance(i).visible()) continue;
                var primitive = primitives.get(i);
                var node = Minecraft2612StaticRigidRenderBackend.nodeTransformFor(snapshot, primitive.nodeIndex());
                var transform = rootUnits.compose(node);
                primitive.geometry().emit((x,y,z,nx,ny,nz,u,v) -> add(result, transform, x,y,z));
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Position> positions, Transform transform, float x, float y, float z) {
        var p = transform.transformPoint(new Vec3(x,y,z));
        positions.add(new Position(p.x(), p.y(), p.z()));
    }

    private static Position apply(BlendEntitySocketPose pose, Position p) {
        var q = pose.rotation();
        double x = p.x() * pose.scale(), y = p.y() * pose.scale(), z = p.z() * pose.scale();
        double tx = 2 * (q.y()*z - q.z()*y), ty = 2 * (q.z()*x - q.x()*z), tz = 2 * (q.x()*y - q.y()*x);
        return new Position(pose.x() + x + q.w()*tx + q.y()*tz - q.z()*ty,
                pose.y() + y + q.w()*ty + q.z()*tx - q.x()*tz,
                pose.z() + z + q.w()*tz + q.x()*ty - q.y()*tx);
    }

    /** Preserves the real prepared geometry and nested graph, changing only whole-frame visibility. */
    public static ModelRenderSnapshot hidden(ModelRenderSnapshot snapshot) {
        var hidden = snapshot.skinnedRenderSnapshot() != null
                ? ModelRenderSnapshot.skinned((SkinnedRenderHandle) snapshot.handle(), snapshot.rootTransform(),
                        snapshot.packedLight(), snapshot.packedOverlay(), snapshot.tintArgb(), RenderVisibility.CULLED,
                        snapshot.culling(), snapshot.skinnedRenderSnapshot())
                : new ModelRenderSnapshot(snapshot.handle(), snapshot.rootTransform(), snapshot.packedLight(),
                        snapshot.packedOverlay(), snapshot.tintArgb(), RenderVisibility.CULLED, snapshot.culling(),
                        snapshot.rigidNodePalette());
        return hidden.withAttachments(snapshot.attachments());
    }
}
