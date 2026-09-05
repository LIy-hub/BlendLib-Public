package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Scope-local replay identity. Revision and frame interval remain provenance, while one marker is fenced per loop. */
record ProceduralVisualEventReplayIdentity(
        BlendResourceId eventId,
        BlendResourceId controllerId,
        BlendResourceId timelineId,
        long loopEpoch,
        int markerIndex,
        long occurrence) {

    ProceduralVisualEventReplayIdentity {
        eventId = Objects.requireNonNull(eventId, "eventId");
        controllerId = Objects.requireNonNull(controllerId, "controllerId");
        timelineId = Objects.requireNonNull(timelineId, "timelineId");
    }

    static ProceduralVisualEventReplayIdentity from(ProceduralVisualEvent event) {
        ProceduralVisualEventProvenance provenance = event.provenance();
        return new ProceduralVisualEventReplayIdentity(event.id(), provenance.controllerId(), provenance.timelineId(), provenance.loopEpoch(),
                provenance.markerIndex(), provenance.occurrence());
    }
}
