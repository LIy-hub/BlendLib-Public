package com.liy.blendlib.fabric.client.render.x7gpu;

import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable T4p extraction-to-submit proof for one current skinned primitive frame.
 *
 * <p>A proof is never constructed from caller-selected CPU bytes. {@link #capture(PreparedSkinnedRenderPrimitive,
 * SkinPalette)} computes the CPU result itself and returns it only in an unforgeable {@link SealedFrame} together
 * with this proof. That seals one exact prepared primitive, current palette, and CPU result before a later Stage-A
 * adapter may materialize GPU upload bytes.</p>
 *
 * <p>This type deliberately owns no direct buffers: a later adapter calls {@link #tryMaterialize()} only after it
 * has selected the GPU candidate, and must then move or close the returned materialization. This keeps the stable
 * CPU-only extraction path free of long-lived native staging memory.</p>
 */
public final class X7SkinnedFrameProvenance {
    private final PreparedSkinnedRenderPrimitive exactPrimitive;
    private final PreparedSkinnedGeometry exactGeometry;
    private final SkinPalette exactPalette;
    private final CpuSkinnedMesh exactCpuResult;

    private X7SkinnedFrameProvenance(
            PreparedSkinnedRenderPrimitive primitive, SkinPalette palette, CpuSkinnedMesh cpuResult) {
        this.exactPrimitive = Objects.requireNonNull(primitive, "primitive");
        this.exactGeometry = primitive.geometry();
        this.exactPalette = Objects.requireNonNull(palette, "palette");
        this.exactCpuResult = Objects.requireNonNull(cpuResult, "cpuResult");
        requireExactGeometryTopology(cpuResult, exactGeometry);
    }

    /**
     * The sole public T4p producer: compute the current CPU result and seal it with its exact primitive and palette.
     */
    public static SealedFrame capture(PreparedSkinnedRenderPrimitive primitive, SkinPalette palette) {
        PreparedSkinnedRenderPrimitive checkedPrimitive = Objects.requireNonNull(primitive, "primitive");
        SkinPalette checkedPalette = Objects.requireNonNull(palette, "palette");
        CpuSkinnedMesh result = CpuSkinner.skin(checkedPrimitive.geometry(), checkedPalette);
        return new SealedFrame(new X7SkinnedFrameProvenance(checkedPrimitive, checkedPalette, result), result);
    }

    /**
     * T4-internal validation seam. It never crosses the render-package boundary and refuses a result that was
     * skinned under a different current palette before it can become a proof or reach materialization.
     */
    static SealedFrame validateAndSeal(
            PreparedSkinnedRenderPrimitive primitive, SkinPalette palette, CpuSkinnedMesh cpuResult) {
        PreparedSkinnedRenderPrimitive checkedPrimitive = Objects.requireNonNull(primitive, "primitive");
        SkinPalette checkedPalette = Objects.requireNonNull(palette, "palette");
        CpuSkinnedMesh checkedCpuResult = Objects.requireNonNull(cpuResult, "cpuResult");
        CpuSkinnedMesh expected = CpuSkinner.skin(checkedPrimitive.geometry(), checkedPalette);
        requireExactCpuResult(checkedCpuResult, expected, checkedPrimitive.geometry());
        return new SealedFrame(new X7SkinnedFrameProvenance(checkedPrimitive, checkedPalette, checkedCpuResult), checkedCpuResult);
    }

    /** Runs the frozen source/palette predicates and derives all exact upload streams for this one proof. */
    public X7SkinnedTexelProvenance.Attempt tryMaterialize() {
        return X7SkinnedTexelProvenance.tryMaterialize(this);
    }

    /** Exact primitive/order check used by the sealed snapshot capture before T3c can observe this proof. */
    public boolean matchesExactly(PreparedSkinnedRenderPrimitive primitive, CpuSkinnedMesh cpuResult) {
        return exactPrimitive == primitive
                && exactGeometry == primitive.geometry()
                && exactCpuResult == cpuResult
                && cpuResult.topology() == exactGeometry.topology()
                && cpuResult.vertexCount() == exactGeometry.vertexCount();
    }

    /** Exact primitive identity check used when the render snapshot returns one proof by its capture order. */
    public boolean matchesPrimitive(PreparedSkinnedRenderPrimitive primitive) {
        return exactPrimitive == Objects.requireNonNull(primitive, "primitive") && exactGeometry == primitive.geometry();
    }

    PreparedSkinnedGeometry exactGeometry() {
        return exactGeometry;
    }

    SkinPalette exactPalette() {
        return exactPalette;
    }

    private static void requireExactCpuResult(
            CpuSkinnedMesh actual, CpuSkinnedMesh expected, PreparedSkinnedGeometry exactGeometry) {
        requireExactGeometryTopology(actual, exactGeometry);
        requireExactGeometryTopology(expected, exactGeometry);
        if (!Arrays.equals(actual.positions(), expected.positions()) || !Arrays.equals(actual.normals(), expected.normals())) {
            throw new IllegalArgumentException("The T4 frame proof CPU result was not skinned by its exact current palette");
        }
    }

    private static void requireExactGeometryTopology(CpuSkinnedMesh result, PreparedSkinnedGeometry exactGeometry) {
        if (result.topology() != exactGeometry.topology() || result.vertexCount() != exactGeometry.vertexCount()) {
            throw new IllegalArgumentException("The T4 frame proof must retain the CPU result from its exact prepared geometry");
        }
    }

    /**
     * Private-constructor exact pair consumed by {@code SkinnedRenderSnapshot}; callers cannot combine a CPU result
     * from palette A with a proof built for palette B.
     */
    public static final class SealedFrame {
        private final X7SkinnedFrameProvenance provenance;
        private final CpuSkinnedMesh cpuResult;

        private SealedFrame(X7SkinnedFrameProvenance provenance, CpuSkinnedMesh cpuResult) {
            this.provenance = Objects.requireNonNull(provenance, "provenance");
            this.cpuResult = Objects.requireNonNull(cpuResult, "cpuResult");
            if (!provenance.matchesExactly(provenance.exactPrimitive, cpuResult)) {
                throw new IllegalArgumentException("A sealed T4p frame must retain its exact proof and CPU result identity");
            }
        }

        /** Immutable CPU result generated by the same public capture operation as the proof. */
        public CpuSkinnedMesh cpuResult() {
            return cpuResult;
        }

        /** Exact proof later materialized by the T4 adapter only after snapshot-order admission. */
        public X7SkinnedFrameProvenance provenance() {
            return provenance;
        }

        /** Snapshot admission check for one exact primitive position. */
        public boolean matchesExactly(PreparedSkinnedRenderPrimitive primitive) {
            return provenance.matchesExactly(Objects.requireNonNull(primitive, "primitive"), cpuResult);
        }
    }
}
