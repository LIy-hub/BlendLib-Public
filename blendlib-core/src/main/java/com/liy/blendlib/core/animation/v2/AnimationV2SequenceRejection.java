package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Owner-frame fail-closed instruction that revokes an accepted controller sequence without selecting a replacement. */
public record AnimationV2SequenceRejection(BlendResourceId controllerId, long sequence) {
    public AnimationV2SequenceRejection {
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "rejected controller id");
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
    }
}
