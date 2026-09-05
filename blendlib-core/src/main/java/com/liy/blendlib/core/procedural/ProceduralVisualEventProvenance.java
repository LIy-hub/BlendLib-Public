package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable X2-timeline provenance required for one presentation-only event marker. */
public record ProceduralVisualEventProvenance(
        BlendResourceId controllerId,
        BlendResourceId timelineId,
        long loopEpoch,
        int markerIndex,
        long occurrence,
        long sourceRevision,
        double frameStartInclusive,
        double frameEndExclusive) {

    public ProceduralVisualEventProvenance {
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        timelineId = Objects.requireNonNull(timelineId, "timelineId");
        ProceduralSupport.requireId(controllerId, "visual event controller id");
        ProceduralSupport.requireId(timelineId, "visual event timeline id");
        if (loopEpoch < 0L || markerIndex < 0 || occurrence < 0L || sourceRevision < 0L
                || !Double.isFinite(frameStartInclusive) || !Double.isFinite(frameEndExclusive)
                || frameStartInclusive < 0.0D || frameEndExclusive <= frameStartInclusive) {
            throw new IllegalArgumentException("visual event provenance contains an invalid timeline boundary");
        }
    }

    String canonicalOrderKey() {
        return controllerId.value() + ':' + timelineId.value() + ':' + loopEpoch + ':' + markerIndex + ':' + occurrence
                + ':' + sourceRevision + ':' + Long.toUnsignedString(Double.doubleToLongBits(frameStartInclusive), 16)
                + ':' + Long.toUnsignedString(Double.doubleToLongBits(frameEndExclusive), 16);
    }
}
