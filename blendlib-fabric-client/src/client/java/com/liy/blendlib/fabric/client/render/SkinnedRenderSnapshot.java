package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedFrameProvenance;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable extraction-to-submit handoff for CPU-skinned primitives in one model generation.
 *
 * <p>Call {@link #capture(SkinnedRenderHandle, List)} only after extraction has sampled a pose,
 * built the corresponding skin palette, and CPU-skinned every prepared primitive. This type owns
 * no controller, world, resource manager, parser, or mutable source-vertex reference. It retains
 * its exact immutable source handle privately, so submit can reject a same-key/generation/count
 * capture from a different reload handle in constant time.</p>
 */
public final class SkinnedRenderSnapshot {
    /** Private, exact extraction source; identity is the non-forgeable handle fence. */
    private final SkinnedRenderHandle sourceHandle;
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<SkinnedMeshSnapshot> meshes;
    /** Empty for the stable CPU-only capture API; otherwise exact extraction-order T4p proofs. */
    private final List<X7SkinnedFrameProvenance> x7FrameProvenances;

    private SkinnedRenderSnapshot(
            SkinnedRenderHandle sourceHandle,
            List<SkinnedMeshSnapshot> meshes,
            List<X7SkinnedFrameProvenance> x7FrameProvenances) {
        this.sourceHandle = Objects.requireNonNull(sourceHandle, "sourceHandle");
        this.modelKey = sourceHandle.modelKey();
        long generation = sourceHandle.generation();
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.meshes = List.copyOf(Objects.requireNonNull(meshes, "meshes"));
        if (this.meshes.isEmpty()) {
            throw new IllegalArgumentException("A skinned render snapshot needs at least one mesh");
        }
        this.x7FrameProvenances = List.copyOf(Objects.requireNonNull(x7FrameProvenances, "x7FrameProvenances"));
        if (!this.x7FrameProvenances.isEmpty() && this.x7FrameProvenances.size() != this.meshes.size()) {
            throw new IllegalArgumentException("T4p frame proof count must match captured skinned mesh count");
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
        return new SkinnedRenderSnapshot(handle, captured, List.of());
    }

    /**
     * T4p-only capture path used by extraction after it has produced every exact current-palette CPU result.
     *
     * <p>The additional proof remains metadata-only until a later render adapter deliberately calls its materializer.
     * Thus the established public {@link #capture(SkinnedRenderHandle, List)} path retains CPU-only behavior and
     * cannot accidentally create a GPU candidate.</p>
     */
    public static SkinnedRenderSnapshot captureWithT4Provenance(
            SkinnedRenderHandle handle, List<X7SkinnedFrameProvenance.SealedFrame> frames) {
        Objects.requireNonNull(handle, "handle");
        List<X7SkinnedFrameProvenance.SealedFrame> checkedFrames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        List<PreparedSkinnedRenderPrimitive> primitives = handle.skinnedPrimitives();
        if (checkedFrames.size() != primitives.size()) {
            throw new IllegalArgumentException("T4p sealed-frame count must match prepared skinned primitive count");
        }
        List<SkinnedMeshSnapshot> captured = new ArrayList<>(checkedFrames.size());
        List<X7SkinnedFrameProvenance> provenances = new ArrayList<>(checkedFrames.size());
        for (int index = 0; index < checkedFrames.size(); index++) {
            PreparedSkinnedRenderPrimitive primitive = primitives.get(index);
            X7SkinnedFrameProvenance.SealedFrame frame = checkedFrames.get(index);
            if (!frame.matchesExactly(primitive)) {
                throw new IllegalArgumentException("T4p sealed frame must retain the exact primitive and CPU result at its capture order");
            }
            captured.add(SkinnedMeshSnapshot.capture(primitive, frame.cpuResult()));
            provenances.add(frame.provenance());
        }
        return new SkinnedRenderSnapshot(handle, captured, provenances);
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

    /**
     * T3c's render-package seam for the exact per-primitive T4p proof. CPU-only snapshots are deliberately
     * GPU-ineligible, and order plus primitive identity are rechecked before the proof becomes observable.
     */
    X7SkinnedFrameProvenance x7FrameProvenanceAt(int index, PreparedSkinnedRenderPrimitive primitive) {
        PreparedSkinnedRenderPrimitive checkedPrimitive = Objects.requireNonNull(primitive, "primitive");
        if (x7FrameProvenances.isEmpty()) {
            throw new IllegalStateException("CPU-only skinned snapshots carry no T4p GPU provenance");
        }
        if (index < 0 || index >= x7FrameProvenances.size()
                || sourceHandle.skinnedPrimitives().get(index) != checkedPrimitive
                || !x7FrameProvenances.get(index).matchesPrimitive(checkedPrimitive)) {
            throw new IllegalArgumentException("T4p proof must be retrieved by its exact prepared primitive capture order");
        }
        return x7FrameProvenances.get(index);
    }

    /** Fails before submit when extraction data is paired with a stale or different exact handle. */
    void requireCompatible(SkinnedRenderHandle handle) {
        SkinnedRenderHandle checkedHandle = Objects.requireNonNull(handle, "handle");
        if (checkedHandle != sourceHandle) {
            throw new IllegalArgumentException("Skinned snapshot must retain the exact source handle identity");
        }
        if (!modelKey.equals(checkedHandle.modelKey()) || generation != checkedHandle.generation()) {
            throw new IllegalArgumentException("Skinned snapshot model key and generation must match the render handle");
        }
        if (meshes.size() != checkedHandle.skinnedPrimitives().size()) {
            throw new IllegalArgumentException("Skinned snapshot mesh count must match the render handle");
        }
    }
}
