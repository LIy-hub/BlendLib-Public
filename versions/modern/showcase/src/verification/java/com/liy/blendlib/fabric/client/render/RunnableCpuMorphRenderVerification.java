package com.liy.blendlib.fabric.client.render;

/** Test-only access to submit payloads; no render internals are exported by the consumer JAR. */
public final class RunnableCpuMorphRenderVerification {
    private RunnableCpuMorphRenderVerification() { }

    public static void verify(ModelRenderSnapshot snapshot) {
        var handle = (SkinnedRenderHandle) snapshot.handle();
        require(handle.cpuMorphProfile(), "morph generation must be explicitly CPU-only");
        var captured = snapshot.skinnedRenderSnapshot();
        require(captured != null && captured.meshCount() == 2, "both authored material primitives are captured");
        for (int i = 0; i < captured.meshCount(); i++) {
            var prepared = handle.skinnedPrimitives().get(i);
            var mesh = captured.meshes().get(i);
            require(mesh.material().equals(prepared.material()), "primitive material routing survives morphing");
            require(mesh.vertexCount() == prepared.geometry().topology().vertexCount()
                    && mesh.indexCount() == prepared.geometry().topology().indexCount(), "source topology counts are unchanged");
            int[] sourceIndices = prepared.geometry().topology().indices();
            float[] sourceUv = prepared.geometry().topology().texCoords();
            int[] emitted = {0};
            mesh.emit((x,y,z,nx,ny,nz,u,v) -> {
                int triangle = emitted[0] / 4, corner = Math.min(emitted[0] % 4, 2);
                int source = sourceIndices[triangle * 3 + corner];
                require(u == sourceUv[source * 2] && v == sourceUv[source * 2 + 1],
                        "exact source UV/index order survives CPU deformation");
                emitted[0]++;
                double length = Math.sqrt(nx*nx + ny*ny + nz*nz);
                require(Double.isFinite(length) && Math.abs(length - 1) < 2e-5, "final CPU normals are normalized");
                require(Float.isFinite(u) && Float.isFinite(v), "original UVs remain finite");
            });
            try { captured.x7FrameProvenanceAt(i, prepared); throw new AssertionError("morph snapshot acquired skin-only GPU provenance"); }
            catch (IllegalStateException expected) { }
        }
        try { SkinnedRenderSnapshot.captureWithT4Provenance(handle, java.util.List.of());
            throw new AssertionError("morph generation accepted T4 capture"); }
        catch (IllegalArgumentException expected) { }
    }

    public static void rejectStale(ModelRenderSnapshot previous, SkinnedRenderHandle replacement) {
        try {
            ModelRenderSnapshot.skinned(replacement, previous.rootTransform(), previous.packedLight(),
                    previous.packedOverlay(), previous.tintArgb(), previous.visibility(), previous.culling(),
                    previous.skinnedRenderSnapshot());
            throw new AssertionError("old capture accepted a replacement generation handle");
        } catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
