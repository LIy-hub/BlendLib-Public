package com.liy.blendlib.fabric.client.render;

/** Test-only access to package-private immutable render payloads; no runtime API widening. */
public final class StaticItemMorphRenderTestAccess {
    private StaticItemMorphRenderTestAccess() { }
    public static Object payload(ModelRenderSnapshot snapshot) { return snapshot.skinnedRenderSnapshot(); }
    public static float firstX(ModelRenderSnapshot snapshot) {
        var positions = new java.util.ArrayList<Float>();
        snapshot.skinnedRenderSnapshot().meshes().getFirst().emit((x,y,z,nx,ny,nz,u,v) -> positions.add(x));
        return positions.getFirst();
    }
}
