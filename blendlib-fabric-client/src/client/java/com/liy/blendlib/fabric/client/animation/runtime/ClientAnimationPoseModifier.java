package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.core.animation.runtime.LocalPose;

/**
 * Client-only post-sample procedural rotation hook for a strict animated local pose.
 *
 * <p>The supplied base pose is the immutable value already retained by the generation-scoped pose
 * cache. Implementations return either that unchanged value or a new immutable pose with the same
 * node set, translations, and scales. Only rotations may differ. BlendLib validates the complete
 * result before constructing any node palette, skin palette, socket transform, or CPU-skinned
 * mesh.</p>
 */
@FunctionalInterface
public interface ClientAnimationPoseModifier {
    LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose);
}
