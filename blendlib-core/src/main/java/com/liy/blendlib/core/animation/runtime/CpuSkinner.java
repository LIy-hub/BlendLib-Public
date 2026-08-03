package com.liy.blendlib.core.animation.runtime;

import java.util.Objects;

/** Pure v1 CPU skinning implementation; callers provide prepared immutable geometry and a pose palette. */
public final class CpuSkinner {
    private static final double EPSILON = 1.0e-8;

    private CpuSkinner() {
    }

    public static CpuSkinnedMesh skin(PreparedSkinnedGeometry geometry, SkinPalette palette) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(palette, "palette");
        float[] sourcePositions = geometry.positionsForSkinning();
        float[] sourceNormals = geometry.normalsForSkinning();
        int[] joints = geometry.jointsForSkinning();
        float[] weights = geometry.weightsForSkinning();
        float[] positions = new float[sourcePositions.length];
        float[] normals = new float[sourceNormals.length];
        // One reusable primitive workspace avoids allocating two Vec3 values for every joint influence.
        float[] transformed = new float[6];
        for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
            int positionOffset = vertex * 3;
            int jointOffset = vertex * 4;
            float sourcePositionX = sourcePositions[positionOffset];
            float sourcePositionY = sourcePositions[positionOffset + 1];
            float sourcePositionZ = sourcePositions[positionOffset + 2];
            float sourceNormalX = sourceNormals[positionOffset];
            float sourceNormalY = sourceNormals[positionOffset + 1];
            float sourceNormalZ = sourceNormals[positionOffset + 2];
            requireFiniteVector(sourcePositionX, sourcePositionY, sourcePositionZ);
            requireFiniteVector(sourceNormalX, sourceNormalY, sourceNormalZ);
            double totalWeight = 0.0;
            double positionX = 0.0;
            double positionY = 0.0;
            double positionZ = 0.0;
            double normalX = 0.0;
            double normalY = 0.0;
            double normalZ = 0.0;
            for (int influence = 0; influence < 4; influence++) {
                float weight = weights[jointOffset + influence];
                if (!Float.isFinite(weight) || weight < 0.0f) {
                    throw new IllegalArgumentException("Skin weights must be finite and non-negative");
                }
                if (weight <= 0.0f) {
                    continue;
                }
                int joint = joints[jointOffset + influence];
                if (joint < 0 || joint >= palette.jointCount()) {
                    throw new IllegalArgumentException("Skin joint slot is outside the palette: " + joint);
                }
                palette.transformPointInto(
                        joint, sourcePositionX, sourcePositionY, sourcePositionZ, transformed, 0);
                palette.transformNormalInto(
                        joint, sourceNormalX, sourceNormalY, sourceNormalZ, transformed, 3);
                totalWeight += weight;
                positionX += weight * transformed[0];
                positionY += weight * transformed[1];
                positionZ += weight * transformed[2];
                normalX += weight * transformed[3];
                normalY += weight * transformed[4];
                normalZ += weight * transformed[5];
            }
            if (!Double.isFinite(totalWeight) || totalWeight <= EPSILON) {
                throw new IllegalArgumentException("Skinned vertex must have a positive finite total weight");
            }
            positions[positionOffset] = finite(positionX / totalWeight);
            positions[positionOffset + 1] = finite(positionY / totalWeight);
            positions[positionOffset + 2] = finite(positionZ / totalWeight);
            double normalLength = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            if (!Double.isFinite(normalLength) || normalLength <= EPSILON) {
                normals[positionOffset] = 0.0f;
                normals[positionOffset + 1] = 0.0f;
                normals[positionOffset + 2] = 0.0f;
            } else {
                normals[positionOffset] = finite(normalX / normalLength);
                normals[positionOffset + 1] = finite(normalY / normalLength);
                normals[positionOffset + 2] = finite(normalZ / normalLength);
            }
        }
        return new CpuSkinnedMesh(geometry.topology(), positions, normals);
    }

    private static void requireFiniteVector(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            // Match the finite-vector contract that the former Vec3 source values enforced.
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("CPU skinning produced a non-finite component");
        }
        return result;
    }
}
