package com.liy.blendlib.core.descriptor;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Descriptor-level animation event metadata; it contains no runtime state. */
public record AnimationEventDefinition(double timeSeconds, BlendResourceId eventKey) {
    public AnimationEventDefinition {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Animation event time must be finite and non-negative");
        }
        eventKey = Objects.requireNonNull(eventKey, "eventKey");
    }
}
