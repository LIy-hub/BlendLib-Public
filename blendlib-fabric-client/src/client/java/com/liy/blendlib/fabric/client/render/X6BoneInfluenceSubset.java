package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import java.util.Objects;

/**
 * Immutable prepare-time triangle selection for one canonical skinned bone.
 *
 * <p>This retains only offsets into the captured mesh's shared immutable index topology. It never
 * copies positions, normals, UVs, or the complete index payload; submit uses the matching
 * {@link SkinnedMeshSnapshot} that was already captured for the exact prepared primitive.</p>
 */
final class X6BoneInfluenceSubset {
    private final BlendResourceId canonicalBoneId;
    private final int boneNodeIndex;
    private final int jointIndex;
    private final PreparedSkinnedRenderPrimitive primitive;
    private final int[] triangleOffsets;

    private X6BoneInfluenceSubset(
            BlendResourceId canonicalBoneId,
            int boneNodeIndex,
            int jointIndex,
            PreparedSkinnedRenderPrimitive primitive,
            int[] triangleOffsets) {
        this.canonicalBoneId = X6Ids.requireId(canonicalBoneId, "canonicalBoneId");
        this.boneNodeIndex = boneNodeIndex;
        this.jointIndex = jointIndex;
        this.primitive = Objects.requireNonNull(primitive, "primitive");
        // The only caller constructs this fresh local subset and never exposes it; retain that
        // small index-offset array directly rather than making a second prepare-time copy.
        this.triangleOffsets = Objects.requireNonNull(triangleOffsets, "triangleOffsets");
        if (boneNodeIndex < 0 || jointIndex < 0 || this.triangleOffsets.length == 0) {
            throw new IllegalArgumentException("X6 bone subset must retain a non-empty valid canonical binding");
        }
        for (int offset : this.triangleOffsets) {
            if (offset < 0 || offset % 3 != 0) {
                throw new IllegalArgumentException("X6 bone subset triangle offsets must address indexed triangles");
            }
        }
    }

    static X6BoneInfluenceSubset prepare(
            BlendResourceId canonicalBoneId,
            X6SkinnedBoneBinding bone,
            PreparedSkinnedRenderPrimitive primitive) {
        canonicalBoneId = X6Ids.requireId(canonicalBoneId, "canonicalBoneId");
        bone = Objects.requireNonNull(bone, "bone");
        primitive = Objects.requireNonNull(primitive, "primitive");
        if (primitive.skinIndex() != bone.skinIndex()) {
            throw new IllegalArgumentException("Canonical X6 bone belongs to a different prepared skin");
        }
        PreparedSkinnedGeometry geometry = primitive.geometry();
        int[] joints = geometry.joints();
        float[] weights = geometry.weights();
        int[] indices = geometry.topology().indices();
        int selected = 0;
        for (int offset = 0; offset < indices.length; offset += 3) {
            if (triangleUsesJoint(indices, joints, weights, offset, bone.jointIndex())) {
                selected++;
            }
        }
        if (selected == 0) {
            throw new IllegalArgumentException("Canonical X6 bone has no positive influence over this prepared primitive");
        }
        int[] offsets = new int[selected];
        int cursor = 0;
        for (int offset = 0; offset < indices.length; offset += 3) {
            if (triangleUsesJoint(indices, joints, weights, offset, bone.jointIndex())) {
                offsets[cursor++] = offset;
            }
        }
        return new X6BoneInfluenceSubset(canonicalBoneId, bone.nodeIndex(), bone.jointIndex(), primitive, offsets);
    }

    private static boolean triangleUsesJoint(int[] indices, int[] joints, float[] weights, int triangleOffset, int jointIndex) {
        return vertexUsesJoint(indices[triangleOffset], joints, weights, jointIndex)
                || vertexUsesJoint(indices[triangleOffset + 1], joints, weights, jointIndex)
                || vertexUsesJoint(indices[triangleOffset + 2], joints, weights, jointIndex);
    }

    private static boolean vertexUsesJoint(int vertex, int[] joints, float[] weights, int jointIndex) {
        int offset = vertex * 4;
        for (int influence = 0; influence < 4; influence++) {
            if (joints[offset + influence] == jointIndex && weights[offset + influence] > 0.0f) {
                return true;
            }
        }
        return false;
    }

    BlendResourceId canonicalBoneId() {
        return canonicalBoneId;
    }

    int boneNodeIndex() {
        return boneNodeIndex;
    }

    int jointIndex() {
        return jointIndex;
    }

    PreparedSkinnedRenderPrimitive primitive() {
        return primitive;
    }

    int triangleCount() {
        return triangleOffsets.length;
    }

    void emit(SkinnedMeshSnapshot mesh, SkinnedMeshSnapshot.VertexSink sink) {
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.indexCount() <= triangleOffsets[triangleOffsets.length - 1] + 2) {
            throw new IllegalArgumentException("Captured skinned mesh no longer matches prepared X6 bone subset topology");
        }
        mesh.emitTriangles(triangleOffsets, sink);
    }
}
