package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable extraction-to-submit handoff for CPU-skinned primitives in one model generation.
 *
 * <p>Call {@link #capture(SkinnedRenderHandle, List)} only after extraction has sampled a pose,
 * built the corresponding skin palette, and CPU-skinned every prepared primitive. This type owns
 * no controller, world, resource manager, parser, or mutable source-vertex reference.</p>
 */
public final class SkinnedRenderSnapshot {
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<SkinnedMeshSnapshot> meshes;

    private SkinnedRenderSnapshot(BlendModelKey modelKey, long generation, List<SkinnedMeshSnapshot> meshes) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.meshes = List.copyOf(Objects.requireNonNull(meshes, "meshes"));
        if (this.meshes.isEmpty()) {
            throw new IllegalArgumentException("A skinned render snapshot needs at least one mesh");
        }
    }

    /**
     * Captures exact CPU outputs in the same deterministic primitive order as the prepared handle.
     *
     * <p>The list's positional contract intentionally prevents a CPU result from one material,
     * mesh, or reload generation being submitted for another. Each output must retain the exact
     * prepared topology object that produced it.</p>
     */
    public static SkinnedRenderSnapshot capture(SkinnedRenderHandle handle, List<CpuSkinnedMesh> outputs) {
        Objects.requireNonNull(handle, "handle");
        List<CpuSkinnedMesh> checkedOutputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        List<PreparedSkinnedRenderPrimitive> primitives = handle.skinnedPrimitives();
        if (checkedOutputs.size() != primitives.size()) {
            throw new IllegalArgumentException("CPU-skinned output count must match prepared skinned primitive count");
        }
        List<SkinnedMeshSnapshot> captured = new ArrayList<>(checkedOutputs.size());
        for (int index = 0; index < checkedOutputs.size(); index++) {
            captured.add(SkinnedMeshSnapshot.capture(primitives.get(index), checkedOutputs.get(index)));
        }
        return new SkinnedRenderSnapshot(handle.modelKey(), handle.generation(), captured);
    }

    public BlendModelKey modelKey() {
        return modelKey;
    }

    public long generation() {
        return generation;
    }

    public int meshCount() {
        return meshes.size();
    }

    /** Package-private submit-only view; it never exposes mutable vertex arrays. */
    List<SkinnedMeshSnapshot> meshes() {
        return meshes;
    }

    /** Fails before submit when extraction data is paired with a stale or different handle. */
    void requireCompatible(SkinnedRenderHandle handle) {
        SkinnedRenderHandle checkedHandle = Objects.requireNonNull(handle, "handle");
        if (!modelKey.equals(checkedHandle.modelKey()) || generation != checkedHandle.generation()) {
            throw new IllegalArgumentException("Skinned snapshot model key and generation must match the render handle");
        }
        if (meshes.size() != checkedHandle.skinnedPrimitives().size()) {
            throw new IllegalArgumentException("Skinned snapshot mesh count must match the render handle");
        }
    }
}
