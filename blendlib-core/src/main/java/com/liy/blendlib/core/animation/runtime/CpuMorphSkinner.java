package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.MorphTargetSet;
import java.util.Objects;

/** Fused dense morph-before-skin path. Retains the exact prepared immutable topology. */
public final class CpuMorphSkinner {
    private static final double EPSILON = 1.0e-8;
    private CpuMorphSkinner() { }

    public static CpuSkinnedMesh skin(PreparedSkinnedGeometry geometry, MorphTargetSet targets,
            MorphWeights morphWeights, int nodeIndex, SkinPalette palette) {
        Objects.requireNonNull(geometry, "geometry"); Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(targets, "targets"); Objects.requireNonNull(morphWeights, "morphWeights");
        var binding = morphWeights.bindings().binding(nodeIndex);
        if (binding == null || targets.vertexCount() != geometry.vertexCount()
                || targets.targetCount() != binding.targetCount() || !targets.targetNames().equals(binding.targetNames()))
            throw new IllegalArgumentException("Morph geometry and weights have different target domains");
        float[] sourcePositions = geometry.positionsForSkinning(), sourceNormals = geometry.normalsForSkinning();
        int[] joints = geometry.jointsForSkinning();
        float[] skinWeights = geometry.weightsForSkinning();
        float[] positions = new float[sourcePositions.length], normals = new float[sourceNormals.length];
        double[] transformed = new double[6];
        for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
            int offset = vertex * 3;
            double px = sourcePositions[offset], py = sourcePositions[offset + 1], pz = sourcePositions[offset + 2];
            double nx = sourceNormals[offset], ny = sourceNormals[offset + 1], nz = sourceNormals[offset + 2];
            for (int target = 0; target < targets.targetCount(); target++) {
                double weight = morphWeights.weight(nodeIndex, target);
                px += weight * targets.positionDelta(target, vertex, 0);
                py += weight * targets.positionDelta(target, vertex, 1);
                pz += weight * targets.positionDelta(target, vertex, 2);
                nx += weight * targets.normalDelta(target, vertex, 0);
                ny += weight * targets.normalDelta(target, vertex, 1);
                nz += weight * targets.normalDelta(target, vertex, 2);
            }
            requireFinite(px); requireFinite(py); requireFinite(pz);
            requireFinite(nx); requireFinite(ny); requireFinite(nz);
            double sourceNormalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Double.isFinite(sourceNormalLength) || sourceNormalLength <= EPSILON)
                throw new IllegalArgumentException("Morphed source normal must remain nondegenerate");
            double total = 0, ox = 0, oy = 0, oz = 0, onx = 0, ony = 0, onz = 0;
            for (int influence = 0; influence < 4; influence++) {
                int slot = vertex * 4 + influence;
                double weight = skinWeights[slot];
                if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Invalid skin weight");
                if (weight == 0) continue;
                int joint = joints[slot];
                if (joint < 0 || joint >= palette.jointCount()) throw new IllegalArgumentException("Invalid skin joint slot");
                palette.transformPointInto(joint, px, py, pz, transformed, 0);
                palette.transformNormalInto(joint, nx, ny, nz, transformed, 3);
                total += weight;
                ox += weight * transformed[0]; oy += weight * transformed[1]; oz += weight * transformed[2];
                onx += weight * transformed[3]; ony += weight * transformed[4]; onz += weight * transformed[5];
            }
            if (!Double.isFinite(total) || total <= EPSILON) throw new IllegalArgumentException("Invalid total skin weight");
            positions[offset] = finite(ox / total); positions[offset + 1] = finite(oy / total); positions[offset + 2] = finite(oz / total);
            double length = Math.sqrt(onx * onx + ony * ony + onz * onz);
            if (!Double.isFinite(length)) throw new IllegalArgumentException("Non-finite skinned normal");
            // Preserve the established skinning contract when opposed joint normals cancel.
            if (length > EPSILON) {
                normals[offset] = finite(onx / length); normals[offset + 1] = finite(ony / length); normals[offset + 2] = finite(onz / length);
            }
        }
        return new CpuSkinnedMesh(geometry.topology(), positions, normals);
    }
    private static void requireFinite(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE)
            throw new IllegalArgumentException("CPU morph produced a non-finite component");
    }
    private static float finite(double value) { requireFinite(value); return (float) value; }
}
