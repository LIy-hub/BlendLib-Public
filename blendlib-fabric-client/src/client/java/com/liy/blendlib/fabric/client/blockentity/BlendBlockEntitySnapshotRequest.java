package com.liy.blendlib.fabric.client.blockentity;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;

/** Immutable, block-local extraction inputs used to create one render snapshot. */
public record BlendBlockEntitySnapshotRequest(
        BlendModelKey modelKey,
        BlendInstanceKey.BlockEntity instanceKey,
        float partialTick,
        int packedLight,
        long clientGameTick,
        double ageInTicks,
        boolean animationVisible,
        double distanceToCameraSq) {
    public BlendBlockEntitySnapshotRequest {
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        if (!Float.isFinite(partialTick) || !Double.isFinite(ageInTicks) || ageInTicks < 0.0D) {
            throw new IllegalArgumentException("Block-entity snapshot extraction inputs must have finite non-negative age");
        }
        if (clientGameTick < 0L) {
            throw new IllegalArgumentException("clientGameTick must be non-negative");
        }
        if (!Double.isFinite(distanceToCameraSq) || distanceToCameraSq < 0.0D) {
            animationVisible = false;
            distanceToCameraSq = Double.POSITIVE_INFINITY;
        }
    }
}
