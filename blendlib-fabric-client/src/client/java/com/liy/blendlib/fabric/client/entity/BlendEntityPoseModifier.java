package com.liy.blendlib.fabric.client.entity;

import net.minecraft.world.entity.Entity;

/**
 * Entity-aware client presentation hook layered over a cached strict animated pose.
 *
 * <p>Implementations may use the current entity and immutable extraction metadata to derive
 * bounded secondary rotation. They return the supplied adapter-owned rotation pose or one derived
 * through its override methods; core transforms, translation, scale, and node membership are not
 * exposed. The callback is never used by common/server code or render submit, and visual output
 * cannot be gameplay authority.</p>
 */
@FunctionalInterface
public interface BlendEntityPoseModifier<E extends Entity> {
    BlendEntityRotationPose modify(
            E entity,
            BlendEntityPoseContext context,
            BlendEntityRotationPose basePose);
}
