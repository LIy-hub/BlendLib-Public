package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/** Immutable extraction inputs passed to an entity snapshot factory before render submit. */
public record BlendEntitySnapshotRequest(
        BlendModelKey modelKey,
        float partialTick,
        int packedLight,
        float ageInTicks,
        double x,
        double y,
        double z,
        long clientGameTick,
        boolean animationVisible,
        double distanceToCameraSq) {
    public BlendEntitySnapshotRequest {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (!Float.isFinite(partialTick) || !Float.isFinite(ageInTicks) || ageInTicks < 0.0F
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Entity snapshot extraction inputs must be finite");
        }
        if (clientGameTick < 0L) {
            throw new IllegalArgumentException("Entity snapshot client game tick must be non-negative");
        }
        if (!Double.isFinite(distanceToCameraSq) || distanceToCameraSq < 0.0D) {
            animationVisible = false;
            distanceToCameraSq = Double.POSITIVE_INFINITY;
        }
    }

    /**
     * @deprecated use {@link #clientGameTick()}; this compatibility alias never represents an
     * entity-local tick counter.
     */
    @Deprecated(forRemoval = false)
    public long updateTick() {
        return clientGameTick;
    }
}
