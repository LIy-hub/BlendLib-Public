package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * One configuration-time marker owned by the frozen X3 timeline catalog, never a per-frame caller claim.
 *
 * <p>This is an Experimental X3 configuration value. A marker is only usable after a {@link ProceduralRigPlan} is
 * bound to one exact X2 runtime; it cannot itself create a publishable per-frame event.</p>
 */
public record ProceduralVisualEventMarker(
        BlendResourceId controllerId,
        BlendAnimationKey timeline,
        int markerIndex,
        double timeSeconds,
        BlendResourceId eventId,
        int priority,
        ProceduralVisualEventPayload payload) {

    public ProceduralVisualEventMarker {
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        timeline = Objects.requireNonNull(timeline, "timeline");
        eventId = Objects.requireNonNull(eventId, "eventId");
        payload = Objects.requireNonNull(payload, "payload");
        ProceduralSupport.requireId(controllerId, "event marker controller id");
        ProceduralSupport.requireId(timeline.resourceId(), "event marker timeline id");
        ProceduralSupport.requireId(eventId, "event marker event id");
        if (markerIndex < 0 || !Double.isFinite(timeSeconds) || timeSeconds <= 0.0D) {
            throw new IllegalArgumentException("event marker index and time must be finite, non-negative, and strictly after timeline start");
        }
    }
}
