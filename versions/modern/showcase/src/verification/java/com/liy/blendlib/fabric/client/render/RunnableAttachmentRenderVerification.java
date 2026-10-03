package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.model.Vec3;
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
}
