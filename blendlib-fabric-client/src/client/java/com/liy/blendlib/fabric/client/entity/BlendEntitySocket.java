package com.liy.blendlib.fabric.client.entity;

import java.util.Objects;

/**
 * One socket captured from the final modified animation pose or the static-morph bone rest pose.
 * Model space uses authored asset units; entity and world space use blocks and include the selected render root.
 * Entity-space scale includes unit conversion; attachment placement removes only that conversion.
 */
public record BlendEntitySocket(
        BlendEntitySocketPose modelSpace,
        BlendEntitySocketPose entitySpace,
        BlendEntitySocketPose worldSpace,
        float unitsToBlocksScale) {
    public BlendEntitySocket {
        modelSpace = Objects.requireNonNull(modelSpace, "modelSpace");
        entitySpace = Objects.requireNonNull(entitySpace, "entitySpace");
        worldSpace = Objects.requireNonNull(worldSpace, "worldSpace");
        if (!Float.isFinite(unitsToBlocksScale) || unitsToBlocksScale <= 0) {
            throw new IllegalArgumentException("unitsToBlocksScale must be finite and positive");
        }
    }

    /** Placement for a separately authored child model, inheriting pose scale but not parent asset units. */
    public BlendEntitySocketPose attachmentPlacement() {
        return new BlendEntitySocketPose(entitySpace.x(), entitySpace.y(), entitySpace.z(),
                entitySpace.rotation(), entitySpace.scale() / unitsToBlocksScale);
    }
}
