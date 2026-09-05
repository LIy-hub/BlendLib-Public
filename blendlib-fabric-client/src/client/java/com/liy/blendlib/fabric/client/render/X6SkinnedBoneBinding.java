package com.liy.blendlib.fabric.client.render;

/**
 * Canonical X6 bone identity resolved against one skinned handle during preparation.
 *
 * <p>The node index is retained for handle/palette validation and auditability; the joint index
 * is the only index used to select immutable vertex influences. Keeping both prevents the old
 * accidental assumption that a skinned primitive node is itself a skeleton joint.</p>
 */
public record X6SkinnedBoneBinding(int nodeIndex, int skinIndex, int jointIndex) {
    public X6SkinnedBoneBinding {
        if (nodeIndex < 0 || skinIndex < 0 || jointIndex < 0) {
            throw new IllegalArgumentException("X6 skinned bone node, skin, and joint indexes must be non-negative");
        }
    }
}
